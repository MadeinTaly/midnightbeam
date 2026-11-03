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
 *   GET  /api/info       {"name":..,"model":..,"type":"projector|tv|tablet|phone","app":<download url>} to label the device in the phone app
 *   GET  /api/state      current values
 *   POST /api/set        {"red":0-100,"bright":5-100,"temp":1000-6500,"on":true|false,"advanced":bool,"advancedConfirm":bool}, any subset
 *   GET  /api/schedule   {"enabled":bool,"value":<dayrhythm value>,"resolved":<Slot[] or {mon:[..],..}>}
 *   POST /api/schedule   {"enabled":bool,"value":..,"resolved":..}  (older {"enabled","slots":[..]} still accepted)
 *   GET  /api/audio      audio effects: {"supported":bool,"enabled":bool,"volume":{"level","max"},"eq":..,"bass":..,"virt":..,"loud":..} (null = not available)
 *   POST /api/audio      {"enabled":bool,"volume":n,"bands":[mB..],"preset":n,"bass":0-1000,"virt":0-1000,"loud":0-1500,"reset":true}, any subset
 *   POST /api/pair       {"pin":"1234"} -> {"k":<pairing key>}, no X-Key needed; only while the TV's pairing popup is open;
 *                        else 403 {"error":"pin"|"closed"|"locked"} (locked: 5 wrong PINs from this address)
 *                        (403 {"error":"closed"} otherwise, {"error":"pin"} if wrong; 5 wrong in a row change the PIN)
 *   GET  /api/days       saved days: [{"name":..,"slots":[..]}]
 *   POST /api/days       {"name":..,"slots":[..]} saves or replaces a day; {"name":..,"delete":true} deletes it
 *
 * Loopback connections (the phone app's WebView) also get the app page (/app.html, its artwork) and AppApi.
 */
final class RemoteServer extends Thread {
    static final int PORT = 8765;
    private static final int MAX_BODY = 32768;
    private static final int MAX_CONNECTIONS = 16;
    private static final int MAX_HEADERS = 64;
    private static final long REQUEST_MS = 10000;

    /** Static files for "add to home screen" (manifest and icons) and the page's scripts. */
    private static final java.util.Map<String, String> STATIC = new java.util.HashMap<>();

    /** The phone app's own page and artwork, served on loopback only. */
    private static final java.util.Map<String, String> APP = new java.util.HashMap<>();

    static {
        STATIC.put("/manifest.webmanifest", "application/manifest+json");
        STATIC.put("/icon-192.png", "image/png");
        STATIC.put("/icon-512.png", "image/png");
        STATIC.put("/apple-touch-icon.png", "image/png");
        // the app's typefaces (SIL OFL, see assets/fonts), used by both pages
        STATIC.put("/fonts/Bungee-Regular.ttf", "font/ttf");
        STATIC.put("/fonts/Chivo-Regular.ttf", "font/ttf");
        STATIC.put("/fonts/Chivo-Bold.ttf", "font/ttf");
        STATIC.put("/fonts/Chivo-Black.ttf", "font/ttf");
        STATIC.put("/fonts/Silkscreen-Bold.ttf", "font/ttf");
        APP.put("/app.html", "text/html; charset=utf-8");
        APP.put("/card.svg", "image/svg+xml");
    }

    /** "METHOD path client" of the request this connection thread is answering, for the dev debug log (send()). */
    private static final ThreadLocal<String> REQ = new ThreadLocal<>();
    /** X-Since of the request: the state revision the client already has (GET /api/state waits for a newer one). */
    static final ThreadLocal<String> SINCE = new ThreadLocal<>();
    /** How long GET /api/state with X-Since waits for a change before answering the same state. */
    static final long WAIT_MS = 20000;

    private final DimService service;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket socket;
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    /** true: all interfaces (LAN remote); false: loopback only (the phone app's own WebView). */
    final boolean lan;

    RemoteServer(DimService service, boolean lan) {
        super("midnightbeam-remote");
        this.service = service;
        this.lan = lan;
        this.assets = service.getAssets();
        setDaemon(true);
    }

    /** Binds the port; false if it is busy (the caller drops this server and tries again on the next render). */
    boolean bind() {
        try {
            socket = lan ? new ServerSocket(PORT) : new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
            return true;
        } catch (IOException e) {
            return false;
        }
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
        try (ServerSocket s = socket) {
            while (!isInterrupted() && !s.isClosed()) {
                try {
                    Socket c = s.accept();
                    if (active.incrementAndGet() > MAX_CONNECTIONS) {
                        active.decrementAndGet();
                        c.close();
                        continue;
                    }
                    c.setSoTimeout(5000);
                    // one short-lived thread per connection: browsers open several sockets in parallel
                    new Connection(this, c).start();
                } catch (IOException e) {
                    if (isInterrupted() || s.isClosed()) return;
                    try {
                        Thread.sleep(200); // e.g. out of file descriptors: do not spin
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = new java.io.BufferedInputStream(c.getInputStream());
        long deadline = System.currentTimeMillis() + REQUEST_MS;
        String requestLine = readLine(in, deadline);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        boolean polled = method.equals("GET") && (path.equals("/api/state") || path.equals("/api/debug"));
        REQ.set(path.startsWith("/api/") && !polled || path.startsWith("/d/")
                ? method + " " + path + " " + c.getInetAddress().getHostAddress() : null);

        String key = null;
        SINCE.set(null);
        int length = 0;
        String line;
        int headers = 0;
        while ((line = readLine(in, deadline)) != null && !line.isEmpty()) {
            if (++headers > MAX_HEADERS) throw new IOException("too many headers");
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-key")) key = value;
            if (name.equals("x-since")) SINCE.set(value);
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
            if (System.currentTimeMillis() > deadline) throw new IOException("request too slow");
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }

        try {
            route(method, path, key, loopback, c.getInetAddress().getHostAddress(), body, read, out);
        } catch (java.io.FileNotFoundException e) { // an asset that is not in the APK
            send(out, 404, "text/plain", "not found");
        }
    }

    private void route(String method, String path, String key, boolean loopback, String client, byte[] body, int read, OutputStream out)
            throws IOException {
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
        } else if (method.equals("POST") && path.equals("/api/pair")) {
            pair(out, body, read, client);
        } else if (path.startsWith("/api/")) {
            if (!keyMatches(key)) {
                send(out, 403, "application/json", "{\"error\":\"pairing key\"}");
            } else if (loopback && method.equals("GET") && path.equals("/api/app")) {
                send(out, 200, "application/json", AppApi.app(service));
            } else if (loopback && method.equals("POST") && path.equals("/api/app")) {
                boolean ok = false;
                try {
                    ok = AppApi.setUpdates(service, new JSONObject(AppApi.text(body, read)));
                } catch (JSONException ignored) {
                }
                if (ok) send(out, 200, "application/json", AppApi.app(service));
                else send(out, 400, "application/json", "{\"error\":\"bad request\"}");
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
            } else if (method.equals("GET") && path.equals("/api/debug") && Dbg.on(service)) {
                send(out, 200, "application/json", Dbg.state(service).toString());
            } else if (method.equals("GET") && path.equals("/api/info")) {
                send(out, 200, "application/json", service.infoJson());
            } else if (method.equals("GET") && path.equals("/api/state")) {
                String since = SINCE.get();
                send(out, 200, "application/json", since == null ? service.stateJson() : service.stateJsonAfter(since, WAIT_MS));
            } else if (method.equals("POST") && path.equals("/api/set")) {
                try {
                    JSONObject j = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                    int advanced = j.has("advanced") ? (j.getBoolean("advanced") ? 1 : 0) : -1;
                    boolean advancedConfirm = j.has("advancedConfirm") && j.getBoolean("advancedConfirm");
                    if (advanced == 1 && !Priv.available(service)) {
                        send(out, 409, "application/json", "{\"error\":\"no privileged access\"}");
                        return;
                    }
                    SetTask task = new SetTask(service,
                            j.has("red") ? j.getInt("red") : -1,
                            j.has("bright") ? j.getInt("bright") : -1,
                            j.has("temp") ? j.getInt("temp") : -1,
                            j.has("on") ? (j.getBoolean("on") ? 1 : 0) : -1,
                            advanced, advancedConfirm);
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

    private static final Object PIN_LOCK = new Object();
    /** Wrong PINs per client address since the pairing popup opened, guarded by PIN_LOCK. */
    private static final java.util.Map<String, Integer> PIN_FAILS = new java.util.HashMap<>();
    private static int pinFailsTotal; // all wrong PINs since the popup opened, guarded by PIN_LOCK
    static final int PIN_TRIES = 5; // per client: then that client is locked out until the popup opens again
    static final int PIN_TRIES_TOTAL = 20; // all clients together: then the PIN changes and pairing closes

    /** Wrong PINs per client address and in total (dev debug channel); never the PIN itself. */
    static JSONObject pinFails() throws JSONException {
        synchronized (PIN_LOCK) {
            JSONObject per = new JSONObject();
            for (java.util.Map.Entry<String, Integer> e : PIN_FAILS.entrySet()) per.put(e.getKey(), e.getValue());
            return new JSONObject().put("total", pinFailsTotal).put("perClient", per);
        }
    }

    /** Forgets the wrong PINs: called when the pairing popup opens (or New code reopens it). */
    static void resetPinFails() {
        synchronized (PIN_LOCK) {
            PIN_FAILS.clear();
            pinFailsTotal = 0;
        }
    }

    /**
     * POST /api/pair: trades the TV's PIN for its pairing key, only while the pairing popup is open. A client with
     * PIN_TRIES wrong PINs is locked out (the others still pair with the PIN on screen, so one device on the network
     * cannot block pairing); PIN_TRIES_TOTAL wrong PINs from everyone change the PIN and close pairing, which bounds a
     * guess spread over many addresses to 20 in 10000 per press of New code.
     */
    private void pair(OutputStream out, byte[] body, int read, String client) throws IOException {
        if (!DimService.pairingOpen()) {
            Dbg.log("pair", "refused, popup closed (" + client + ")");
            send(out, 403, "application/json", "{\"error\":\"closed\"}");
            return;
        }
        String pin;
        try {
            pin = new JSONObject(AppApi.text(body, read)).optString("pin");
        } catch (JSONException e) {
            send(out, 400, "application/json", "{\"error\":\"bad json\"}");
            return;
        }
        String key = null, error = "pin";
        synchronized (PIN_LOCK) {
            Integer fails = PIN_FAILS.get(client);
            if (!DimService.pairingOpen()) {
                error = "closed";
            } else if (fails != null && fails >= PIN_TRIES) {
                error = "locked";
            } else if (MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                    DimService.pairingPin(prefs()).getBytes(StandardCharsets.UTF_8))) {
                PIN_FAILS.remove(client);
                key = DimService.pairingKey(prefs());
            } else {
                PIN_FAILS.put(client, fails == null ? 1 : fails + 1);
                if (++pinFailsTotal >= PIN_TRIES_TOTAL) {
                    DimService.newPairingPin(prefs());
                    DimService.openPairing(false);
                }
            }
        }
        Dbg.log("pair", (key != null ? "success " : "fail (" + error + ") ") + client);
        if (key != null) send(out, 200, "application/json", "{\"k\":" + JSONObject.quote(key) + "}");
        else send(out, 403, "application/json", "{\"error\":\"" + error + "\"}");
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
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : code == 413 ? "Payload Too Large" : code == 502 ? "Bad Gateway" : "Error";
        String req = REQ.get();
        if (req != null) {
            Dbg.log("http", req + " -> " + code);
            REQ.set(null);
        }
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
    private static String readLine(InputStream in, long deadline) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (System.currentTimeMillis() > deadline) throw new IOException("request too slow");
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
            } finally {
                server.active.decrementAndGet();
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
        final int advanced;
        final boolean advancedConfirm;

        SetTask(DimService service, int red, int bright, int temp, int on, int advanced, boolean advancedConfirm) {
            this.service = service;
            this.red = red;
            this.bright = bright;
            this.temp = temp;
            this.on = on;
            this.advanced = advanced;
            this.advancedConfirm = advancedConfirm;
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
