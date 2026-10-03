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

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        load();
        if (intent != null) {
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
        scheduleResolved = prefs.getString("schedule", "[]");
        scheduleValue = prefs.getString("schedule_value", scheduleResolved);
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
        boolean visible = !off && !(red == 0 && bright == 100);
        if (visible) showOverlay(overlayColor(red, bright, temp)); else removeOverlay();
        if (server != null && (!(remote || local) || server.lan != remote)) {
            server.shutdown();
            server = null;
        }
        if ((remote || local) && server == null) {
            pairingKey(prefs);
            server = new RemoteServer(this, remote);
            server.start();
        }
        if (!visible && !remote && !local && !scheduleOn) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).notify(1, buildNotification());
        return START_STICKY;
    }

    // ---- Phone remote (called on the main thread by RemoteServer tasks) ----

    void applyRemote(RemoteServer.SetTask t) {
        boolean newValues = t.red >= 0 || t.bright >= 0 || t.temp >= 0;
        boolean o = t.on >= 0 ? t.on == 0 : (!newValues && off);
        setValues(t.red >= 0 ? t.red : red, t.bright >= 0 ? t.bright : bright, t.temp >= 0 ? t.temp : temp, o);
        holdManual();
        render();
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
        String name = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        try {
            return new JSONObject().put("name", name != null && !name.isEmpty() ? name : android.os.Build.MODEL)
                    .put("model", android.os.Build.MODEL).put("type", Compat.deviceType(this)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    String stateJson() {
        return state(red, bright, temp, off).toString();
    }

    /** State as it will be once a SetTask posted to the main thread has run. */
    String stateJsonWith(RemoteServer.SetTask t) {
        boolean newValues = t.red >= 0 || t.bright >= 0 || t.temp >= 0;
        boolean o = t.on >= 0 ? t.on == 0 : (!newValues && off);
        return state(t.red >= 0 ? clamp(t.red, 0, 100) : red, t.bright >= 0 ? clamp(t.bright, 5, 100) : bright,
                t.temp >= 0 ? clamp(t.temp, 1000, 6500) : temp, o).toString();
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

    private JSONObject state(int r, int b, int t, boolean o) {
        try {
            Calendar now = Calendar.getInstance();
            return new JSONObject().put("on", !o).put("red", r).put("bright", b).put("temp", t)
                    .put("schedule", scheduleOn)
                    .put("mode", DimAccessibilityService.instance != null ? "accessibility" : "overlay")
                    .put("clamped", clamped)
                    .put("blocked", blocked)
                    // device clock, minutes since midnight: the page draws the "now" line with it
                    .put("minute", now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                            + now.get(Calendar.SECOND) / 60.0);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
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
        return sb.toString();
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

    private void showOverlay(int color) {
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
        main.removeCallbacks(this);
        removeOverlay();
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("red=" + red + " bright=" + bright + " temp=" + temp + " visible=" + (view != null)
                + " off=" + off + " mode=" + (DimAccessibilityService.instance != null ? "accessibility" : "overlay")
                + " clamped=" + clamped + " blocked=" + blocked + " remote=" + (server != null) + " schedule=" + scheduleOn);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
