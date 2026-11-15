package sk.noreels;

import android.content.Context;
import android.content.SharedPreferences;

/** Nastavenia appky (prepínače na hlavnej obrazovke). */
final class Prefs {
    static final String IG_TAB = "ig_tab";
    static final String YT_TAB = "yt_tab";
    static final String BLOCK_SCROLL = "block_scroll";
    static final String DIAG = "diag";
    static final String CURTAIN = "curtain";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("noreels", Context.MODE_PRIVATE);
    }

    static boolean on(SharedPreferences p, String key) {
        // diagnostika je predvolene vypnutá, všetko ostatné zapnuté
        return p.getBoolean(key, !DIAG.equals(key));
    }
}
