package dev.midnightbeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * API of the phone app's own page (assets/app.html), answered only on loopback connections:
 *
 *   GET  /api/app        {"overlay":bool,"accessibilityHint":bool,"accessibility":bool,"sdk":n,"version":..}
 *                        for the setup and settings screens
 *   GET  /api/devices    saved devices [{"id":"host:port","name":..,"host":..,"port":..}] (keys stay in the app)
 *   POST /api/devices    {"op":"add","link":..} | {"op":"edit","id":..,"name":..,"type":..} (either) | {"op":"delete","id":..}
 *                        type: projector, tv, tablet, phone, monitor or other (icon only)
 *   ANY  /d/{host:port}/api/...   forwarded to that saved device with its pairing key
 */
final class AppApi {
    private static final String[] TYPES = {"projector", "tv", "tablet", "phone", "monitor", "other"};
    private static final String[] FORWARDED = {"/api/info", "/api/state", "/api/set", "/api/schedule", "/api/days"};

    private AppApi() {
    }

    static String app(Context context) {
        String version = "";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("overlay", Settings.canDrawOverlays(context))
                    .put("accessibilityHint", Compat.needsAccessibilityHint())
                    .put("accessibility", DimAccessibilityService.instance != null)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("type", Compat.deviceType(context))
                    .put("version", version).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String devices(SharedPreferences p) {
        JSONArray a = Devices.load(p), out = new JSONArray();
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                if (d == null) continue;
                out.put(new JSONObject().put("id", d.optString("host") + ":" + d.optInt("port"))
                        .put("name", d.optString("name")).put("host", d.optString("host")).put("port", d.optInt("port"))
                        .put("type", d.optString("type")));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** Changes the device list; returns the JSON answer, null if the request is not valid. */
    static String changeDevices(SharedPreferences p, JSONObject j) {
        String op = j.optString("op"), id = j.optString("id");
        if (op.equals("add")) {
            String added = Devices.addLink(p, Uri.parse(j.optString("link").trim()));
            return added == null ? null : "{\"ok\":true,\"id\":" + JSONObject.quote(added) + "}";
        }
        JSONObject d = Devices.byId(p, id);
        if (d == null) return null;
        if (op.equals("edit")) {
            String n = Devices.cleanName(j.optString("name", null)), t = j.optString("type", null);
            boolean typeOk = false;
            for (String x : TYPES) typeOk |= x.equals(t);
            if ((n == null || n.isEmpty()) && !typeOk) return null;
            if (n != null && !n.isEmpty()) Devices.rename(p, d.optString("host"), d.optInt("port"), n);
            if (typeOk) Devices.setType(p, d.optString("host"), d.optInt("port"), t);
        } else if (op.equals("delete")) {
            Devices.delete(p, d.optString("host"), d.optInt("port"));
        } else {
            return null;
        }
        return "{\"ok\":true}";
    }

    /** Forwards /d/{host:port}/api/... to the saved device and writes its answer (502 if unreachable). */
    static void forward(SharedPreferences p, String path, String method, byte[] body, int length, OutputStream out)
            throws IOException {
        int slash = path.indexOf('/', 3);
        String id = slash > 3 ? path.substring(3, slash) : "";
        try {
            id = java.net.URLDecoder.decode(id, "UTF-8"); // the page may send "host%3Aport"
        } catch (IllegalArgumentException e) {
            id = "";
        }
        String rest = slash > 3 ? path.substring(slash) : "";
        JSONObject d = Devices.byId(p, id);
        boolean allowed = false;
        for (String f : FORWARDED) allowed |= rest.equals(f);
        if (d == null || !allowed || !(method.equals("GET") || method.equals("POST"))) {
            RemoteServer.send(out, 404, "application/json", "{\"error\":\"not found\"}");
            return;
        }
        int code;
        byte[] answer;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + d.optString("host") + ":" + d.optInt("port") + rest)
                    .openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Key", d.optString("key"));
            if (method.equals("POST")) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body, 0, length);
                }
            }
            code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            answer = in == null ? new byte[0] : readAll(in);
        } catch (IOException e) {
            RemoteServer.send(out, 502, "application/json", "{\"error\":\"unreachable\"}");
            return;
        }
        RemoteServer.send(out, code, "application/json", answer);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 256 * 1024) throw new IOException("too large");
            }
            return b.toByteArray();
        }
    }

    static String text(byte[] body, int length) {
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }
}
