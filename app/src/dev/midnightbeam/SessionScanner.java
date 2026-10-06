package dev.midnightbeam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For players that do not announce their audio session (e.g. many video apps): every few seconds reads the playing
 * sessions from the audio service dump and attaches the effects to them, like equalizer apps do in their "legacy"
 * mode. Needs the DUMP permission, which only adb can grant (adb shell pm grant dev.midnightbeam
 * android.permission.DUMP); without it this thread does nothing. Runs only while audio effects are on.
 */
final class SessionScanner extends Thread {
    /** A row of the AudioFlinger tracks table: [type] name active client session ... */
    private static final Pattern TRACK = Pattern.compile("^\\s+(?:[A-Z]{1,2}\\s+)?\\d+\\s+(yes|no)\\s+\\d+\\s+(\\d+)\\s");
    private static final long PERIOD_MS = 4000;

    private final DimService service;
    private final AudioFx audio;
    private final Set<Integer> attached = new HashSet<>();
    private volatile boolean running = true;

    SessionScanner(DimService service, AudioFx audio) {
        super("midnightbeam-sessions");
        this.service = service;
        this.audio = audio;
        setDaemon(true);
    }

    static boolean allowed(DimService service) {
        return service.checkSelfPermission("android.permission.DUMP") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void finish() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        while (running) {
            if (allowed(service)) scan();
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
        for (Integer id : attached) audio.session(id, false);
        attached.clear();
    }

    private void scan() {
        Set<Integer> active = new HashSet<>(), present = new HashSet<>();
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "media.audio_flinger").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = TRACK.matcher(line);
                if (!m.find()) continue;
                int session = Integer.parseInt(m.group(2));
                if (session <= 0) continue;
                present.add(session);
                if (m.group(1).equals("yes")) active.add(session);
            }
        } catch (Exception e) {
            return;
        } finally {
            if (p != null) p.destroy();
        }
        for (Integer id : active) {
            if (attached.add(id)) audio.session(id, true);
        }
        for (Integer id : new HashSet<>(attached)) {
            if (!present.contains(id)) { // the player released it
                attached.remove(id);
                audio.session(id, false);
            }
        }
    }
}
