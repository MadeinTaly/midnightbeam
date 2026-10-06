package dev.midnightbeam;

import android.content.SharedPreferences;
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

    AudioFx(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Whether this device accepts a global equalizer; probed once. */
    static synchronized boolean supported() {
        if (supported == null) {
            try {
                new Equalizer(0, 0).release();
                supported = true;
            } catch (Throwable e) {
                supported = false;
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
            applySaved();
        } else {
            release();
        }
    }

    synchronized void release() {
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
                eq = new Equalizer(PRIORITY, 0);
            } catch (Throwable ignored) {
            }
        }
        if (bass == null) {
            try {
                bass = new BassBoost(PRIORITY, 0);
            } catch (Throwable ignored) {
            }
        }
        if (virt == null) {
            try {
                virt = new Virtualizer(PRIORITY, 0);
            } catch (Throwable ignored) {
            }
        }
        if (loud == null) {
            try {
                loud = new LoudnessEnhancer(0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applySaved() {
        try {
            if (eq != null) {
                int[] levels = bands(eq);
                for (int i = 0; i < levels.length; i++) eq.setBandLevel((short) i, (short) levels[i]);
                eq.setEnabled(true);
            }
            if (bass != null) {
                int s = prefs.getInt("audio_bass", 0);
                if (s > 0) bass.setStrength((short) s);
                bass.setEnabled(s > 0);
            }
            if (virt != null) {
                int s = prefs.getInt("audio_virt", 0);
                if (s > 0) virt.setStrength((short) s);
                virt.setEnabled(s > 0);
            }
            if (loud != null) {
                int g = prefs.getInt("audio_loud", 0);
                if (g > 0) loud.setTargetGain(g);
                loud.setEnabled(g > 0);
            }
        } catch (RuntimeException ignored) {
            // the driver refused a value: keep going with what it accepted
        }
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
     * Applies any subset of {"enabled","bands":[mB..],"preset":index,"bass","virt","loud","reset":true},
     * clamped to what the device allows, and stores it.
     */
    synchronized void apply(JSONObject j) {
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
                if (e == null) e = new Equalizer(PRIORITY, 0);
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
