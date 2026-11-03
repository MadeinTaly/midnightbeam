package dev.midnightbeam;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.Locale;

/**
 * Full-screen, click-through overlay that dims the screen and adds a warm (red) tint.
 * Values change live, without restarting anything:
 *
 *   am start-foreground-service -n dev.midnightbeam/.DimService --ei red 60 --ei bright 40 --ei temp 1100
 *   am start-foreground-service -n dev.midnightbeam/.DimService --ez off true
 *   am start-foreground-service -n dev.midnightbeam/.DimService --ez remote true    (phone remote on/off)
 *
 * red    0-100      strength of the warm filter
 * bright 5-100      100 = no dimming
 * temp   1000-6500  filter colour temperature in kelvin (default 1100, a deep red)
 *
 * It also runs the phone remote (RemoteServer) and the daily schedule: time slots, the active one is the
 * last slot whose time has passed (wrapping over midnight), applied once when it becomes active, so a manual
 * change lasts until the next slot. Checked every minute.
 * Current state: dumpsys activity service dev.midnightbeam/.DimService
 */
public class DimService extends Service implements Runnable {
    static final String PREFS = "dim";
    private static final String CHANNEL = "dim";
    private static final int TYPE_ACCESSIBILITY_OVERLAY = 2032;
    private static final int TYPE_APPLICATION_OVERLAY = 2038; // API 26 constant, not in the API 23 stubs
    private static final float MAX_FILTER_ALPHA = 0.65f; // opacity of the filter at red=100
    static final int DEFAULT_TEMP = 1100;
    private static final int MAX_SLOTS = 24;
    private static final int MAX_DAYS = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private View view;
    private WindowManager viewWm;
    private boolean viewAccessibility;
    private volatile boolean clamped;
    /** The overlay could not be added: the "Display over other apps" permission is missing. */
    private volatile boolean blocked;
    private RemoteServer server;
    private boolean destroyed; // main thread only
    private Advertiser advertiser;
    private Osd osd;
    private SessionReceiver sessionReceiver;
    private SessionScanner scanner;
    private AudioFx audio;
    /** Audio effects of the running service, if any: the TV screen reads them here, a second set would take over. */
    static volatile AudioFx runningAudio;
    /** The running service, for the dev debug snapshot (Dbg). */
    static volatile DimService instance;

