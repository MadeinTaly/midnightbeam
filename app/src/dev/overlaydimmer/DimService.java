package dev.overlaydimmer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Full-screen, click-through overlay that dims the screen and adds a warm (red) tint.
 * Values change live, without restarting anything:
 *
 *   am start-foreground-service -n dev.overlaydimmer/.DimService --ei red 60 --ei bright 40 --ei temp 1100
 *   am start-foreground-service -n dev.overlaydimmer/.DimService --ez off true
 *
 * red    0-100      strength of the warm filter
 * bright 5-100      100 = no dimming
 * temp   1000-6500  filter colour temperature in kelvin (default 1100, a deep red)
 * Current state: dumpsys activity service dev.overlaydimmer/.DimService
 */
public class DimService extends Service {
    static final String PREFS = "dim";
    private static final String CHANNEL = "dim";
    private static final int TYPE_APPLICATION_OVERLAY = 2038; // API 26 constant, not in the API 23 stubs
    private static final float MAX_FILTER_ALPHA = 0.65f; // opacity of the filter at red=100
    static final int DEFAULT_TEMP = 1100;

    private View view;
    private int red;
    private int bright = 100;
    private int temp = DEFAULT_TEMP;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        red = p.getInt("red", 0);
        bright = p.getInt("bright", 100);
        temp = p.getInt("temp", DEFAULT_TEMP);
        boolean off = p.getBoolean("off", false);
        if (intent != null) {
            red = clamp(intent.getIntExtra("red", red), 0, 100);
            bright = clamp(intent.getIntExtra("bright", bright), 5, 100);
            temp = clamp(intent.getIntExtra("temp", temp), 1000, 6500);
            boolean newValues = intent.hasExtra("red") || intent.hasExtra("bright") || intent.hasExtra("temp");
            off = intent.getBooleanExtra("off", newValues ? false : off);
            p.edit().putInt("red", red).putInt("bright", bright).putInt("temp", temp).putBoolean("off", off).apply();
        }
        if (off || (red == 0 && bright == 100)) {
            removeOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        showOverlay(overlayColor(red, bright, temp));
        return START_STICKY;
    }

    /**
     * Warm filter layer with a black (dimming) layer drawn on top of it, merged into one ARGB colour.
     * Dimming on top also darkens the filter itself, so low brightness does not leave an orange haze.
     */
    static int overlayColor(int red, int bright, int temp) {
        int tint = colorFromTemperature(temp);
        float tintA = red / 100f * MAX_FILTER_ALPHA;
        float dimA = (100 - bright) / 100f;
        float a = 1f - (1f - dimA) * (1f - tintA);
        if (a <= 0f) return Color.TRANSPARENT;
        float k = tintA * (1f - dimA) / a; // share of the filter colour in the merged colour, the rest is black
        return Color.argb(Math.round(a * 255),
                Math.round(Color.red(tint) * k),
                Math.round(Color.green(tint) * k),
                Math.round(Color.blue(tint) * k));
    }

    /**
     * Black body colour for a temperature in kelvin, after Tanner Helland's well known curve fit
     * (https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html).
     */
    static int colorFromTemperature(int kelvin) {
        double t = kelvin / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661
                : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return Color.rgb(channel(r), channel(g), channel(b));
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    private void showOverlay(int color) {
        if (view == null) {
            view = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            view.setBackgroundColor(color);
            getSystemService(WindowManager.class).addView(view, lp);
        } else {
            view.setBackgroundColor(color);
        }
    }

    private void removeOverlay() {
        if (view != null) {
            getSystemService(WindowManager.class).removeView(view);
            view = null;
        }
    }

    /** Foreground notification; NotificationChannel & co. are API 26, reached by reflection (API 23 stubs). */
    private Notification buildNotification() {
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "Overlay Dimmer", 1 /* IMPORTANCE_MIN */);
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationManager.class.getMethod("createNotificationChannel", channelClass).invoke(nm, channel);
            Notification.Builder b = Notification.Builder.class
                    .getConstructor(Context.class, String.class).newInstance(this, CHANNEL);
            return b.setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_menu_view))
                    .setContentTitle("Overlay Dimmer")
                    .setContentText("filter " + red + "%, brightness " + bright + "%, " + temp + " K")
                    .build();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("red=" + red + " bright=" + bright + " temp=" + temp + " visible=" + (view != null));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
