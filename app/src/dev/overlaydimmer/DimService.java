package dev.overlaydimmer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
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
 *   am start-foreground-service -n dev.overlaydimmer/.DimService --ei red 60 --ei bright 40 --ei temp 1100
 *   am start-foreground-service -n dev.overlaydimmer/.DimService --ez off true
 *   am start-foreground-service -n dev.overlaydimmer/.DimService --ez remote true    (phone remote on/off)
 *
 * red    0-100      strength of the warm filter
 * bright 5-100      100 = no dimming
 * temp   1000-6500  filter colour temperature in kelvin (default 1100, a deep red)
 *
 * It also runs the phone remote (RemoteServer) and the daily schedule: time slots, the active one is the
 * last slot whose time has passed (wrapping over midnight), applied once when it becomes active, so a manual
 * change lasts until the next slot. Checked every minute.
 * Current state: dumpsys activity service dev.overlaydimmer/.DimService
 */
public class DimService extends Service implements Runnable {
    static final String PREFS = "dim";
    private static final String CHANNEL = "dim";
    private static final int TYPE_APPLICATION_OVERLAY = 2038; // API 26 constant, not in the API 23 stubs
    private static final float MAX_FILTER_ALPHA = 0.65f; // opacity of the filter at red=100
    static final int DEFAULT_TEMP = 1100;
    private static final int MAX_SLOTS = 24;
    private static final int MAX_DAYS = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private View view;
    private RemoteServer server;

    // Read by the server thread, so volatile.
    private volatile int red;
    private volatile int bright = 100;
    private volatile int temp = DEFAULT_TEMP;
    private volatile boolean off;
    private volatile boolean remote;
    private volatile boolean scheduleOn;
    private volatile String scheduleSlots = "[]";

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
            }
            if (intent.hasExtra("remote")) {
                remote = intent.getBooleanExtra("remote", false);
                prefs.edit().putBoolean("remote", remote).apply();
            }
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
        remote = prefs.getBoolean("remote", false);
        scheduleOn = prefs.getBoolean("schedule_on", false);
        scheduleSlots = prefs.getString("schedule", "[]");
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
        if (remote && server == null) {
            pairingKey(prefs);
            server = new RemoteServer(this);
            server.start();
        } else if (!remote && server != null) {
            server.shutdown();
            server = null;
        }
        if (!visible && !remote && !scheduleOn) {
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
        render();
    }

    /** Validates and stores the schedule, then applies the slot active now. */
    void applySchedule(boolean enabled, JSONArray slots) {
        JSONArray clean = cleanSlots(slots);
        scheduleOn = enabled && clean.length() > 0;
        scheduleSlots = clean.toString();
        prefs.edit().putBoolean("schedule_on", scheduleOn).putString("schedule", scheduleSlots)
                .remove("schedule_last").apply();
        main.removeCallbacks(this);
        run();
        render();
    }

    /** Keeps only well-formed slots, with values clamped to their ranges. */
    private static JSONArray cleanSlots(JSONArray slots) {
        JSONArray clean = new JSONArray();
        for (int i = 0; slots != null && i < slots.length() && clean.length() < MAX_SLOTS; i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null || minutes(s.optString("time")) < 0) continue;
            try {
                clean.put(new JSONObject()
                        .put("time", s.optString("time"))
                        .put("on", s.optBoolean("on", true))
                        .put("red", clamp(s.optInt("red", 0), 0, 100))
                        .put("bright", clamp(s.optInt("bright", 100), 5, 100))
                        .put("temp", clamp(s.optInt("temp", DEFAULT_TEMP), 1000, 6500))
                        // UI metadata for the remote page timeline, stored as-is (short strings only)
                        .put("model", shortText(s.optString("model"), 24))
                        .put("label", shortText(s.optString("label"), 24)));
            } catch (JSONException ignored) {
            }
        }
        return clean;
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
            return new JSONObject().put("enabled", scheduleOn).put("slots", new JSONArray(scheduleSlots)).toString();
        } catch (JSONException e) {
            return "{\"enabled\":false,\"slots\":[]}";
        }
    }

    private JSONObject state(int r, int b, int t, boolean o) {
        try {
            Calendar now = Calendar.getInstance();
            return new JSONObject().put("on", !o).put("red", r).put("bright", b).put("temp", t)
                    .put("schedule", scheduleOn)
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

    /** Schedule tick: applies the active slot when it changes, then re-arms itself at the next minute. */
    @Override
    public void run() {
        if (scheduleOn) {
            try {
                JSONArray slots = new JSONArray(scheduleSlots);
                Calendar now = Calendar.getInstance();
                int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                JSONObject active = null;
                JSONObject latest = null;
                for (int i = 0; i < slots.length(); i++) {
                    JSONObject s = slots.getJSONObject(i);
                    int m = minutes(s.getString("time"));
                    if (latest == null || m > minutes(latest.getString("time"))) latest = s;
                    if (m <= current && (active == null || m > minutes(active.getString("time")))) active = s;
                }
                if (active == null) active = latest; // before the first slot: yesterday's last one
                String key = active.getString("time");
                if (!key.equals(prefs.getString("schedule_last", null))) {
                    prefs.edit().putString("schedule_last", key).apply();
                    setValues(active.optInt("red"), active.optInt("bright", 100), active.optInt("temp", DEFAULT_TEMP),
                            !active.optBoolean("on", true));
                    render();
                }
            } catch (JSONException ignored) {
            }
            Calendar c = Calendar.getInstance();
            main.postDelayed(this, (60 - c.get(Calendar.SECOND)) * 1000L + 500);
        }
    }

    private static String shortText(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) return -1;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3));
        return h < 24 && m < 60 ? h * 60 + m : -1;
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
        if (view == null) {
            view = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            view.setBackgroundColor(color);
            getSystemService(WindowManager.class).addView(view, lp);
        } else {
            view.setBackgroundColor(color);
        }
    }

    private void removeOverlay() {
        if (view != null) {
            getSystemService(WindowManager.class).removeView(view);
            view = null;
        }
    }

    /** Foreground notification; NotificationChannel & co. are API 26, reached by reflection (API 23 stubs). */
    private Notification buildNotification() {
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "Overlay Dimmer", 1 /* IMPORTANCE_MIN */);
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationManager.class.getMethod("createNotificationChannel", channelClass).invoke(nm, channel);
            Notification.Builder b = Notification.Builder.class
                    .getConstructor(Context.class, String.class).newInstance(this, CHANNEL);
            String text = off ? "off" : "filter " + red + "%, brightness " + bright + "%, " + temp + " K";
            if (remote) text += " · phone remote on";
            if (scheduleOn) text += " · schedule on";
            return b.setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_menu_view))
                    .setContentTitle("Overlay Dimmer")
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
                + " off=" + off + " remote=" + (server != null) + " schedule=" + scheduleOn);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
