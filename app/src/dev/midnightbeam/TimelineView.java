package dev.midnightbeam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;
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
 * Daily: the first slot of the day is drawn first and the last one wraps over midnight (same layout idea as the
 * phone page). Weekly: today from midnight, the slot carried over from the previous days first.
 *
 * Remote editing: when focused, left/right pick the start edge of a slot, OK grabs it, left/right then move it by
 * {@value #STEP} minutes (it stays at least {@value #GAP} minutes away from its neighbours), OK, up or down drop it
 * and save, Back puts it back. While an edge is grabbed the axis does not move under it.
 */
public class TimelineView extends View implements Comparator<JSONObject> {
    private static final int DAY_MIN = 1440;
    private static final int PREVIEW_GREY = 205;
    static final int STEP = 10;
    static final int GAP = 10;

    private final MainActivity activity;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint now = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private List<JSONObject> slots = new ArrayList<>();
    private boolean enabled;
    private boolean weekly;
    private int sel = -1;       // slot whose start edge is selected
    private boolean grabbed;
    private int grabFrom;       // start of the grabbed slot when it was picked up
    private int frozenOrigin;   // axis start while an edge is grabbed

    public TimelineView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        Context context = activity;
        density = context.getResources().getDisplayMetrics().density;
        text.setTextSize(13 * density);
        text.setTypeface(Fonts.regular(context));
        now.setColor(MainActivity.ACCENT);
        now.setStrokeWidth(3 * density);
        edge.setStrokeWidth(4 * density);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2 * density);
        frame.setColor(MainActivity.FOCUS);
    }

    /** Sets the schedule to draw (today's slots as stored by DimService); ignored while an edge is grabbed. */
    void setSchedule(String json, boolean on, boolean weekly) {
        if (grabbed) return;
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) list.add(a.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        Collections.sort(list, this);
        String selTime = sel >= 0 && sel < slots.size() ? slots.get(sel).optString("time") : null;
        slots = list;
        enabled = on;
        this.weekly = weekly;
        sel = -1; // the same slot as before (by its start), after re-sorting
        for (int i = 0; selTime != null && i < slots.size(); i++) {
            if (editable(i) && selTime.equals(slots.get(i).optString("time"))) sel = i;
        }
        if (sel < 0) sel = nextEditable(-1, 1);
        setFocusable(sel >= 0);
        invalidate();
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    boolean isGrabbed() {
        return grabbed;
    }

    @Override
    public int compare(JSONObject a, JSONObject b) {
        return minutes(a) - minutes(b);
    }

    private static int minutes(JSONObject s) {
        String t = s.optString("time", "00:00");
        return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5));
    }

    private static int mod(int m) {
        return ((m % DAY_MIN) + DAY_MIN) % DAY_MIN;
    }

    static String hhmm(int m) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    /** The slot carried over from the previous days (weekly) has no edge of its own today. */
    private boolean editable(int i) {
        return !slots.get(i).optBoolean("carry", false);
    }

    private int nextEditable(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < slots.size(); i += dir) if (editable(i)) return i;
        return -1;
    }

    /** Minutes of slot i: until the next slot (daily: cyclic; weekly: today only, the last one until midnight). */
    private int duration(int i) {
        int n = slots.size(), start = minutes(slots.get(i));
        if (weekly) return (i + 1 < n ? minutes(slots.get(i + 1)) : DAY_MIN) - start;
        if (n == 1) return DAY_MIN;
        int d = mod(minutes(slots.get((i + 1) % n)) - start);
        return d == 0 ? DAY_MIN : d;
    }

    private int origin() {
        if (weekly) return 0;
        return grabbed ? frozenOrigin : minutes(slots.get(0));
    }

    private int offset(int minute) {
        return weekly ? minute : mod(minute - origin());
    }

    // ---- remote keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (sel < 0) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (grabbed) {
                    move(dir);
                } else {
                    int next = nextEditable(sel, dir);
                    if (next >= 0) sel = next;
                }
                changed();
                return true; // at the ends too: focus stays on the timeline
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (grabbed) {
                    drop(true);
                } else {
                    grabbed = true;
                    grabFrom = minutes(slots.get(sel));
                    frozenOrigin = weekly ? 0 : minutes(slots.get(0));
                }
                changed();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (grabbed) {
                    drop(true);
                    changed();
                }
                return super.onKeyDown(keyCode, event); // focus moves on
            case KeyEvent.KEYCODE_BACK:
                if (grabbed) {
                    drop(false);
                    changed();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus && grabbed) drop(true);
        changed();
    }

    private void changed() {
        invalidate();
        activity.refreshTimelineTitle();
    }

    /** Moves the grabbed edge to the next multiple of STEP minutes in that direction, within its neighbours. */
    private void move(int dir) {
        int[] starts = new int[slots.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = minutes(slots.get(i));
        int target = ScheduleMath.moveEdge(starts, sel, dir, STEP, GAP, !weekly);
        try {
            slots.get(sel).put("time", hhmm(target));
        } catch (JSONException ignored) {
        }
    }

    /** Releases the grabbed edge: saves the new time, or puts the old one back. */
    private void drop(boolean save) {
        grabbed = false;
        int cur = minutes(slots.get(sel));
        if (cur == grabFrom) return;
        if (save) {
            activity.moveScheduleEdge(hhmm(grabFrom), hhmm(cur));
        } else {
            try {
                slots.get(sel).put("time", hhmm(grabFrom));
            } catch (JSONException ignored) {
            }
        }
    }

    /** What the remote does here, for the title above the timeline. */
    String hint() {
        if (!hasFocus() || sel < 0) return "";
        return grabbed
                ? MainActivity.t("◀ ▶ move by 10 min · OK to save · Back to undo",
                        "◀ ▶ sposta di 10 min · OK per salvare · Indietro per annullare")
                : MainActivity.t("◀ ▶ pick an edge · OK to move it",
                        "◀ ▶ scegli un bordo · OK per spostarlo");
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        int n = slots.size();
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < n; i++) {
            JSONObject s = slots.get(i);
            int off = offset(minutes(s)), dur = duration(i);
            int first = Math.min(dur, DAY_MIN - off);
            block(canvas, s, off * w / DAY_MIN, (off + first) * w / DAY_MIN, h, true);
            if (dur > first) block(canvas, s, 0, (dur - first) * w / DAY_MIN, h, false); // wraps over the axis start
        }
        Calendar c = Calendar.getInstance();
        int current = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        float nx = offset(current) / (float) DAY_MIN * w;
        canvas.drawLine(nx, 0, nx, h, now);
        if (hasFocus()) {
            float in = frame.getStrokeWidth() / 2;
            canvas.drawRoundRect(in, in, w - in, h - in, 14 * density, 14 * density, frame);
            if (sel >= 0) drawEdge(canvas, w, h);
        }
    }

    private void block(Canvas canvas, JSONObject s, float left, float right, float h, boolean labels) {
        float gap = 4 * density, radius = 14 * density;
        rect.set(left + gap / 2, 0, right - gap / 2, h);
        if (rect.width() <= 0) return;
        fill.setColor(preview(s));
        if (!enabled) fill.setAlpha(110);
        canvas.drawRoundRect(rect, radius, radius, fill);
        if (!labels) return;
        boolean dark = s.optBoolean("on", true) && luminance(fill.getColor()) < 110;
        text.setColor(dark ? Color.WHITE : MainActivity.BG);
        float tx = rect.left + 10 * density;
        float room = rect.width() - 16 * density;
        if (room > 30 * density) {
            text.setFakeBoldText(true);
            drawClipped(canvas, s.optString("time"), tx, 20 * density, room);
            text.setFakeBoldText(false);
            drawClipped(canvas, s.optString("label"), tx, 38 * density, room);
            drawClipped(canvas, modelText(s), tx, 56 * density, room);
        }
    }

    /** The selected edge: a bar with its time on a tag (arrows while grabbed). */
    private void drawEdge(Canvas canvas, float w, float h) {
        int start = minutes(slots.get(sel));
        float x = offset(start) / (float) DAY_MIN * w;
        x = Math.max(edge.getStrokeWidth() / 2, x);
        int colour = grabbed ? MainActivity.PRIMARY : MainActivity.LIGHT;
        edge.setColor(colour);
        canvas.drawLine(x, 0, x, h, edge);
        String label = grabbed ? "◀ " + hhmm(start) + " ▶" : hhmm(start);
        text.setFakeBoldText(true);
        float tw = text.measureText(label), pad = 8 * density, th = 22 * density;
        float left = Math.max(0, Math.min(w - tw - 2 * pad, x - tw / 2 - pad));
        float top = h / 2 - th / 2;
        rect.set(left, top, left + tw + 2 * pad, top + th);
        fill.setColor(colour);
        canvas.drawRoundRect(rect, th / 2, th / 2, fill);
        text.setColor(grabbed ? Color.WHITE : MainActivity.BG);
        canvas.drawText(label, left + pad, top + th / 2 + 5 * density, text);
        text.setFakeBoldText(false);
    }

    private void drawClipped(Canvas canvas, String s, float x, float y, float room) {
        if (s == null || s.isEmpty()) return;
        int count = text.breakText(s, true, room, null);
        canvas.drawText(count < s.length() ? s.substring(0, Math.max(0, count - 1)) + "…" : s, x, y, text);
    }

    private static String modelText(JSONObject s) {
        if (!s.optBoolean("on", true)) return "☀ " + MainActivity.t("no filter", "nessun filtro");
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
