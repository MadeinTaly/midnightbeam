package dev.overlaydimmer;

import android.os.Handler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Asks a newly added device for its name (GET /api/info) off the main thread, then reports back on it. */
final class InfoFetch implements Runnable {
    private final PhoneActivity activity;
    private final Handler main;
    private final String host;
    private final int port;
    private final String key;
    private String name;

    InfoFetch(PhoneActivity activity, Handler main, String host, int port, String key) {
        this.activity = activity;
        this.main = main;
        this.host = host;
        this.port = port;
        this.key = key;
    }

    @Override
    public void run() {
        if (name != null) { // second pass, on the main thread
            activity.deviceNamed(host, port, name);
            return;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/api/info").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("X-Key", key);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                name = new JSONObject(b.toString("UTF-8")).optString("name");
            }
        } catch (Exception e) {
            return;
        }
        if (!name.isEmpty()) main.post(this);
    }
}
