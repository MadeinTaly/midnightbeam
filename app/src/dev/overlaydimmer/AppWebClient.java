package dev.overlaydimmer;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Keeps navigation inside the device page; any other link opens in the browser. A top-level class, see MainActivity. */
final class AppWebClient extends WebViewClient {
    private final PhoneActivity activity;
    String origin = "";

    AppWebClient(PhoneActivity activity) {
        this.activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url.equals(origin) || url.startsWith(origin + "/")) return false;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        if (failingUrl != null && failingUrl.startsWith(origin)) activity.loadFailed();
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        activity.pageDone();
    }
}
