package sk.noreels;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1) Skryje kartu Reels (Instagram) / Shorts (YouTube): spodnú lištu nakreslí nanovo bez nej,
 *    ostatné tlačidlá rovnomerne rozložené. Vzhľad tlačidiel sa appka "naučí" zo snímky
 *    obrazovky (raz pre každý stav) a uloží si ho.
 * 2) Swipe medzi kartami Instagramu kartu Reels preskočí (pokračuje na ďalšiu kartu).
 * 3) Keď sa otvorí reel/short (napr. poslaný v správe) a skúsiš posunúť na ďalší,
 *    prehrávač sa zavrie (krok späť).
 */
public class BlockerService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static final String YT = "com.google.android.youtube";
    private static final String IG_PAGER = IG + ":id/swipeable_tab_view_pager";
    private static final boolean DEBUG = false;
    private static final String TAG = "NoReelsDbg";

    static volatile boolean running;
    static volatile boolean cacheCleared;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // dopyty na Instagram/YouTube môžu trvať (appka je zaneprázdnená) — bežia mimo hlavného vlákna,
    // aby prekrytie stále reagovalo a kreslilo plynulo
    private android.os.HandlerThread worker;
    private Handler bg;
    private boolean queryRunning;
    private boolean queryAgain;
    private final Map<String, String> titles = new HashMap<>(); // názov okna appky (na rýchle spoznanie)
    private long preShownAt;
    private final Runnable updateRunnable = () -> {
        updateScheduled = false;
        updateOverlay();
    };
    private SharedPreferences prefs;
    private WindowManager wm;

    // --- prekrytie ---
    private BarView overlay;
    private WindowManager.LayoutParams overlayLp;
    private String overlayPkg;
    private boolean updateScheduled;
    private long lastUpdate;
    private long solidSince;
    private List<AccessibilityNodeInfo> barTargets = Collections.emptyList();
    private List<Tab> curTabs;
    private Bitmap[] curIcons;
    private String curBase = "";
    private int curBg;
    private String optimisticId = "";
    private long optimisticUntil;

    // --- naučené obrázky tlačidiel ---
    private final Map<String, Bitmap> crops = new HashMap<>();
    private final Map<String, Bitmap> candidates = new HashMap<>(); // čakajú na potvrdenie druhou snímkou
    private String stateKey = "";
    private long stateSince;
    private boolean learnInFlight;
    private long lastScreenshot;

    // --- okamžité zobrazenie pri otvorení appky ---
    private static final class Shown {
        Rect rect;
        Bitmap[] icons;
        int bg;
    }
    private final Map<String, Shown> lastShown = new HashMap<>();
    private final Map<String, Boolean> hadBar = new HashMap<>();
    private String lastStatePkg = "";
    private long graceUntil;
    private boolean targetVisible;
    private long appearedAt;

    // --- odchod z karty Reels/Shorts ---
    private final Set<String> sawSelection = new HashSet<>();
    private long insideSince;
    private long lastLeave;

    // --- swipe medzi kartami (Instagram) ---
    private volatile int blockedPagerIndex = 1;
    private volatile int lastPagerIdx = -1;
    private long lastSkip;

    // --- blokovanie posúvania v prehrávači ---
    private int pagerKey;
    private int baseIndex = -1;
    private long lastKick;

    // --- diagnostika ---
    private String lastDiagTab = "";

    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Path p = new Path();
            p.moveTo(i.getIntExtra("x1", 900), i.getIntExtra("y1", 1200));
            p.lineTo(i.getIntExtra("x2", 150), i.getIntExtra("y2", 1200));
            dispatchGesture(new GestureDescription.Builder().addStroke(
                    new GestureDescription.StrokeDescription(p, 0, i.getIntExtra("ms", 250))).build(), null, null);
        }
    };

    @Override
    protected void onServiceConnected() {
        running = true;
        prefs = Prefs.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        worker = new android.os.HandlerThread("noreels-query");
        worker.start();
        bg = new Handler(worker.getLooper());
        if (DEBUG) registerReceiver(debugReceiver, new IntentFilter("sk.noreels.DBG_SWIPE"));
        DiagLog.add("Služba spustená (Android API " + Build.VERSION.SDK_INT + ")");
    }

    @Override
    public void onDestroy() {
        running = false;
        if (DEBUG) {
            try {
                unregisterReceiver(debugReceiver);
            } catch (RuntimeException ignored) {}
        }
        hideOverlay();
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || bg == null) return;
        final String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        boolean target = IG.equals(pkg) || YT.equals(pkg);
        int type = e.getEventType();

        if (target && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            final AccessibilityEvent copy = AccessibilityEvent.obtain(e);
            bg.post(() -> {
                try {
                    handleScroll(copy, pkg);
                } catch (RuntimeException ignored) {
                } finally {
                    copy.recycle();
                }
            });
        }
        if (overlay == null && (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            quickAppear();
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = e.getClassName() == null ? "" : e.getClassName().toString();
            if (target) {
                resetPager(); // nová obrazovka => nový prehrávač začína odznova
                onTargetWindow(pkg, cls);
                lastStatePkg = pkg;
            } else if (!pkg.isEmpty() && !pkg.equals(getPackageName())
                    && !pkg.equals("com.android.systemui") && !cls.contains("InputMethod")) {
                lastStatePkg = pkg;
            }
            requestUpdate(true);
            return;
        }
        requestUpdate(false);
    }

    /**
     * Okno appky sa práve objavilo (spoznáme ho podľa názvu, bez čakania na appku) a naposledy
     * malo lištu => ukázať ju hneď.
     */
    private void quickAppear() {
        if (lastShown.isEmpty() || titles.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (!w.isFocused() && !w.isActive()) continue;
                CharSequence title = w.getTitle();
                if (title == null) continue;
                for (Map.Entry<String, String> en : titles.entrySet()) {
                    String pkg = en.getKey();
                    Shown last = lastShown.get(pkg);
                    if (!en.getValue().contentEquals(title) || last == null
                            || Boolean.FALSE.equals(hadBar.get(pkg))
                            || !Prefs.on(prefs, IG.equals(pkg) ? Prefs.IG_TAB : Prefs.YT_TAB)) continue;
                    appearedAt = now;
                    placeOverlay(last.rect, pkg);
                    if (overlay != null) {
                        overlay.showBar(last.icons, last.bg);
                        barTargets = Collections.emptyList();
                        graceUntil = now + 4500;
                        preShownAt = now;
                    }
                    return;
                }
            }
        } catch (RuntimeException ignored) {}
    }

    /** Hlavná obrazovka appky je otvorená — ak sa lišta do chvíle neobjaví, nie je tu. */
    private void onTargetWindow(String pkg, String cls) {
        long now = SystemClock.uptimeMillis();
        if (overlay != null && cls.contains("Activity") && graceUntil > now) {
            graceUntil = Math.min(graceUntil, now + 2500);
        }
        // appka sa práve spúšťa (úvodná obrazovka) a naposledy mala lištu => ukázať ju hneď
        Shown last = lastShown.get(pkg);
        if (overlay == null && !targetVisible && last != null
                && !Boolean.FALSE.equals(hadBar.get(pkg))
                && Prefs.on(prefs, IG.equals(pkg) ? Prefs.IG_TAB : Prefs.YT_TAB)) {
            appearedAt = now;
            placeOverlay(last.rect, pkg);
            if (overlay != null) {
                overlay.showBar(last.icons, last.bg);
                barTargets = Collections.emptyList();
                graceUntil = now + 4500;
                preShownAt = now;
            }
        }
    }

    // =====================================================================
    //  Swipe medzi kartami a posúvanie v prehrávači
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

    /** Beží na pracovnom vlákne. */
    private void handleScroll(AccessibilityEvent e, String pkg) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return;
        final String id = src.getViewIdResourceName();
        final int from = e.getFromIndex();
        final int to = e.getToIndex();
        if (Prefs.on(prefs, Prefs.DIAG)) {
            DiagLog.add("SCROLL " + shortPkg(pkg) + " id=" + id + " cls=" + src.getClassName()
                    + " from=" + from + " to=" + to);
        }
        if (IG_PAGER.equals(id)) {
            handleTabSwipe(src, from);
            return;
        }
        final boolean viewer = isViewerPager(pkg, id);
        final int key = src.hashCode(); // rovnaký objekt prehrávača = rovnaký kľúč
        handler.post(() -> onViewerScroll(pkg, viewer, key, from, to));
    }

    private void onViewerScroll(String pkg, boolean viewer, int key, int from, int to) {
        if (!viewer) {
            // posúvanie niečoho iného (chat, feed, komentáre) => mimo prehrávača
            resetPager();
            return;
        }
        if (!Prefs.on(prefs, Prefs.BLOCK_SCROLL) || from < 0) return;
        if (key != pagerKey || baseIndex < 0) {
            pagerKey = key;
            baseIndex = from;    // toto je reel/short, ktorý ti poslali
            return;
        }
        boolean movedAway = from != baseIndex || (to >= 0 && to != baseIndex);
        if (movedAway) kick(pkg);
    }

    /** Instagram: posun stránok dorazil na Reels — ak sú naozaj vybrané, preskočiť ich. */
    private void handleTabSwipe(AccessibilityNodeInfo pager, int idx) {
        if (idx != blockedPagerIndex || !Prefs.on(prefs, Prefs.IG_TAB)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo tab = root == null ? null : findIgTab(root);
        if (tab != null && selectedIn(tab, 2)) skipReels(pager);
    }

    /** Plynulo pokračuje v smere swipu na ďalšiu kartu (Domov -> chaty, chaty -> Domov). */
    private void skipReels(AccessibilityNodeInfo pager) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSkip < 600) return;
        lastSkip = now;
        boolean forward = lastPagerIdx >= 0 && lastPagerIdx < blockedPagerIndex;
        boolean ok = pager.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        DiagLog.add("SWIPE cez Reels -> " + (forward ? "ďalej" : "späť") + (ok ? "" : " (zlyhalo)"));
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
    //  Prekrytie lišty
    // =====================================================================

    private void requestUpdate(boolean immediately) {
        long now = SystemClock.uptimeMillis();
        if (immediately || now - lastUpdate >= 100) {
            handler.removeCallbacks(updateRunnable);
            updateScheduled = false;
            updateOverlay();
        } else if (!updateScheduled) {
            updateScheduled = true;
            handler.postDelayed(updateRunnable, 100 - (now - lastUpdate));
        }
    }

    private void updateLater(long ms) {
        handler.postDelayed(() -> requestUpdate(false), ms);
    }

    /** Zistí stav appky na pracovnom vlákne a výsledok použije na hlavnom. */
    private void updateOverlay() {
        lastUpdate = SystemClock.uptimeMillis();
        if (queryRunning) {
            queryAgain = true;
            return;
        }
        queryRunning = true;
        bg.post(() -> {
            final Query q = runQuery();
            handler.post(() -> {
                queryRunning = false;
                apply(q);
                if (queryAgain) {
                    queryAgain = false;
                    requestUpdate(false);
                }
            });
        });
    }

    private static final class Query {
        AccessibilityNodeInfo tab, root;
        AccessibilityWindowInfo win;
        String pkg, seenPkg;
        final Rect tabRect = new Rect();
        boolean misplaced, usable, tabSelected;
        Bar bar;
    }

    private Query runQuery() {
        Query q = new Query();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String p = r.getPackageName().toString();
                if (IG.equals(p) && Prefs.on(prefs, Prefs.IG_TAB)) {
                    q.seenPkg = p;
                    q.tab = findIgTab(r);
                } else if (YT.equals(p) && Prefs.on(prefs, Prefs.YT_TAB)) {
                    q.seenPkg = p;
                    q.tab = findYtTab(r);
                }
                if (q.tab != null) {
                    q.pkg = p;
                    q.win = w;
                    q.root = r;
                    break;
                }
            }
            if (q.tab != null) {
                q.tab.getBoundsInScreen(q.tabRect);
                // lišta je vždy dole; počas načítavania appky býva tlačidlo chvíľu na nezmyselnom mieste
                q.misplaced = q.tabRect.top < screenHeight() * 0.75f;
                if (!q.tabRect.isEmpty() && !q.misplaced && q.tab.isVisibleToUser()) {
                    q.bar = findBar(q.tab, q.tabRect);
                    q.tabSelected = selectedIn(q.tab, 2);
                    q.usable = true;
                }
            }
        } catch (RuntimeException ignored) {
            // okná sa práve menia — skúsime pri ďalšej udalosti
        }
        return q;
    }

    private void apply(Query q) {
        long now = SystemClock.uptimeMillis();
        if (cacheCleared) {
            cacheCleared = false;
            crops.clear();
            candidates.clear();
            lastShown.clear();
        }
        final AccessibilityNodeInfo tab = q.tab, root = q.root;
        final AccessibilityWindowInfo win = q.win;
        final String pkg = q.pkg, seenPkg = q.seenPkg;
        final Rect tabRect = q.tabRect;

        // appka sa práve objavila na obrazovke?
        if (seenPkg != null && !targetVisible && overlay == null) appearedAt = now;
        targetVisible = seenPkg != null;

        if (!q.usable) {
            boolean targetInFront = seenPkg != null || IG.equals(lastStatePkg) || YT.equals(lastStatePkg)
                    || now - preShownAt < 1500;
            if (overlay != null && targetInFront && now < graceUntil) { // appka sa ešte načítava
                updateLater(100);
                return;
            }
            // appka sa práve otvára a naposledy mala lištu => ukázať ju hneď, nečakať na načítanie
            Shown last = seenPkg == null ? null : lastShown.get(seenPkg);
            if (overlay == null && last != null && now - appearedAt < 400
                    && !Boolean.FALSE.equals(hadBar.get(seenPkg))) {
                placeOverlay(last.rect, seenPkg);
                if (overlay != null) {
                    overlay.showBar(last.icons, last.bg);
                    barTargets = Collections.emptyList();
                    graceUntil = now + 4500;
                    preShownAt = now;
                    updateLater(100);
                }
                return;
            }
            if (seenPkg != null && tab == null) hadBar.put(seenPkg, false); // appka je otvorená, ale bez lišty
            diagTab("karta nenájdená");
            insideSince = 0;
            hideOverlay();
            return;
        }
        graceUntil = 0;
        hadBar.put(pkg, true);
        if (win.getTitle() != null) titles.put(pkg, win.getTitle().toString());

        final Bar bar = q.bar;
        boolean tabSelected = q.tabSelected;
        diagTab("karta " + shortPkg(pkg) + " " + tabRect.toShortString() + " vybraná=" + tabSelected
                + (bar == null ? " lišta=nie" : " lišta=" + bar.rect.toShortString()
                + " ikon=" + bar.tabs.size() + " inaVybraná=" + bar.anySelected));
        if (bar != null && IG.equals(pkg)) {
            blockedPagerIndex = bar.blockedIndex;
            for (int j = 0; j < bar.tabs.size(); j++) { // odkiaľ sa swipuje = naposledy vybraná karta
                if (bar.tabs.get(j).selected) lastPagerIdx = j < bar.blockedIndex ? j : j + 1;
            }
        }

        checkInsideBlockedTab(pkg, root, bar, tabSelected);

        boolean night = isNight();
        String theme = pkg + (night ? "|n" : "|d");
        int bgDefault = night ? (YT.equals(pkg) ? 0xFF0F0F0F : 0xFF0C0C0C) : 0xFFFFFFFF;
        boolean hasBg = prefs.contains("bg4|" + theme);
        int bg = prefs.getInt("bg4|" + theme, bgDefault);

        if (bar == null) {
            placeOverlay(tabRect, pkg);
            setSolid(bg);
            return;
        }

        // stav lišty = geometria + vzhľad každého tlačidla
        String[] keys = new String[bar.tabs.size()];
        String[] genKeys = new String[keys.length];
        Bitmap[] icons = new Bitmap[keys.length];
        boolean exact = hasBg;   // máme presný obrázok každého tlačidla?
        boolean all = hasBg;     // máme aspoň náhradný obrázok každého tlačidla?
        String selectedId = "";
        StringBuilder sk = new StringBuilder(theme).append(bar.rect.toShortString());
        for (int i = 0; i < keys.length; i++) {
            Tab t = bar.tabs.get(i);
            String base = theme + "|" + t.rect.width() + "x" + t.rect.height() + "|";
            genKeys[i] = base + t.id + (t.selected ? "|sel" : "|unsel");
            keys[i] = genKeys[i] + (t.sig.matches(".*\\d.*") ? "|badge" : "");
            if (t.selected) selectedId = t.id;
            icons[i] = crop(keys[i]);
            if (icons[i] == null) {
                exact = false;
                icons[i] = crop(genKeys[i]); // rovnaké tlačidlo, len iný odznak
                if (icons[i] == null && t.selected) {
                    // vzor zvýraznenia (podfarbenie) z iného tlačidla, ktoré už vybrané poznáme
                    String ref = null;
                    for (Tab o : bar.tabs) {
                        if (o != t && crop(base + o.id + "|sel") != null) {
                            ref = base + o.id + "|sel";
                            break;
                        }
                    }
                    icons[i] = filled(base + t.id + "|unsel", ref, bg);
                }
                // posledná možnosť: to isté tlačidlo v opačnom stave (lepšie než odkryť lištu)
                if (icons[i] == null) icons[i] = crop(base + t.id + (t.selected ? "|unsel" : "|sel"));
                if (icons[i] == null) all = false;
            }
            sk.append('#').append(keys[i]);
        }
        // na niektorých kartách (správy) Instagram snímky obrazovky blokuje — tam sa učiť nedá
        boolean canLearn = !prefs.getStringSet("nolearn", Collections.<String>emptySet())
                .contains(theme + "|" + selectedId);
        String newState = sk.toString();
        if (!newState.equals(stateKey)) {
            stateKey = newState;
            stateSince = now;
        }

        barTargets = new ArrayList<>(keys.length);
        for (Tab t : bar.tabs) barTargets.add(t.node);

        curBase = theme + "|" + bar.tabs.get(0).rect.width() + "x" + bar.tabs.get(0).rect.height() + "|";
        curTabs = bar.tabs;
        curIcons = icons;
        curBg = bg;
        if (now < optimisticUntil && !selectedId.equals(optimisticId)
                && overlay != null && overlay.isBarMode()) {
            updateLater(80); // appka ešte neprepla kartu — nechať okamžitú odozvu na ťuknutie
            return;
        }
        optimisticUntil = 0;
        boolean settling = now - stateSince < 450;
        if (all && (exact || !canLearn || settling)) {
            placeOverlay(bar.rect, pkg);
            overlay.showBar(icons, bg);
            if (exact) {
                Shown s = new Shown();
                s.rect = new Rect(bar.rect);
                s.icons = icons;
                s.bg = bg;
                lastShown.put(pkg, s);
            } else if (canLearn) {
                updateLater(150); // po ustálení sa doučí presný vzhľad
            }
        } else if (!canLearn) {
            placeOverlay(tabRect, pkg);
            setSolid(bg);
        } else if (overlay != null && overlay.isBarMode() && pkg.equals(overlayPkg) && now - stateSince < 450) {
            // stav sa práve mení (animácia, swipe) — necháme chvíľu starý obraz lišty
            updateLater(150);
        } else {
            // neznámy stav: ukážeme skutočné tlačidlá, odfotíme ich a zapamätáme si
            placeOverlay(tabRect, pkg);
            setSolid(bg);
            learn(theme, bar, keys, genKeys, selectedId, win);
        }
    }

    private void setSolid(int color) {
        if (overlay == null) return;
        if (overlay.isBarMode()) solidSince = SystemClock.uptimeMillis();
        overlay.showSolid(color);
    }

    // ---------------------------------------------------------------------
    //  Učenie vzhľadu tlačidiel zo snímky obrazovky
    // ---------------------------------------------------------------------

    private File cropFile(String key) {
        return new File(new File(getFilesDir(), "bar_v4"), Integer.toHexString(key.hashCode())
                + "_" + key.length() + ".png");
    }

    private Bitmap crop(String key) {
        if (crops.containsKey(key)) return crops.get(key);
        Bitmap b = null;
        File f = cropFile(key);
        if (f.exists()) b = BitmapFactory.decodeFile(f.getPath());
        crops.put(key, b);
        return b;
    }

    static void clearCache(Context c) {
        File dir = new File(c.getFilesDir(), "bar_v4");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        SharedPreferences p = Prefs.get(c);
        SharedPreferences.Editor ed = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("bg4|")) ed.remove(k);
        ed.remove("nolearn");
        ed.apply();
        cacheCleared = true;
    }

    private void learn(String theme, Bar bar, String[] keys, String[] genKeys, String selectedId,
                       AccessibilityWindowInfo win) {
        if (learnInFlight || overlay == null || overlay.isBarMode()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        long now = SystemClock.uptimeMillis();
        // počkať, kým sa lišta ustáli a naše prekrytie je naozaj preč z ostatných tlačidiel
        long wait = Math.max(600 - (now - stateSince), 250 - (now - solidSince));
        wait = Math.max(wait, 1100 - (now - lastScreenshot)); // systém dovolí 1 snímku za sekundu
        if (!win.isActive()) wait = Math.max(wait, 300);
        if (wait > 0) {
            updateLater(wait);
            return;
        }
        learnInFlight = true;
        lastScreenshot = now;
        final String state = stateKey;
        final long requested = now;
        final Rect[] rects = new Rect[keys.length];
        for (int i = 0; i < rects.length; i++) rects[i] = new Rect(bar.tabs.get(i).rect);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    learnInFlight = false;
                    try {
                        boolean valid = state.equals(stateKey) && overlay != null
                                && !overlay.isBarMode() && solidSince <= requested;
                        Bitmap hw = valid
                                ? Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace())
                                : null;
                        Bitmap sw = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                        if (hw != null) hw.recycle();
                        if (sw != null) {
                            storeCrops(theme, sw, rects, keys, genKeys, selectedId);
                            sw.recycle();
                        }
                    } catch (RuntimeException ex) {
                        DiagLog.add("Chyba pri učení lišty: " + ex);
                    } finally {
                        result.getHardwareBuffer().close();
                    }
                    requestUpdate(true);
                }

                @Override
                public void onFailure(int errorCode) {
                    learnInFlight = false;
                    DiagLog.add("Snímka zlyhala, kód " + errorCode);
                    updateLater(600);
                }
            });
        } catch (RuntimeException ex) {
            learnInFlight = false;
            DiagLog.add("Snímka nedostupná: " + ex);
        }
    }

    private void storeCrops(String theme, Bitmap screen, Rect[] rects, String[] keys, String[] genKeys,
                            String selectedId) {
        List<Integer> corners = new ArrayList<>();
        int learned = 0;
        new File(getFilesDir(), "bar_v4").mkdirs();
        for (int i = 0; i < rects.length; i++) {
            Rect r = rects[i];
            if (r.left < 0 || r.top < 0 || r.right > screen.getWidth() || r.bottom > screen.getHeight()
                    || r.width() < 8 || r.height() < 8) {
                return;
            }
            Bitmap b = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height());
            if (isUniform(b)) {
                // prázdny (čierny) výrez => appka na tejto karte snímky blokuje; už to neskúšať
                Set<String> no = new HashSet<>(prefs.getStringSet("nolearn", Collections.<String>emptySet()));
                no.add(theme + "|" + selectedId);
                prefs.edit().putStringSet("nolearn", no).apply();
                DiagLog.add("Snímky sú tu blokované (" + selectedId + ") — použijem naučené ikony");
                return;
            }
            // vzorky pozadia pri okrajoch tlačidla (nie úplne hore — tam býva deliaca čiara)
            int bw = b.getWidth(), bh = b.getHeight();
            corners.add(b.getPixel(2, bh / 2));
            corners.add(b.getPixel(bw - 3, bh / 2));
            corners.add(b.getPixel(2, bh - 4));
            corners.add(b.getPixel(bw - 3, bh - 4));
            corners.add(b.getPixel(2, bh / 4));
            corners.add(b.getPixel(bw - 3, bh / 4));
            if (crops.get(keys[i]) != null) continue; // už naučené
            Bitmap prev = candidates.put(keys[i], b);
            if (prev == null || !prev.sameAs(b)) continue; // ešte sa mení (animácia) — čakáme na zhodu
            candidates.remove(keys[i]);
            learned++;
            for (String k : new String[]{keys[i], genKeys[i]}) {
                crops.put(k, b);
                try (FileOutputStream out = new FileOutputStream(cropFile(k))) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception ex) {
                    DiagLog.add("Uloženie obrázka zlyhalo: " + ex);
                }
            }
        }
        if (learned == 0) return;
        // farba pozadia lišty = najčastejšia farba v rohoch tlačidiel
        int best = 0, bestCount = 0;
        for (int a : corners) {
            int count = 0;
            for (int o : corners) if (close(a, o)) count++;
            if (count > bestCount) {
                bestCount = count;
                best = a;
            }
        }
        prefs.edit().putInt("bg4|" + theme, best | 0xFF000000).apply();
        DiagLog.add("Naučené tlačidlá lišty (" + learned + ")");
    }

    private static boolean isUniform(Bitmap b) {
        int first = b.getPixel(0, 0);
        for (int y = 0; y < b.getHeight(); y += 2) {
            for (int x = 0; x < b.getWidth(); x += 2) {
                if (b.getPixel(x, y) != first) return false;
            }
        }
        return true;
    }

    private static int diff(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Náhrada za "vybraný" vzhľad tlačidla, ktorý sa nedá odfotiť (karta správ):
     * z obrysovej ikony spraví plnú a podloží ju rovnakým zvýraznením, aké má iné vybrané tlačidlo.
     */
    private Bitmap filled(String unselKey, String refSelKey, int bg) {
        String key = unselKey + "|filled" + (refSelKey == null ? "" : "+" + refSelKey);
        if (crops.containsKey(key)) return crops.get(key);
        Bitmap src = crop(unselKey);
        if (src == null) return null; // nevkladať do pamäte — obrysová ikona sa môže doučiť neskôr
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // farba ikony = farba najviac odlišná od pozadia
        int iconColor = bg, iconDiff = 1;
        for (int c : px) {
            int d = diff(c, bg);
            if (d > iconDiff) {
                iconDiff = d;
                iconColor = c;
            }
        }
        boolean[] isBg = new boolean[w * h];
        for (int i = 0; i < px.length; i++) isBg[i] = diff(px[i], bg) < iconDiff / 4;

        // pozadie spojené s okrajom výrezu je "vonku"; zvyšok pozadia je vnútro ikony
        boolean[] outside = new boolean[w * h];
        int[] stack = new int[w * h * 4 + 4 * (w + h)];
        int sp = 0;
        for (int x = 0; x < w; x++) {
            stack[sp++] = x;
            stack[sp++] = (h - 1) * w + x;
        }
        for (int y = 1; y < h - 1; y++) {
            stack[sp++] = y * w;
            stack[sp++] = y * w + w - 1;
        }
        while (sp > 0) {
            int i = stack[--sp];
            if (outside[i] || !isBg[i]) continue;
            outside[i] = true;
            int x = i % w, y = i / w;
            if (x > 0 && !outside[i - 1] && isBg[i - 1]) stack[sp++] = i - 1;
            if (x < w - 1 && !outside[i + 1] && isBg[i + 1]) stack[sp++] = i + 1;
            if (y > 0 && !outside[i - w] && isBg[i - w]) stack[sp++] = i - w;
            if (y < h - 1 && !outside[i + w] && isBg[i + w]) stack[sp++] = i + w;
        }

        // podklad: zvýraznenie z iného vybraného tlačidla (bez jeho ikony), inak čisté pozadie
        int[] base = new int[w * h];
        java.util.Arrays.fill(base, bg);
        Bitmap ref = refSelKey == null ? null : crop(refSelKey);
        if (ref != null && ref.getWidth() == w && ref.getHeight() == h) {
            int[] rp = new int[w * h];
            ref.getPixels(rp, 0, w, 0, 0, w, h);
            // farba zvýraznenia = najčastejšia farba odlišná od pozadia
            Map<Integer, Integer> counts = new HashMap<>();
            int pill = bg, pillCount = 0;
            for (int c : rp) {
                if (diff(c, bg) < 12) continue;
                int cnt = counts.merge(c, 1, Integer::sum);
                if (cnt > pillCount) {
                    pillCount = cnt;
                    pill = c;
                }
            }
            int pillDiff = diff(pill, bg);
            for (int i = 0; i < rp.length; i++) {
                // viac odlišné než zvýraznenie = ikona toho tlačidla => nahradiť farbou zvýraznenia
                base[i] = diff(rp[i], bg) > pillDiff + 6 || diff(rp[i], pill) < 6 ? pill : rp[i];
                if (diff(rp[i], bg) < 6) base[i] = bg;
            }
        }

        for (int i = 0; i < px.length; i++) {
            float a;
            if (outside[i]) {
                a = Math.min(1f, diff(px[i], bg) / (float) iconDiff);
            } else {
                int x = i % w, y = i / w;
                boolean edge = (x > 0 && outside[i - 1]) || (x < w - 1 && outside[i + 1])
                        || (y > 0 && outside[i - w]) || (y < h - 1 && outside[i + w]);
                // vnútro ikony je plné; vyhladený ostáva len jej vonkajší okraj
                a = edge ? Math.min(1f, diff(px[i], bg) / (float) iconDiff) : 1f;
            }
            px[i] = mix(base[i], iconColor, a);
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        crops.put(key, out);
        if (DEBUG) {
            try (FileOutputStream f = new FileOutputStream(new File(getExternalFilesDir(null), "synth.png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, f);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean close(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) < 6
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) < 6
                && Math.abs((a & 0xFF) - (b & 0xFF)) < 6;
    }

    // ---------------------------------------------------------------------
    //  Poistka: karta Reels/Shorts je otvorená dlhšie => späť na Domov
    // ---------------------------------------------------------------------

    private void checkInsideBlockedTab(String pkg, AccessibilityNodeInfo root, Bar bar, boolean tabSelected) {
        if (bar != null && bar.anySelected) sawSelection.add(pkg);
        boolean inside = tabSelected
                || (bar != null && !bar.anySelected && sawSelection.contains(pkg));
        long now = SystemClock.uptimeMillis();
        if (!inside) {
            insideSince = 0;
            return;
        }
        if (insideSince == 0) {
            insideSince = now;
            if (tabSelected && IG.equals(pkg) && root != null) {
                bg.post(() -> {
                    try {
                        List<AccessibilityNodeInfo> pagers = root.findAccessibilityNodeInfosByViewId(IG_PAGER);
                        if (!pagers.isEmpty()) skipReels(pagers.get(0));
                    } catch (RuntimeException ignored) {}
                });
            }
            updateLater(1300);
            return;
        }
        // poistka, keby preskočenie nezabralo
        if (now - insideSince < 1200 || now - lastLeave < 800) return;
        lastLeave = now;
        insideSince = 0;
        bg.post(() -> {
            boolean ok = false;
            try {
                AccessibilityNodeInfo home = null;
                if (IG.equals(pkg) && root != null) {
                    List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(IG + ":id/feed_tab");
                    if (!l.isEmpty()) home = l.get(0);
                }
                if (home == null && bar != null && !bar.tabs.isEmpty()) home = bar.tabs.get(0).node; // vľavo = Domov
                ok = home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (RuntimeException ignored) {}
            if (!ok) performGlobalAction(GLOBAL_ACTION_BACK);
            DiagLog.add("BLOK: karta " + (IG.equals(pkg) ? "Reels" : "Shorts") + " otvorená -> Domov"
                    + (ok ? "" : " (späť)"));
        });
        updateLater(200);
    }

    // ---------------------------------------------------------------------
    //  Hľadanie karty a lišty
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

    /** Podpis vzhľadu tlačidla: vybrané/nevybrané, popis, odznaky (počet správ a pod.). */
    private static void signature(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        String id = n.getViewIdResourceName();
        sb.append('[').append(id == null ? "" : id.substring(id.indexOf('/') + 1));
        if (n.isSelected() || n.isChecked()) sb.append("*");
        if (n.getContentDescription() != null) sb.append(" d=").append(n.getContentDescription());
        if (n.getText() != null) sb.append(" t=").append(n.getText());
        if (depth > 0) {
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null && c.isVisibleToUser()) signature(c, depth - 1, sb);
            }
        }
        sb.append(']');
    }

    private static final class Tab {
        AccessibilityNodeInfo node;
        Rect rect;
        String id;        // ktoré tlačidlo to je
        boolean selected; // je to práve otvorená karta?
        String sig;       // presný vzhľad (vrátane odznakov)
    }

    private static final class Bar {
        final Rect rect = new Rect();              // pás lišty na obrazovke
        final List<Tab> tabs = new ArrayList<>();  // ponechané tlačidlá zľava doprava
        boolean anySelected;                       // je vybraná iná karta?
        int blockedIndex;                          // poradie skrytej karty v lište
    }

    /** Ostatné tlačidlá v tom istom riadku ako skrytá karta. */
    private Bar findBar(AccessibilityNodeInfo tab, Rect tabRect) {
        int screenW = screenWidth();
        AccessibilityNodeInfo anc = tab;
        for (int level = 0; level < 6; level++) {
            anc = anc.getParent();
            if (anc == null) return null;
            List<Rect> rects = new ArrayList<>();
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collectRow(anc, tabRect, screenW, rects, nodes, 0);
            if (rects.size() < 2) continue;

            Bar bar = new Bar();
            int top = tabRect.top, bottom = tabRect.bottom;
            for (int i = 0; i < rects.size(); i++) {
                Rect r = rects.get(i);
                top = Math.min(top, r.top);
                bottom = Math.max(bottom, r.bottom);
                Tab t = new Tab();
                t.node = nodes.get(i);
                t.rect = r;
                StringBuilder sb = new StringBuilder();
                signature(t.node, 3, sb);
                t.sig = sb.toString();
                String rid = t.node.getViewIdResourceName();
                t.id = rid != null ? rid.substring(rid.indexOf('/') + 1) : "#" + i;
                t.selected = selectedIn(t.node, 2);
                if (t.selected) bar.anySelected = true;
                if (r.left < tabRect.left) bar.blockedIndex++;
                bar.tabs.add(t);
            }
            bar.rect.set(0, top, screenW, bottom);
            return bar;
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

    private void clickTarget(int index, int action) {
        if (index < 0 || index >= barTargets.size()) return;
        if (action == AccessibilityNodeInfo.ACTION_CLICK) showTapped(index);
        final AccessibilityNodeInfo n = barTargets.get(index);
        bg.post(() -> {
            try {
                n.refresh();
                n.performAction(action);
            } catch (RuntimeException ignored) {}
        });
        updateLater(120);
        updateLater(450);
    }

    /** Okamžitá odozva na ťuknutie: zvýraznenie preskočí na ťuknuté tlačidlo hneď. */
    private void showTapped(int index) {
        if (overlay == null || !overlay.isBarMode() || curTabs == null || curIcons == null
                || index >= curTabs.size() || curIcons.length != curTabs.size()) return;
        Bitmap[] icons = new Bitmap[curIcons.length];
        for (int j = 0; j < icons.length; j++) {
            Tab t = curTabs.get(j);
            if (j == index) {
                icons[j] = t.selected ? curIcons[j] : crop(curBase + t.id + "|sel");
                if (icons[j] == null) {
                    String ref = null;
                    for (Tab o : curTabs) {
                        if (o != t && crop(curBase + o.id + "|sel") != null) {
                            ref = curBase + o.id + "|sel";
                            break;
                        }
                    }
                    icons[j] = filled(curBase + t.id + "|unsel", ref, curBg);
                }
            } else {
                icons[j] = t.selected ? crop(curBase + t.id + "|unsel") : curIcons[j];
            }
            if (icons[j] == null) return; // tento stav ešte nepoznáme
        }
        overlay.showBar(icons, curBg);
        optimisticId = curTabs.get(index).id;
        optimisticUntil = SystemClock.uptimeMillis() + 600;
    }

    // ---------------------------------------------------------------------
    //  Okno prekrytia
    // ---------------------------------------------------------------------

    private void placeOverlay(Rect r, String pkg) {
        overlayPkg = pkg;
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
            overlayLp.windowAnimations = 0; // bez prelínania — objaví sa a zmizne okamžite
            overlayLp.x = r.left;
            overlayLp.y = r.top;
            solidSince = SystemClock.uptimeMillis();
            boolean opening = SystemClock.uptimeMillis() - appearedAt < 700;
            if (opening) overlay.setAlpha(0f);
            try {
                wm.addView(overlay, overlayLp);
                if (opening) overlay.animate().alpha(1f).setDuration(200).start();
            } catch (RuntimeException ex) {
                overlay = null;
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
                wm.removeViewImmediate(overlay);
            } catch (RuntimeException ignored) {}
            overlay = null;
        }
        barTargets = Collections.emptyList();
    }

    // =====================================================================

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

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
