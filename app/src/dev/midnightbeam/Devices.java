package dev.midnightbeam;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Saved devices of the phone app: a JSON array [{name, host, port, key, type}] in the shared preferences. */
final class Devices {
    private static final String PREF = "devices";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{1,128}");
    private static final Pattern FRAGMENT_KEY = Pattern.compile("(?:^|&)k=([0-9a-f]+)");

    private Devices() {
    }

    static JSONArray load(SharedPreferences p) {
        try {
            return new JSONArray(p.getString(PREF, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static int find(JSONArray a, String host, int port) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d != null && d.optString("host").equals(host) && d.optInt("port") == port) return i;
        }
        return -1;
    }

    /** Adds the device or updates its key (and its name, when one is given). */
    static void upsert(SharedPreferences p, String name, String host, int port, String key) {
        JSONArray a = load(p);
        int i = find(a, host, port);
        try {
            JSONObject d = i >= 0 ? a.getJSONObject(i) : new JSONObject();
            d.put("host", host).put("port", port).put("key", key);
            if (name != null && !name.isEmpty()) d.put("name", name);
            else if (!d.has("name")) d.put("name", host);
            if (i < 0) a.put(d);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        p.edit().putString(PREF, a.toString()).apply();
    }

    static void rename(SharedPreferences p, String host, int port, String name) {
        JSONArray a = load(p);
        int i = find(a, host, port);
        if (i < 0 || name.isEmpty()) return;
        try {
            a.getJSONObject(i).put("name", name);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        p.edit().putString(PREF, a.toString()).apply();
    }

    /** Kind of device, for its icon only (see AppApi). */
    static void setType(SharedPreferences p, String host, int port, String type) {
        JSONArray a = load(p);
        int i = find(a, host, port);
        if (i < 0) return;
        try {
            a.getJSONObject(i).put("type", type);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        p.edit().putString(PREF, a.toString()).apply();
    }

    static void delete(SharedPreferences p, String host, int port) {
        JSONArray a = load(p);
        int i = find(a, host, port);
        if (i < 0) return;
        a.remove(i);
        p.edit().putString(PREF, a.toString()).apply();
    }

    /** JSON object of the device with this "host:port" id, or null. */
    static JSONObject byId(SharedPreferences p, String id) {
        int c = id.lastIndexOf(':');
        if (c <= 0) return null;
        try {
            JSONArray a = load(p);
            int i = find(a, id.substring(0, c), Integer.parseInt(id.substring(c + 1)));
            return i >= 0 ? a.optJSONObject(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Saves the device of a pairing link: midnightbeam://add?host=..&port=..&k=..&name=.. or the TV's QR link
     * http://IP:8765/#k=... Returns its "host:port" id, or null if the link is not valid.
     */
    static String addLink(SharedPreferences p, Uri u) {
        if (u == null) return null;
        String host, key, name = null;
        int port = RemoteServer.PORT;
        if ("midnightbeam".equals(u.getScheme())) {
            host = u.getQueryParameter("host");
            key = u.getQueryParameter("k");
            name = u.getQueryParameter("name");
            try {
                String ps = u.getQueryParameter("port");
                if (ps != null) port = Integer.parseInt(ps);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if ("http".equals(u.getScheme())) {
            host = u.getHost();
            if (u.getPort() > 0) port = u.getPort();
            key = u.getQueryParameter("k");
            String f = u.getFragment();
            if (key == null && f != null) {
                Matcher m = FRAGMENT_KEY.matcher(f);
                if (m.find()) key = m.group(1);
            }
        } else {
            return null;
        }
        if (host == null || !HOST.matcher(host).matches() || key == null || !KEY.matcher(key).matches()
                || port < 1 || port > 65535) return null;
        upsert(p, cleanName(name), host, port, key);
        return host + ":" + port;
    }

    /** Device name without control characters, at most 40 characters; null stays null. */
    static String cleanName(String name) {
        if (name == null) return null;
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }
}