    // Read by the server thread, so volatile.
    private volatile int red;
    private volatile int bright = 100;
    private volatile int temp = DEFAULT_TEMP;
    private volatile boolean off;
    private volatile boolean remote;
    private boolean local; // phone UI open: serve the page on loopback only (not persisted)
    private volatile boolean scheduleOn;
    private volatile String scheduleResolved = "[]"; // daily Slot[] or weekly {mon:[..],..}, normalised
    private volatile String scheduleValue = "[]";    // as sent by the page (dayrhythm value), for reloading the editor
    // Advanced filter: a SurfaceFlinger colour matrix instead of the overlay, when the device offers a privileged shell.
    private volatile boolean advanced;
    private volatile boolean advancedApplied;
    private volatile boolean advancedTrial;
    private String lastMatrix;
    private int matrixRetries;
    private boolean matrixRetryDue;
    private final Runnable advancedTrialTimeout = new TrialTimeout(this);

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Dbg.on(this);
        Dbg.log("service", "create");
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("advanced_trial", false)) {
            prefs.edit().putBoolean("advanced", false).putBoolean("advanced_trial", false).apply();
        }
        audio = new AudioFx(this, prefs);
        runningAudio = audio;
        sessionReceiver = new SessionReceiver(this);
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        registerReceiver(sessionReceiver, f);
    }

    /** A player opened or closed its audio session (SessionReceiver). */
    void audioSession(int session, boolean open) {
        audio.session(session, open);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        load();
        if (intent != null) {
            if (Dbg.on(this) && intent.getExtras() != null) {
                StringBuilder sb = new StringBuilder();
                for (String k : intent.getExtras().keySet()) {
                    String v = String.valueOf(intent.getExtras().get(k));
                    sb.append(' ').append(k).append('=').append(v.length() > 80 ? v.substring(0, 80) + "..." : v);
                }
                Dbg.log("service", "start extras:" + sb);
            }
            if (intent.hasExtra("devPriv") && Dbg.on(this)) {
                prefs.edit().putBoolean("devPriv", intent.getBooleanExtra("devPriv", false)).apply();
            }
            if (intent.hasExtra("advanced")) setAdvanced(intent.getBooleanExtra("advanced", advanced));
            if (intent.getBooleanExtra("advancedConfirm", false)) confirmAdvanced();
            boolean newValues = intent.hasExtra("red") || intent.hasExtra("bright") || intent.hasExtra("temp");
            if (newValues || intent.hasExtra("off")) {
                setValues(intent.getIntExtra("red", red), intent.getIntExtra("bright", bright),
                        intent.getIntExtra("temp", temp), intent.getBooleanExtra("off", !newValues && off));
                holdManual();
            }
            if (intent.hasExtra("remote")) {
                remote = intent.getBooleanExtra("remote", false);
                prefs.edit().putBoolean("remote", remote).apply();
            }
            if (intent.hasExtra("refresh")) removeOverlay(); // re-created in the right window by render()
            if (intent.hasExtra("local")) local = intent.getBooleanExtra("local", false);
            if (intent.getBooleanExtra("newkey", false)) newPairingKey(prefs);
            try { // from the TV settings screen: same bodies as POST /api/schedule and /api/audio
                if (intent.hasExtra("schedule")) {
                    JSONObject j = new JSONObject(intent.getStringExtra("schedule"));
                    applySchedule(j.optBoolean("enabled", false), j);
                }
                if (intent.hasExtra("audio")) applyAudio(new JSONObject(intent.getStringExtra("audio")));
            } catch (JSONException ignored) {
            }
        }
        main.removeCallbacks(this);
        run(); // schedule tick now, then every minute
        return render();
    }

    private void load() {
        red = prefs.getInt("red", 0);
        bright = prefs.getInt("bright", 100);
        temp = prefs.getInt("temp", DEFAULT_TEMP);
        off = prefs.getBoolean("off", false);
        remote = prefs.getBoolean("remote", Compat.isTv(this)); // on TVs the phone remote is on by default
        scheduleOn = prefs.getBoolean("schedule_on", false);
        advanced = prefs.getBoolean("advanced", false);
        // a matrix left by a process that died while it was on: the first render re-applies it or clears it
        advancedApplied = prefs.getBoolean("matrix_on", false);
        seedSchedule(this, prefs);
        scheduleResolved = prefs.getString("schedule", "[]");
        scheduleValue = prefs.getString("schedule_value", scheduleResolved);
        audio.refresh();
    }

    /**
     * TVs without a phone could not make slots at all: on a TV that has never had a schedule, store the phone page's
     * "Movie night" preset (switched off), so the remote can move its edges and turn it on.
     */
    static void seedSchedule(Context context, SharedPreferences prefs) {
        if (prefs.contains("schedule") || !Compat.isTv(context)) return;
        String d = defaultSchedule();
        prefs.edit().putString("schedule", d).putString("schedule_value", d).commit();
    }

    /** The "Movie night" preset of app.html/remote.html, with the same model values. */
    private static String defaultSchedule() {
        Object[][] slots = {
                {"07:00", "off", false, 0, 100, 1100, MainActivity.t("Morning", "Mattina")},
                {"14:00", "off", false, 0, 100, 1100, MainActivity.t("Afternoon", "Pomeriggio")},
                {"19:30", "evening", true, 20, 40, 1100, MainActivity.t("Evening", "Sera")},
                {"21:00", "cinema", true, 15, 55, 1500, MainActivity.t("Movie", "Film")},
                {"23:30", "night", true, 20, 10, 1100, MainActivity.t("Night", "Notte")}};
        JSONArray a = new JSONArray();
        try {
            for (Object[] s : slots) {
                a.put(new JSONObject().put("time", s[0]).put("model", s[1]).put("on", s[2]).put("red", s[3])
                        .put("bright", s[4]).put("temp", s[5]).put("label", s[6]));
            }
        } catch (JSONException ignored) {
        }
        return a.toString();
    }

    private void setValues(int r, int b, int t, boolean o) {
        red = clamp(r, 0, 100);
        bright = clamp(b, 5, 100);
        temp = clamp(t, 1000, 6500);
        off = o;
        prefs.edit().putInt("red", red).putInt("bright", bright).putInt("temp", temp).putBoolean("off", off).apply();
    }

    /** Shows/updates/removes the overlay and the server, and stops the service when nothing needs it. */
    private int render() {
        if (destroyed) return START_NOT_STICKY; // a task queued on main can run after onDestroy
        boolean visible = !off && !(red == 0 && bright == 100);
        boolean wantMatrix = advanced && visible && Priv.available(this);
        // after a failure the matrix is tried again only when a retry is due, the overlay covers meanwhile
        boolean useMatrix = wantMatrix && (Priv.lastOk || matrixRetryDue);
        matrixRetryDue = false;
        if (useMatrix) {
            String command = matrixCommand(red, bright, temp);
            boolean fresh = !command.equals(lastMatrix) || !Priv.lastOk;
            // the overlay goes once the shell has answered yes. Exception: the very first su call, whose root
            // prompt ignores taps while an overlay covers it. A retry after a failure keeps the overlay meanwhile.
            boolean firstSu = !Priv.verified && Priv.lastOk && Priv.SU.equals(Priv.method(this));
            if ((Priv.verified && Priv.lastOk) || firstSu) removeOverlay();
            else showOverlay(overlayColor(red, bright, temp));
            if (fresh) {
                lastMatrix = command;
                setMatrixOn(true);
                Dbg.log("matrix", "apply red=" + red + " bright=" + bright + " temp=" + temp);
                int before = Priv.results;
                Priv.exec(this, command);
                // check the outcome once the shell answered: on failure render() shows the overlay again and
                // schedules a retry (the owner may take a while to answer a root prompt)
                main.postDelayed(new MatrixCheck(this, before, 0), 200);
            } else {
                matrixRetries = 0;
            }
        } else {
            resetMatrix();
            if (visible) showOverlay(overlayColor(red, bright, temp)); else removeOverlay();
            // the privileged shell may be a moment away (the system asking the owner, or a slow bind): try again
            if (wantMatrix && !Priv.lastOk && matrixRetries < 10) {
                matrixRetries++;
                main.postDelayed(new RenderLater(this, true), 5000L * matrixRetries);
            }
        }
        if (server != null && (!(remote || local) || server.lan != remote)) {
            Dbg.log("server", "stop");
            server.shutdown();
            server = null;
            if (advertiser != null) advertiser.stop();
        }
        if ((remote || local) && server == null) {
            pairingKey(prefs);
            server = new RemoteServer(this, remote);
            if (!server.bind()) server = null; // port busy: tried again on the next render
            else {
                server.start();
                Dbg.log("server", "start port " + RemoteServer.PORT + (remote ? " lan" : " loopback"));
            }
            if (server != null && remote) { // announced on the network only when it is reachable from it
                if (advertiser == null) advertiser = new Advertiser(this);
                advertiser.start(deviceName(), deviceId(prefs), Compat.deviceType(this));
            }
        }
        // players that do not announce their audio session: found by reading the audio service (needs DUMP)
        if (audio.enabled() && scanner == null && SessionScanner.allowed(this)) {
            scanner = new SessionScanner(this, audio);
            scanner.start();
        } else if (!audio.enabled() && scanner != null) {
            scanner.finish();
            scanner = null;
        }
        stateChanged();
        if (!visible && !remote && !local && !scheduleOn && !audio.enabled()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).notify(1, buildNotification());
        return START_STICKY;
    }

    // ---- Phone remote (called on the main thread by RemoteServer tasks) ----

    /**
     * The one place the advanced filter is switched (TV screen and phone alike): turning it on before the owner
     * ever kept it starts the 15 s trial, which every screen shows from "advanced_trial" and the state JSON.
     */
    private void setAdvanced(boolean on) {
        // already on (trial running or kept): nothing to restart. Off always goes through, it also ends a trial.
        if (on && advanced && (advancedTrial || prefs.getBoolean("advanced_ok", false))) return;
        advanced = on;
        advancedTrial = on && !prefs.getBoolean("advanced_ok", false);
        prefs.edit().putBoolean("advanced", advanced).putBoolean("advanced_trial", advancedTrial).apply();
        main.removeCallbacks(advancedTrialTimeout);
        if (advancedTrial) main.postDelayed(advancedTrialTimeout, 15000L);
        lastMatrix = null;
        matrixRetries = 0;
    }

    private void confirmAdvanced() {
        if (!advancedTrial) return;
        advancedTrial = false;
        prefs.edit().putBoolean("advanced_ok", true).putBoolean("advanced_trial", false).apply();
        main.removeCallbacks(advancedTrialTimeout);
    }

    void applyRemote(RemoteServer.SetTask t) {
        boolean newValues = t.red >= 0 || t.bright >= 0 || t.temp >= 0;
        boolean o = t.on >= 0 ? t.on == 0 : (!newValues && off);
        if (t.advanced >= 0) setAdvanced(t.advanced == 1);
        if (t.advancedConfirm) confirmAdvanced();
        setValues(t.red >= 0 ? t.red : red, t.bright >= 0 ? t.bright : bright, t.temp >= 0 ? t.temp : temp, o);
        holdManual();
        render();
    }

    String audioJson() {
        return audio.json();
    }

    /** Applies audio effect changes (called on the main thread by AudioTask). */
    void applyAudio(JSONObject body) {
        Dbg.log("audio", "apply " + body.names());
        String before = prefs.getString("audio_bands", "");
        audio.apply(body);
        render();
        try {
            audioOsd(body, before, new JSONObject(audio.json()));
        } catch (JSONException ignored) {
        }
    }

    /** On-screen display of the audio setting that just changed: name, value and a bar where it fits. */
    private void audioOsd(JSONObject body, String before, JSONObject now) throws JSONException {
        if (osd == null) osd = new Osd(this);
        String t;
        if (body.has("label")) { // a preset of the phone app (e.g. a video preset): its name
            osd.show(MainActivity.t("Preset", "Preset"), body.optString("label").replaceAll("\\p{Cntrl}", ""), -1);
        } else if (body.has("volume") && now.optJSONObject("volume") != null) {
            JSONObject v = now.getJSONObject("volume");
            int pct = Math.round(v.getInt("level") * 100f / Math.max(1, v.getInt("max")));
            osd.show(MainActivity.t("Volume", "Volume"), pct + "%", pct);
        } else if (body.has("enabled")) {
            osd.show(MainActivity.t("Audio effects", "Effetti audio"),
                    body.optBoolean("enabled") ? MainActivity.t("On", "Attivi") : MainActivity.t("Off", "Spenti"), -1);
        } else if (body.optBoolean("reset", false)) {
            osd.show(MainActivity.t("Equalizer", "Equalizzatore"), MainActivity.t("Reset", "Azzerato"), -1);
        } else if (body.has("preset") && now.optJSONObject("eq") != null) {
            org.json.JSONArray names = now.getJSONObject("eq").getJSONArray("presets");
            int i = body.optInt("preset", -1);
            if (i >= 0 && i < names.length()) osd.show(MainActivity.t("Preset", "Preset"), names.getString(i), -1);
        } else if (body.has("bands") && now.optJSONObject("eq") != null) {
            JSONObject eq = now.getJSONObject("eq");
            org.json.JSONArray bands = eq.getJSONArray("bands");
            String[] old = before.split(",");
            int changed = 0;
            for (int i = 0; i < bands.length(); i++) {
                int was = 0;
                try {
                    was = i < old.length ? Integer.parseInt(old[i].trim()) : 0;
                } catch (NumberFormatException ignored) {
                }
                if (bands.getJSONObject(i).getInt("level") != was) {
                    changed = i;
                    break;
                }
            }
            JSONObject b = bands.getJSONObject(changed);
            int min = eq.getInt("min"), max = eq.getInt("max"), level = b.getInt("level");
            int hz = b.getInt("freq");
            String freq = hz >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f kHz", hz / 1000f).replace(".0 ", " ") : hz + " Hz";
            osd.show(MainActivity.t("Equalizer ", "Equalizzatore ") + freq, decibel(level),
                    Math.round((level - min) * 100f / Math.max(1, max - min)));
        } else if (body.has("bass")) {
            t = MainActivity.t("Bass boost", "Bassi");
            osd.show(t, Math.round(body.optInt("bass") / 10f) + "%", Math.round(body.optInt("bass") / 10f));
        } else if (body.has("virt")) {
            t = MainActivity.t("Surround", "Effetto spaziale");
            osd.show(t, Math.round(body.optInt("virt") / 10f) + "%", Math.round(body.optInt("virt") / 10f));
        } else if (body.has("loud")) {
            osd.show(MainActivity.t("Loudness", "Volume percepito"), decibel(body.optInt("loud")),
                    Math.round(body.optInt("loud") * 100f / 1500));
        }
    }

    /** Millibel as "+3.0 dB". */
    private static String decibel(int mB) {
        return String.format(java.util.Locale.ROOT, "%+.1f dB", mB / 100f);
    }

    /**
     * Validates and stores the schedule, then applies the slot active now. Body: {"enabled", "resolved", "value"}
     * as sent by the dayrhythm page (resolved = daily Slot[] or weekly {mon:[..],..}); the older {"slots":[..]}
     * form is still accepted.
     */
    void applySchedule(boolean enabled, JSONObject body) {
        Object resolved = body.opt("resolved");
        if (resolved == null) resolved = body.opt("slots");
        String normalised;
        boolean any;
        if (resolved instanceof JSONObject) {
            JSONObject week = new JSONObject();
            any = false;
            for (String d : ScheduleMath.WEEKDAYS) {
                JSONArray day = cleanSlots(((JSONObject) resolved).optJSONArray(d));
                any |= day.length() > 0;
                try {
                    week.put(d, day);
                } catch (JSONException ignored) {
                }
            }
            normalised = week.toString();
        } else {
            JSONArray day = cleanSlots(resolved instanceof JSONArray ? (JSONArray) resolved : new JSONArray());
            any = day.length() > 0;
            normalised = day.toString();
        }
        if (body.has("transition")) prefs.edit().putInt("transition", clamp(body.optInt("transition", 30), 0, 240)).apply();
        Object value = body.opt("value");
        String raw = value instanceof JSONArray || value instanceof JSONObject ? value.toString() : normalised;
        if (raw.length() > 65536) raw = normalised;
        scheduleOn = enabled && any;
        scheduleResolved = normalised;
        scheduleValue = raw;
        prefs.edit().putBoolean("schedule_on", scheduleOn).putString("schedule", scheduleResolved)
                .putString("schedule_value", scheduleValue).remove("schedule_last").apply();
        main.removeCallbacks(this);
        run();
        render();
    }

    /**
     * Keeps only well-formed slots, with values clamped to their ranges. Accepts both the flat form
     * {time,on,red,bright,temp} and the dayrhythm form {time,model,label,values:{on,red,bright,temp}}.
     */
    private static JSONArray cleanSlots(JSONArray slots) {
        JSONArray clean = new JSONArray();
        for (int i = 0; slots != null && i < slots.length() && clean.length() < MAX_SLOTS; i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null || minutes(s.optString("time")) < 0) continue;
            JSONObject v = s.optJSONObject("values");
            if (v == null) v = s;
            String model = s.optString("model");
            boolean on = v.has("on") ? v.optBoolean("on", true) : s.optBoolean("on", !"off".equals(model));
            try {
                clean.put(new JSONObject()
                        .put("time", s.optString("time"))
                        .put("on", on)
                        .put("red", clamp(v.optInt("red", 0), 0, 100))
                        .put("bright", clamp(v.optInt("bright", 100), 5, 100))
                        .put("temp", clamp(v.optInt("temp", DEFAULT_TEMP), 1000, 6500))
                        .put("model", shortText(model, 24))
                        .put("label", shortText(s.optString("label"), 40)));
            } catch (JSONException ignored) {
            }
        }
        return clean;
    }

    /**
     * Today's slots for the TV timeline. Weekly: today's own slots, starting with the slot carried over from the
     * previous days when today does not start at midnight (marked "carry").
     */
    static String todaySlots(String resolved) {
        if (resolved == null || !resolved.trim().startsWith("{")) return resolved == null ? "[]" : resolved;
        try {
            JSONObject week = new JSONObject(resolved);
            int today = ScheduleMath.mondayIndex(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
            JSONArray own = week.optJSONArray(ScheduleMath.WEEKDAYS[today]);
            if (own == null) own = new JSONArray();
            int[][] starts = weekStarts(week);
            int[] carried = ScheduleMath.activeWeekly(starts, today, 0);
            JSONArray out = new JSONArray();
            boolean startsAtMidnight = false;
            for (int i = 0; i < own.length(); i++) {
                if (minutes(own.getJSONObject(i).optString("time")) == 0) startsAtMidnight = true;
            }
            if (carried != null && !startsAtMidnight && !(carried[0] == today)) {
                JSONObject c = new JSONObject(week.getJSONArray(ScheduleMath.WEEKDAYS[carried[0]])
                        .getJSONObject(carried[1]).toString());
                c.put("time", "00:00").put("carry", true);
                out.put(c);
            }
            for (int i = 0; i < own.length(); i++) out.put(own.getJSONObject(i));
            return out.toString();
        } catch (JSONException e) {
            return "[]";
        }
    }

    private static int[][] weekStarts(JSONObject week) throws JSONException {
        int[][] starts = new int[7][];
        for (int d = 0; d < 7; d++) {
            JSONArray day = week.optJSONArray(ScheduleMath.WEEKDAYS[d]);
            starts[d] = new int[day == null ? 0 : day.length()];
            for (int i = 0; i < starts[d].length; i++) starts[d][i] = minutes(day.getJSONObject(i).optString("time"));
        }
        return starts;
    }

    // ---- Saved days: named schedules created on the phone page, kept on the TV for every phone ----

    String daysJson() {
        return prefs.getString("days", "[]");
    }

    /** Saves (or replaces, by name) a named day. */
    void saveDay(String name, JSONArray slots) {
        name = shortText(name, 32).trim();
        JSONArray clean = cleanSlots(slots);
        if (name.isEmpty() || clean.length() == 0) return;
        JSONArray out = new JSONArray();
        try {
            JSONArray days = new JSONArray(daysJson());
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.getJSONObject(i);
                if (!d.optString("name").equals(name)) out.put(d);
            }
            if (out.length() < MAX_DAYS) out.put(new JSONObject().put("name", name).put("slots", clean));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("days", out.toString()).apply();
    }

    void deleteDay(String name) {
        JSONArray out = new JSONArray();
        try {
            JSONArray days = new JSONArray(daysJson());
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.getJSONObject(i);
                if (!d.optString("name").equals(name)) out.put(d);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("days", out.toString()).apply();
    }

    String pairingKey() {
        return prefs.getString("key", null);
    }

    /** Device name and model, for naming this device in the phone app. */
    String infoJson() {
        try {
            return new JSONObject().put("name", deviceName()).put("model", android.os.Build.MODEL)
                    .put("type", Compat.deviceType(this)).put("id", deviceId(prefs)).put("app", Compat.appUrl(this)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private final Object stateLock = new Object();

    /** Wakes the remotes waiting in stateJsonAfter: every change goes through render(). */
    private void stateChanged() {
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    /**
     * The state as soon as its "rev" differs from the one the client has, or after waitMs with no change: the
     * phone app and the web remote keep one such request open, so a change made anywhere shows at once everywhere.
     */
    String stateJsonAfter(String since, long waitMs) {
        long end = System.currentTimeMillis() + waitMs;
        synchronized (stateLock) {
            while (true) {
                String s = stateJson();
                long left = end - System.currentTimeMillis();
                if (left <= 0 || destroyed || !since.equals(revOf(s))) return s;
                try {
                    stateLock.wait(left);
                } catch (InterruptedException e) {
                    return s;
                }
            }
        }
    }

    private static String revOf(String json) {
        try {
            return new JSONObject(json).optString("rev");
        } catch (JSONException e) {
            return "";
        }
    }

    String stateJson() {
        return state(red, bright, temp, off, advanced, advancedTrial, prefs.getBoolean("advanced_ok", false)).toString();
    }

    /** State as it will be once a SetTask posted to the main thread has run. */
    String stateJsonWith(RemoteServer.SetTask t) {
        boolean newValues = t.red >= 0 || t.bright >= 0 || t.temp >= 0;
        boolean o = t.on >= 0 ? t.on == 0 : (!newValues && off);
        boolean a = t.advanced >= 0 ? t.advanced == 1 : advanced;
        boolean trial = t.advancedConfirm ? false : t.advanced >= 0 ? a && !prefs.getBoolean("advanced_ok", false) : advancedTrial;
        boolean ok = prefs.getBoolean("advanced_ok", false) || (t.advancedConfirm && advancedTrial);
        return state(t.red >= 0 ? clamp(t.red, 0, 100) : red, t.bright >= 0 ? clamp(t.bright, 5, 100) : bright,
                t.temp >= 0 ? clamp(t.temp, 1000, 6500) : temp, o, a, trial, ok).toString();
    }

    String scheduleJson() {
        try {
            JSONObject o = new JSONObject().put("enabled", scheduleOn);
            String v = scheduleValue.trim();
            o.put("value", v.startsWith("{") ? new JSONObject(v) : new JSONArray(v));
            String r = scheduleResolved.trim();
            o.put("resolved", r.startsWith("{") ? new JSONObject(r) : new JSONArray(r));
            o.put("slots", r.startsWith("{") ? new JSONArray() : new JSONArray(r)); // older clients
            o.put("transition", prefs.getInt("transition", 30));
            return o.toString();
        } catch (JSONException e) {
            return "{\"enabled\":false,\"value\":[],\"resolved\":[],\"slots\":[]}";
        }
    }

    private JSONObject state(int r, int b, int t, boolean o, boolean advancedValue, boolean trialValue, boolean advancedOkValue) {
        try {
            Calendar now = Calendar.getInstance();
            JSONObject j = new JSONObject().put("on", !o).put("red", r).put("bright", b).put("temp", t)
                    .put("schedule", scheduleOn)
                    .put("mode", DimAccessibilityService.instance != null ? "accessibility" : "overlay")
                    .put("advanced", advancedValue)
                    .put("advancedActive", advancedApplied && Priv.verified && Priv.lastOk)
                    .put("advancedMethod", Priv.method(this))
                    .put("advancedLastOk", Priv.lastOk)
                    .put("advancedOk", advancedOkValue)
                    .put("advancedTrial", trialValue)
                    .put("clamped", clamped)
                    .put("blocked", blocked);
            // revision of everything but the clock, for stateJsonAfter
            j.put("rev", Integer.toHexString(j.toString().hashCode()));
            // device clock, minutes since midnight: the page draws the "now" line with it
            return j.put("minute", now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    + now.get(Calendar.SECOND) / 60.0);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    String deviceName() {
        String name = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        return name != null && !name.isEmpty() ? name : android.os.Build.MODEL;
    }

    /** Random, stable id of this device: it names it on the network (mDNS), unlike the pairing key it is not secret. */
    static String deviceId(SharedPreferences p) {
        String id = p.getString("device_id", null);
        if (id != null) return id;
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        p.edit().putString("device_id", sb.toString()).apply();
        return sb.toString();
    }

    static String pairingKey(SharedPreferences p) {
        String k = p.getString("key", null);
        return k != null ? k : newPairingKey(p);
    }

    static String newPairingKey(SharedPreferences p) {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        p.edit().putString("key", sb.toString()).commit();
        newPairingPin(p);
        return sb.toString();
    }

    /** The 4-digit code for pairing by hand (the TV shows it while its pairing popup is open). */
    static String pairingPin(SharedPreferences p) {
        String pin = p.getString("pin", null);
        return pin != null ? pin : newPairingPin(p);
    }

    static String newPairingPin(SharedPreferences p) {
        String pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        p.edit().putString("pin", pin).commit();
        return pin;
    }

    /** POST /api/pair is answered only while this is set: the TV's pairing popup is open. */
    static volatile boolean pairOpen;

    static void openPairing(boolean open) {
        if (open != pairOpen) Dbg.log("pair", open ? "window open" : "window closed");
        if (open) RemoteServer.resetPinFails(); // a fresh popup: every phone gets its tries back
        pairOpen = open;
    }

    static boolean pairingOpen() {
        return pairOpen;
    }

    // ---- Schedule tick ----

    /**
     * Schedule tick. Applies the active slot; in the last minutes of a slot (the transition, default 30 min, at most
     * half the slot) the values glide towards the next slot's, so the change is not noticeable. A manual change
     * (app, phone, adb) holds until the next slot starts. Runs every minute, every 15 s during a transition.
     */
    @Override
    public void run() {
        if (!scheduleOn) return;
        long delay;
        Calendar now = Calendar.getInstance();
        delay = (60 - now.get(Calendar.SECOND)) * 1000L + 500;
        try {
            double m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0;
            java.util.List<JSONObject> list = new java.util.ArrayList<>();
            java.util.List<Integer> abs = new java.util.ArrayList<>();
            int period;
            double t;
            if (scheduleResolved.trim().startsWith("{")) {
                JSONObject week = new JSONObject(scheduleResolved);
                for (int d = 0; d < 7; d++) {
                    JSONArray day = week.optJSONArray(ScheduleMath.WEEKDAYS[d]);
                    for (int i = 0; day != null && i < day.length(); i++) {
                        list.add(day.getJSONObject(i));
                        abs.add(d * ScheduleMath.DAY + minutes(day.getJSONObject(i).optString("time")));
                    }
                }
                period = ScheduleMath.WEEK;
                t = ScheduleMath.mondayIndex(now.get(Calendar.DAY_OF_WEEK)) * ScheduleMath.DAY + m;
            } else {
                JSONArray day = new JSONArray(scheduleResolved);
                for (int i = 0; i < day.length(); i++) {
                    list.add(day.getJSONObject(i));
                    abs.add(minutes(day.getJSONObject(i).optString("time")));
                }
                period = ScheduleMath.DAY;
                t = m;
            }
            int[] starts = new int[abs.size()];
            for (int i = 0; i < starts.length; i++) starts[i] = abs.get(i);
            double[] w = ScheduleMath.window(starts, period, t);
            if (w != null) {
                JSONObject a = list.get((int) w[0]);
                JSONObject b = list.get((int) w[1]);
                String key = String.valueOf(starts[(int) w[0]]);
                if (!key.equals(prefs.getString("schedule_last", null))) {
                    Dbg.log("schedule", "slot " + a.optString("time") + " " + a.optString("label") + " (start " + key + ")");
                    prefs.edit().putString("schedule_last", key).remove("manual_key").apply();
                }
                if (!key.equals(prefs.getString("manual_key", null))) {
                    double f = ScheduleMath.rampFraction(w[2], w[3], prefs.getInt("transition", 30));
                    boolean aOn = a.optBoolean("on", true), bOn = b.optBoolean("on", true);
                    // an "off" slot blends as a neutral picture (no filter, full brightness)
                    int r = (int) Math.round(lerp(aOn ? a.optInt("red") : 0, bOn ? b.optInt("red") : 0, f));
                    int br = (int) Math.round(lerp(aOn ? a.optInt("bright", 100) : 100, bOn ? b.optInt("bright", 100) : 100, f));
                    int tp = (int) Math.round(lerp(a.optInt("temp", DEFAULT_TEMP), b.optInt("temp", DEFAULT_TEMP), f));
                    boolean o = !(aOn || (bOn && f > 0));
                    if (r != red || br != bright || tp != temp || o != off) {
                        setValues(r, br, tp, o);
                        render();
                    }
                    if (w[2] <= Math.min(prefs.getInt("transition", 30), w[3] / 2) + 1) delay = 15000;
                }
            }
        } catch (JSONException ignored) {
        }
        main.postDelayed(this, delay);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /** A manual change while the schedule is on holds until the next slot starts. */
    private void holdManual() {
        if (scheduleOn) prefs.edit().putString("manual_key", prefs.getString("schedule_last", "")).apply();
    }

    private static String shortText(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int minutes(String hhmm) {
        return ScheduleMath.minutes(hhmm);
    }

    // ---- Overlay ----

    /**
     * Warm filter layer with a black (dimming) layer drawn on top of it, merged into one ARGB colour.
     * Dimming on top also darkens the filter itself, so low brightness does not leave an orange haze.
     */
    static int overlayColor(int red, int bright, int temp) {
        int tint = colorFromTemperature(temp);
        float tintA = red / 100f * MAX_FILTER_ALPHA;
        float dimA = (100 - bright) / 100f;
        float a = 1f - (1f - dimA) * (1f - tintA);
        if (a <= 0f) return Color.TRANSPARENT;
        float k = tintA * (1f - dimA) / a; // share of the filter colour in the merged colour, the rest is black
        return Color.argb(Math.round(a * 255),
                Math.round(Color.red(tint) * k),
                Math.round(Color.green(tint) * k),
                Math.round(Color.blue(tint) * k));
    }

    /**
     * Black body colour for a temperature in kelvin, after Tanner Helland's well known curve fit
     * (https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html).
     */
    static int colorFromTemperature(int kelvin) {
        double t = kelvin / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661
                : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return Color.rgb(channel(r), channel(g), channel(b));
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    // ---- Advanced filter: SurfaceFlinger colour matrix ----

    /** `service call SurfaceFlinger 1015 i32 0`: back to the untouched picture, on every display. */
    static final String RESET_MATRIX = "service call SurfaceFlinger 1015 i32 0";

    /**
     * The same warm filter as the overlay, but as a per-channel multiplication (row-major 4x4 matrix):
     * the channels the black-body curve leaves alone are kept, the others are scaled, so black stays black.
     * The strongest channel is normalised to 1, otherwise the tint would also dim the picture.
     */
    static String matrixCommand(int red, int bright, int temp) {
        int tint = colorFromTemperature(temp);
        float r = Color.red(tint) / 255f, g = Color.green(tint) / 255f, b = Color.blue(tint) / 255f;
        float max = Math.max(r, Math.max(g, b));
        if (max > 0f) {
            r /= max;
            g /= max;
            b /= max;
        }
        float strength = clamp(red, 0, 100) / 100f;
        r = 1f - strength * (1f - r);
        g = 1f - strength * (1f - g);
        b = 1f - strength * (1f - b);
        // never black the screen: brightness dims the picture, but only down to 30%
        float dim = 0.3f + 0.7f * clamp(bright, 5, 100) / 100f;
        // the gains above are on encoded (gamma) values like the overlay; SurfaceFlinger multiplies linear light
        r = linear(r * dim);
        g = linear(g * dim);
        b = linear(b * dim);
        float[] m = {r, 0, 0, 0, 0, g, 0, 0, 0, 0, b, 0, 0, 0, 0, 1};
        StringBuilder sb = new StringBuilder("service call SurfaceFlinger 1015 i32 1");
        for (float v : m) sb.append(" f ").append(String.format(Locale.ROOT, "%.4f", v));
        return sb.toString();
    }

    /** A gain on encoded values as a gain on linear light (gamma 2.2, exact for a pure scale). */
    static float linear(float gain) {
        return (float) Math.pow(Math.max(0f, Math.min(1f, gain)), 2.2);
    }

    private void resetMatrix() {
        if (lastMatrix == null && !advancedApplied) return;
        lastMatrix = null;
        if (advancedApplied) {
            Dbg.log("matrix", "reset");
            Priv.exec(this, RESET_MATRIX);
        }
        setMatrixOn(false);
    }

    /** Remembered across processes, so a matrix left by a crash is cleared at the next start. */
    private void setMatrixOn(boolean on) {
        advancedApplied = on;
        if (prefs.getBoolean("matrix_on", false) != on) prefs.edit().putBoolean("matrix_on", on).apply();
    }

    /** Polls every 200 ms for the privileged shell's answer (up to a minute), then renders again. */
    static final class MatrixCheck implements Runnable {
        private final DimService service;
        private final int before, tries;

        MatrixCheck(DimService service, int before, int tries) {
            this.service = service;
            this.before = before;
            this.tries = tries;
        }

        @Override
        public void run() {
            if (service.destroyed) return;
            if (Priv.results == before && tries < 300) service.main.postDelayed(new MatrixCheck(service, before, tries + 1), 200);
            else service.render();
        }
    }

    /** End of the 15 s trial: without a "Keep" the advanced filter is turned off again (a plain class, see MainActivity). */
    static final class TrialTimeout implements Runnable {
        private final DimService service;

        TrialTimeout(DimService service) {
            this.service = service;
        }

        @Override
        public void run() {
            service.endTrial();
        }
    }

    private void endTrial() {
        if (!advancedTrial) return;
        advancedTrial = false;
        if (prefs.getBoolean("advanced_ok", false)) {
            prefs.edit().putBoolean("advanced_trial", false).apply();
            return;
        }
        Dbg.log("matrix", "trial expired, back to overlay");
        advanced = false;
        prefs.edit().putBoolean("advanced", false).putBoolean("advanced_trial", false).apply();
        lastMatrix = null;
        matrixRetries = 0;
        render();
    }

    /** Retries the matrix after the privileged shell became usable (a plain class, see MainActivity). */
    static final class RenderLater implements Runnable {
        private final DimService service;
        private final boolean retry;

        RenderLater(DimService service, boolean retry) {
            this.service = service;
            this.retry = retry;
        }

        @Override
        public void run() {
            if (retry) service.matrixRetryDue = true;
            service.render();
        }
    }


    private void showOverlay(int color) {
        if (destroyed) return;
        DimAccessibilityService svc = DimAccessibilityService.instance;
        boolean acc = svc != null;
        if (view != null && viewAccessibility != acc) removeOverlay();
        float maxAlpha = 1f;
        clamped = false;
        if (!acc && Build.VERSION.SDK_INT >= 31) {
            maxAlpha = Compat.maxObscuringOpacity(this);
            float desired = Color.alpha(color) / 255f;
            if (desired > maxAlpha) {
                clamped = true;
                color = Color.argb(Math.round(Math.min(1f, desired / maxAlpha) * 255),
                        Color.red(color), Color.green(color), Color.blue(color));
            } else if (maxAlpha > 0f) {
                color = Color.argb(Math.round(desired / maxAlpha * 255),
                        Color.red(color), Color.green(color), Color.blue(color));
            }
        }
        Dbg.log("overlay", String.format(Locale.ROOT, "show argb=%08X alpha=%d clamped=%b maxObscuring=%.2f via %s",
                color, Color.alpha(color), clamped, maxAlpha, acc ? "accessibility" : "app overlay") + (view == null ? " (new)" : ""));
        if (view == null) {
            view = new View(this);
            viewAccessibility = acc;
            viewWm = acc ? (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)
                    : getSystemService(WindowManager.class);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    acc ? TYPE_ACCESSIBILITY_OVERLAY : TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.alpha = maxAlpha;
            Compat.coverCutout(lp);
            view.setBackgroundColor(color);
            try {
                viewWm.addView(view, lp);
                blocked = false;
            } catch (RuntimeException e) {
                // no "Display over other apps" permission (BadTokenException / SecurityException): do not crash,
                // report it in the state; the next change tries again
                view = null;
                blocked = true;
            }
        } else {
            view.setBackgroundColor(color);
        }
    }

    private void removeOverlay() {
        if (view != null) {
            Dbg.log("overlay", "removed");
            try {
                viewWm.removeView(view);
            } catch (IllegalArgumentException e) {
                // the accessibility window is already gone with its service
            }
            view = null;
        }
    }

    /** Foreground notification; NotificationChannel & co. are API 26, reached by reflection (API 23 stubs). */
    private Notification buildNotification() {
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "MidnightBeam", 1 /* IMPORTANCE_MIN */);
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationManager.class.getMethod("createNotificationChannel", channelClass).invoke(nm, channel);
            Notification.Builder b = Notification.Builder.class
                    .getConstructor(Context.class, String.class).newInstance(this, CHANNEL);
            String text = off ? "off" : "filter " + red + "%, brightness " + bright + "%, " + temp + " K";
            if (remote) text += " · phone remote on";
            if (scheduleOn) text += " · schedule on";
            if (audio.enabled()) text += " · audio effects on";
            return b.setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_menu_view))
                    .setContentTitle("MidnightBeam")
                    .setContentText(text)
                    .build();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        instance = null;
        Dbg.log("service", "destroy");
        main.removeCallbacks(this);
        removeOverlay();
        resetMatrix();
        runningAudio = null;
        audio.release();
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (advertiser != null) advertiser.stop();
        if (osd != null) osd.hide();
        if (scanner != null) scanner.finish();
        try {
            unregisterReceiver(sessionReceiver);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args.length > 0 && args[0].equals("debug")) {
            try {
                pw.println(Dbg.on(this) ? Dbg.state(this).toString(2) : "debug off (release build)");
            } catch (JSONException e) {
                pw.println(e);
            }
            return;
        }
        pw.println("red=" + red + " bright=" + bright + " temp=" + temp + " visible=" + (view != null)
                + " off=" + off + " mode=" + (DimAccessibilityService.instance != null ? "accessibility" : "overlay")
                + " clamped=" + clamped + " blocked=" + blocked + " remote=" + (server != null) + " schedule=" + scheduleOn);
    }

    // ---- Dev debug snapshot (Dbg.state; read from a binder or server thread, values may be a moment stale) ----

    JSONObject debugFilter() throws JSONException {
        int argb = overlayColor(red, bright, temp);
        return new JSONObject().put("enabled", !off).put("red", red).put("bright", bright).put("temp", temp)
                .put("overlayArgb", String.format(Locale.ROOT, "%08X", argb)).put("alpha", Color.alpha(argb))
                .put("clamped", clamped).put("blocked", blocked)
                .put("advanced", advanced).put("advancedActive", advancedApplied)
                .put("advancedMethod", Priv.method(this)).put("advancedLastOk", Priv.lastOk)
                .put("maxObscuringOpacity", Build.VERSION.SDK_INT >= 31 ? (double) Compat.maxObscuringOpacity(this) : JSONObject.NULL)
                .put("overlayAttached", view != null)
                .put("type", view == null ? JSONObject.NULL : viewAccessibility ? "accessibility (2032)" : "application (2038)");
    }

    JSONObject debugSchedule() throws JSONException {
        JSONObject o = new JSONObject().put("on", scheduleOn).put("transitionMin", prefs.getInt("transition", 30))
                .put("lastSlotStart", prefs.getString("schedule_last", null)).put("manualHold", prefs.contains("manual_key"));
        if (!scheduleOn) return o;
        Calendar now = Calendar.getInstance();
        double m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0;
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        java.util.List<Integer> abs = new java.util.ArrayList<>();
        int period;
        double t;
        if (scheduleResolved.trim().startsWith("{")) {
            JSONObject week = new JSONObject(scheduleResolved);
            for (int d = 0; d < 7; d++) {
                JSONArray day = week.optJSONArray(ScheduleMath.WEEKDAYS[d]);
                for (int i = 0; day != null && i < day.length(); i++) {
                    list.add(day.getJSONObject(i));
                    abs.add(d * ScheduleMath.DAY + minutes(day.getJSONObject(i).optString("time")));
                }
            }
            period = ScheduleMath.WEEK;
            t = ScheduleMath.mondayIndex(now.get(Calendar.DAY_OF_WEEK)) * ScheduleMath.DAY + m;
        } else {
            JSONArray day = new JSONArray(scheduleResolved);
            for (int i = 0; i < day.length(); i++) {
                list.add(day.getJSONObject(i));
                abs.add(minutes(day.getJSONObject(i).optString("time")));
            }
            period = ScheduleMath.DAY;
            t = m;
        }
        int[] starts = new int[abs.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = abs.get(i);
        double[] w = ScheduleMath.window(starts, period, t);
        if (w != null) {
            long next = System.currentTimeMillis() + Math.round(w[2] * 60000);
            o.put("activeSlot", list.get((int) w[0])).put("nextSlot", list.get((int) w[1]))
                    .put("nextChangeAt", new java.text.SimpleDateFormat("EEE HH:mm", Locale.ROOT).format(new java.util.Date(next)))
                    .put("minutesToNext", w[2])
                    .put("transitioning", w[2] <= Math.min(prefs.getInt("transition", 30), w[3] / 2));
        }
        return o;
    }

    JSONObject debugRemote() throws JSONException {
        return new JSONObject().put("enabled", remote).put("localOnly", local).put("running", server != null)
                .put("port", RemoteServer.PORT).put("lanIp", RemoteServer.localIp()).put("pairingOpen", pairOpen)
                .put("pinFails", RemoteServer.pinFails())
                .put("advertiserRegistered", advertiser != null && advertiser.registered());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
