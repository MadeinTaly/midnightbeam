package dev.midnightbeam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;

/**
 * Update check at start: asks GitHub for the newest release (pre-releases included), and when it is newer than this
 * copy offers to install it through PackageInstaller (Android still shows its own confirmation). Skipped on F-Droid
 * installs (different signer, F-Droid updates them). Prefs: "upd" (check, default on), "updAuto" (install without our
 * question, set by "Don't ask again" + Update; "Don't ask again" + Not now turns "upd" off), "updBeta" (channel: beta
 * also takes pre-releases; default beta when this copy is itself a pre-release, like 0.3.0-beta.1). Back to stable from a
 * beta: Android cannot downgrade in place, so the copy stays on its beta until a newer stable is out, then takes it.
 */
final class Updater {
    static final String REPO = "MadeinTaly/midnightbeam";
    /** Release asset name prefix: only "<APK>*.apk" is taken, never another APK attached to the release. */
    static final String APK = "midnightbeam";
    private static final String TAG = "Updater";
    private static final long EVERY_MS = 6 * 3600 * 1000L;
    private static long lastCheck;
    private static volatile boolean busy;

    private Updater() {
    }

    private static Boolean available;

    /** False on F-Droid installs and in builds packaged without REQUEST_INSTALL_PACKAGES (F-Droid recipe). */
    static boolean available(Context c) {
        if (available == null) {
            boolean ok = false;
            if (!Compat.fromFdroid(c)) {
                try {
                    String[] p = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
                    ok = p != null && Arrays.asList(p).contains("android.permission.REQUEST_INSTALL_PACKAGES");
                } catch (Exception e) {
                }
            }
            available = ok;
        }
        return available;
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("upd", true);
    }

