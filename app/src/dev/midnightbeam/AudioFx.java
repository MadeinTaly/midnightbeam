package dev.midnightbeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Optional audio effects (equalizer, bass boost, virtualizer, loudness) applied globally on audio session 0, the
 * output mix. That is deprecated and only works where the device's audio driver supports global effects (many
 * TVs and projectors do, most phones do not); each effect is created on its own, a missing one is just absent.
 * Settings live in the service's preferences: audio_on, audio_bands (mB, comma separated), audio_bass (0-1000),
 * audio_virt (0-1000), audio_loud (mB, 0-1500). A strength of 0 leaves that effect disabled.
 * Methods are synchronized: apply runs on the main thread, json on the server thread.
 */
final class AudioFx {
    private static final int PRIORITY = 1000;
    private static final int MAX_LOUD = 1500;
    private static Boolean supported;

    private final SharedPreferences prefs;
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;
    private LoudnessEnhancer loud;
    /**
     * Output stage (-1) when the system lets us use it: unlike the output mix (0), Android does not suspend it when
     * a playing app enables an effect of its own (e.g. a video player's loudness boost). Probed once.
     */
    private static int SESSION = -2;

    private final AudioManager audioManager;
    private final Context context;

    AudioFx(Context context, SharedPreferences prefs) {
        this.prefs = prefs;
        this.context = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Media volume of the device, {"level","max"}: plain Android, works on any device (null if unavailable). */
    private Object volumeJson() throws JSONException {
        if (audioManager == null) return JSONObject.NULL;
        return new JSONObject().put("level", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void setVolume(int level) {
        if (audioManager == null) return;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamp(level, 0, max), 0);
        } catch (RuntimeException e) { // e.g. fixed volume or Do Not Disturb restrictions
            log("volume", e);
        }
    }

    /** Whether this device accepts a global equalizer; probed once (output stage first, then output mix). */
    static synchronized boolean supported() {
        if (supported == null) {
            supported = false;
            for (int session : new int[] {-1, 0}) {
                try {
                    new Equalizer(0, session).release();
                    SESSION = session;
                    supported = true;
                    log("session", session);
                    break;
                } catch (Throwable e) {
                    log("session " + session, e);
                }
            }
        }
        return supported;
    }

    boolean enabled() {
        return prefs.getBoolean("audio_on", false);
    }

    /** Creates and applies the effects when audio is on, releases them when it is off. */
    synchronized void refresh() {
        if (enabled() && supported()) {
            create();
            for (Integer id : open) if (!sessions.containsKey(id)) attach(id);
            applySaved();
        } else {
            release();
        }
    }

    synchronized void release() {
        for (Integer id : new java.util.ArrayList<>(sessions.keySet())) detach(id);
        if (eq != null) eq.release();
        if (bass != null) bass.release();
        if (virt != null) virt.release();
        if (loud != null) loud.release();
        eq = null;
        bass = null;
        virt = null;
        loud = null;
    }

    private void create() {
        if (eq == null) {
            try {
                eq = new Equalizer(PRIORITY, SESSION);
            } catch (Throwable ignored) {
            }
        }
        if (bass == null) {
            try {
                bass = new BassBoost(PRIORITY, SESSION);
            } catch (Throwable ignored) {
            }
        }
        if (virt == null) {
            try {
                virt = new Virtualizer(PRIORITY, SESSION);
            } catch (Throwable ignored) {
            }
        }
        if (loud == null) {
            try {
                loud = new LoudnessEnhancer(SESSION);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applySaved() {
        applyTo(eq, bass, virt, loud);
        int gain = prefs.getInt("audio_loud", 0);
        for (java.util.Map.Entry<Integer, android.media.audiofx.AudioEffect[]> e : sessions.entrySet()) {
            android.media.audiofx.AudioEffect[] fx = e.getValue();
            // an app may use its own loudness boost on its session (same effect, shared): only take it over when
            // our loudness is up, and let it go at 0 so the app's own setting stays as it was
            if (gain > 0 && fx[3] == null) {
                try {
                    fx[3] = new LoudnessEnhancer(e.getKey());
                } catch (Throwable ignored) {
                }
            } else if (gain == 0 && fx[3] != null) {
                try {
                    fx[3].release();
                } catch (RuntimeException ignored) {
                }
                fx[3] = null;
            }
            applyTo((Equalizer) fx[0], (BassBoost) fx[1], (Virtualizer) fx[2], (LoudnessEnhancer) fx[3]);
        }
    }

    /** Applies the saved values to one set of effects; each effect on its own, so one refused value never leaves the others off. */
    private void applyTo(Equalizer eq, BassBoost bass, Virtualizer virt, LoudnessEnhancer loud) {
        if (eq != null) {
            log("eq on", tryEnable(eq, true));
            int[] levels = bands(eq);
            for (int i = 0; i < levels.length; i++) {
                try {
                    eq.setBandLevel((short) i, (short) levels[i]);
                } catch (RuntimeException e) {
                    log("eq band " + i, e);
                }
            }
        }
        if (bass != null) {
            int s = prefs.getInt("audio_bass", 0);
            try {
                if (s > 0) bass.setStrength((short) s);
            } catch (RuntimeException e) {
                log("bass", e);
            }
            log("bass on", tryEnable(bass, s > 0));
        }
        if (virt != null) {
            int s = prefs.getInt("audio_virt", 0);
            try {
                if (s > 0) virt.setStrength((short) s);
            } catch (RuntimeException e) {
                log("virt", e);
            }
            log("virt on", tryEnable(virt, s > 0));
        }
        if (loud != null) {
            int g = prefs.getInt("audio_loud", 0);
            try {
                if (g > 0) loud.setTargetGain(g);
            } catch (RuntimeException e) {
                log("loud", e);
            }
            log("loud on", tryEnable(loud, g > 0));
        }
    }

    // ---- Per-app sessions (announced by players, see SessionReceiver) ----

    /** Effects attached to each open app session: {Equalizer, BassBoost, Virtualizer, LoudnessEnhancer}. */
    private final java.util.Map<Integer, android.media.audiofx.AudioEffect[]> sessions = new java.util.HashMap<>();
    /** Sessions announced as open, also while audio is off (attached when it is turned on). */
    private final java.util.Set<Integer> open = new java.util.HashSet<>();

    synchronized void session(int id, boolean opened) {
        if (opened) {
            open.add(id);
            if (enabled() && supported() && !sessions.containsKey(id)) attach(id);
        } else {
            open.remove(id);
            detach(id);
        }
    }

    private void attach(int id) {
        android.media.audiofx.AudioEffect[] fx = new android.media.audiofx.AudioEffect[4];
        try {
            fx[0] = new Equalizer(PRIORITY, id);
        } catch (Throwable e) {
            log("session " + id + " eq", e);
        }
        try {
            fx[1] = new BassBoost(PRIORITY, id);
        } catch (Throwable ignored) {
        }
        try {
            fx[2] = new Virtualizer(PRIORITY, id);
        } catch (Throwable ignored) {
        }
        if (prefs.getInt("audio_loud", 0) > 0) { // see applySaved: never switch off an app's own loudness boost
            try {
                fx[3] = new LoudnessEnhancer(id);
            } catch (Throwable ignored) {
            }
        }
        sessions.put(id, fx);
        applyTo((Equalizer) fx[0], (BassBoost) fx[1], (Virtualizer) fx[2], (LoudnessEnhancer) fx[3]);
    }

    private void detach(int id) {
        android.media.audiofx.AudioEffect[] fx = sessions.remove(id);
        if (fx == null) return;
        for (android.media.audiofx.AudioEffect e : fx) {
            if (e != null) {
                try {
                    e.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** setEnabled's status (0 = success), or the exception it threw. */
    private static Object tryEnable(android.media.audiofx.AudioEffect fx, boolean on) {
        try {
            return fx.setEnabled(on) + " enabled=" + fx.getEnabled() + " control=" + fx.hasControl();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void log(String what, Object result) {
        android.util.Log.i("MidnightBeamAudio", what + ": " + result);
    }

    /** Saved band levels, one per band of the equalizer, clamped to its range (0 where nothing is saved). */
    private int[] bands(Equalizer e) {
        short[] range = e.getBandLevelRange();
        int[] levels = new int[e.getNumberOfBands()];
        String[] saved = prefs.getString("audio_bands", "").split(",");
        for (int i = 0; i < levels.length && i < saved.length; i++) {
            try {
                levels[i] = clamp(Integer.parseInt(saved[i].trim()), range[0], range[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return levels;
    }

    /** State for the phone: supported, enabled, and the four effects (null where the device cannot create them). */
    synchronized String json() {
        boolean on = enabled();
        boolean sup = supported();
        try {
            JSONObject o = new JSONObject().put("supported", sup).put("enabled", on);
            Object eqJson = JSONObject.NULL, bassJson = JSONObject.NULL, virtJson = JSONObject.NULL, loudJson = JSONObject.NULL;
            if (sup) {
                boolean temporary = eq == null && bass == null && virt == null && loud == null;
                if (temporary) create(); // disabled: still report what the device offers, then let go
                try {
                    if (eq != null) eqJson = eqJson();
                    if (bass != null) bassJson = new JSONObject().put("strength", clamp(prefs.getInt("audio_bass", 0), 0, 1000));
                    if (virt != null) virtJson = new JSONObject().put("strength", clamp(prefs.getInt("audio_virt", 0), 0, 1000));
                    if (loud != null) loudJson = new JSONObject().put("gain", clamp(prefs.getInt("audio_loud", 0), 0, MAX_LOUD));
                } finally {
                    if (temporary) release();
                }
            }
            o.put("volume", volumeJson());
            // can follow players that do not announce their session (SessionScanner, needs DUMP granted with adb)
            o.put("follow", context.checkSelfPermission("android.permission.DUMP") == android.content.pm.PackageManager.PERMISSION_GRANTED);
            return o.put("eq", eqJson).put("bass", bassJson).put("virt", virtJson).put("loud", loudJson).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject eqJson() throws JSONException {
        short[] range = eq.getBandLevelRange();
        int[] levels = bands(eq);
        JSONArray bands = new JSONArray();
        for (int i = 0; i < levels.length; i++) {
            bands.put(new JSONObject().put("freq", eq.getCenterFreq((short) i) / 1000).put("level", levels[i]));
        }
        JSONArray presets = new JSONArray();
        for (int i = 0; i < eq.getNumberOfPresets(); i++) presets.put(eq.getPresetName((short) i));
        return new JSONObject().put("bands", bands).put("min", range[0]).put("max", range[1]).put("presets", presets);
    }

    /**
     * Applies any subset of {"volume":0..max,"enabled","bands":[mB..],"preset":index,"bass","virt","loud","reset":true},
     * clamped to what the device allows, and stores it.
     */
    synchronized void apply(JSONObject j) {
        if (j.has("volume")) setVolume(j.optInt("volume"));
        SharedPreferences.Editor ed = prefs.edit();
        if (j.has("enabled")) ed.putBoolean("audio_on", j.optBoolean("enabled", false));
        if (j.optBoolean("reset", false)) {
            ed.putString("audio_bands", "").putInt("audio_bass", 0).putInt("audio_virt", 0).putInt("audio_loud", 0);
        }
        if (j.has("bass")) ed.putInt("audio_bass", clamp(j.optInt("bass"), 0, 1000));
        if (j.has("virt")) ed.putInt("audio_virt", clamp(j.optInt("virt"), 0, 1000));
        if (j.has("loud")) ed.putInt("audio_loud", clamp(j.optInt("loud"), 0, MAX_LOUD));
        ed.apply();
        if (supported() && (j.has("bands") || j.has("preset"))) {
            Equalizer e = eq;
            try {
                if (e == null) e = new Equalizer(PRIORITY, SESSION);
                short[] range = e.getBandLevelRange();
                JSONArray arr = j.optJSONArray("bands");
                if (arr != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < e.getNumberOfBands(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(clamp(arr.optInt(i, 0), range[0], range[1]));
                    }
                    prefs.edit().putString("audio_bands", sb.toString()).apply();
                } else {
                    int p = j.optInt("preset", -1);
                    if (p >= 0 && p < e.getNumberOfPresets()) {
                        e.usePreset((short) p);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < e.getNumberOfBands(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append(e.getBandLevel((short) i));
                        }
                        prefs.edit().putString("audio_bands", sb.toString()).apply();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (e != null && e != eq) e.release();
            }
        }
        refresh();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
