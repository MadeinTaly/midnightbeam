package dev.redmoonbeam;
public class WindowTest {
    static int f = 0;
    static void eq(String what, double got, double want) { if (Math.abs(got - want) > 1e-9) { f++; System.out.println("FAIL " + what + " got " + got + " want " + want); } }
    public static void main(String[] a) {
        int[] d = {420, 1170, 1380};
        double[] w = ScheduleMath.window(d, 1440, 1360); // in b (19:30), next c at 23:00
        eq("active", w[0], 1); eq("next", w[1], 2); eq("toNext", w[2], 20); eq("dur", w[3], 210);
        w = ScheduleMath.window(d, 1440, 1430); // in c, next a tomorrow 07:00
        eq("wrap active", w[0], 2); eq("wrap next", w[1], 0); eq("wrap toNext", w[2], 430); eq("wrap dur", w[3], 480);
        w = ScheduleMath.window(new int[] {600}, 1440, 100);
        eq("single toNext", w[2], 500); eq("single dur", w[3], 1440);
        eq("ramp outside", ScheduleMath.rampFraction(40, 210, 30), 0);
        eq("ramp mid", ScheduleMath.rampFraction(15, 210, 30), 0.5);
        eq("ramp capped half", ScheduleMath.rampFraction(10, 40, 30), 0.5);
        eq("ramp off", ScheduleMath.rampFraction(5, 210, 0), 0);
        int[] wk = {420, 1200, 2*1440+480}; // mon 07:00, mon 20:00, wed 08:00
        w = ScheduleMath.window(wk, 10080, 1440 + 720); // tue 12:00
        eq("week active", w[0], 1); eq("week next", w[1], 2); eq("week toNext", w[2], 1440 + 480 - 720);
        System.out.println(f == 0 ? "window: all ok" : f + " failed");
        if (f > 0) System.exit(1);
    }
}
