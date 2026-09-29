package dev.overlaydimmer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Horizontal day timeline of the schedule for the TV screen: one rounded block per time slot, as wide as the
 * slot lasts, coloured with a preview of a grey picture under that slot's filter, plus a red "now" line.
 * The first slot of the day is drawn first; the last one wraps over midnight. Same layout idea as the phone page.
 */
public class TimelineView extends View implements Comparator<JSONObject> {
    private static final int DAY_MIN = 1440;
    private static final int PREVIEW_GREY = 205;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint now = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private List<JSONObject> slots = new ArrayList<>();
    private boolean enabled;

    public TimelineView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        text.setTextSize(13 * density);
        now.setColor(Color.rgb(217, 119, 97));
        now.setStrokeWidth(3 * density);
    }

    /** Sets the schedule to draw (the JSON array stored by DimService). */
    void setSchedule(String json, boolean on) {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) list.add(a.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        Collections.sort(list, this);
        slots = list;
        enabled = on;
        invalidate();
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    @Override
    public int compare(JSONObject a, JSONObject b) {
        return minutes(a) - minutes(b);
    }

    private static int minutes(JSONObject s) {
        String t = s.optString("time", "00:00");
        return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = slots.size();
        if (n == 0) return;
        float w = getWidth(), h = getHeight(), gap = 4 * density, radius = 14 * density;
        int first = minutes(slots.get(0));
        float x = 0;
        for (int i = 0; i < n; i++) {
            JSONObject s = slots.get(i);
            int start = minutes(s);
            int end = i + 1 < n ? minutes(slots.get(i + 1)) : first + DAY_MIN;
            float width = (end - start) / (float) DAY_MIN * w;
            rect.set(x + gap / 2, 0, x + width - gap / 2, h);
            fill.setColor(preview(s));
            if (!enabled) fill.setAlpha(110);
            canvas.drawRoundRect(rect, radius, radius, fill);

            boolean dark = !s.optBoolean("on", true) ? false : luminance(fill.getColor()) < 110;
            text.setColor(dark ? Color.WHITE : Color.rgb(22, 62, 56));
            float tx = rect.left + 10 * density;
            float room = rect.width() - 16 * density;
            if (room > 30 * density) {
                text.setFakeBoldText(true);
                drawClipped(canvas, s.optString("time"), tx, 20 * density, room);
                text.setFakeBoldText(false);
                drawClipped(canvas, s.optString("label"), tx, 38 * density, room);
                drawClipped(canvas, modelText(s), tx, 56 * density, room);
            }
            x += width;
        }
        Calendar c = Calendar.getInstance();
        int current = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        float nx = (((current - first) % DAY_MIN + DAY_MIN) % DAY_MIN) / (float) DAY_MIN * w;
        canvas.drawLine(nx, 0, nx, h, now);
    }

    private void drawClipped(Canvas canvas, String s, float x, float y, float room) {
        if (s == null || s.isEmpty()) return;
        int count = text.breakText(s, true, room, null);
        canvas.drawText(count < s.length() ? s.substring(0, Math.max(0, count - 1)) + "…" : s, x, y, text);
    }

    private static String modelText(JSONObject s) {
        if (!s.optBoolean("on", true)) return "☀ no filter";
        return s.optInt("red") + "% · " + s.optInt("bright", 100) + "% · " + s.optInt("temp") + "K";
    }

    /** A mid-grey picture under the slot's filter, same blending as the real overlay. */
    private static int preview(JSONObject s) {
        if (!s.optBoolean("on", true)) return Color.rgb(PREVIEW_GREY, PREVIEW_GREY, PREVIEW_GREY);
        int o = DimService.overlayColor(s.optInt("red"), s.optInt("bright", 100), s.optInt("temp", DimService.DEFAULT_TEMP));
        float a = Color.alpha(o) / 255f;
        return Color.rgb(Math.round(PREVIEW_GREY * (1 - a) + Color.red(o) * a),
                Math.round(PREVIEW_GREY * (1 - a) + Color.green(o) * a),
                Math.round(PREVIEW_GREY * (1 - a) + Color.blue(o) * a));
    }

    private static int luminance(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }
}
