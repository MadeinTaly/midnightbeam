package dev.midnightbeam;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

import io.nayuki.qrcodegen.QrCode;

/**
 * Remote-friendly settings screen for cheap TV-box remotes: only D-pad and OK are needed
 * (up/down to move, left/right to change, OK to switch on/off). The focused row is highlighted
 * explicitly, because many TV-box themes show no visible focus. Changes apply live.
 *
 * No inner or anonymous classes on purpose: javac 21+ gives their constructors a MethodParameters
 * attribute with an unnamed parameter, which crashes older d8 versions (e.g. build-tools 34).
 */
public class MainActivity extends Activity
        implements CompoundButton.OnCheckedChangeListener, OnFocusChangeListener, View.OnClickListener, Runnable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TimelineView timeline;
    private TextView timelineTitle;
    private Switch enabled;
    private Switch remote;
    private ImageView qr;
    private TextView remoteInfo;
    private Button newKey;
    private Button accessibility;
    private TextView accessibilityHint;
    private Slider red;
    private Slider bright;
    private Slider temp;
    private boolean ready;

    /** UI language: Italian on Italian devices, English otherwise. */
    static String t(String en, String it) {
        return "it".equals(Locale.getDefault().getLanguage()) ? it : en;
    }

    // Palette (same as the phone page): primary, accent, light, background, surface.
    static final int PRIMARY = Color.rgb(0xFF, 0x2B, 0x2B);
    static final int ACCENT = Color.rgb(0xFF, 0x6B, 0x4D);
    static final int LIGHT = Color.rgb(0xFF, 0xD9, 0xB3);
    static final int BG = Color.rgb(0x0B, 0x0B, 0x0F);
    static final int SURFACE = Color.rgb(0x20, 0x30, 0x4A);

    static final int FOCUS = ACCENT;
    static final int NORMAL = LIGHT;
    static final int FOCUS_BACKGROUND = SURFACE;
    static final int BUTTON_BACKGROUND = SURFACE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Compat.isTv(this)) {
            startActivity(new Intent(this, PhoneActivity.class));
            finish();
            return;
        }
        SharedPreferences p = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);
        prefs = p;

        // Two columns (filter controls, phone remote QR code) above the schedule timeline.
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        int pad = dp(48);
        screen.setPadding(pad, dp(28), pad, dp(24));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        screen.addView(page, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        page.addView(root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));

        TextView title = text("MidnightBeam", 28, ACCENT);
        root.addView(title);

        accessibilityHint = text(t("For full darkness and to cover the system bars, enable MidnightBeam in Accessibility",
                "Per un oscuramento totale e per coprire le barre di sistema, attiva MidnightBeam in Accessibilità"), 16, LIGHT);
        accessibilityHint.setPadding(0, dp(12), 0, dp(4));
        accessibility = new Button(this);
        accessibility.setText(t("Open Accessibility settings", "Apri impostazioni Accessibilità"));
        accessibility.setAllCaps(false);
        accessibility.setTextColor(LIGHT);
        accessibility.setBackgroundColor(BUTTON_BACKGROUND);
        accessibility.setFocusable(true);
        accessibility.setOnClickListener(this);
        accessibility.setOnFocusChangeListener(this);
        root.addView(accessibilityHint);
        root.addView(accessibility, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        enabled = new Switch(this);
        enabled.setText(t("Enabled", "Attivo"));
        enabled.setTextColor(LIGHT);
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        enabled.setPadding(0, dp(24), 0, dp(8));
        enabled.setChecked(!p.getBoolean("off", false));
        enabled.setFocusable(true);
        enabled.setOnFocusChangeListener(this);
        enabled.setOnCheckedChangeListener(this);
        root.addView(enabled);

        red = new Slider(this, root, t("Warm filter", "Filtro caldo"), "%", 0, 100, 5, p.getInt("red", 0));
        bright = new Slider(this, root, t("Brightness", "Luminosità"), "%", 5, 100, 5, p.getInt("bright", 100));
        temp = new Slider(this, root, t("Colour temperature (lower = redder)", "Colore (più basso = più rosso)"), " K", 1000, 4000, 100,
                p.getInt("temp", DimService.DEFAULT_TEMP));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(dp(40), dp(8), 0, 0);
        page.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));

        remote = new Switch(this);
        remote.setText(t("Phone remote", "Telecomando dal telefono"));
        remote.setTextColor(LIGHT);
        remote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        remote.setPadding(dp(8), dp(8), dp(8), dp(8));
        remote.setChecked(p.getBoolean("remote", false));
        remote.setFocusable(true);
        remote.setOnFocusChangeListener(this);
        remote.setOnCheckedChangeListener(this);
        side.addView(remote, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        qr = new ImageView(this);
        qr.setPadding(0, dp(16), 0, dp(8));
        side.addView(qr, new LinearLayout.LayoutParams(dp(210), dp(226)));

        remoteInfo = text("", 14, NORMAL);
        remoteInfo.setGravity(Gravity.CENTER_HORIZONTAL);
        side.addView(remoteInfo);

        newKey = new Button(this);
        newKey.setText(t("New pairing code", "Nuovo codice di abbinamento"));
        newKey.setAllCaps(false);
        newKey.setTextColor(LIGHT);
        newKey.setBackgroundColor(BUTTON_BACKGROUND);
        newKey.setPadding(dp(20), dp(10), dp(20), dp(10));
        newKey.setFocusable(true);
        newKey.setOnClickListener(this);
        newKey.setOnFocusChangeListener(this);
        side.addView(newKey, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        timelineTitle = text("", 15, NORMAL);
        timelineTitle.setPadding(0, dp(12), 0, dp(6));
        screen.addView(timelineTitle);
        timeline = new TimelineView(this);
        screen.addView(timeline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        setContentView(screen);
        refreshRemote();
        enabled.requestFocus();
        ready = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accessibility != null) {
            int vis = Compat.needsAccessibilityHint() ? View.VISIBLE : View.GONE;
            accessibility.setVisibility(vis);
            accessibilityHint.setVisibility(vis);
        }
        if (timeline != null) run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(this);
    }

    /** Refreshes the schedule timeline (set from the phone page) every 30 s while the screen is open. */
    @Override
    public void run() {
        boolean on = prefs.getBoolean("schedule_on", false);
        timeline.setSchedule(DimService.todaySlots(prefs.getString("schedule", "[]")), on);
        timelineTitle.setText(timeline.isEmpty()
                ? t("Schedule: set it from the phone remote", "Programmazione: impostala dal telefono")
                : on ? t("Schedule \u00b7 on", "Programmazione \u00b7 attiva")
                : t("Schedule \u00b7 off (turn it on from the phone remote)",
                    "Programmazione \u00b7 spenta (attivala dal telefono)"));
        timeline.setVisibility(timeline.isEmpty() ? View.GONE : View.VISIBLE);
        main.postDelayed(this, 30000);
    }

    /** Shows the QR code and the address of the phone remote when it is on. */
    private void refreshRemote() {
        boolean on = remote.isChecked();
        qr.setVisibility(on ? View.VISIBLE : View.GONE);
        newKey.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            remoteInfo.setText(t("Control this filter from a phone on the same network.",
                    "Comanda il filtro da un telefono sulla stessa rete."));
            return;
        }
        String ip = RemoteServer.localIp();
        if (ip == null) {
            qr.setVisibility(View.GONE);
            remoteInfo.setText(t("Not connected to a network.", "Nessuna rete collegata."));
            return;
        }
        String url = "http://" + ip + ":" + RemoteServer.PORT + "/#k=" + DimService.pairingKey(prefs);
        qr.setImageBitmap(qrBitmap(url));
        remoteInfo.setText(t("Scan with the phone camera", "Inquadra con la fotocamera del telefono")
                + "\n" + ip + ":" + RemoteServer.PORT);
    }

    /** QR code as a bitmap: black modules on white, with the standard 4-module quiet zone. */
    private static Bitmap qrBitmap(String text) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 4;
        int size = code.size + border * 2;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = code.getModule(x - border, y - border);
                px[y * size + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap small = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(small, size * 8, size * 8, false); // crisp, no smoothing
    }

    @Override
    public void onClick(View v) {
        if (v == accessibility) {
            startActivity(Compat.accessibilitySettings());
        } else if (v == newKey) {
            DimService.newPairingKey(prefs); // phones paired with the old code stop working
            refreshRemote();
        }
    }

    /** Focus highlight of the switches and the button (the sliders handle their own). */
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (v instanceof TextView) ((TextView) v).setTextColor(hasFocus ? FOCUS : LIGHT);
        v.setBackgroundColor(hasFocus ? FOCUS_BACKGROUND : v == newKey || v == accessibility ? BUTTON_BACKGROUND : Color.TRANSPARENT);
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean checked) {
        if (b == remote) {
            if (checked) DimService.pairingKey(prefs);
            Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", checked));
            refreshRemote();
        } else {
            apply();
        }
    }

    /** Called by a slider moved by the user: moving a slider also turns the filter on. */
    void sliderMoved() {
        if (enabled.isChecked()) {
            apply();
        } else {
            enabled.setChecked(true); // onCheckedChanged applies
        }
    }

    private void apply() {
        if (!ready) return;
        Intent i = new Intent(this, DimService.class);
        if (enabled.isChecked()) {
            i.putExtra("red", red.value()).putExtra("bright", bright.value()).putExtra("temp", temp.value());
        } else {
            i.putExtra("off", true);
        }
        Compat.startForegroundService(this, i);
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Label + value + SeekBar; SeekBar has no setMin() before API 26, so values are offset/scaled. */
    static final class Slider implements SeekBar.OnSeekBarChangeListener, OnFocusChangeListener {
        private final MainActivity activity;
        private final String label;
        private final String unit;
        private final int min;
        private final int step;
        private final TextView caption;
        private final SeekBar bar;

        Slider(MainActivity activity, LinearLayout parent, String label, String unit,
               int min, int max, int step, int value) {
            this.activity = activity;
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.step = step;
            caption = activity.text("", 18, NORMAL);
            caption.setPadding(0, activity.dp(20), 0, activity.dp(4));
            caption.setGravity(Gravity.START);
            bar = new SeekBar(activity);
            bar.setMax((max - min) / step);
            bar.setKeyProgressIncrement(1);
            bar.setProgress((Math.max(min, Math.min(max, value)) - min) / step);
            bar.setOnSeekBarChangeListener(this);
            bar.setFocusable(true);
            bar.setOnFocusChangeListener(this);
            parent.addView(caption);
            parent.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            refresh();
        }

        int value() {
            return min + bar.getProgress() * step;
        }

        private void refresh() {
            caption.setText((bar.hasFocus() ? "▶ " : "") + label + ": " + value() + unit);
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            caption.setTextColor(hasFocus ? FOCUS : NORMAL);
            bar.setBackgroundColor(hasFocus ? FOCUS_BACKGROUND : Color.TRANSPARENT);
            refresh();
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            refresh();
            if (fromUser) activity.sliderMoved();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
