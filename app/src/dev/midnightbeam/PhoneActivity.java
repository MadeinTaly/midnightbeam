package dev.midnightbeam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.midnightbeam.MainActivity.ACCENT;
import static dev.midnightbeam.MainActivity.BG;
import static dev.midnightbeam.MainActivity.LIGHT;
import static dev.midnightbeam.MainActivity.PRIMARY;
import static dev.midnightbeam.MainActivity.SURFACE;
import static dev.midnightbeam.MainActivity.t;

/**
 * Phone/tablet screen: the remote page in a WebView. "This device" is the app's own server on loopback
 * (only while this screen is open); saved devices are TVs reached over the network with their pairing key.
 * A top bar opens the device list (switch, rename, delete). No inner classes, see MainActivity.
 */
public class PhoneActivity extends Activity implements View.OnClickListener, DialogInterface.OnClickListener, Runnable {
    static final String UA_SUFFIX = " MidnightBeamApp/1.4";
    private static final int RENAME = 1, DELETE = 2, TOGGLE = 3, RETRY = 4, PERMISSION = 5, ACCESSIBILITY = 6;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{1,128}");

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private AppWebClient client;
    private TextView title;
    private TextView permission;
    private TextView accessibility;
    private LinearLayout panel;
    private TextView error;
    private String current = ""; // "host:port" of the selected saved device, "" = this device
    private boolean localOn;
    private boolean resumed;
    private boolean needsLoad = true;
    private boolean failed;
    private int retries;
    private String dialogHost;
    private int dialogPort;
    private int dialogKind;
    private EditText dialogInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);
        current = prefs.getString("phone_cur", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(SURFACE);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setTag(new int[] {TOGGLE, 0});
        bar.setOnClickListener(this);
        title = label("", 18, LIGHT);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bar.addView(label("▾", 20, ACCENT));
        root.addView(bar);

        permission = label(t("Allow \"Display over other apps\" so the filter can be drawn: tap here",
                "Consenti \"Mostra sopra altre app\" per disegnare il filtro: tocca qui"), 14, BG);
        permission.setBackgroundColor(PRIMARY);
        permission.setPadding(dp(16), dp(10), dp(16), dp(10));
        permission.setTag(new int[] {PERMISSION, 0});
        permission.setOnClickListener(this);
        permission.setVisibility(View.GONE);
        root.addView(permission);

        accessibility = label(t("For full darkness and to cover the system bars, enable MidnightBeam in Accessibility",
                "Per un oscuramento totale e per coprire le barre di sistema, attiva MidnightBeam in Accessibilità")
                + " \u203a", 14, BG);
        accessibility.setBackgroundColor(ACCENT);
        accessibility.setPadding(dp(16), dp(10), dp(16), dp(10));
        accessibility.setTag(new int[] {ACCESSIBILITY, 0});
        accessibility.setOnClickListener(this);
        accessibility.setVisibility(View.GONE);
        root.addView(accessibility);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG);
        panel.setVisibility(View.GONE);
        root.addView(panel);

        FrameLayout body = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + UA_SUFFIX);
        client = new AppWebClient(this);
        web.setWebViewClient(client);
        body.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        error = label("", 16, LIGHT);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(BG);
        error.setPadding(dp(32), dp(32), dp(32), dp(32));
        error.setTag(new int[] {RETRY, 0});
        error.setOnClickListener(this);
        error.setVisibility(View.GONE);
        body.addView(error, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent); // onResume follows and loads the page
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        permission.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        accessibility.setVisibility(Compat.needsAccessibilityHint() ? View.VISIBLE : View.GONE);
        setLocal(current.isEmpty());
        if (needsLoad || current.isEmpty()) startLoad();
        web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        web.onPause();
        main.removeCallbacks(this);
        setLocal(false);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Loopback server of the service: on only while this device is the one shown and the screen is open. */
    private void setLocal(boolean on) {
        if (on == localOn) return;
        localOn = on;
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", on));
    }

    /** Loads the page; right after the server was asked to start it gets a moment to bind its port. */
    private void startLoad() {
        needsLoad = false;
        retries = 0;
        main.removeCallbacks(this);
        main.postDelayed(this, localOn ? 400 : 0);
    }

    /** Runnable: (re)loads the selected device. */
    @Override
    public void run() {
        String host = "127.0.0.1";
        int port = RemoteServer.PORT;
        String key = DimService.pairingKey(prefs);
        String name = t("This device", "Questo dispositivo");
        if (!current.isEmpty()) {
            JSONArray a = Devices.load(prefs);
            int i = Devices.find(a, hostOf(current), portOf(current));
            if (i < 0) {
                current = "";
                prefs.edit().putString("phone_cur", "").apply();
                setLocal(resumed);
                run();
                return;
            }
            JSONObject d = a.optJSONObject(i);
            host = d.optString("host");
            port = d.optInt("port");
            key = d.optString("key");
            name = d.optString("name");
        }
        title.setText(name);
        client.origin = "http://" + host + ":" + port;
        failed = false;
        error.setVisibility(View.GONE);
        web.loadUrl(client.origin + "/?r=" + System.currentTimeMillis() + "#k=" + key);
    }

    /** Called by AppWebClient when the main page could not be loaded: retry a few times, then say so. */
    void loadFailed() {
        failed = true;
        if (retries < 4) {
            retries++;
            main.postDelayed(this, 700);
        } else {
            error.setText(t("Cannot reach this device. Check that it is on and on the same network.\n\nTap to retry.",
                    "Dispositivo non raggiungibile. Controlla che sia acceso e sulla stessa rete.\n\nTocca per riprovare."));
            error.setVisibility(View.VISIBLE);
        }
    }

    void pageDone() {
        if (!failed) retries = 0;
    }

    // ---- Adding a device from a link: midnightbeam://add?host=..&port=..&k=..&name=.. or http://IP:8765/#k=.. ----

    private void handleIntent(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        if (u == null) return;
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
                return;
            }
        } else {
            host = u.getHost();
            if (u.getPort() > 0) port = u.getPort();
            key = u.getQueryParameter("k");
            String f = u.getFragment();
            if (key == null && f != null) {
                Matcher m = Pattern.compile("(?:^|&)k=([0-9a-f]+)").matcher(f);
                if (m.find()) key = m.group(1);
            }
        }
        if (host == null || !HOST.matcher(host).matches() || key == null || !KEY.matcher(key).matches()
                || port < 1 || port > 65535) return;
        if (name != null) {
            name = name.replaceAll("\\p{Cntrl}", "").trim();
            if (name.length() > 40) name = name.substring(0, 40);
        }
        Devices.upsert(prefs, name, host, port, key);
        if (name == null || name.isEmpty()) new Thread(new InfoFetch(this, main, host, port, key)).start();
        select(host + ":" + port);
        setIntent(new Intent());
    }

    /** The device answered /api/info: use its name if the saved one is still just the address. */
    void deviceNamed(String host, int port, String name) {
        JSONArray a = Devices.load(prefs);
        int i = Devices.find(a, host, port);
        if (i >= 0 && a.optJSONObject(i).optString("name").equals(host)) {
            Devices.rename(prefs, host, port, name);
            if (current.equals(host + ":" + port)) title.setText(name);
            rebuildPanel();
        }
    }

    // ---- Device list ----

    private void select(String hostPort) {
        current = hostPort;
        prefs.edit().putString("phone_cur", hostPort).apply();
        panel.setVisibility(View.GONE);
        needsLoad = true;
        if (resumed) setLocal(current.isEmpty());
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        addRow(t("This device", "Questo dispositivo"), t("the filter of this phone", "il filtro di questo telefono"),
                "", null, 0);
        JSONArray a = Devices.load(prefs);
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d == null) continue;
            addRow(d.optString("name"), d.optString("host") + ":" + d.optInt("port"),
                    d.optString("host") + ":" + d.optInt("port"), d.optString("host"), d.optInt("port"));
        }
        if (a.length() == 0) {
            TextView hint = label(t("To add a TV, scan the QR code on its screen with the phone camera.",
                    "Per aggiungere un TV, inquadra il QR sul suo schermo con la fotocamera."), 13, LIGHT);
            hint.setPadding(dp(16), dp(8), dp(16), dp(12));
            panel.addView(hint);
        }
    }

    private void addRow(String name, String sub, String hostPort, String host, int port) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(4), 0);
        boolean on = hostPort.equals(current);
        TextView text = label(name + "\n" + sub, 15, on ? ACCENT : LIGHT);
        text.setMinHeight(dp(56));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTag(hostPort);
        text.setOnClickListener(this);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (host != null) {
            row.addView(action("✎", t("Rename", "Rinomina"), RENAME, host, port));
            row.addView(action("✕", t("Delete", "Elimina"), DELETE, host, port));
        }
        panel.addView(row);
    }

    private TextView action(String glyph, String description, int kind, String host, int port) {
        TextView v = label(glyph, 20, LIGHT);
        v.setGravity(Gravity.CENTER);
        v.setContentDescription(description);
        v.setTag(new Object[] {kind, host, port});
        v.setOnClickListener(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return v;
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof String) { // device row
            select((String) tag);
            startLoad();
            return;
        }
        if (tag instanceof Object[]) {
            Object[] a = (Object[]) tag;
            askDialog((Integer) a[0], (String) a[1], (Integer) a[2]);
            return;
        }
        int kind = ((int[]) tag)[0];
        if (kind == TOGGLE) {
            if (panel.getVisibility() == View.VISIBLE) {
                panel.setVisibility(View.GONE);
            } else {
                rebuildPanel();
                panel.setVisibility(View.VISIBLE);
            }
        } else if (kind == RETRY) {
            startLoad();
        } else if (kind == ACCESSIBILITY) {
            startActivity(Compat.accessibilitySettings());
        } else if (kind == PERMISSION) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
    }

    private void askDialog(int kind, String host, int port) {
        dialogKind = kind;
        dialogHost = host;
        dialogPort = port;
        JSONArray a = Devices.load(prefs);
        int i = Devices.find(a, host, port);
        String name = i >= 0 ? a.optJSONObject(i).optString("name") : host;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        if (kind == RENAME) {
            dialogInput = new EditText(this);
            dialogInput.setSingleLine(true);
            dialogInput.setText(name);
            dialogInput.selectAll();
            b.setTitle(t("Rename", "Rinomina")).setView(dialogInput);
        } else {
            b.setTitle(t("Delete \"", "Elimina \"") + name + "\"?");
        }
        b.setPositiveButton(kind == RENAME ? "OK" : t("Delete", "Elimina"), this)
                .setNegativeButton(t("Cancel", "Annulla"), null).show();
    }

    /** Dialog buttons (rename / delete confirmation). */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which != DialogInterface.BUTTON_POSITIVE) return;
        if (dialogKind == RENAME) {
            String n = dialogInput.getText().toString().replaceAll("\\p{Cntrl}", "").trim();
            Devices.rename(prefs, dialogHost, dialogPort, n.length() > 40 ? n.substring(0, 40) : n);
            if (current.equals(dialogHost + ":" + dialogPort)) startLoad();
        } else {
            Devices.delete(prefs, dialogHost, dialogPort);
            if (current.equals(dialogHost + ":" + dialogPort)) {
                select("");
                startLoad();
            }
        }
        rebuildPanel();
    }

    private static String hostOf(String hostPort) {
        return hostPort.substring(0, hostPort.lastIndexOf(':'));
    }

    private static int portOf(String hostPort) {
        return Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));
    }

    private TextView label(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
