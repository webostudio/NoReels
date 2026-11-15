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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1) Skryje kartu Reels (Instagram) / Shorts (YouTube).
 *    Android 14+: spodnú lištu nakreslí nanovo bez tej karty, ostatné ikony rovnomerne rozložené.
 *    Starší Android: kartu prekryje obdĺžnikom vo farbe lišty.
 *    Keď sa na kartu dostaneš inak (napr. swipnutím), hneď ťa vráti na Domov.
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
    private final Map<String, Integer> sampledColors = new HashMap<>();
    private final Map<String, Long> sampledAt = new HashMap<>();
    private long lastScreenshot;

    // --- odchod z karty Reels/Shorts ---
    private final Set<String> sawSelection = new HashSet<>();
    private long reelsSince;
    private long lastLeave;

    // --- prekreslená lišta (Android 14+) ---
    private List<AccessibilityNodeInfo> barTargets = Collections.emptyList();
    private Bitmap barBitmap;
    private String barBitmapKey;
    private String lastBarKey;
    private final Map<String, Integer> barColors = new HashMap<>();
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
        DiagLog.add("Služba spustená (Android API " + Build.VERSION.SDK_INT + ")");
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
        AccessibilityNodeInfo root = null;
        AccessibilityWindowInfo win = null;
        String pkg = null;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String p = r.getPackageName().toString();
                if (IG.equals(p) && Prefs.on(prefs, Prefs.IG_TAB)) {
                    tab = findIgTab(r);
                } else if (YT.equals(p) && Prefs.on(prefs, Prefs.YT_TAB)) {
                    tab = findYtTab(r);
                }
                if (tab != null) {
                    pkg = p;
                    win = w;
                    root = r;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // okná sa práve menia — skúsime pri ďalšej udalosti
        }

        if (tab == null) {
            diagTab("karta nenájdená");
            reelsSince = 0;
            hideOverlay();
            return;
        }
        Rect tabRect = new Rect();
        tab.getBoundsInScreen(tabRect);
        if (tabRect.isEmpty() || !tab.isVisibleToUser()) {
            hideOverlay();
            return;
        }

        Bar bar = findBar(tab, tabRect);
        boolean tabSelected = selectedIn(tab, 2);
        diagTab("karta " + shortPkg(pkg) + " id=" + tab.getViewIdResourceName()
                + " desc=" + tab.getContentDescription() + " " + tabRect.toShortString()
                + " vybraná=" + tabSelected
                + (bar == null ? " lišta=nie" : " lišta=" + bar.rect.toShortString()
                + " ikon=" + bar.targets.size() + " inaVybraná=" + bar.anySelected));

        if (checkInsideBlockedTab(pkg, root, bar, tabSelected)) return;

        boolean night = isNight();
        Integer barColor = barColors.get(pkg + (night ? ":n" : ":d"));
        if (bar != null && barBitmap != null && bar.key.equals(barBitmapKey) && barColor != null) {
            barTargets = bar.targets;
            placeOverlay(bar.rect);
            overlay.showBar(barBitmap, bar.icons, barColor);
        } else {
            placeOverlay(tabRect);
            overlay.showSolid(barColor != null ? barColor : colorFor(pkg, tabRect));
        }

        if (bar != null && Build.VERSION.SDK_INT >= 34) {
            if (!bar.key.equals(lastBarKey)) {
                lastBarKey = bar.key;
                forceCapture = true;
            }
            Rect winBounds = new Rect();
            win.getBoundsInScreen(winBounds);
            requestCapture(win.getId(), winBounds, bar, pkg + (night ? ":n" : ":d"));
        }
        startPeriodic();
    }

    /**
     * Si práve na karte Reels/Shorts (napr. po swipnutí)? Potom ťa vráti na Domov.
     * Karta je otvorená, keď je označená ako vybraná, alebo keď appka inak vyberanie
     * kariet hlási, ale teraz nie je vybraná žiadna z ostatných.
     */
    private boolean checkInsideBlockedTab(String pkg, AccessibilityNodeInfo root, Bar bar, boolean tabSelected) {
        if (bar != null && bar.anySelected) sawSelection.add(pkg);
        boolean inside = tabSelected
                || (bar != null && !bar.anySelected && sawSelection.contains(pkg));
        long now = SystemClock.uptimeMillis();
        if (!inside) {
            reelsSince = 0;
            return false;
        }
        // pri nepriamom znaku počkáme chvíľu, či to nie je len prechodný stav
        if (!tabSelected) {
            if (reelsSince == 0) {
                reelsSince = now;
                handler.postDelayed(this::scheduleOverlayUpdate, 300);
                return false;
            }
            if (now - reelsSince < 250) return false;
        }
        if (now - lastLeave < 800) return false;
        lastLeave = now;
        reelsSince = 0;
        leaveToHome(pkg, root, bar);
        return true;
    }

    private void leaveToHome(String pkg, AccessibilityNodeInfo root, Bar bar) {
        AccessibilityNodeInfo home = null;
        if (IG.equals(pkg) && root != null) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(IG + ":id/feed_tab");
            if (!l.isEmpty()) home = l.get(0);
        }
        if (home == null && bar != null && !bar.targets.isEmpty()) home = bar.targets.get(0); // úplne vľavo = Domov
        boolean ok = home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!ok) performGlobalAction(GLOBAL_ACTION_BACK);
        forceCapture = true;
        scheduleOverlayUpdate();
        DiagLog.add("BLOK: karta " + (IG.equals(pkg) ? "Reels" : "Shorts") + " otvorená -> Domov"
                + (ok ? "" : " (späť)"));
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

    private static boolean selectedIn(AccessibilityNodeInfo n, int depth) {
        if (n.isSelected() || n.isChecked()) return true;
        if (depth == 0) return false;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && selectedIn(c, depth - 1)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    //  Lišta: ostatné tlačidlá v tom istom riadku ako skrytá karta
    // ---------------------------------------------------------------------

    private static final class Bar {
        final Rect rect = new Rect();                                  // pás lišty na obrazovke
        final List<AccessibilityNodeInfo> targets = new ArrayList<>(); // kam posielať ťuknutia
        Rect[] icons;                                                  // ponechané tlačidlá v rámci pásu
        boolean anySelected;                                           // je vybraná iná karta?
        String key;                                                    // geometria (na zistenie zmeny)
    }

    private Bar findBar(AccessibilityNodeInfo tab, Rect tabRect) {
        int screenW = screenWidth();
        AccessibilityNodeInfo anc = tab;
        for (int level = 0; level < 6; level++) {
            anc = anc.getParent();
            if (anc == null) return null;
            List<Rect> rects = new ArrayList<>();
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collectRow(anc, tabRect, screenW, rects, nodes, 0);
            if (rects.size() >= 2) return buildBar(tabRect, screenW, rects, nodes);
        }
        return null;
    }

    /** Zbiera klikateľné tlačidlá, ktoré sú v rovnakom riadku a podobne veľké ako karta. */
    private static void collectRow(AccessibilityNodeInfo n, Rect tabRect, int screenW,
                                   List<Rect> rects, List<AccessibilityNodeInfo> nodes, int depth) {
        if (depth > 8) return;
        int tabH = tabRect.height();
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            Rect r = new Rect();
            c.getBoundsInScreen(r);
            if (r.isEmpty() || r.bottom <= tabRect.top || r.top >= tabRect.bottom) continue;
            boolean inRow = c.isClickable()
                    && Math.abs(r.centerY() - tabRect.centerY()) <= tabH / 2
                    && r.height() >= tabH * 0.5f && r.height() <= tabH * 1.6f
                    && r.width() <= screenW / 2
                    && !r.contains(tabRect) && !Rect.intersects(r, tabRect);
            if (inRow) {
                boolean dup = false;
                for (Rect o : rects) if (Rect.intersects(o, r)) { dup = true; break; }
                if (!dup) {
                    int at = 0;
                    while (at < rects.size() && rects.get(at).left < r.left) at++;
                    rects.add(at, r);
                    nodes.add(at, c);
                }
                continue;
            }
            collectRow(c, tabRect, screenW, rects, nodes, depth + 1);
        }
    }

    private static Bar buildBar(Rect tabRect, int screenW, List<Rect> rects, List<AccessibilityNodeInfo> nodes) {
        int top = tabRect.top, bottom = tabRect.bottom;
        for (Rect r : rects) {
            top = Math.min(top, r.top);
            bottom = Math.max(bottom, r.bottom);
        }
        Bar bar = new Bar();
        bar.rect.set(0, top, screenW, bottom);
        bar.targets.addAll(nodes);
        bar.icons = new Rect[rects.size()];
        StringBuilder key = new StringBuilder(bar.rect.toShortString());
        for (int i = 0; i < rects.size(); i++) {
            Rect rel = new Rect(rects.get(i));
            rel.offset(0, -top);
            bar.icons[i] = rel;
            key.append('|').append(rel.toShortString());
            if (selectedIn(nodes.get(i), 2)) bar.anySelected = true;
        }
        bar.key = key.toString();
        return bar;
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

    /** Snímka len okna appky (bez nášho prekrytia), z nej sa vystrihne lišta a jej farba. */
    private void requestCapture(int windowId, Rect winBounds, Bar bar, String colorKey) {
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
                                Rect c = new Rect(crop);
                                if (c.intersect(0, 0, sw.getWidth(), sw.getHeight()) && c.height() > 4) {
                                    cut = Bitmap.createBitmap(sw, c.left, c.top, c.width(), c.height());
                                } else {
                                    DiagLog.add("Lišta mimo snímky: " + crop.toShortString()
                                            + " snímka " + sw.getWidth() + "x" + sw.getHeight());
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
                    barColors.put(colorKey, backgroundOf(cut));
                    Bitmap old = barBitmap;
                    barBitmap = cut;
                    barBitmapKey = key;
                    updateOverlay();
                    if (old != null && old != cut) old.recycle();
                }

                @Override
                public void onFailure(int errorCode) {
                    captureInFlight = false;
                    DiagLog.add("Snímka lišty zlyhala, kód " + errorCode);
                }
            });
        } catch (RuntimeException ex) {
            captureInFlight = false;
            DiagLog.add("Snímka lišty nedostupná: " + ex);
        }
    }

    /** Farba pozadia lišty = najčastejšia farba v jej štyroch rohoch. */
    private static int backgroundOf(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = {
                b.getPixel(2, 2), b.getPixel(2, h - 3),
                b.getPixel(w - 3, 2), b.getPixel(w - 3, h - 3)
        };
        int best = px[2], bestCount = 0;
        for (int a : px) {
            int count = 0;
            for (int o : px) if (close(a, o)) count++;
            if (count > bestCount) {
                bestCount = count;
                best = a;
            }
        }
        return best | 0xFF000000;
    }

    private static boolean close(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) < 6
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) < 6
                && Math.abs((a & 0xFF) - (b & 0xFF)) < 6;
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
    //  Farba pre jednoduchý režim (Android 11–13) — zo snímky obrazovky
    // =====================================================================

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int colorFor(String pkg, Rect r) {
        boolean night = isNight();
        String key = pkg + (night ? ":n" : ":d");
        Integer c = sampledColors.get(key);
        Long at = sampledAt.get(key);
        long now = SystemClock.uptimeMillis();
        // na Androide 14+ farbu zistí snímka okna appky, tu netreba
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Build.VERSION.SDK_INT < 34
                && (at == null || now - at > 5000) && now - lastScreenshot > 2000) {
            sampleColor(key, new Rect(r));
        }
        if (c != null) return c;
        if (night) return YT.equals(pkg) ? 0xFF0F0F0F : 0xFF0C0C0C;
        return 0xFFFFFFFF;
    }

    private void sampleColor(String key, Rect r) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        lastScreenshot = SystemClock.uptimeMillis();
        int screenW = screenWidth();
        // bod vedľa ikony (mimo nášho obdĺžnika), pri hornom okraji lišty — mimo zvýraznenia susednej karty
        final int x = r.right + 4 < screenW ? r.right + 4 : r.left - 4;
        final int y = r.top + 2;
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
