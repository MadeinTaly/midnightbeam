package dev.midnightbeam;

import android.content.Context;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Privileged shell for the optional advanced filter. Two sources, detected without prompting:
 *  - a su binary the app can execute (Magisk / KernelSU): the app runs `su -c`, the system asks the owner once;
 *  - the device's own ADB on 127.0.0.1, used only when it needs no authorisation (ro.adb.secure=0).
 * Commands are serialised on one background thread and only the newest is kept, so dragging a slider does not
 * queue up a burst of shell calls.
 */
final class Priv {
    static final String SU = "su";
    static final String ADB = "adb";
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
            "/vendor/bin/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su", "/debug_ramdisk/su"};

    private static final Object LOCK = new Object();
    private static String pending;
    private static Thread worker;
    /** False after a command failed all its tries: DimService falls back to the overlay and retries later. */
    static volatile boolean lastOk = true;
    /** Bumped after every finished command, so callers can tell when the answer to theirs arrived. */
    static volatile int results;
    /** True once a command succeeded in this process: the shell works, so the overlay can go before each answer. */
    static volatile boolean verified;

    private Priv() {
    }

    /** Cheap, passive check: never executes su and never opens a connection. */
    static boolean available(Context c) {
        return method(c) != null;
    }

    /** su when the app may run it; otherwise ADB only if it listens and asks for no authorisation (AdbClient fails then). */
    static String method(Context c) {
        // dev builds only: pretend ADB is there, to exercise the UI and the fallback on emulators
        if (Dbg.on(c) && c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean("devPriv", false)) return ADB;
        if (suPath() != null) return SU;
        if (adbPort(c) > 0 && !adbAuthRequired()) return ADB;
        return null;
    }

    /** Queues a command; a command still waiting is replaced by this one. */
    static void exec(Context c, String command) {
        if (command == null || command.isEmpty()) return;
        synchronized (LOCK) {
            pending = command;
            if (worker == null) {
                worker = new Thread(new Worker(c.getApplicationContext()), "priv");
                worker.setDaemon(true);
                worker.start();
            }
            LOCK.notifyAll();
        }
    }

    private static boolean attempt(Context c, String command) {
        for (int i = 0; i < 3; i++) {
            try {
                String out = runOnce(c, command);
                if (!failed(command, out)) return true;
                Dbg.log("priv", "denied: " + oneLine(out));
            } catch (Exception e) {
                Dbg.log("priv", "failed: " + e);
            }
            synchronized (LOCK) {
                if (pending != null) return false; // a newer command arrived: run that one instead
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String runOnce(Context c, String command) throws IOException {
        String su = suPath();
        if (su != null) {
            String out = runProcess(su, command);
            if (!failed(command, out) || adbPort(c) <= 0 || adbAuthRequired()) return out;
        }
        int port = adbPort(c);
        if (port > 0 && !adbAuthRequired()) return AdbClient.shell(port, command);
        throw new IOException("no privileged access");
    }

    /** Runs `su -c command` and returns stdout+stderr. Blocks while the system asks the owner to allow it. */
    private static String runProcess(String su, String command) throws IOException {
        Process p = new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start();
        // an unanswered root prompt must not hold the queue forever
        Thread watchdog = new Thread(new Kill(p), "priv-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            InputStream in = p.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            watchdog.interrupt();
            p.destroy();
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    static String suPath() {
        for (String path : SU_PATHS) {
            File f = new File(path);
            if (f.isFile() && f.canExecute()) return path;
        }
        return null;
    }

    /** Network ADB port of this device, or 0 when it is not exposed. */
    static int adbPort(Context c) {
        int port = number(prop("service.adb.tcp.port"));
        if (port <= 0) port = number(prop("persist.adb.tcp.port"));
        if (port > 0) return port;
        try {
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1) return 5555;
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    static boolean adbAuthRequired() {
        String secure = prop("ro.adb.secure");
        return !"0".equals(secure);
    }

    /** `service call` answers "Result: Parcel(NULL)" on success: anything else counts as a failure. */
    static boolean failed(String command, String out) {
        if (out == null) return true;
        if (command.startsWith("service call ")) return !out.contains("Parcel(NULL)");
        String s = out.toLowerCase(Locale.ROOT);
        return s.contains("permission denied") || s.contains("operation not permitted")
                || s.contains("securityexception") || s.contains("exception")
                || s.contains("unknown command") || s.contains("unable to");
    }

    private static String oneLine(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String prop(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Destroys a su process still running after 60 s (the owner never answered the prompt). */
    private static final class Kill implements Runnable {
        private final Process p;

        Kill(Process p) {
            this.p = p;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(60000);
                p.destroy();
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static final class Worker implements Runnable {
        private final Context c;

        Worker(Context c) {
            this.c = c;
        }

        @Override
        public void run() {
            while (true) {
                String command;
                synchronized (LOCK) {
                    while (pending == null) {
                        try {
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    command = pending;
                    pending = null;
                }
                lastOk = attempt(c, command);
                if (lastOk) verified = true;
                results++;
                Dbg.log("priv", (lastOk ? "ok: " : "failed: ") + command);
            }
        }
    }
}
