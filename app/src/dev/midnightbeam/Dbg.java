package dev.midnightbeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Debug channel of dev builds (DEV=1 ./build.sh makes the APK debuggable): an event ring buffer and a JSON snapshot
 * of the whole app state, read with `dumpsys activity service dev.midnightbeam/.DimService debug` or GET /api/debug.
 * Closed in release builds: on() is false, nothing is recorded and no route answers. Never holds the pairing key or PIN.
 */
final class Dbg {
    private static final int MAX = 100;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static volatile boolean enabled; // set by the first on(context) call of the process

    private Dbg() {
    }

    static boolean on(Context c) {
        boolean d = (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        enabled = d;
        return d;
    }

    static void log(String tag, String msg) {
        if (!enabled) return;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + tag + " " + msg;
        synchronized (EVENTS) {
            if (EVENTS.size() >= MAX) EVENTS.removeFirst();
            EVENTS.addLast(line);
        }
    }

    private static final String[] SECTIONS = {"app", "device", "permissions", "filter", "schedule", "remote", "audio",
            "devices", "updater", "events"};

    /** Every section in its own try/catch: one failing must not kill the dump. */
    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        DimService svc = DimService.instance;
        for (String name : SECTIONS) {
            boolean needsService = name.equals("filter") || name.equals("schedule") || name.equals("remote") || name.equals("audio");
            if (needsService && svc == null) continue;
            try {
                o.put(name, section(name, c, svc));
            } catch (Exception e) {
                try {
                    o.put(name, new JSONObject().put("error", e.toString()));
                } catch (JSONException ignored) {
                }
            }
        }
        return o;
    }

    private static Object section(String name, Context c, DimService svc) throws Exception {
        SharedPreferences p = c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        PackageManager pm = c.getPackageManager();
        switch (name) {
            case "app": {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(c.getPackageName(), 0);
                return new JSONObject().put("versionName", pi.versionName).put("versionCode", pi.versionCode)
                        .put("debuggable", on(c)).put("serviceRunning", svc != null);
            }
            case "device": {
                JSONObject d = new JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                        .put("product", Build.PRODUCT).put("board", prop("ro.board.platform"))
                        .put("hardware", prop("ro.hardware")).put("sdk", Build.VERSION.SDK_INT)
                        .put("buildType", Build.TYPE)
                        .put("touchscreen", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
                        .put("leanback", pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK));
                Display disp = ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                disp.getRealMetrics(m);
                JSONArray types = new JSONArray();
                Display.HdrCapabilities hdr = disp.getHdrCapabilities();
                if (hdr != null) for (int t : hdr.getSupportedHdrTypes()) types.put(t);
                return d.put("display", new JSONObject().put("width", m.widthPixels).put("height", m.heightPixels)
                        .put("densityDpi", m.densityDpi).put("refreshHz", disp.getRefreshRate()).put("hdrTypes", types));
            }
            case "permissions":
                return new JSONObject().put("overlay", Settings.canDrawOverlays(c))
                        .put("writeSecureSettings", granted(c, "android.permission.WRITE_SECURE_SETTINGS"))
                        .put("dump", granted(c, "android.permission.DUMP"))
                        .put("requestInstallPackages", granted(c, "android.permission.REQUEST_INSTALL_PACKAGES"))
                        .put("canRequestPackageInstalls", pm.canRequestPackageInstalls())
                        .put("accessibilityRunning", DimAccessibilityService.instance != null);
            case "filter":
                return svc.debugFilter();
            case "schedule":
                return svc.debugSchedule();
            case "remote":
                return svc.debugRemote();
            case "audio":
                return new JSONObject(svc.audioJson());
            case "devices":
                return new JSONObject().put("saved", Devices.load(p).length()).put("discoveryRunning", Discovery.active)
                        .put("discoveredUnsaved", new JSONArray(Discovery.unsaved(p)).length());
            case "updater":
                return new JSONObject().put("upd", p.getBoolean("upd", true)).put("updAuto", p.getBoolean("updAuto", false))
                        .put("updBeta", Updater.beta(c)).put("updSkip", p.getString("updSkip", ""))
                        .put("fdroid", Compat.fromFdroid(c));
            default:
                synchronized (EVENTS) {
                    return new JSONArray(EVENTS);
                }
        }
    }

    private static boolean granted(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String prop(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }
}
