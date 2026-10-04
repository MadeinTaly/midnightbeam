package dev.midnightbeam;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/**
 * Announces this TV on the local network with mDNS / DNS-SD (service type _midnightbeam._tcp), the way WLED
 * devices announce themselves, so the phone app finds it at once and follows it when its address changes.
 * TXT record: id (random, stable, not secret) and type. The pairing key is never announced.
 */
final class Advertiser implements NsdManager.RegistrationListener {
    static final String SERVICE_TYPE = "_midnightbeam._tcp";

    private final NsdManager nsd;
    private boolean registered;

    Advertiser(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    void start(String name, String id, String type) {
        if (registered || nsd == null) return;
        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceName(name);
        s.setServiceType(SERVICE_TYPE);
        s.setPort(RemoteServer.PORT);
        s.setAttribute("id", id);
        s.setAttribute("type", type);
        try {
            nsd.registerService(s, NsdManager.PROTOCOL_DNS_SD, this);
            registered = true;
        } catch (RuntimeException ignored) {
            // no mDNS on this box: the phone app still finds it with its fallback scan
        }
    }

    void stop() {
        if (!registered) return;
        registered = false;
        try {
            nsd.unregisterService(this);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
        registered = false;
    }

    @Override
    public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
    }

    @Override
    public void onServiceRegistered(NsdServiceInfo serviceInfo) {
    }

    @Override
    public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
    }
}
