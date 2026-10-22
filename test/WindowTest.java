package dev.midnightbeam;
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
        // remote edge moves: 10 min steps, 10 min from the neighbours
        int[] day = {420, 1260, 1380}; // 07:00, 21:00, 23:00 (daily, cyclic)
        eq("edge right", ScheduleMath.moveEdge(day, 1, 1, 10, 10, true), 1270);
        eq("edge snaps", ScheduleMath.moveEdge(new int[] {425, 1260}, 0, 1, 10, 10, true), 430);
        eq("edge stops at next", ScheduleMath.moveEdge(new int[] {420, 1370, 1380}, 1, 1, 10, 10, true), 1370);
        eq("edge over midnight", ScheduleMath.moveEdge(day, 2, 1, 10, 10, true), 1390);
        eq("edge past 00:00", ScheduleMath.moveEdge(new int[] {0, 1260}, 0, -1, 10, 10, true), 1430);
        eq("first edge stops at last", ScheduleMath.moveEdge(new int[] {1390, 1380}, 0, -1, 10, 10, true), 1390);
        eq("single slot wraps", ScheduleMath.moveEdge(new int[] {0}, 0, -1, 10, 10, true), 1430);
        eq("week day floor", ScheduleMath.moveEdge(new int[] {0, 20}, 1, -1, 10, 10, false), 10);
        eq("week day ceiling", ScheduleMath.moveEdge(new int[] {600, 1435}, 1, 1, 10, 10, false), 1439);
        eq("week first at 00:00", ScheduleMath.moveEdge(new int[] {0, 600}, 0, -1, 10, 10, false), 0);
        eq("tight week stays", ScheduleMath.moveEdge(new int[] {600, 605, 900}, 1, -1, 10, 10, false), 605);
        eq("tight week not back", ScheduleMath.moveEdge(new int[] {600, 905, 910}, 1, 1, 10, 10, false), 905);
        eq("tight day stays", ScheduleMath.moveEdge(new int[] {600, 605, 900}, 1, -1, 10, 10, true), 605);
        eq("tight day not back", ScheduleMath.moveEdge(new int[] {600, 905, 910}, 1, 1, 10, 10, true), 905);
        System.out.println(f == 0 ? "window: all ok" : f + " failed");
        if (f > 0) System.exit(1);
    }
}
