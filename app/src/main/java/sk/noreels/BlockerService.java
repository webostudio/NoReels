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
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1) Skryje kartu Reels (Instagram) / Shorts (YouTube).
 *    Android 14+: spodnú lištu nakreslí nanovo bez tej karty, ostatné ikony rovnomerne rozložené.
 *    Starší Android: kartu prekryje obdĺžnikom vo farbe lišty.
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

    // --- prekrytie ---
    private BarView overlay;
    private WindowManager.LayoutParams overlayLp;
    private boolean updateScheduled;
    private long lastTabBack;
    private final Map<String, Integer> sampledColors = new HashMap<>();
    private final Map<String, Long> sampledAt = new HashMap<>();
    private long lastScreenshot;

    // --- prekreslená lišta (Android 14+) ---
    private List<AccessibilityNodeInfo> barTargets = Collections.emptyList();
    private Bitmap barBitmap;
    private String barBitmapKey;
    private String lastBarKey;
    private long lastCapture;
    private boolean captureInFlight;
    private boolean capturePending;
    private boolean forceCapture;
    private boolean periodicRunning;

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
        DiagLog.add("Služba spustená (Android " + Build.VERSION.SDK_INT + ")");
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
            resetPager();        // nová obrazovka => nový prehrávač začína odznova
            forceCapture = true; // a lišta mohla zmeniť vybranú kartu
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
        if (Prefs.on(prefs, Prefs.DIAG)) {
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
    //  Prekrytie karty / lišty
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
        AccessibilityWindowInfo win = null;
        String pkg = null;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
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
                    win = w;
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
        Rect tabRect = new Rect();
        tab.getBoundsInScreen(tabRect);
        if (tabRect.isEmpty() || !tab.isVisibleToUser()) {
            hideOverlay();
            return;
        }

        // Poistka: ak by sa karta predsa nejako otvorila, vrátime sa späť.
        if (tab.isSelected()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTabBack > 1500) {
                lastTabBack = now;
                performGlobalAction(GLOBAL_ACTION_BACK);
                DiagLog.add("BLOK: karta bola otvorená -> späť");
            }
        }

        Bar bar = Build.VERSION.SDK_INT >= 34 ? findBar(tab) : null;
        diagTab("karta " + shortPkg(pkg) + " id=" + tab.getViewIdResourceName()
                + " desc=" + tab.getContentDescription() + " " + tabRect.toShortString()
                + (bar == null ? " lišta=nie" : " lišta=" + bar.rect.toShortString()
                + " ikon=" + bar.targets.size()));

        if (bar != null && barBitmap != null && bar.key.equals(barBitmapKey)) {
            barTargets = bar.targets;
            placeOverlay(bar.rect);
            overlay.showBar(barBitmap, bar.icons, Math.min(2, bar.rect.width() - 1));
        } else {
            placeOverlay(tabRect);
            overlay.showSolid(colorFor(pkg, tabRect));
        }

        if (bar != null) {
            if (!bar.key.equals(lastBarKey)) {
                lastBarKey = bar.key;
                forceCapture = true;
            }
            Rect winBounds = new Rect();
            win.getBoundsInScreen(winBounds);
            requestCapture(win.getId(), winBounds, bar);
        }
        startPeriodic();
    }

    // ---------------------------------------------------------------------
    //  Hľadanie karty
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    //  Lišta: kontajner a ostatné tlačidlá
    // ---------------------------------------------------------------------

    private static final class Bar {
        final Rect rect = new Rect();                          // celá lišta na obrazovke
        final List<AccessibilityNodeInfo> targets = new ArrayList<>(); // kam posielať ťuknutia
        Rect[] icons;                                          // ponechané tlačidlá v rámci lišty
        String key;                                            // geometria (na zistenie zmeny)
    }

    private Bar findBar(AccessibilityNodeInfo tab) {
        int screenW = screenWidth();
        Rect r = new Rect();
        AccessibilityNodeInfo slot = tab;
        AccessibilityNodeInfo container = tab.getParent();
        for (int i = 0; i < 5 && container != null; i++) {
            container.getBoundsInScreen(r);
            if (r.width() >= screenW * 0.9f) break;
            slot = container;
            container = container.getParent();
        }
        if (container == null) return null;
        container.getBoundsInScreen(r);
        if (r.width() < screenW * 0.9f || r.height() > screenHeight() * 0.15f) return null;

        Rect slotRect = new Rect();
        slot.getBoundsInScreen(slotRect);

        List<Rect> rects = new ArrayList<>();
        List<AccessibilityNodeInfo> targets = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            AccessibilityNodeInfo c = container.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            Rect cr = new Rect();
            c.getBoundsInScreen(cr);
            if (cr.isEmpty() || cr.equals(slotRect) || cr.height() < r.height() * 0.4f) continue;
            if (!cr.intersect(r)) continue;
            AccessibilityNodeInfo t = clickableIn(c, 3);
            if (t == null) continue;
            // zoradiť zľava doprava
            int at = 0;
            while (at < rects.size() && rects.get(at).left < cr.left) at++;
            rects.add(at, cr);
            targets.add(at, t);
        }
        if (rects.size() < 2) return null;

        Bar bar = new Bar();
        bar.rect.set(r);
        bar.targets.addAll(targets);
        bar.icons = new Rect[rects.size()];
        StringBuilder key = new StringBuilder(r.toShortString());
        for (int i = 0; i < rects.size(); i++) {
            Rect rel = new Rect(rects.get(i));
            rel.offset(-r.left, -r.top);
            bar.icons[i] = rel;
            key.append('|').append(rel.toShortString());
        }
        bar.key = key.toString();
        return bar;
    }

    private static AccessibilityNodeInfo clickableIn(AccessibilityNodeInfo n, int depth) {
        if (n.isClickable()) return n;
        if (depth == 0) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo hit = clickableIn(c, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private void clickTarget(int index, int action) {
        if (index < 0 || index >= barTargets.size()) return;
        AccessibilityNodeInfo n = barTargets.get(index);
        n.refresh();
        n.performAction(action);
        // vybraná karta sa zmení — obnoviť obraz lišty
        handler.postDelayed(() -> { forceCapture = true; scheduleOverlayUpdate(); }, 250);
        handler.postDelayed(() -> { forceCapture = true; scheduleOverlayUpdate(); }, 800);
    }

    /** Snímka len okna appky (bez nášho prekrytia), z nej sa vystrihne lišta. */
    private void requestCapture(int windowId, Rect winBounds, Bar bar) {
        if (Build.VERSION.SDK_INT < 34 || captureInFlight) return;
        long now = SystemClock.uptimeMillis();
        long since = now - lastCapture;
        if (!forceCapture && since < 2000) return;
        if (since < 400) { // systém dovolí snímku len párkrát za sekundu
            if (!capturePending) {
                capturePending = true;
                handler.postDelayed(() -> {
                    capturePending = false;
                    forceCapture = true;
                    scheduleOverlayUpdate();
                }, 400 - since);
            }
            return;
        }
        forceCapture = false;
        captureInFlight = true;
        lastCapture = now;
        final String key = bar.key;
        final Rect crop = new Rect(bar.rect);
        crop.offset(-winBounds.left, -winBounds.top);
        try {
            takeScreenshotOfWindow(windowId, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    captureInFlight = false;
                    Bitmap cut = null;
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (sw != null) {
                                if (crop.left >= 0 && crop.top >= 0
                                        && crop.right <= sw.getWidth() && crop.bottom <= sw.getHeight()) {
                                    cut = Bitmap.createBitmap(sw, crop.left, crop.top, crop.width(), crop.height());
                                }
                                sw.recycle();
                            }
                        }
                    } catch (RuntimeException ex) {
                        DiagLog.add("Chyba snímky lišty: " + ex);
                    } finally {
                        result.getHardwareBuffer().close();
                    }
                    if (cut == null) return;
                    if (!key.equals(lastBarKey)) { // lišta sa medzitým zmenila
                        cut.recycle();
                        return;
                    }
                    Bitmap old = barBitmap;
                    barBitmap = cut;
                    barBitmapKey = key;
                    updateOverlay();
                    if (old != null && old != cut) old.recycle();
                }

                @Override
                public void onFailure(int errorCode) {
                    captureInFlight = false;
                    if (Prefs.on(prefs, Prefs.DIAG)) DiagLog.add("Snímka lišty zlyhala: " + errorCode);
                }
            });
        } catch (RuntimeException ex) {
            captureInFlight = false;
            DiagLog.add("Snímka lišty nedostupná: " + ex);
        }
    }

    /** Kým je prekrytie zobrazené, občas obnoví obraz lišty (odznaky správ a pod.). */
    private void startPeriodic() {
        if (periodicRunning) return;
        periodicRunning = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (overlay == null) {
                    periodicRunning = false;
                    return;
                }
                scheduleOverlayUpdate();
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    // ---------------------------------------------------------------------
    //  Okno prekrytia
    // ---------------------------------------------------------------------

    private void placeOverlay(Rect r) {
        if (overlay == null) {
            overlay = new BarView(this, new BarView.Listener() {
                @Override
                public void onTap(int index) {
                    clickTarget(index, AccessibilityNodeInfo.ACTION_CLICK);
                }

                @Override
                public void onLongPress(int index) {
                    clickTarget(index, AccessibilityNodeInfo.ACTION_LONG_CLICK);
                }
            });
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
            try {
                wm.addView(overlay, overlayLp);
            } catch (RuntimeException ex) {
                DiagLog.add("Chyba prekrytia: " + ex);
            }
            return;
        }
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

    private void hideOverlay() {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (RuntimeException ignored) {}
            overlay = null;
        }
        barTargets = Collections.emptyList();
    }

    // =====================================================================
    //  Farba lišty pre jednoduchý režim — zo snímky obrazovky vedľa ikony
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
        int screenW = screenWidth();
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
                        if (sw == null) return;
                        if (x >= 0 && y >= 0 && x < sw.getWidth() && y < sw.getHeight()) {
                            int color = sw.getPixel(x, y) | 0xFF000000;
                            sampledColors.put(key, color);
                            sampledAt.put(key, SystemClock.uptimeMillis());
                            if (overlay != null && !overlay.isBarMode()) overlay.showSolid(color);
                        }
                        sw.recycle();
                    } catch (RuntimeException ignored) {
                    } finally {
                        result.getHardwareBuffer().close();
                    }
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

    private int screenWidth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getCurrentWindowMetrics().getBounds().width();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
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
