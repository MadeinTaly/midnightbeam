package dev.midnightbeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds MidnightBeam TVs on the local network with mDNS / DNS-SD while the phone screen is open (like the WLED app:
 * NsdManager discovery plus a Wi-Fi multicast lock, services resolved one at a time). A saved TV seen at a new
 * address is moved there at once; TVs not saved yet are listed for "Add device" (GET /api/discovered).
 */
final class Discovery implements NsdManager.DiscoveryListener, NsdManager.ResolveListener {
    /** Devices seen in this session, by id: {id, name, type, host, port}. */
    private static final Map<String, JSONObject> FOUND = new LinkedHashMap<>();

    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final SharedPreferences prefs;
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean running;
    private boolean resolving;

    Discovery(Context context, SharedPreferences prefs) {
        this.prefs = prefs;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock = wifi == null ? null : wifi.createMulticastLock("midnightbeam-discovery");
        if (lock != null) lock.setReferenceCounted(false);
    }

    synchronized void start() {
        if (running || nsd == null) return;
        try {
            if (lock != null) lock.acquire();
            nsd.discoverServices(Advertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
            running = true;
        } catch (RuntimeException e) {
            release();
        }
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        queue.clear();
        try {
            nsd.stopServiceDiscovery(this);
        } catch (RuntimeException ignored) {
        }
        release();
    }

    private void release() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** TVs seen on the network and not saved yet. */
    static String unsaved(SharedPreferences p) {
        JSONArray saved = Devices.load(p), out = new JSONArray();
        synchronized (FOUND) {
            for (JSONObject d : FOUND.values()) {
                boolean known = false;
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject s = saved.optJSONObject(i);
                    if (s != null && (d.optString("id").equals(s.optString("did"))
                            || (d.optString("host").equals(s.optString("host")) && d.optInt("port") == s.optInt("port")))) {
                        known = true;
                    }
                }
                if (!known) out.put(d);
            }
        }
        return out.toString();
    }

    // ---- discovery ----

    @Override
    public void onServiceFound(NsdServiceInfo service) {
        synchronized (this) {
            if (!running) return;
            queue.add(service);
        }
        next();
    }

    /** Resolves the queued services one at a time (older Android allows only one resolve at once). */
    private void next() {
        NsdServiceInfo s;
        synchronized (this) {
            if (resolving || queue.isEmpty() || !running) return;
            resolving = true;
            s = queue.poll();
        }
        try {
            nsd.resolveService(s, this);
        } catch (RuntimeException e) {
            synchronized (this) {
                resolving = false;
            }
        }
    }

    @Override
    public void onServiceResolved(NsdServiceInfo s) {
        synchronized (this) {
            resolving = false;
        }
        InetAddress a = s.getHost();
        Map<String, byte[]> attrs = s.getAttributes();
        byte[] idBytes = attrs == null ? null : attrs.get("id");
        if (a instanceof Inet4Address && idBytes != null) {
            String id = new String(idBytes, StandardCharsets.UTF_8);
            byte[] t = attrs.get("type");
            found(id, s.getServiceName(), t == null ? "tv" : new String(t, StandardCharsets.UTF_8), a.getHostAddress(), s.getPort());
        }
        next();
    }

    @Override
    public void onResolveFailed(NsdServiceInfo s, int errorCode) {
        synchronized (this) {
            resolving = false;
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && running) queue.add(s); // try again later
        }
        next();
    }

    private void found(String id, String name, String type, String host, int port) {
        if (!id.matches("[0-9a-f]{1,32}")) return;
        try {
            JSONObject d = new JSONObject().put("id", id).put("name", Devices.cleanName(name)).put("type", type)
                    .put("host", host).put("port", port);
            synchronized (FOUND) {
                FOUND.put(id, d);
            }
        } catch (JSONException e) {
            return;
        }
        // a saved TV announced at a new address (DHCP after a reboot): follow it
        JSONArray saved = Devices.load(prefs);
        for (int i = 0; i < saved.length(); i++) {
            JSONObject s = saved.optJSONObject(i);
            if (s == null || !id.equals(s.optString("did"))) continue;
            if (!s.optString("host").equals(host) || s.optInt("port") != port) {
                String old = s.optString("host") + ":" + s.optInt("port");
                Devices.move(prefs, s.optString("host"), s.optInt("port"), host);
                AppApi.noteMoved(old, host + ":" + s.optInt("port"));
            }
        }
    }

    @Override
    public void onServiceLost(NsdServiceInfo service) {
    }

    @Override
    public void onDiscoveryStarted(String serviceType) {
    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        synchronized (this) {
            running = false;
        }
        release();
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
    }
}
