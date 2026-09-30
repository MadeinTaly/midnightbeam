package dev.overlaydimmer;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Minimal HTTP server for the phone remote, local network only. Serves the remote page (assets/remote.html)
 * and a tiny JSON API; every API call must carry the pairing key (header X-Key) from the QR code.
 *
 *   GET  /               the remote page (the key travels in the URL fragment, never sent to the server by the browser)
 *   GET  /remote.css     its stylesheet (Tailwind, compiled at development time, see web/)
 *   GET  /api/state      current values
 *   POST /api/set        {"red":0-100,"bright":5-100,"temp":1000-6500,"on":true|false}, any subset
 *   GET  /api/schedule   {"enabled":bool,"value":<dayrhythm value>,"resolved":<Slot[] or {mon:[..],..}>}
 *   POST /api/schedule   {"enabled":bool,"value":..,"resolved":..}  (older {"enabled","slots":[..]} still accepted)
 *   GET  /api/days       saved days: [{"name":..,"slots":[..]}]
 *   POST /api/days       {"name":..,"slots":[..]} saves or replaces a day; {"name":..,"delete":true} deletes it
 */
final class RemoteServer extends Thread {
    static final int PORT = 8765;
    private static final int MAX_BODY = 4096;

    /** Static files for "add to home screen" (manifest and icons) and the page's scripts. */
    private static final java.util.Map<String, String> STATIC = new java.util.HashMap<>();

    static {
        STATIC.put("/manifest.webmanifest", "application/manifest+json");
        STATIC.put("/icon-192.png", "image/png");
        STATIC.put("/icon-512.png", "image/png");
        STATIC.put("/apple-touch-icon.png", "image/png");
        STATIC.put("/dayrhythm.min.js", "text/javascript; charset=utf-8");
    }

    private final DimService service;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket socket;

    RemoteServer(DimService service) {
        super("overlay-dimmer-remote");
        this.service = service;
        this.assets = service.getAssets();
        setDaemon(true);
    }

    void shutdown() {
        interrupt();
        ServerSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        try (ServerSocket s = new ServerSocket(PORT)) {
            socket = s;
            while (!isInterrupted()) {
                try (Socket c = s.accept()) {
                    c.setSoTimeout(5000);
                    handle(c);
                } catch (IOException e) {
                    if (isInterrupted()) return;
                }
            }
        } catch (IOException ignored) {
            // port busy or socket closed by shutdown()
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        String key = null;
        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-key")) key = value;
            if (name.equals("content-length")) {
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    length = -1;
                }
            }
        }
        OutputStream out = c.getOutputStream();
        if (length < 0 || length > MAX_BODY) {
            send(out, 413, "text/plain", "too large");
            return;
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }

        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            send(out, 200, "text/html; charset=utf-8", asset("remote.html"));
        } else if (method.equals("GET") && path.equals("/remote.css")) {
            send(out, 200, "text/css; charset=utf-8", asset("remote.css"));
        } else if (method.equals("GET") && STATIC.containsKey(path)) {
            send(out, 200, STATIC.get(path), assetBytes(path.substring(1)));
        } else if (path.startsWith("/api/")) {
            if (!keyMatches(key)) {
                send(out, 403, "application/json", "{\"error\":\"pairing key\"}");
            } else if (method.equals("GET") && path.equals("/api/state")) {
                send(out, 200, "application/json", service.stateJson());
            } else if (method.equals("POST") && path.equals("/api/set")) {
                try {
                    JSONObject j = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                    SetTask task = new SetTask(service,
                            j.has("red") ? j.getInt("red") : -1,
                            j.has("bright") ? j.getInt("bright") : -1,
                            j.has("temp") ? j.getInt("temp") : -1,
                            j.has("on") ? (j.getBoolean("on") ? 1 : 0) : -1);
                    main.post(task);
                    send(out, 200, "application/json", service.stateJsonWith(task));
                } catch (JSONException e) {
                    send(out, 400, "application/json", "{\"error\":\"bad json\"}");
                }
            } else if (method.equals("GET") && path.equals("/api/schedule")) {
                send(out, 200, "application/json", service.scheduleJson());
            } else if (method.equals("POST") && path.equals("/api/schedule")) {
                try {
                    JSONObject j = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                    main.post(new ScheduleTask(service, j.optBoolean("enabled", false), j));
                    send(out, 200, "application/json", "{\"ok\":true}");
                } catch (JSONException e) {
                    send(out, 400, "application/json", "{\"error\":\"bad json\"}");
                }
            } else if (method.equals("GET") && path.equals("/api/days")) {
                send(out, 200, "application/json", service.daysJson());
            } else if (method.equals("POST") && path.equals("/api/days")) {
                try {
                    JSONObject j = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                    DayTask task = new DayTask(service, j.optString("name"), j.optBoolean("delete", false),
                            j.optJSONArray("slots"));
                    main.post(task);
                    send(out, 200, "application/json", "{\"ok\":true}");
                } catch (JSONException e) {
                    send(out, 400, "application/json", "{\"error\":\"bad json\"}");
                }
            } else {
                send(out, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } else {
            send(out, 404, "text/plain", "not found");
        }
    }

    private boolean keyMatches(String key) {
        String expected = service.pairingKey();
        if (key == null || expected == null) return false;
        return MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String asset(String name) throws IOException {
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] assetBytes(String name) throws IOException {
        try (InputStream a = assets.open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] b) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /** Reads an ASCII line terminated by CRLF or LF; null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return sb.toString();
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Applies values on the main thread; -1 means "leave unchanged". A plain class, see MainActivity. */
    static final class SetTask implements Runnable {
        private final DimService service;
        final int red;
        final int bright;
        final int temp;
        final int on;

        SetTask(DimService service, int red, int bright, int temp, int on) {
            this.service = service;
            this.red = red;
            this.bright = bright;
            this.temp = temp;
            this.on = on;
        }

        @Override
        public void run() {
            service.applyRemote(this);
        }
    }

    /** Stores a new schedule on the main thread. */
    static final class ScheduleTask implements Runnable {
        private final DimService service;
        private final boolean enabled;
        private final JSONObject body;

        ScheduleTask(DimService service, boolean enabled, JSONObject body) {
            this.service = service;
            this.enabled = enabled;
            this.body = body;
        }

        @Override
        public void run() {
            service.applySchedule(enabled, body);
        }
    }

    /** Saves or deletes a named day on the main thread. */
    static final class DayTask implements Runnable {
        private final DimService service;
        private final String name;
        private final boolean delete;
        private final JSONArray slots;

        DayTask(DimService service, String name, boolean delete, JSONArray slots) {
            this.service = service;
            this.name = name;
            this.delete = delete;
            this.slots = slots;
        }

        @Override
        public void run() {
            if (delete) service.deleteDay(name); else service.saveDay(name, slots);
        }
    }

    /** First site-local IPv4 address of this device, for the QR code; null if not on a network. */
    static String localIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) {
        }
        return null;
    }
}
