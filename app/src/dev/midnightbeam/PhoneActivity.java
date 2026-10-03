package dev.midnightbeam;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import static dev.midnightbeam.MainActivity.BG;
import static dev.midnightbeam.MainActivity.LIGHT;
import static dev.midnightbeam.MainActivity.t;

/**
 * Phone/tablet screen: the app page (assets/app.html) in a WebView, served by the service on loopback while this
 * screen is open. The page controls this device and the saved TVs (forwarded by AppApi). Pairing links from the TV's
 * QR code open this activity and save the TV. No inner classes, see MainActivity.
 */
public class PhoneActivity extends Activity implements View.OnClickListener, Runnable {
    static final String UA_SUFFIX = " MidnightBeamApp/1.6";
    private static final int SCAN = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private AppWebClient client;
    private TextView error;
    private String select = ""; // device to show after a pairing link, "" = the page's own choice
    private boolean needsLoad = true;
    private boolean failed;
    private int retries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout body = new FrameLayout(this);
        body.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + UA_SUFFIX);
        client = new AppWebClient(this);
        client.origin = "http://127.0.0.1:" + RemoteServer.PORT;
        web.setWebViewClient(client);
        body.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        error = new TextView(this);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        error.setTextColor(LIGHT);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(BG);
        int pad = Math.round(32 * getResources().getDisplayMetrics().density);
        error.setPadding(pad, pad, pad, pad);
        error.setOnClickListener(this);
        error.setVisibility(View.GONE);
        body.addView(error, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(body);

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
        // the page is served by the service: keep its loopback server on while this screen is open
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", true));
        if (needsLoad) startLoad();
        web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        main.removeCallbacks(this);
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", false));
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // the page handles back itself (sub-screens, sheets) through its history entries
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Loads the page; the server just asked to start gets a moment to bind its port. */
    private void startLoad() {
        needsLoad = false;
        retries = 0;
        main.removeCallbacks(this);
        main.postDelayed(this, 400);
    }

    /** Runnable: (re)loads the page. */
    @Override
    public void run() {
        failed = false;
        error.setVisibility(View.GONE);
        String hash = "#k=" + DimService.pairingKey(prefs) + (select.isEmpty() ? "" : "&dev=" + Uri.encode(select));
        select = "";
        web.loadUrl(client.origin + "/app.html?r=" + System.currentTimeMillis() + hash);
    }

    /** Called by AppWebClient when the page could not be loaded: retry a few times, then say so. */
    void loadFailed() {
        failed = true;
        if (retries < 5) {
            retries++;
            main.postDelayed(this, 600);
        } else {
            error.setText(t("The app could not start its page.\n\nTap to retry.",
                    "L'app non riesce ad avviare la sua pagina.\n\nTocca per riprovare."));
            error.setVisibility(View.VISIBLE);
        }
    }

    void pageDone() {
        if (!failed) retries = 0;
    }

    /** Links of the page that ask the app for something: midnightbeam-app://overlay, accessibility, scan. */
    void appAction(String what) {
        try {
            if ("scan".equals(what)) {
                scan();
            } else if ("overlay".equals(what)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } else if ("accessibility".equals(what)) {
                startActivity(Compat.accessibilitySettings());
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Scans the TV's QR code: with a barcode scanner app that answers the common ZXing SCAN intent (e.g. Binary Eye)
     * the result comes back here; otherwise the camera app opens, and its QR detection opens the pairing link,
     * which brings the TV to this activity (see the intent filters).
     */
    private void scan() {
        Intent scan = new Intent("com.google.zxing.client.android.SCAN").putExtra("SCAN_MODE", "QR_CODE_MODE");
        if (scan.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(scan, SCAN);
            return;
        }
        try {
            startActivity(new Intent("android.media.action.STILL_IMAGE_CAMERA"));
        } catch (RuntimeException e) {
            web.evaluateJavascript("window.mbNotice && window.mbNotice('noCamera')", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra("SCAN_RESULT");
        handleIntent(new Intent(Intent.ACTION_VIEW, text == null ? null : Uri.parse(text.trim())));
        if (select.isEmpty()) web.evaluateJavascript("window.mbNotice && window.mbNotice('badLink')", null);
    }

    /** Pairing link (midnightbeam://add?.. or the TV's http://IP:8765/#k=..): save the TV and show it. */
    private void handleIntent(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        if (u == null) return;
        String id = Devices.addLink(prefs, u);
        setIntent(new Intent());
        if (id == null) return;
        if (Devices.byId(prefs, id).optString("name").equals(id.substring(0, id.lastIndexOf(':')))) {
            int c = id.lastIndexOf(':');
            new Thread(new InfoFetch(this, main, id.substring(0, c), Integer.parseInt(id.substring(c + 1)),
                    Devices.byId(prefs, id).optString("key"))).start();
        }
        select = id;
        needsLoad = true;
    }

    /** The device answered /api/info: use its name if the saved one is still just the address. */
    void deviceNamed(String host, int port, String name) {
        org.json.JSONObject d = Devices.byId(prefs, host + ":" + port);
        if (d != null && d.optString("name").equals(host)) {
            Devices.rename(prefs, host, port, Devices.cleanName(name));
            web.evaluateJavascript("window.mbDevicesChanged && window.mbDevicesChanged()", null);
        }
    }

    /** Tap on the error message: retry. */
    @Override
    public void onClick(View v) {
        startLoad();
    }
}
