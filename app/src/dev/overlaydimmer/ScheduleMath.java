package dev.overlaydimmer;

import java.util.Arrays;

/**
 * Which schedule slot is active, same normative algorithm as the dayrhythm library (docs/API.md, section 7.5)
 * and checked against its conformance vectors (test/ScheduleMathTest.java). Pure Java, no Android types.
 *
 * Daily: slots are active from their start (inclusive) to the next slot's start; the last one wraps over midnight.
 * Weekly (Monday = day 0): the last slot of a day carries on into the next days until another slot starts; an empty
 * day carries the previous slot all day; Sunday's last slot wraps into Monday. No slots at all: nothing active.
 */
final class ScheduleMath {
    static final int DAY = 1440;
    static final int WEEK = 7 * DAY;

    private ScheduleMath() {
    }

    /** Index of the active slot for minute {@code m} (0..1439) of the day, or -1 for an empty day. */
    static int activeDaily(int[] starts, double m) {
        if (starts.length == 0) return -1;
        m = ((m % DAY) + DAY) % DAY;
        int best = -1;
        int latest = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] > starts[latest]) latest = i;
            if (starts[i] <= m && (best < 0 || starts[i] > starts[best])) best = i;
        }
        return best >= 0 ? best : latest; // before the first slot: yesterday's last one
    }

    /**
     * Active slot of a week: {@code days[d]} holds the start minutes of day d (Monday = 0). Returns
     * {day, index} of the active slot, or null when the week has no slots.
     */
    static int[] activeWeekly(int[][] days, int day, double m) {
        double t = day * (double) DAY + m;
        int bestDay = -1, bestIndex = -1, lastDay = -1, lastIndex = -1;
        double best = -1, last = -1;
        for (int d = 0; d < 7; d++) {
            int[] starts = days[d] == null ? new int[0] : days[d];
            for (int i = 0; i < starts.length; i++) {
                double abs = d * (double) DAY + starts[i];
                if (abs > last) {
                    last = abs;
                    lastDay = d;
                    lastIndex = i;
                }
                if (abs <= t && abs > best) {
                    best = abs;
                    bestDay = d;
                    bestIndex = i;
                }
            }
        }
        if (lastDay < 0) return null;
        return bestDay >= 0 ? new int[] {bestDay, bestIndex} : new int[] {lastDay, lastIndex};
    }

    /**
     * Active and next slot on a cyclic timeline of {@code period} minutes (1440 for a day, 10080 for a week with
     * absolute starts from Monday 00:00). Returns {activeIndex, nextIndex, minutesToNext, activeDuration},
     * or null when there are no starts. With a single slot, next = active and both values equal the period.
     */
    static double[] window(int[] absStarts, int period, double t) {
        if (absStarts.length == 0) return null;
        t = ((t % period) + period) % period;
        int active = -1, latest = 0, next = -1, earliest = 0;
        for (int i = 0; i < absStarts.length; i++) {
            if (absStarts[i] > absStarts[latest]) latest = i;
            if (absStarts[i] < absStarts[earliest]) earliest = i;
            if (absStarts[i] <= t && (active < 0 || absStarts[i] > absStarts[active])) active = i;
            if (absStarts[i] > t && (next < 0 || absStarts[i] < absStarts[next])) next = i;
        }
        if (active < 0) active = latest;
        if (next < 0) next = earliest;
        double toNext = ((absStarts[next] - t) % period + period) % period;
        if (toNext == 0) toNext = period;
        double duration = ((absStarts[next] - absStarts[active]) % period + period) % period;
        if (duration == 0) duration = period;
        return new double[] {active, next, toNext, duration};
    }

    /**
     * Fraction (0..1) of the way from the active slot's values to the next slot's during the gradual change at
     * the end of the active slot: 0 outside the transition window. The window is {@code transition} minutes,
     * at most half of the slot.
     */
    static double rampFraction(double toNext, double duration, double transition) {
        double w = Math.min(transition, duration / 2);
        if (w <= 0 || toNext >= w) return 0;
        return 1 - toNext / w;
    }

    /** "HH:MM" to minutes, or -1 if malformed. */
    static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) return -1;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3));
        return h < 24 && m < 60 ? h * 60 + m : -1;
    }

    /** Weekday index with Monday = 0, from java.util.Calendar's DAY_OF_WEEK (Sunday = 1). */
    static int mondayIndex(int calendarDayOfWeek) {
        return (calendarDayOfWeek + 5) % 7;
    }

    static final String[] WEEKDAYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    static int weekdayIndex(String key) {
        return Arrays.asList(WEEKDAYS).indexOf(key);
    }
}