    /** Settings switch: turning it back on also brings the question back. */
    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("upd", on).putBoolean("updAuto", false).apply();
        lastCheck = 0;
    }

    /** Beta channel: on by default when the installed version has a suffix (0.3.0-beta.1, 2.0.0-rc.1). */
    static boolean beta(Context c) {
        return prefs(c).getBoolean("updBeta", versionName(c).contains("-"));
    }

    /** Settings switch for the channel; the next start screen checks again at once. */
    static void setBeta(Context c, boolean on) {
        prefs(c).edit().putBoolean("updBeta", on).apply();
        lastCheck = 0;
    }

    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Call from onResume of the start screen: at most one check every 6 hours per process. */
    static void check(Activity a) {
        long now = System.currentTimeMillis();
        Dbg.on(a);
        if (busy || now - lastCheck < EVERY_MS || !enabled(a) || !available(a)) return;
        busy = true;
        new Thread(new Check(a), "update-check").start();
    }

    private static final class Check implements Runnable {
        private final Activity a;

        Check(Activity a) {
            this.a = a;
        }

        @Override
        public void run() {
            try {
                String mine = versionName(a);
                boolean beta = beta(a);
                String skip = prefs(a).getBoolean("updAuto", false) ? prefs(a).getString("updSkip", "") : "";
                JSONArray rels = new JSONArray(get("https://api.github.com/repos/" + REPO + "/releases?per_page=20"));
                lastCheck = System.currentTimeMillis(); // only after an answer: a TV that starts before Wi-Fi retries
                String best = mine, bestUrl = null;
                // newest by version, not by date: a backport published later must not hide a newer release
                for (int i = 0; i < rels.length(); i++) {
                    JSONObject r = rels.getJSONObject(i);
                    if (r.optBoolean("draft") || (r.optBoolean("prerelease") && !beta)) continue;
                    String tag = r.getString("tag_name").replaceFirst("^v", "");
                    if (compare(tag, best) <= 0 || tag.equals(skip)) continue;
                    JSONArray assets = r.getJSONArray("assets");
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject as = assets.getJSONObject(j);
                        String name = as.getString("name"), url = as.getString("browser_download_url");
                        if (name.startsWith(APK) && name.endsWith(".apk")
                                && url.startsWith("https://github.com/" + REPO + "/releases/download/")) {
                            best = tag;
                            bestUrl = url;
                            break;
                        }
                    }
                }
                Dbg.log("updater", "check done: mine " + mine + ", beta " + beta + ", newer "
                        + (bestUrl != null ? best : "none"));
                if (bestUrl != null) a.runOnUiThread(new Offer(a, best, bestUrl));
            } catch (Exception e) {
                Log.w(TAG, "check failed: " + e);
                Dbg.log("updater", "check failed: " + e);
            } finally {
                busy = false;
            }
        }
    }

    private static final class Offer implements Runnable, DialogInterface.OnClickListener {
        private final Activity a;
        private final String version, url;
        private CheckBox again;

        Offer(Activity a, String version, String url) {
            this.a = a;
            this.version = version;
            this.url = url;
        }

        @Override
        public void run() {
            if (a.isFinishing() || a.isDestroyed()) return;
            Dbg.log("updater", "offer " + version);
            if (prefs(a).getBoolean("updAuto", false)) {
                install(a, url, version);
                return;
            }
            again = new CheckBox(a);
            again.setText(t("Don't ask again", "Non chiedere più"));
            int p = (int) (20 * a.getResources().getDisplayMetrics().density);
            android.widget.FrameLayout box = new android.widget.FrameLayout(a);
            box.setPadding(p, p / 2, p, 0);
            box.addView(again);
            AlertDialog d = new AlertDialog.Builder(a)
                    .setTitle(t("Update available", "Aggiornamento disponibile"))
                    .setMessage(t("Version " + version + " is out. Install it now?",
                            "È uscita la versione " + version + ". Installarla ora?"))
                    .setView(box)
                    .setPositiveButton(t("Update", "Aggiorna"), this)
                    .setNegativeButton(t("Not now", "Non ora"), this)
                    .show();
            d.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus(); // remote: OK = Update, up = the checkbox
        }

        @Override
        public void onClick(DialogInterface d, int which) {
            boolean stop = again != null && again.isChecked();
            if (which == DialogInterface.BUTTON_POSITIVE) {
                if (stop) prefs(a).edit().putBoolean("updAuto", true).apply();
                install(a, url, version);
            } else if (stop) {
                prefs(a).edit().putBoolean("upd", false).apply();
                Toast.makeText(a, t("Update check off: turn it back on in the settings",
                        "Controllo aggiornamenti disattivato: riattivalo dalle impostazioni"), Toast.LENGTH_LONG).show();
            }
        }
    }

    static void install(Context c, String url, String version) {
        Dbg.log("updater", "install " + version);
        Toast.makeText(c, t("Downloading the update…", "Scarico l'aggiornamento…"), Toast.LENGTH_SHORT).show();
        new Thread(new Install(c.getApplicationContext(), url, version), "update-install").start();
    }

    private static final class Install implements Runnable {
        private final Context c;
        private final String url, version;

        Install(Context c, String url, String version) {
            this.c = c;
            this.url = url;
            this.version = version;
        }

        @Override
        public void run() {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(c.getPackageName());
                id = pi.createSession(sp);
                PackageInstaller.Session s = pi.openSession(id);
                HttpURLConnection h = open(url);
                try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("base.apk", 0, h.getContentLengthLong())) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    s.fsync(out);
                } finally {
                    h.disconnect();
                }
                // FLAG_MUTABLE (API 31): the installer adds the status extras to this intent
                PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Done.class).putExtra("v", version),
                        PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000);
                s.commit(done.getIntentSender());
                s.close();
            } catch (Exception e) {
                Log.w(TAG, "install failed: " + e);
                Dbg.log("updater", "install failed: " + e);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Failed(c));
                if (id >= 0) {
                    try {
                        pi.abandonSession(id);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private static final class Failed implements Runnable {
        private final Context c;

        Failed(Context c) {
            this.c = c;
        }

        @Override
        public void run() {
            Toast.makeText(c, t("Update not installed", "Aggiornamento non installato"), Toast.LENGTH_LONG).show();
        }
    }

    /** Installer status: shows Android's confirmation, or a failure toast. Declared in the manifest. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Dbg.log("updater", "install status " + status);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                // declined in Android's dialog: without our question it would download again every 6 hours
                if (prefs(c).getBoolean("updAuto", false)) prefs(c).edit().putString("updSkip", i.getStringExtra("v")).apply();
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "status " + status + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                Toast.makeText(c, t("Update not installed", "Aggiornamento non installato"), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Semver-ish: 1.9.0 < 1.10.0, 0.3.0-beta.1 < 0.3.0-beta.2 < 0.3.0; a leading "v" is ignored. */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("-", 2), y = b.replaceFirst("^v", "").split("-", 2);
        int c = parts(x[0], y[0]);
        if (c != 0) return c;
        if (x.length == 1 || y.length == 1) return y.length - x.length; // a release beats its pre-releases
        return parts(x[1], y[1]);
    }

    private static int parts(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) return Integer.signum(c);
        }
        return 0;
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", REPO);
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode() + " " + url);
        return h;
    }

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
    }

    static String t(String en, String it) {
        return "it".equals(Locale.getDefault().getLanguage()) ? it : en;
    }
}
