package sk.noreels;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1) Nad kartu Reels (Instagram) / Shorts (YouTube) položí obdĺžnik vo farbe spodnej lišty,
 *    takže ikona nie je vidieť a ťuknutie na ňu nič neurobí.
 * 2) Keď sa otvorí reel/short (napr. poslaný v správe) a skúsiš posunúť na ďalší,
 *    prehrávač sa zavrie (krok späť).
 */
public class BlockerService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static final String YT = "com.google.android.youtube";

    static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager wm;

    // --- prekrytie ikony ---
    private View overlay;
    private WindowManager.LayoutParams overlayLp;
    private String overlayPkg;
    private boolean updateScheduled;
    private final Map<String, Integer> sampledColors = new HashMap<>();
    private final Map<String, Long> sampledAt = new HashMap<>();
    private long lastScreenshot;
    private long lastTabBack;

    // --- blokovanie posúvania ---
    private int pagerKey;
    private int baseIndex = -1;
    private long lastKick;

    // --- diagnostika ---
    private String lastDiagTab = "";

    @Override
    protected void onServiceConnected() {
        running = true;
        prefs = Prefs.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DiagLog.add("Služba spustená");
    }

    @Override
    public void onDestroy() {
        running = false;
        hideOverlay();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null) return;
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        boolean target = IG.equals(pkg) || YT.equals(pkg);
        int type = e.getEventType();

        if (target && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScroll(e, pkg);
        }
        if (target && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            resetPager(); // nová obrazovka => nový prehrávač začína odznova
        }
        scheduleOverlayUpdate();
    }

    // =====================================================================
    //  Blokovanie posúvania na ďalší reel / short
    // =====================================================================

    private static boolean isViewerPager(String pkg, String id) {
        if (id == null) return false;
        if (IG.equals(pkg)) {
            return id.contains("clips_viewer") || id.contains("clips_view_pager")
                    || id.contains("reels_viewer");
        }
        return id.contains("reel_recycler") || id.contains("reel_watch_pager")
                || id.contains("shorts_pager");
    }

    private void handleScroll(AccessibilityEvent e, String pkg) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return;
        String id = src.getViewIdResourceName();
        int from = e.getFromIndex();
        int to = e.getToIndex();
        boolean diag = Prefs.on(prefs, Prefs.DIAG);
        if (diag) {
            DiagLog.add("SCROLL " + shortPkg(pkg) + " id=" + id + " cls=" + src.getClassName()
                    + " from=" + from + " to=" + to);
        }

        if (!isViewerPager(pkg, id)) {
            // posúvanie niečoho iného (chat, feed, komentáre) => mimo prehrávača
            resetPager();
            return;
        }
        if (!Prefs.on(prefs, Prefs.BLOCK_SCROLL) || from < 0) return;

        int key = src.hashCode(); // rovnaký objekt prehrávača = rovnaký kľúč
        if (key != pagerKey || baseIndex < 0) {
            pagerKey = key;
            baseIndex = from;    // toto je reel/short, ktorý ti poslali
            return;
        }
        boolean movedAway = from != baseIndex || (to >= 0 && to != baseIndex);
        if (movedAway) kick(pkg);
    }

    private void kick(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - lastKick < 1500) return;
        lastKick = now;
        resetPager();
        performGlobalAction(GLOBAL_ACTION_BACK);
        DiagLog.add("BLOK: posun na ďalší v " + shortPkg(pkg) + " -> späť");
        Toast.makeText(this, "Ďalšie " + (IG.equals(pkg) ? "reels" : "shorts") + " sú zablokované",
                Toast.LENGTH_SHORT).show();
    }

    private void resetPager() {
        pagerKey = 0;
        baseIndex = -1;
    }

    // =====================================================================
    //  Prekrytie karty Reels / Shorts
    // =====================================================================

    private void scheduleOverlayUpdate() {
        if (updateScheduled) return;
        updateScheduled = true;
        handler.postDelayed(() -> {
            updateScheduled = false;
            updateOverlay();
        }, 100);
    }

    private void updateOverlay() {
        AccessibilityNodeInfo tab = null;
        String pkg = null;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                String p = root.getPackageName().toString();
                if (IG.equals(p) && Prefs.on(prefs, Prefs.IG_TAB)) {
                    tab = findIgTab(root);
                } else if (YT.equals(p) && Prefs.on(prefs, Prefs.YT_TAB)) {
                    tab = findYtTab(root);
                }
                if (tab != null) {
                    pkg = p;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // okná sa práve menia — skúsime pri ďalšej udalosti
        }

        if (tab == null) {
            diagTab("karta nenájdená");
            hideOverlay();
            return;
        }
        Rect r = new Rect();
        tab.getBoundsInScreen(r);
        if (r.isEmpty() || !tab.isVisibleToUser()) {
            hideOverlay();
            return;
        }
        diagTab("karta " + shortPkg(pkg) + " id=" + tab.getViewIdResourceName()
                + " desc=" + tab.getContentDescription() + " " + r.toShortString());

        // Poistka: ak by sa karta predsa nejako otvorila, vrátime sa späť.
        if (tab.isSelected()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTabBack > 1500) {
                lastTabBack = now;
                performGlobalAction(GLOBAL_ACTION_BACK);
                DiagLog.add("BLOK: karta bola otvorená -> späť");
            }
        }
        showOverlay(pkg, r);
    }

    /** Instagram: tlačidlo karty Reels v spodnej lište. */
    private AccessibilityNodeInfo findIgTab(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_tab");
        for (AccessibilityNodeInfo n : list) {
            if (n.isVisibleToUser()) return n;
        }
        return findBottomButton(root, "Reels");
    }

    /** YouTube: tlačidlo Shorts v spodnej lište. */
    private AccessibilityNodeInfo findYtTab(AccessibilityNodeInfo root) {
        return findBottomButton(root, "Shorts");
    }

    /** Klikateľné tlačidlo s daným názvom úplne dole na obrazovke (spodná lišta). */
    private AccessibilityNodeInfo findBottomButton(AccessibilityNodeInfo root, String label) {
        int screenH = screenHeight();
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(label);
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : list) {
            AccessibilityNodeInfo btn = n;
            // text môže byť v dieťati tlačidla — vyjdeme ku klikateľnému rodičovi
            for (int i = 0; i < 3 && btn != null && !btn.isClickable(); i++) btn = btn.getParent();
            if (btn == null || !btn.isClickable() || !btn.isVisibleToUser()) continue;
            if (!matchesLabel(btn, label) && !matchesLabel(n, label)) continue;
            btn.getBoundsInScreen(r);
            boolean atBottom = r.top > screenH * 0.82f && r.height() < screenH * 0.12f;
            if (atBottom) return btn;
        }
        return null;
    }

    private static boolean matchesLabel(AccessibilityNodeInfo n, String label) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        return (d != null && d.toString().trim().toLowerCase().startsWith(label.toLowerCase()))
                || (t != null && t.toString().trim().equalsIgnoreCase(label));
    }

    private void showOverlay(String pkg, Rect r) {
        int color = colorFor(pkg, r);
        if (overlay == null) {
            overlay = new View(this);
            overlay.setClickable(true); // zje ťuknutia, aby sa karta nedala otvoriť
            overlayLp = new WindowManager.LayoutParams(
                    r.width(), r.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            overlayLp.gravity = Gravity.TOP | Gravity.START;
            overlayLp.x = r.left;
            overlayLp.y = r.top;
            overlay.setBackgroundColor(color);
            try {
                wm.addView(overlay, overlayLp);
            } catch (RuntimeException ex) {
                overlay = null;
                DiagLog.add("Chyba prekrytia: " + ex);
                return;
            }
        } else {
            overlay.setBackgroundColor(color);
            if (overlayLp.x != r.left || overlayLp.y != r.top
                    || overlayLp.width != r.width() || overlayLp.height != r.height()) {
                overlayLp.x = r.left;
                overlayLp.y = r.top;
                overlayLp.width = r.width();
                overlayLp.height = r.height();
                try {
                    wm.updateViewLayout(overlay, overlayLp);
                } catch (RuntimeException ignored) {}
            }
        }
        overlayPkg = pkg;
    }

    private void hideOverlay() {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (RuntimeException ignored) {}
            overlay = null;
            overlayPkg = null;
        }
    }

    // =====================================================================
    //  Farba lišty — zistí sa zo snímky obrazovky vedľa ikony (Android 11+)
    // =====================================================================

    private int colorFor(String pkg, Rect r) {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String key = pkg + (night ? ":n" : ":d");
        Integer c = sampledColors.get(key);
        Long at = sampledAt.get(key);
        long now = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && (at == null || now - at > 5000) && now - lastScreenshot > 2000) {
            sampleColor(key, new Rect(r));
        }
        if (c != null) return c;
        if (night) return YT.equals(pkg) ? 0xFF0F0F0F : 0xFF000000;
        return 0xFFFFFFFF;
    }

    private void sampleColor(String key, Rect r) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        lastScreenshot = SystemClock.uptimeMillis();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // bod tesne vedľa ikony (mimo nášho obdĺžnika), v strede výšky lišty
        final int x = r.left > 6 ? r.left - 4 : Math.min(r.right + 4, screenW - 1);
        final int y = r.centerY();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hw == null) return;
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                        result.getHardwareBuffer().close();
                        if (sw == null) return;
                        if (x >= 0 && y >= 0 && x < sw.getWidth() && y < sw.getHeight()) {
                            int color = sw.getPixel(x, y) | 0xFF000000;
                            sampledColors.put(key, color);
                            sampledAt.put(key, SystemClock.uptimeMillis());
                            if (overlay != null) overlay.setBackgroundColor(color);
                        }
                        sw.recycle();
                    } catch (RuntimeException ignored) {}
                }

                @Override
                public void onFailure(int errorCode) {}
            });
        } catch (RuntimeException ignored) {}
    }

    // =====================================================================

    private int screenHeight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getCurrentWindowMetrics().getBounds().height();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void diagTab(String msg) {
        if (!Prefs.on(prefs, Prefs.DIAG) || msg.equals(lastDiagTab)) return;
        lastDiagTab = msg;
        DiagLog.add(msg);
    }

    private static String shortPkg(String pkg) {
        return IG.equals(pkg) ? "IG" : YT.equals(pkg) ? "YT" : pkg;
    }
}
