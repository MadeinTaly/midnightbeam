package dev.midnightbeam;

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
import java.net.InetAddress;
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
 *   GET  /api/info       {"name":..,"model":..,"type":"projector|tv|tablet|phone"} to label the device in the phone app
 *   GET  /api/state      current values
 *   POST /api/set        {"red":0-100,"bright":5-100,"temp":1000-6500,"on":true|false}, any subset
 *   GET  /api/schedule   {"enabled":bool,"value":<dayrhythm value>,"resolved":<Slot[] or {mon:[..],..}>}
 *   POST /api/schedule   {"enabled":bool,"value":..,"resolved":..}  (older {"enabled","slots":[..]} still accepted)
 *   GET  /api/audio      audio effects: {"supported":bool,"enabled":bool,"volume":{"level","max"},"eq":..,"bass":..,"virt":..,"loud":..} (null = not available)
 *   POST /api/audio      {"enabled":bool,"volume":n,"bands":[mB..],"preset":n,"bass":0-1000,"virt":0-1000,"loud":0-1500,"reset":true}, any subset
 *   GET  /api/days       saved days: [{"name":..,"slots":[..]}]
 *   POST /api/days       {"name":..,"slots":[..]} saves or replaces a day; {"name":..,"delete":true} deletes it
 *
 * Loopback connections (the phone app's WebView) also get the app page (/app.html, its artwork) and AppApi.
 */
final class RemoteServer extends Thread {
    static final int PORT = 8765;
    private static final int MAX_BODY = 32768;

    /** Static files for "add to home screen" (manifest and icons) and the page's scripts. */
    private static final java.util.Map<String, String> STATIC = new java.util.HashMap<>();

    /** The phone app's own page and artwork, served on loopback only. */
    private static final java.util.Map<String, String> APP = new java.util.HashMap<>();

    static {
        STATIC.put("/manifest.webmanifest", "application/manifest+json");
        STATIC.put("/icon-192.png", "image/png");
        STATIC.put("/icon-512.png", "image/png");
        STATIC.put("/apple-touch-icon.png", "image/png");
        APP.put("/app.html", "text/html; charset=utf-8");
        APP.put("/art.jpg", "image/jpeg");
    }

    private final DimService service;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket socket;
    /** true: all interfaces (LAN remote); false: loopback only (the phone app's own WebView). */
    final boolean lan;

    RemoteServer(DimService service, boolean lan) {
        super("midnightbeam-remote");
        this.service = service;
        this.lan = lan;
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
        try (ServerSocket s = lan ? new ServerSocket(PORT) : new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))) {
            socket = s;
            while (!isInterrupted()) {
                try {
                    Socket c = s.accept();
                    c.setSoTimeout(5000);
                    // one short-lived thread per connection: browsers open several sockets in parallel
                    new Connection(this, c).start();
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
        boolean loopback = c.getInetAddress().isLoopbackAddress();
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
        } else if (method.equals("GET") && dayrhythmType(path) != null) {
            try {
                send(out, 200, dayrhythmType(path), assetBytes(path.substring(1)));
            } catch (IOException e) {
                send(out, 404, "text/plain", "not found");
            }
        } else if (loopback && method.equals("GET") && APP.containsKey(path)) {
            send(out, 200, APP.get(path), assetBytes(path.substring(1)));
        } else if (loopback && path.startsWith("/d/")) {
            if (!keyMatches(key)) send(out, 403, "application/json", "{\"error\":\"pairing key\"}");
            else AppApi.forward(prefs(), path, method, body, read, out);
        } else if (path.startsWith("/api/")) {
            if (!keyMatches(key)) {
                send(out, 403, "application/json", "{\"error\":\"pairing key\"}");
            } else if (loopback && method.equals("GET") && path.equals("/api/app")) {
                send(out, 200, "application/json", AppApi.app(service));
            } else if (loopback && method.equals("GET") && path.equals("/api/discovered")) {
                send(out, 200, "application/json", Discovery.unsaved(prefs()));
            } else if (loopback && method.equals("GET") && path.equals("/api/devices")) {
                send(out, 200, "application/json", AppApi.devices(prefs()));
            } else if (loopback && method.equals("POST") && path.equals("/api/devices")) {
                String r = null;
                try {
                    r = AppApi.changeDevices(prefs(), new JSONObject(AppApi.text(body, read)));
                } catch (JSONException ignored) {
                }
                if (r == null) send(out, 400, "application/json", "{\"error\":\"bad request\"}");
                else send(out, 200, "application/json", r);
            } else if (method.equals("GET") && path.equals("/api/info")) {
                send(out, 200, "application/json", service.infoJson());
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
            } else if (method.equals("GET") && path.equals("/api/audio")) {
                send(out, 200, "application/json", service.audioJson());
            } else if (method.equals("POST") && path.equals("/api/audio")) {
                try {
                    AudioTask task = new AudioTask(service, new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8)));
                    main.post(task);
                    task.await(); // answer with the state after the change
                    send(out, 200, "application/json", service.audioJson());
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

    private android.content.SharedPreferences prefs() {
        return service.getSharedPreferences(DimService.PREFS, android.content.Context.MODE_PRIVATE);
    }

    private boolean keyMatches(String key) {
        String expected = service.pairingKey();
        if (key == null || expected == null) return false;
        return MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String asset(String name) throws IOException {
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    /** MIME type of a library file under /dayrhythm/ (the library's own sources), null for any other path. */
    private static String dayrhythmType(String path) {
        if (!path.startsWith("/dayrhythm/") || path.contains("..") || path.contains("//") || path.contains("\\")) return null;
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        return null;
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

    static void send(OutputStream out, int code, String type, String body) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8), null);
    }

    static void send(OutputStream out, int code, String type, String body, String extraHeader) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8), extraHeader);
    }

    static void send(OutputStream out, int code, String type, byte[] b) throws IOException {
        send(out, code, type, b, null);
    }

    /** extraHeader: one more "Name: value" header line, or null. */
    static void send(OutputStream out, int code, String type, byte[] b, String extraHeader) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : code == 502 ? "Bad Gateway" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (extraHeader != null ? extraHeader + "\r\n" : "")
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

    /** Serves one connection on its own thread (a plain class, see MainActivity). */
    static final class Connection extends Thread {
        private final RemoteServer server;
        private final Socket socket;

        Connection(RemoteServer server, Socket socket) {
            super("midnightbeam-conn");
            this.server = server;
            this.socket = socket;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (Socket c = socket) {
                server.handle(c);
            } catch (IOException ignored) {
            }
        }
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

    /** Applies audio effect changes on the main thread; the server thread can wait for it. */
    static final class AudioTask implements Runnable {
        private final DimService service;
        private final JSONObject body;
        private final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        AudioTask(DimService service, JSONObject body) {
            this.service = service;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                service.applyAudio(body);
            } finally {
                done.countDown();
            }
        }

        void await() {
            try {
                done.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
