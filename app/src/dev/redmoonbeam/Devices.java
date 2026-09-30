package dev.redmoonbeam;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Saved devices of the phone app: a JSON array [{name, host, port, key}] in the shared preferences. */
final class Devices {
    private static final String PREF = "devices";

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

    static void delete(SharedPreferences p, String host, int port) {
        JSONArray a = load(p);
        int i = find(a, host, port);
        if (i < 0) return;
        a.remove(i);
        p.edit().putString(PREF, a.toString()).apply();
    }
}
