package sk.noreels;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Jednoduchá obrazovka: stav, návod na zapnutie, prepínače a diagnostika. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private TextView diagText;
    private LinearLayout diagBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);

        col.addView(text("\nAko zapnúť (Xiaomi / HyperOS):\n"
                + "1. Ťukni na „Nastavenia aplikácie“ → vpravo hore ⋮ → „Povoliť obmedzené nastavenia“. "
                + "Ak to tam nie je, najprv sprav krok 2 a vráť sa sem.\n"
                + "2. Ťukni na „Zjednodušenie ovládania“ → Stiahnuté aplikácie → NoReels → zapnúť.\n"
                + "3. V nastaveniach aplikácie zapni Autoštart a pri Batérii daj „Bez obmedzení“, "
                + "aby to Xiaomi po čase nevyplo.\n"));

        col.addView(button("1. Nastavenia aplikácie", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        }));
        col.addView(button("2. Zjednodušenie ovládania", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        col.addView(button("3. Autoštart (Xiaomi)", v -> openXiaomiAutostart()));

        col.addView(text("\nČo blokovať:"));
        col.addView(toggle("Skryť kartu Reels v Instagrame", Prefs.IG_TAB));
        col.addView(toggle("Skryť kartu Shorts v YouTube", Prefs.YT_TAB));
        col.addView(toggle("Pri poslanom reeli/shorte zakázať posun na ďalší", Prefs.BLOCK_SCROLL));

        col.addView(text("\nAk po aktualizácii Instagramu/YouTube niečo prestane fungovať, "
                + "zapni diagnostiku, choď do appky, otvor reel a skús ho posunúť, "
                + "potom sa vráť sem a skopíruj záznam."));
        Switch diag = toggle("Diagnostika", Prefs.DIAG);
        col.addView(diag);

        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Obnoviť", v -> refreshDiag()));
        row.addView(button("Kopírovať", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("NoReels", DiagLog.dump()));
            Toast.makeText(this, "Skopírované", Toast.LENGTH_SHORT).show();
        }));
        row.addView(button("Vymazať", v -> {
            DiagLog.clear();
            refreshDiag();
        }));
        diagBox.addView(row);
        diagText = new TextView(this);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        diagText.setTextIsSelectable(true);
        diagBox.addView(diagText);
        col.addView(diagBox);
        diag.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.DIAG, checked).apply();
            diagBox.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isServiceEnabled();
        status.setText(enabled ? "✅ NoReels je zapnutý" : "⚠️ NoReels je vypnutý — postupuj podľa krokov");
        diagBox.setVisibility(Prefs.on(prefs, Prefs.DIAG) ? View.VISIBLE : View.GONE);
        refreshDiag();
    }

    private void refreshDiag() {
        diagText.setText(DiagLog.dump());
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, BlockerService.class);
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(enabled);
        for (String part : s) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    private void openXiaomiAutostart() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Otvor to cez Nastavenia aplikácie → Autoštart", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    // --- pomocné ---

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Switch toggle(String label, String key) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(Prefs.on(prefs, key));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        return sw;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
