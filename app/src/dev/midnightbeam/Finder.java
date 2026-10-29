package dev.midnightbeam;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds a paired TV whose address changed (DHCP gives it a new one after a reboot): probes the phone's /24 subnet
 * on the TV's port and asks /api/info with the saved pairing key. Only that TV accepts the key, so the first
 * host answering 200 is the right one. Takes about 3 s; each device is searched at most once a minute.
 * No inner or anonymous classes (see MainActivity): the probes are this class's own instances.
 */
final class Finder implements Runnable {
    private static final int THREADS = 32;
    private static final int CONNECT_MS = 350;
    private static final Map<String, Long> LAST = new HashMap<>();

    private final String prefix;
    private final int from;
    private final int to;
    private final String skip;
    private final int port;
    private final String key;
    private final AtomicReference<String> found;

    private Finder(String prefix, int from, int to, String skip, int port, String key, AtomicReference<String> found) {
        this.prefix = prefix;
        this.from = from;
        this.to = to;
        this.skip = skip;
        this.port = port;
        this.key = key;
        this.found = found;
    }

    /** New address of the device with this id ("host:port") and key, or null (not found, or searched recently). */
    static String find(String id, String oldHost, int port, String key) {
        synchronized (LAST) {
            Long last = LAST.get(id);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 60000) return null;
        }
        String me = RemoteServer.localIp();
        if (me == null) return null;
        synchronized (LAST) {
            LAST.put(id, System.currentTimeMillis());
        }
        String prefix = me.substring(0, me.lastIndexOf('.') + 1);
        AtomicReference<String> found = new AtomicReference<>();
        Thread[] threads = new Thread[THREADS];
        int per = (254 + THREADS - 1) / THREADS;
        for (int t = 0; t < THREADS; t++) {
            int from = 1 + t * per;
            threads[t] = new Thread(new Finder(prefix, from, Math.min(254, from + per - 1), oldHost, port, key, found),
                    "midnightbeam-find");
            threads[t].setDaemon(true);
            threads[t].start();
        }
        long deadline = System.currentTimeMillis() + 6000;
        for (Thread th : threads) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || found.get() != null) break;
            try {
                th.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return found.get();
    }

    /** True if host:port answers /api/info to this key with exactly this device id. */
    static boolean verify(String host, int port, String key, String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/api/info").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            c.setRequestProperty("X-Key", key);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 8192) b.write(buf, 0, n);
                return c.getResponseCode() == 200 && id.equals(new org.json.JSONObject(b.toString("UTF-8")).optString("id"));
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void run() {
        for (int i = from; i <= to && found.get() == null; i++) {
            String host = prefix + i;
            if (host.equals(skip)) continue;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            } catch (Exception e) {
                continue; // nothing listening there
            }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/api/info").openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(1500);
                c.setRequestProperty("X-Key", key);
                if (c.getResponseCode() == 200) found.compareAndSet(null, host);
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }
}
