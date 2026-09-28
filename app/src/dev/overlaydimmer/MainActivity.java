package dev.overlaydimmer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Remote-friendly settings screen for cheap TV-box remotes: only D-pad and OK are needed
 * (up/down to move, left/right to change, OK to switch on/off). The focused row is highlighted
 * explicitly, because many TV-box themes show no visible focus. Changes apply live.
 */
public class MainActivity extends Activity {
    private Switch enabled;
    private Slider red;
    private Slider bright;
    private Slider temp;
    private boolean ready;

    private static final int FOCUS = Color.rgb(255, 140, 40);
    private static final int NORMAL = Color.rgb(220, 220, 220);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 20));
        int pad = dp(48);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = text("Overlay Dimmer", 28, Color.WHITE);
        root.addView(title);

        enabled = new Switch(this);
        enabled.setText("Enabled");
        enabled.setTextColor(Color.WHITE);
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        enabled.setPadding(0, dp(24), 0, dp(8));
        enabled.setChecked(!p.getBoolean("off", false));
        enabled.setFocusable(true);
        enabled.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                enabled.setTextColor(hasFocus ? FOCUS : Color.WHITE);
                enabled.setBackgroundColor(hasFocus ? Color.rgb(45, 35, 25) : Color.TRANSPARENT);
            }
        });
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                apply();
            }
        });
        root.addView(enabled);

        red = new Slider(root, "Warm filter", "%", 0, 100, 5, p.getInt("red", 0));
        bright = new Slider(root, "Brightness", "%", 5, 100, 5, p.getInt("bright", 100));
        temp = new Slider(root, "Colour temperature (lower = redder)", " K", 1000, 4000, 100, p.getInt("temp", DimService.DEFAULT_TEMP));

        setContentView(root);
        enabled.requestFocus();
        ready = true;
    }

    private void apply() {
        if (!ready) return;
        Intent i = new Intent(this, DimService.class);
        if (enabled.isChecked()) {
            i.putExtra("red", red.value()).putExtra("bright", bright.value()).putExtra("temp", temp.value());
        } else {
            i.putExtra("off", true);
        }
        Compat.startForegroundService(this, i);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Label + value + SeekBar; SeekBar has no setMin() before API 26, so values are offset/scaled. */
    private final class Slider implements SeekBar.OnSeekBarChangeListener, OnFocusChangeListener {
        private final String label;
        private final String unit;
        private final int min;
        private final int step;
        private final TextView caption;
        private final SeekBar bar;

        Slider(LinearLayout parent, String label, String unit, int min, int max, int step, int value) {
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.step = step;
            caption = text("", 18, NORMAL);
            caption.setPadding(0, dp(20), 0, dp(4));
            bar = new SeekBar(MainActivity.this);
            bar.setMax((max - min) / step);
            bar.setKeyProgressIncrement(1);
            bar.setProgress((Math.max(min, Math.min(max, value)) - min) / step);
            bar.setOnSeekBarChangeListener(this);
            bar.setFocusable(true);
            bar.setOnFocusChangeListener(this);
            parent.addView(caption);
            parent.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            caption.setGravity(Gravity.START);
            refresh();
        }

        int value() {
            return min + bar.getProgress() * step;
        }

        private void refresh() {
            caption.setText((bar.hasFocus() ? "\u25B6 " : "") + label + ": " + value() + unit);
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            caption.setTextColor(hasFocus ? FOCUS : NORMAL);
            bar.setBackgroundColor(hasFocus ? Color.rgb(45, 35, 25) : Color.TRANSPARENT);
            refresh();
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            refresh();
            if (fromUser && !enabled.isChecked()) {
                enabled.setChecked(true); // moving a slider turns the filter on; the listener applies
            } else if (fromUser) {
                apply();
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
