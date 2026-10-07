package dev.midnightbeam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * On-screen display shown for a moment when a setting changes from the phone (e.g. "Volume 45%"), like the volume
 * bar of a TV. Drawn as an accessibility overlay when that mode is on (above the screensaver too), otherwise as an
 * app overlay. Never focusable or touchable. A plain class (see MainActivity on inner classes): it is its own
 * hide Runnable.
 */
final class Osd implements Runnable {
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final int TYPE_ACCESSIBILITY_OVERLAY = 2032;
    private static final long SHOW_MS = 2200;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout box;
    private TextView label;
    private TextView value;
    private View fill;
    private View rest;
    private LinearLayout bar;

    Osd(Context context) {
        this.context = context;
    }

    /** Shows (or updates) the display: a label, a value and, with percent 0-100, a bar; -1 = no bar. */
    void show(String labelText, String valueText, int percent) {
        if (box == null && !attach()) return;
        label.setText(labelText);
        value.setText(valueText);
        if (percent >= 0) {
            int p = Math.max(0, Math.min(100, percent));
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(p, 0.001f)));
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(100 - p, 0.001f)));
            bar.setVisibility(View.VISIBLE);
        } else {
            bar.setVisibility(View.GONE);
        }
        main.removeCallbacks(this);
        main.postDelayed(this, SHOW_MS);
    }

    /** Runnable: hides the display. */
    @Override
    public void run() {
        hide();
    }

    void hide() {
        main.removeCallbacks(this);
        if (box != null) {
            try {
                wm.removeView(box);
            } catch (RuntimeException ignored) {
            }
            box = null;
        }
    }

    private boolean attach() {
        DimAccessibilityService svc = DimAccessibilityService.instance;
        Context owner = svc != null ? svc : context;
        wm = (WindowManager) owner.getSystemService(Context.WINDOW_SERVICE);

        // one line: "Volume   45%", with a thin bar along the bottom edge
        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(0xE6, 0x16, 0x11, 0x10));
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setClipToOutline(true);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(8), dp(18), dp(7));
        label = new TextView(context);
        label.setTextColor(MainActivity.LIGHT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setSingleLine(true);
        label.setTypeface(Fonts.regular(context));
        row.addView(label);
        value = new TextView(context);
        value.setTextColor(Color.WHITE);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        value.setTypeface(Fonts.bold(context));
        value.setSingleLine(true);
        value.setPadding(dp(14), 0, 0, 0);
        row.addView(value);
        box.addView(row);

        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.rgb(0x3A, 0x2C, 0x27));
        fill = new View(context);
        fill.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {MainActivity.PRIMARY, Color.rgb(0xFF, 0xB1, 0x5C)}));
        rest = new View(context);
        bar.addView(fill);
        bar.addView(rest);
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                svc != null ? TYPE_ACCESSIBILITY_OVERLAY : TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.06f);
        try {
            wm.addView(box, lp);
            return true;
        } catch (RuntimeException e) { // no overlay permission: nothing to show
            box = null;
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
