package dev.midnightbeam;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.Window;
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
    private TextView remoteInfo;
    private Button pairBtn;
    private Button appBtn;
    // Popup with a big QR code (phone pairing or app download), at most one open
    private Dialog popup;
    private boolean popupPair;
    private LinearLayout popupBox;
    private ImageView popupQr;
    private TextView popupPin;
    private TextView popupInfo;
    private TextView popupAddr;
    private Button popupNew;
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

    // Palette (same as the phone page, "Arcade" oxblood): gold leads, oxblood red answers, on a wine-black ground.
    // Warm only, no blue: this app is against blue light.
    static final int PRIMARY = Color.rgb(0xD7, 0x33, 0x37); // oxblood red: the hard offset shadow, the grabbed edge
    static final int ACCENT = Color.rgb(0xED, 0xC2, 0x3E); // gold: the cursor row, the title's lead
    static final int LIGHT = Color.rgb(0xF3, 0xEE, 0xE4);
    static final int BG = Color.rgb(0x18, 0x08, 0x0B);
    static final int SURFACE = Color.rgb(0x24, 0x10, 0x15);
    static final int RULE = Color.rgb(0x53, 0x32, 0x39);

    static final int FOCUS = ACCENT;
    static final int NORMAL = LIGHT;

    // What each filter slider does, from its minimum to its maximum (as on the phone page); the bar wears the value's colour.
    static final int[] SPECTRUM_RED = {0x8A7466, 0xC4402F, 0xE0262B};
    static final int[] SPECTRUM_BRIGHT = {0x5A4A4C, 0x8F8379, 0xF3EEE4};
    static final int[] SPECTRUM_TEMP = {0xFF3800, 0xFF6D00, 0xFF8912, 0xFFA148, 0xFFB46B, 0xFFC38A, 0xFFD1A3}; // 1000 to 4000 K

    /** The colour at {@code f} (0 to 1) along a spectrum. */
    static int spectrumAt(int[] sp, float f) {
        float at = Math.max(0f, Math.min(1f, f)) * (sp.length - 1);
        int i = Math.min(sp.length - 2, (int) at);
        float t = at - i;
        int a = sp[i], b = sp[i + 1];
        return Color.rgb(Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t),
                Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                Math.round((a & 255) * (1 - t) + (b & 255) * t));
    }

    /** The press surface: a 2dp border on a fill, with the red offset shadow; focused, it lights gold like a cursor. */
    Drawable surface(boolean focused) {
        int r = dp(4), off = dp(5);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(PRIMARY);
        shadow.setCornerRadius(r);
        GradientDrawable face = new GradientDrawable();
        face.setColor(focused ? ACCENT : SURFACE);
        face.setStroke(dp(2), focused ? BG : LIGHT);
        face.setCornerRadius(r);
        LayerDrawable d = new LayerDrawable(new Drawable[] {shadow, face});
        d.setLayerInset(0, off, off, 0, 0);
        d.setLayerInset(1, 0, 0, off, off);
        return d;
    }

    /** Puts the press surface on a button, the text centred on the face (the shadow takes the bottom-right). */
    void press(Button b, boolean focused) {
        b.setBackground(surface(focused));
        b.setTextColor(focused ? BG : LIGHT);
        b.setPadding(dp(20), dp(10), dp(25), dp(15));
    }

    /** Focus of a switch: the whole row turns into the cursor row (gold, dark text). */
    static void cursor(TextView v, boolean on) {
        v.setBackgroundColor(on ? ACCENT : Color.TRANSPARENT);
        v.setTextColor(on ? BG : LIGHT);
    }

    /** Arcade switch colours: gold thumb and red track when on, muted when off. */
    static void tint(Switch s) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[] {ACCENT, LIGHT}));
        s.setTrackTintList(new ColorStateList(states, new int[] {PRIMARY, RULE}));
    }

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
        DimService.seedSchedule(this, p);

        // Two columns (filter controls, phone remote QR code) above the schedule timeline and the Advanced section.
        // The whole screen scrolls: moving the focus with the remote brings each row into view.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        screen.setPadding(dp(36), dp(14), dp(36), dp(12));
        scroll.addView(screen);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        screen.addView(page, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        page.addView(root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 5f));

        TextView title = text("MIDNIGHTBEAM", 26, LIGHT);
        title.setShadowLayer(0.01f, -dp(2), 0, PRIMARY); // the split: one hard red edge, no blur
        title.setTag(Fonts.DISPLAY);
        title.setPadding(dp(8), 0, 0, dp(4));
        root.addView(title);

        enabled = new Switch(this);
        enabled.setText(t("Enabled", "Attivo"));
        enabled.setTextColor(LIGHT);
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        enabled.setPadding(dp(8), dp(8), dp(8), dp(8));
        tint(enabled);
        enabled.setChecked(!p.getBoolean("off", false));
        enabled.setFocusable(true);
        enabled.setOnFocusChangeListener(this);
        enabled.setOnCheckedChangeListener(this);
        root.addView(enabled);

        red = new Slider(this, root, t("Warm filter", "Filtro caldo"), "%", 0, 100, 5, p.getInt("red", 0));
        bright = new Slider(this, root, t("Brightness", "Luminosità"), "%", 5, 100, 5, p.getInt("bright", 100));
        temp = new Slider(this, root, t("Colour (lower = redder)", "Colore (più basso = più rosso)"), " K", 1000, 4000, 100,
                p.getInt("temp", DimService.DEFAULT_TEMP));
        red.paint(SPECTRUM_RED);
        bright.paint(SPECTRUM_BRIGHT);
        temp.paint(SPECTRUM_TEMP);

        // test card: the real filter covers it, so the sliders' effect on whites, blues, skin and shadows shows here
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(28), dp(6), 0, 0);
        page.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f));
        TextView cardTitle = text(t("Test image: whites, blue sky, colours, shadows", "Immagine di prova: bianchi, cielo blu, colori, ombre"), 13, NORMAL);
        cardTitle.setPadding(0, 0, 0, dp(6));
        mid.addView(cardTitle);
        mid.addView(new TestCardView(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        accessibilityHint = text(t("To cover the screensaver and the system bars too (and for full darkness), enable MidnightBeam in Accessibility",
                "Per coprire anche lo screensaver e le barre di sistema (e per il buio totale), attiva MidnightBeam in Accessibilità"), 13, LIGHT);
        accessibilityHint.setPadding(0, dp(12), 0, dp(6));
        accessibility = new Button(this);
        accessibility.setText(t("Enable", "Attiva"));
        accessibility.setAllCaps(false);
        press(accessibility, false);
        accessibility.setFocusable(true);
        accessibility.setOnClickListener(this);
        accessibility.setOnFocusChangeListener(this);
        mid.addView(accessibilityHint);
        mid.addView(accessibility, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(dp(28), 0, 0, 0);
        page.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));

        remote = new Switch(this);
        remote.setText(t("Phone remote", "Telecomando"));
        remote.setTextColor(LIGHT);
        remote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        remote.setPadding(dp(8), dp(8), dp(8), dp(8));
        tint(remote);
        remote.setChecked(p.getBoolean("remote", Compat.isTv(this)));
        remote.setFocusable(true);
        remote.setOnFocusChangeListener(this);
        remote.setOnCheckedChangeListener(this);
        side.addView(remote, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        remoteInfo = text("", 12, NORMAL);
        remoteInfo.setGravity(Gravity.CENTER_HORIZONTAL);
        side.addView(remoteInfo);

        pairBtn = sideButton(side, t("Pair a phone", "Abbina telefono"), dp(6));
        appBtn = sideButton(side, t("Android app", "App per Android"), dp(8));

        // the timeline's title and the Advanced button share one row
        LinearLayout tlRow = new LinearLayout(this);
        tlRow.setOrientation(LinearLayout.HORIZONTAL);
        tlRow.setGravity(Gravity.CENTER_VERTICAL);
        tlRow.setPadding(0, dp(4), 0, dp(6));
        screen.addView(tlRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        timelineTitle = text("", 15, NORMAL);
        timelineTitle.setPadding(dp(8), dp(4), dp(8), dp(4));
        tlRow.addView(timelineTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        advanced = button(tlRow);
        ((LinearLayout.LayoutParams) advanced.getLayoutParams()).topMargin = 0;
        timeline = new TimelineView(this);
        screen.addView(timeline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.HORIZONTAL);
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

    /**
     * After the Advanced section opens (once laid out): scrolls its row (timeline title, button) to the top of the
     * screen, so the timeline and the section's columns fill it; closed, back to the top of the page.
     */
    static final class ShowAdvanced implements Runnable {
        private final MainActivity activity;
        private final boolean open;

        ShowAdvanced(MainActivity activity, boolean open) {
            this.activity = activity;
            this.open = open;
        }

        @Override
        public void run() {
            View row = (View) activity.advanced.getParent();
            ((ScrollView) row.getParent().getParent()).smoothScrollTo(0, open ? row.getTop() : 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accessibility != null) {
            refreshAccessibility();
        }
        if (timeline != null) run();
        prefs.registerOnSharedPreferenceChangeListener(prefsSync);
        // the phone remote is on by default on TVs: make sure its server runs while the QR code is shown
        if (remote != null && remote.isChecked()) {
            Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", true));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(this);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsSync);
        if (popup != null) popup.dismiss();
        DimService.openPairing(false);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsSync = new PrefsSync(this);

    /** Hands every settings change (the phone remote writes them through the service) to the main thread. */
    static final class PrefsSync implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final MainActivity activity;

        PrefsSync(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            activity.main.post(new PrefChanged(activity, key));
        }
    }

    static final class PrefChanged implements Runnable {
        private final MainActivity activity;
        private final String key;

        PrefChanged(MainActivity activity, String key) {
            this.activity = activity;
            this.key = key;
        }

        @Override
        public void run() {
            activity.prefChanged(key);
        }
    }

    /** Shows a setting changed elsewhere (the phone): switches and sliders are set by code, nothing is sent back. */
    void prefChanged(String key) {
        if (key == null || !ready) return;
        switch (key) {
            case "off":
                updating = true;
                enabled.setChecked(!prefs.getBoolean("off", false));
                updating = false;
                break;
            case "red":
                follow(red, prefs.getInt("red", 0));
                break;
            case "bright":
                follow(bright, prefs.getInt("bright", 100));
                break;
            case "temp":
                follow(temp, prefs.getInt("temp", DimService.DEFAULT_TEMP));
                break;
            case "transition":
                if (transition != null) follow(transition, prefs.getInt("transition", 30));
                break;
            case "schedule":
            case "schedule_on":
            case "schedule_value":
                main.removeCallbacks(this);
                run();
                break;
            case "remote":
                updating = true;
                remote.setChecked(prefs.getBoolean("remote", Compat.isTv(this)));
                updating = false;
                break;
            case "key":
            case "pin":
                refreshPopup();
                break;
            default:
                break;
        }
    }

    /** Moves a slider to a value changed elsewhere, unless the user is moving it right now. */
    private static void follow(Slider s, int value) {
        if (!s.busy()) s.set(value);
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

    /**
     * Builds the Advanced section from the current settings (each time it opens: the phone may have changed them):
     * three columns across the bottom of the screen, schedule, audio and equalizer.
     */
    private void buildAdvanced() {
        advancedBox.removeAllViews();
        bands.clear();
        eqPreset = null;
        eqPresetIndex = -1; // not stored on the device: the phone may have changed the bands since
        fxOn = null;
        fxReset = null;
        LinearLayout sched = column(0), audio = column(dp(28)), eqCol = column(dp(28));

        sched.addView(section(t("Schedule", "Programmazione")));
        updating = true;
        scheduleOn = switchRow(sched, t("Schedule on", "Programmazione attiva"));
        scheduleOn.setChecked(prefs.getBoolean("schedule_on", false));
        scheduleOn.setEnabled(!timeline.isEmpty());
        transition = new Slider(this, sched, t("Gradual change at the end of each slot", "Passaggio graduale a fine fascia"),
                " min", 0, 60, 15, prefs.getInt("transition", 30));

        audio.addView(section("Audio"));
        JSONObject a;
        try {
            a = new JSONObject(audioJson());
        } catch (JSONException | RuntimeException e) {
            a = new JSONObject();
        }
        JSONObject vol = a.optJSONObject("volume");
        volume = vol == null ? null : new Slider(this, audio, t("Volume", "Volume"), "", 0,
                vol.optInt("max", 15), 1, vol.optInt("level"));
        bass = virt = loud = null;
        if (!a.optBoolean("supported", false)) {
            audio.addView(note(t("This device does not allow audio effects (equalizer, bass, surround).",
                    "Questo dispositivo non permette effetti audio (equalizzatore, bassi, surround).")));
        } else {
            fxOn = switchRow(audio, t("Audio effects", "Effetti audio"));
            fxOn.setChecked(a.optBoolean("enabled", false));
            audio.addView(note(t("Tune them by ear with something playing underneath (a film, music).",
                    "Regolali a orecchio con qualcosa in riproduzione sotto (un film, musica).")));
            JSONObject o = a.optJSONObject("bass");
            if (o != null) bass = new Slider(this, audio, t("Bass boost", "Bassi"), "%", 0, 100, 5, o.optInt("strength") / 10);
            o = a.optJSONObject("virt");
            if (o != null) virt = new Slider(this, audio, "Surround", "%", 0, 100, 5, o.optInt("strength") / 10);
            o = a.optJSONObject("loud");
            if (o != null) loud = new Slider(this, audio, t("Loudness", "Volume extra"), " dB", 0, 15, 1, o.optInt("gain") / 100);
            fxReset = button(audio);
            fxReset.setText(t("Reset audio effects", "Azzera effetti audio"));

            JSONObject eq = a.optJSONObject("eq");
            if (eq != null) {
                eqCol.addView(section(t("Equalizer", "Equalizzatore")));
                eqPresets = eq.optJSONArray("presets");
                if (eqPresets != null && eqPresets.length() > 0) {
                    eqPreset = button(eqCol);
                    refreshPreset();
                }
                JSONArray b = eq.optJSONArray("bands");
                int min = eq.optInt("min", -1500) / 100, max = eq.optInt("max", 1500) / 100;
                for (int i = 0; b != null && i < b.length(); i++) {
                    JSONObject band = b.optJSONObject(i);
                    int hz = band.optInt("freq");
                    String name = hz >= 1000 ? (hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.valueOf(Math.round(hz / 100f) / 10f)) + " kHz"
                            : hz + " Hz";
                    bands.add(new Slider(this, eqCol, name, " dB", min, max, 1, Math.round(band.optInt("level") / 100f)));
                }
            }
        }
        if (eqCol.getChildCount() == 0) advancedBox.removeView(eqCol);
        updating = false;
        Fonts.apply(advancedBox);
    }

    /** One column of the Advanced section (equal widths), {@code gap} from the previous one. */
    private LinearLayout column(int gap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(gap, 0, 0, 0);
        advancedBox.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return c;
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
        TextView v = text(s.toUpperCase(Locale.ROOT), 20, LIGHT);
        v.setTag(Fonts.DISPLAY);
        v.setPadding(0, dp(28), 0, dp(4));
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
        s.setPadding(dp(8), dp(12), dp(8), dp(12));
        tint(s);
        s.setFocusable(true);
        s.setOnFocusChangeListener(this);
        s.setOnCheckedChangeListener(this);
        parent.addView(s);
        return s;
    }

    private Button button(LinearLayout parent) {
        Button b = new Button(this);
        b.setAllCaps(false);
        press(b, false);
        b.setFocusable(true);
        b.setOnClickListener(this);
        b.setOnFocusChangeListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(b, lp);
        return b;
    }

    private Button sideButton(LinearLayout side, String label, int top) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        press(b, false);
        b.setFocusable(true);
        b.setOnClickListener(this);
        b.setOnFocusChangeListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        side.addView(b, lp);
        return b;
    }

    /** Info text and the Pair button: shown when the remote is on and the TV has a network address. */
    private void refreshRemote() {
        boolean on = remote.isChecked();
        boolean net = on && RemoteServer.localIp() != null;
        pairBtn.setVisibility(net ? View.VISIBLE : View.GONE);
        remoteInfo.setText(!on ? t("Control this filter from a phone on the same network.",
                "Comanda il filtro da un telefono sulla stessa rete.")
                : net ? t("Pair a phone to control this filter.", "Abbina un telefono per comandare il filtro.")
                : t("Not connected to a network.", "Nessuna rete collegata."));
        if (!net && popup != null && popupPair) popup.dismiss();
    }

    /** Opens the centred popup with a big QR code: phone pairing (QR link, PIN, new code) or the app download. */
    private void showPopup(boolean pair) {
        if (popup != null || (pair && RemoteServer.localIp() == null)) return;
        popupPair = pair;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.max(dp(160), Math.min(Math.round(dm.heightPixels * 0.75f), dm.widthPixels - dp(300) - dp(110)));
        // two columns, so the QR can be big and everything still fits a TV screen: QR left, texts right
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(24), dp(32), dp(24));
        popupQr = new ImageView(this);
        row.addView(popupQr, new LinearLayout.LayoutParams(side, side));
        popupBox = new LinearLayout(this);
        popupBox.setOrientation(LinearLayout.VERTICAL);
        popupBox.setGravity(Gravity.CENTER_HORIZONTAL);
        popupBox.setPadding(dp(28), 0, 0, 0);
        row.addView(popupBox, new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = text(pair ? t("PAIR", "ABBINA") : t("DOWNLOAD APP", "SCARICA L'APP"), 30, LIGHT);
        title.setTag(Fonts.DISPLAY);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(0.01f, -dp(2), 0, PRIMARY);
        title.setPadding(0, 0, 0, dp(10));
        popupBox.addView(title);
        TextView info = text(pair ? t("Scan with the phone camera, or in the MidnightBeam app enter the code",
                "Inquadra con la fotocamera del telefono, o nell'app MidnightBeam inserisci il codice")
                : t("Scan with an Android phone to download the app", "Inquadra con un telefono Android per scaricare l'app"),
                18, LIGHT);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        popupBox.addView(info);
        popupInfo = info;
        popupPin = null;
        popupAddr = null;
        popupNew = null;
        if (pair) {
            popupPin = text("", 64, ACCENT);
            popupPin.setGravity(Gravity.CENTER_HORIZONTAL);
            popupPin.setTag(Fonts.FIGURE);
            popupPin.setPadding(0, dp(8), 0, 0);
            popupBox.addView(popupPin);
            popupAddr = text("", 14, NORMAL);
            popupAddr.setGravity(Gravity.CENTER_HORIZONTAL);
            popupBox.addView(popupAddr);
            popupNew = new Button(this);
            popupNew.setText(t("New code", "Nuovo codice"));
            popupNew.setAllCaps(false);
            press(popupNew, false);
            popupNew.setFocusable(true);
            popupNew.setOnClickListener(this);
            popupNew.setOnFocusChangeListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            popupBox.addView(popupNew, lp);
        } else {
            popupAddr = text(Compat.fromFdroid(this) ? "f-droid.org/packages/dev.midnightbeam" : "github.com/MadeinTaly/midnightbeam",
                    14, NORMAL);
            popupAddr.setPadding(0, dp(10), 0, 0);
            popupAddr.setGravity(Gravity.CENTER_HORIZONTAL);
            popupBox.addView(popupAddr);
            popupBox.setFocusable(true);
            popupBox.setOnClickListener(this); // OK closes it
        }
        Fonts.apply(row);

        popup = new Dialog(this);
        popup.requestWindowFeature(Window.FEATURE_NO_TITLE);
        popup.setContentView(row);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setStroke(dp(2), RULE);
        bg.setCornerRadius(dp(4));
        popup.getWindow().setBackgroundDrawable(bg);
        popup.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOnDismissListener(new PopupClosed(this));
        if (pair) DimService.openPairing(true);
        refreshPopup();
        popup.show();
        (pair ? popupNew : popupBox).requestFocus();
    }

    /** Draws the open popup from the current key and PIN (they change with New code, or after too many wrong PINs). */
    private void refreshPopup() {
        if (popup == null) return;
        if (!popupPair) {
            popupQr.setImageBitmap(qrBitmap(Compat.appUrl(this)));
            popupQr.setContentDescription(Compat.appUrl(this));
            return;
        }
        String ip = RemoteServer.localIp();
        if (ip == null) {
            popup.dismiss();
            return;
        }
        popupQr.setImageBitmap(qrBitmap("http://" + ip + ":" + RemoteServer.PORT + "/#k=" + DimService.pairingKey(prefs)));
        boolean open = DimService.pairingOpen();
        popupPin.setText(open ? DimService.pairingPin(prefs) : "----");
        popupInfo.setText(open ? t("Scan with the phone camera, or in the MidnightBeam app enter the code",
                "Inquadra con la fotocamera del telefono, o nell'app MidnightBeam inserisci il codice")
                : t("Too many wrong codes: press New code", "Troppi codici errati: premi Nuovo codice"));
        popupAddr.setText(ip + ":" + RemoteServer.PORT);
    }

    void popupClosed() {
        popup = null;
        DimService.openPairing(false);
    }

    /** Dismiss listener of the popup (a plain class, see the note on inner classes above). */
    static final class PopupClosed implements DialogInterface.OnDismissListener {
        private final MainActivity activity;

        PopupClosed(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            activity.popupClosed();
        }
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
                Compat.enableOrOpenAccessibility(this);
            }
        } else if (v == pairBtn || v == appBtn) {
            showPopup(v == pairBtn);
        } else if (v == popupNew) {
            DimService.newPairingKey(prefs); // phones paired with the old code stop working
            DimService.openPairing(true);
            refreshPopup();
        } else if (v == popupBox) {
            popup.dismiss();
        } else if (v == advanced) {
            boolean open = advancedBox.getVisibility() != View.VISIBLE;
            if (open) buildAdvanced();
            advancedBox.setVisibility(open ? View.VISIBLE : View.GONE);
            refreshAdvanced();
            advancedBox.post(new ShowAdvanced(this, open));
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
        if (v instanceof Button && !(v instanceof CompoundButton)) {
            press((Button) v, hasFocus);
        } else if (v instanceof TextView) {
            cursor((TextView) v, hasFocus);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean checked) {
        if (b == remote) {
            if (!updating) {
                if (checked) DimService.pairingKey(prefs);
                Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", checked));
            }
            refreshRemote();
        } else if (b == scheduleOn) {
            if (!updating) setScheduleOn(checked);
        } else if (b == fxOn) {
            if (updating) return;
            try {
                sendAudio(new JSONObject().put("enabled", checked));
            } catch (JSONException ignored) {
            }
        } else if (!updating) {
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
        private final LinearLayout row;
        private final TextView caption;
        private final TextView figure;
        private final TextView unitView;
        private final SeekBar bar;
        private int[] spectrum;
        private long movedAt; // last move by the user (uptime ms)
        private boolean dragging; // finger on the bar

        Slider(MainActivity activity, LinearLayout parent, String label, String unit,
               int min, int max, int step, int value) {
            this.activity = activity;
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.step = step;
            caption = activity.text(label, 16, NORMAL);
            caption.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); // Chivo Bold, like the switches
            figure = activity.text("", 16, ACCENT);
            figure.setTag(Fonts.FIGURE);
            row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(activity.dp(8), activity.dp(3), activity.dp(8), activity.dp(3));
            row.addView(caption, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(figure);
            unitView = activity.text(unit.trim(), 15, ACCENT); // the unit beside the pixel figure, in Chivo Bold
            unitView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            unitView.setPadding(activity.dp(6), 0, 0, 0);
            row.addView(unitView);
            bar = new SeekBar(activity);
            bar.setMax((max - min) / step);
            bar.setKeyProgressIncrement(1);
            bar.setProgress((Math.max(min, Math.min(max, value)) - min) / step);
            bar.setOnSeekBarChangeListener(this);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(RULE));
            bar.setFocusable(true);
            bar.setOnFocusChangeListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = activity.dp(8);
            parent.addView(row, lp);
            parent.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            refresh();
        }

        int value() {
            return min + bar.getProgress() * step;
        }

        /** Colours the bar and the thumb with the value's colour along this setting's spectrum. */
        void paint(int[] spectrum) {
            this.spectrum = spectrum;
            refresh();
        }

        /** The user has this slider focused and moved it a moment ago: leave it alone. */
        boolean busy() {
            return dragging || bar.hasFocus() && android.os.SystemClock.uptimeMillis() - movedAt < 1500;
        }

        /** Shows a value read back from the device (not a user move: nothing is sent). */
        void set(int value) {
            bar.setProgress((value - min) / step);
            refresh();
        }

        private void refresh() {
            caption.setText((bar.hasFocus() ? "▶ " : "") + label);
            figure.setText(String.valueOf(value()));
            if (spectrum != null) {
                ColorStateList c = ColorStateList.valueOf(spectrumAt(spectrum, bar.getProgress() / (float) Math.max(1, bar.getMax())));
                bar.setProgressTintList(c);
                bar.setThumbTintList(c);
            }
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            row.setBackgroundColor(hasFocus ? ACCENT : Color.TRANSPARENT);
            caption.setTextColor(hasFocus ? BG : LIGHT);
            figure.setTextColor(hasFocus ? BG : ACCENT);
            unitView.setTextColor(hasFocus ? BG : ACCENT);
            bar.setBackgroundColor(hasFocus ? SURFACE : Color.TRANSPARENT);
            refresh();
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            refresh();
            if (fromUser) {
                movedAt = android.os.SystemClock.uptimeMillis();
                activity.sliderMoved(this);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            dragging = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            dragging = false;
            movedAt = android.os.SystemClock.uptimeMillis();
        }
    }
}
