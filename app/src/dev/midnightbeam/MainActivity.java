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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
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
    private Button getApp;
    private boolean showingApp; // the QR code shows the Android app download instead of the phone remote
    private Button accessibility;
    private TextView accessibilityHint;
    private Slider red;
    private Slider bright;
    private Slider temp;
    private boolean ready;
    // Advanced section (collapsed): schedule switches and audio, built each time it opens
    private Button advanced;
    private LinearLayout advancedBox;
    private Switch scheduleOn;
    private Slider transition;
    private Slider volume;
    private Switch fxOn;
    private Button eqPreset;
    private int eqPresetIndex = -1;
    private JSONArray eqPresets;
    private final List<Slider> bands = new ArrayList<>();
    private Slider bass;
    private Slider virt;
    private Slider loud;
    private Button fxReset;
    private boolean updating; // switches set by code, not by the user

    /** UI language: Italian on Italian devices, English otherwise. */
    static String t(String en, String it) {
        return "it".equals(Locale.getDefault().getLanguage()) ? it : en;
    }

    // Palette (same as the phone page): primary, accent, light, background, surface.
    static final int PRIMARY = Color.rgb(0xFF, 0x2B, 0x2B);
    static final int ACCENT = Color.rgb(0xFF, 0x6B, 0x4D);
    static final int LIGHT = Color.rgb(0xFF, 0xD9, 0xB3);
    static final int BG = Color.rgb(0x0B, 0x0B, 0x0F);
    static final int SURFACE = Color.rgb(0x2A, 0x1F, 0x1B); // warm dark, no blue: this app is against blue light

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

        // Two columns (filter controls, phone remote QR code) above the schedule timeline and the Advanced section.
        // The whole screen scrolls: moving the focus with the remote brings each row into view.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        int pad = dp(48);
        screen.setPadding(pad, dp(28), pad, dp(24));
        scroll.addView(screen);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        screen.addView(page, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        page.addView(root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));

        TextView title = text("MidnightBeam", 28, ACCENT);
        root.addView(title);

        accessibilityHint = text(t("To cover the screensaver and the system bars too (and for full darkness), enable MidnightBeam in Accessibility",
                "Per coprire anche lo screensaver e le barre di sistema (e per il buio totale), attiva MidnightBeam in Accessibilità"), 16, LIGHT);
        accessibilityHint.setPadding(0, dp(12), 0, dp(4));
        accessibility = new Button(this);
        accessibility.setText(t("Enable", "Attiva"));
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
        remote.setChecked(p.getBoolean("remote", Compat.isTv(this)));
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

        getApp = new Button(this);
        getApp.setAllCaps(false);
        getApp.setTextColor(LIGHT);
        getApp.setBackgroundColor(BUTTON_BACKGROUND);
        getApp.setPadding(dp(20), dp(10), dp(20), dp(10));
        getApp.setFocusable(true);
        getApp.setOnClickListener(this);
        getApp.setOnFocusChangeListener(this);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        appLp.topMargin = dp(10);
        side.addView(getApp, appLp);

        timelineTitle = text("", 15, NORMAL);
        timelineTitle.setPadding(0, dp(12), 0, dp(6));
        screen.addView(timelineTitle);
        timeline = new TimelineView(this);
        screen.addView(timeline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        advanced = button(screen);
        ((LinearLayout.LayoutParams) advanced.getLayoutParams()).topMargin = dp(20);
        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);
        screen.addView(advancedBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshAdvanced();

        Fonts.apply(screen); // same typeface as the phone app
        setContentView(scroll);
        refreshRemote();
        enabled.requestFocus();
        ready = true;
    }

    /** On a TV the hint stays until the service runs: without it the screensaver is drawn above the filter. */
    private void refreshAccessibility() {
        int vis = DimAccessibilityService.instance == null ? View.VISIBLE : View.GONE;
        accessibility.setVisibility(vis);
        accessibilityHint.setVisibility(vis);
    }

    private final Runnable accessibilityCheck = new AccessibilityCheck(this);

    /** Re-checks the service after the Enable button (a plain class, see the note on inner classes above). */
    static final class AccessibilityCheck implements Runnable {
        private final MainActivity activity;

        AccessibilityCheck(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.refreshAccessibility();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accessibility != null) {
            refreshAccessibility();
        }
        if (timeline != null) run();
        // the phone remote is on by default on TVs: make sure its server runs while the QR code is shown
        if (remote != null && remote.isChecked()) {
            Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", true));
        }
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
        String resolved = prefs.getString("schedule", "[]");
        timeline.setSchedule(DimService.todaySlots(resolved), on, resolved.trim().startsWith("{"));
        timeline.setVisibility(timeline.isEmpty() ? View.GONE : View.VISIBLE);
        refreshTimelineTitle();
        if (scheduleOn != null) {
            updating = true;
            scheduleOn.setChecked(on);
            scheduleOn.setEnabled(!timeline.isEmpty());
            updating = false;
        }
        main.postDelayed(this, 30000);
    }

    /** Title above the timeline: the schedule state, or what the remote does while the timeline has the focus. */
    void refreshTimelineTitle() {
        String hint = timeline.hint();
        boolean on = prefs.getBoolean("schedule_on", false);
        timelineTitle.setText(!hint.isEmpty() ? hint : timeline.isEmpty()
                ? t("Schedule: set it from the phone remote", "Programmazione: impostala dal telefono")
                : on ? t("Schedule \u00b7 on", "Programmazione \u00b7 attiva")
                : t("Schedule \u00b7 off (turn it on in Advanced or from the phone)",
                    "Programmazione \u00b7 spenta (attivala in Avanzate o dal telefono)"));
        timelineTitle.setTextColor(hint.isEmpty() ? NORMAL : FOCUS);
    }

    /**
     * A slot edge moved on the timeline: today's slot starting at {@code from} now starts at {@code to}. Updates
     * both the schedule the service runs (resolved) and the one the phone editor shows (value), then saves.
     */
    void moveScheduleEdge(String from, String to) {
        try {
            String r = prefs.getString("schedule", "[]").trim();
            String v = prefs.getString("schedule_value", r).trim();
            Object value = v.startsWith("{") ? new JSONObject(v) : new JSONArray(v);
            Object resolved;
            if (r.startsWith("{")) { // weekly: today's own slots
                JSONObject week = new JSONObject(r);
                String day = ScheduleMath.WEEKDAYS[ScheduleMath.mondayIndex(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))];
                JSONArray own = week.optJSONArray(day);
                if (own == null) return;
                retime(own, from, to);
                resolved = week;
                JSONObject w = value instanceof JSONObject ? ((JSONObject) value).optJSONObject("week") : null;
                if (w == null) value = new JSONObject().put("week", new JSONObject(week.toString()));
                else if (w.optJSONArray(day) != null) retime(w.getJSONArray(day), from, to);
                else w.put(day, new JSONArray(own.toString())); // was linked to a saved day: today gets its own copy
            } else {
                JSONArray day = new JSONArray(r);
                retime(day, from, to);
                resolved = day;
                if (value instanceof JSONArray) retime((JSONArray) value, from, to);
                else value = day;
            }
            sendSchedule(new JSONObject().put("enabled", prefs.getBoolean("schedule_on", false))
                    .put("resolved", resolved).put("value", value));
        } catch (JSONException ignored) {
        }
    }

    private static void retime(JSONArray slots, String from, String to) throws JSONException {
        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s != null && from.equals(s.optString("time"))) {
                s.put("time", to);
                return;
            }
        }
    }

    /** Schedule switch: the same schedule, on or off. */
    private void setScheduleOn(boolean on) {
        try {
            String r = prefs.getString("schedule", "[]").trim();
            String v = prefs.getString("schedule_value", r).trim();
            sendSchedule(new JSONObject().put("enabled", on)
                    .put("resolved", r.startsWith("{") ? new JSONObject(r) : new JSONArray(r))
                    .put("value", v.startsWith("{") ? new JSONObject(v) : new JSONArray(v)));
        } catch (JSONException ignored) {
        }
    }

    private void sendSchedule(JSONObject body) {
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("schedule", body.toString()));
        main.removeCallbacks(this);
        main.postDelayed(this, 500); // the service has saved it by then: redraw from the stored schedule
    }

    private void sendAudio(JSONObject body) {
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("audio", body.toString()));
    }

    // ---- Advanced section ----

    private void refreshAdvanced() {
        boolean open = advancedBox.getVisibility() == View.VISIBLE;
        advanced.setText((open ? "\u25be " : "\u25b8 ") + t("Advanced: schedule, audio", "Avanzate: programmazione, audio"));
    }

    /** Builds the Advanced rows from the current settings (each time it opens: the phone may have changed them). */
    private void buildAdvanced() {
        advancedBox.removeAllViews();
        bands.clear();
        eqPreset = null;
        eqPresetIndex = -1; // not stored on the device: the phone may have changed the bands since
        fxOn = null;
        fxReset = null;
        advancedBox.addView(section(t("Schedule", "Programmazione")));
        updating = true;
        scheduleOn = switchRow(advancedBox, t("Schedule on", "Programmazione attiva"));
        scheduleOn.setChecked(prefs.getBoolean("schedule_on", false));
        scheduleOn.setEnabled(!timeline.isEmpty());
        transition = new Slider(this, advancedBox, t("Gradual change at the end of each slot", "Passaggio graduale a fine fascia"),
                " min", 0, 60, 15, prefs.getInt("transition", 30));

        advancedBox.addView(section("Audio"));
        JSONObject a;
        try {
            a = new JSONObject(audioJson());
        } catch (JSONException | RuntimeException e) {
            a = new JSONObject();
        }
        JSONObject vol = a.optJSONObject("volume");
        volume = vol == null ? null : new Slider(this, advancedBox, t("Volume", "Volume"), "", 0,
                vol.optInt("max", 15), 1, vol.optInt("level"));
        bass = virt = loud = null;
        if (!a.optBoolean("supported", false)) {
            advancedBox.addView(note(t("This device does not allow audio effects (equalizer, bass, surround).",
                    "Questo dispositivo non permette effetti audio (equalizzatore, bassi, surround).")));
        } else {
            fxOn = switchRow(advancedBox, t("Audio effects", "Effetti audio"));
            fxOn.setChecked(a.optBoolean("enabled", false));
            advancedBox.addView(note(t("Tune them by ear with something playing underneath (a film, music).",
                    "Regolali a orecchio con qualcosa in riproduzione sotto (un film, musica).")));
            JSONObject eq = a.optJSONObject("eq");
            if (eq != null) {
                eqPresets = eq.optJSONArray("presets");
                if (eqPresets != null && eqPresets.length() > 0) {
                    eqPreset = button(advancedBox);
                    refreshPreset();
                }
                JSONArray b = eq.optJSONArray("bands");
                int min = eq.optInt("min", -1500) / 100, max = eq.optInt("max", 1500) / 100;
                for (int i = 0; b != null && i < b.length(); i++) {
                    JSONObject band = b.optJSONObject(i);
                    int hz = band.optInt("freq");
                    String name = hz >= 1000 ? (hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.valueOf(Math.round(hz / 100f) / 10f)) + " kHz"
                            : hz + " Hz";
                    bands.add(new Slider(this, advancedBox, t("Equalizer ", "Equalizzatore ") + name, " dB", min, max, 1,
                            Math.round(band.optInt("level") / 100f)));
                }
            }
            JSONObject o = a.optJSONObject("bass");
            if (o != null) bass = new Slider(this, advancedBox, t("Bass boost", "Bassi"), "%", 0, 100, 5, o.optInt("strength") / 10);
            o = a.optJSONObject("virt");
            if (o != null) virt = new Slider(this, advancedBox, "Surround", "%", 0, 100, 5, o.optInt("strength") / 10);
            o = a.optJSONObject("loud");
            if (o != null) loud = new Slider(this, advancedBox, t("Loudness", "Volume extra"), " dB", 0, 15, 1, o.optInt("gain") / 100);
            fxReset = button(advancedBox);
            fxReset.setText(t("Reset audio effects", "Azzera effetti audio"));
        }
        updating = false;
        Fonts.apply(advancedBox);
    }

    /** Audio state: from the service's effects when it runs (no effects are live otherwise). */
    private String audioJson() {
        AudioFx fx = DimService.runningAudio;
        return (fx != null ? fx : new AudioFx(this, prefs)).json();
    }

    private void refreshPreset() {
        eqPreset.setText(t("Equalizer preset: ", "Preset equalizzatore: ")
                + (eqPresetIndex >= 0 ? eqPresets.optString(eqPresetIndex) : t("custom", "personalizzato"))
                + t("  (OK: next)", "  (OK: successivo)"));
    }

    /** An effect changed: sent with the effects switched on (changing one means wanting to hear it). */
    private void sendEffect(JSONObject body) throws JSONException {
        if (fxOn != null && !fxOn.isChecked()) {
            updating = true;
            fxOn.setChecked(true);
            updating = false;
            body.put("enabled", true);
        }
        sendAudio(body);
    }

    private TextView section(String s) {
        TextView v = text(s, 20, ACCENT);
        v.setPadding(0, dp(24), 0, 0);
        return v;
    }

    private TextView note(String s) {
        TextView v = text(s, 15, NORMAL);
        v.setPadding(0, dp(6), 0, 0);
        return v;
    }

    private Switch switchRow(LinearLayout parent, String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(LIGHT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        s.setPadding(0, dp(16), 0, dp(8));
        s.setFocusable(true);
        s.setOnFocusChangeListener(this);
        s.setOnCheckedChangeListener(this);
        parent.addView(s);
        return s;
    }

    private Button button(LinearLayout parent) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(LIGHT);
        b.setBackgroundColor(BUTTON_BACKGROUND);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setFocusable(true);
        b.setOnClickListener(this);
        b.setOnFocusChangeListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(b, lp);
        return b;
    }

    /** Shows the QR code and the address of the phone remote when it is on. */
    private void refreshRemote() {
        getApp.setText(showingApp ? t("Back to the phone remote", "Torna al telecomando")
                : t("Android app", "App per Android"));
        if (showingApp) { // QR code of the app (F-Droid page or latest APK, like this copy), for phones and tablets
            qr.setVisibility(View.VISIBLE);
            newKey.setVisibility(View.GONE);
            qr.setImageBitmap(qrBitmap(Compat.appUrl(this)));
            remoteInfo.setText(t("Scan with an Android phone to download the app",
                    "Inquadra con un telefono Android per scaricare l'app") + "\n"
                    + (Compat.fromFdroid(this) ? "f-droid.org/packages/dev.midnightbeam" : "github.com/MadeinTaly/midnightbeam"));
            return;
        }
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
                + "\n" + ip + ":" + RemoteServer.PORT
                + "\n" + t("Code", "Codice") + " " + DimService.pairingKey(prefs).replaceAll("(.{4})(?!$)", "$1 "));
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
            if (Compat.enableAccessibility(this)) {
                main.postDelayed(accessibilityCheck, 1500); // the service connects a moment later
            } else {
                startActivity(Compat.accessibilitySettings());
            }
        } else if (v == getApp) {
            showingApp = !showingApp;
            refreshRemote();
        } else if (v == newKey) {
            DimService.newPairingKey(prefs); // phones paired with the old code stop working
            refreshRemote();
        } else if (v == advanced) {
            boolean open = advancedBox.getVisibility() != View.VISIBLE;
            if (open) buildAdvanced();
            advancedBox.setVisibility(open ? View.VISIBLE : View.GONE);
            refreshAdvanced();
        } else if (v == eqPreset) {
            eqPresetIndex = (eqPresetIndex + 1) % eqPresets.length();
            refreshPreset();
            try {
                sendEffect(new JSONObject().put("preset", eqPresetIndex));
            } catch (JSONException ignored) {
            }
            main.postDelayed(audioRefresh, 300); // the preset set the bands: show them
        } else if (v == fxReset) {
            try {
                sendAudio(new JSONObject().put("reset", true).put("bands", new JSONArray()));
            } catch (JSONException ignored) {
            }
            eqPresetIndex = -1;
            if (eqPreset != null) refreshPreset();
            main.postDelayed(audioRefresh, 300);
        }
    }

    private final Runnable audioRefresh = new AudioRefresh(this);

    /** Reads the effects back after a preset or a reset (a plain class, see the note on inner classes above). */
    static final class AudioRefresh implements Runnable {
        private final MainActivity activity;

        AudioRefresh(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.refreshEffects();
        }
    }

    void refreshEffects() {
        try {
            JSONObject a = new JSONObject(audioJson());
            JSONObject eq = a.optJSONObject("eq");
            JSONArray b = eq == null ? null : eq.optJSONArray("bands");
            for (int i = 0; b != null && i < b.length() && i < bands.size(); i++) {
                bands.get(i).set(Math.round(b.getJSONObject(i).optInt("level") / 100f));
            }
            JSONObject o = a.optJSONObject("bass");
            if (o != null && bass != null) bass.set(o.optInt("strength") / 10);
            o = a.optJSONObject("virt");
            if (o != null && virt != null) virt.set(o.optInt("strength") / 10);
            o = a.optJSONObject("loud");
            if (o != null && loud != null) loud.set(o.optInt("gain") / 100);
        } catch (JSONException | RuntimeException ignored) {
        }
    }

    /** Focus highlight of the switches and the button (the sliders handle their own). */
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (v instanceof TextView) ((TextView) v).setTextColor(hasFocus ? FOCUS : LIGHT);
        v.setBackgroundColor(hasFocus ? FOCUS_BACKGROUND
                : v instanceof Button && !(v instanceof CompoundButton) ? BUTTON_BACKGROUND : Color.TRANSPARENT);
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean checked) {
        if (b == remote) {
            if (checked) DimService.pairingKey(prefs);
            Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", checked));
            refreshRemote();
        } else if (b == scheduleOn) {
            if (!updating) setScheduleOn(checked);
        } else if (b == fxOn) {
            if (updating) return;
            try {
                sendAudio(new JSONObject().put("enabled", checked));
            } catch (JSONException ignored) {
            }
        } else {
            apply();
        }
    }

    /** Called by a slider moved by the user. */
    void sliderMoved(Slider s) {
        try {
            if (s == transition) {
                prefs.edit().putInt("transition", s.value()).apply(); // read by the service at every schedule tick
            } else if (s == volume) {
                sendAudio(new JSONObject().put("volume", s.value()));
            } else if (bands.contains(s)) {
                JSONArray levels = new JSONArray();
                for (Slider band : bands) levels.put(band.value() * 100);
                eqPresetIndex = -1;
                if (eqPreset != null) refreshPreset();
                sendEffect(new JSONObject().put("bands", levels));
            } else if (s == bass || s == virt) {
                sendEffect(new JSONObject().put(s == bass ? "bass" : "virt", s.value() * 10));
            } else if (s == loud) {
                sendEffect(new JSONObject().put("loud", s.value() * 100));
            } else {
                filterMoved();
            }
        } catch (JSONException ignored) {
        }
    }

    /** Moving a filter slider also turns the filter on. */
    private void filterMoved() {
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

        /** Shows a value read back from the device (not a user move: nothing is sent). */
        void set(int value) {
            bar.setProgress((value - min) / step);
            refresh();
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
            if (fromUser) activity.sliderMoved(this);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
