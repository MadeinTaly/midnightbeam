package dev.redmoonbeam;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the dayrhythm conformance vectors against ScheduleMath (plain JVM, no Android). The vectors are converted
 * to a line format by test/run.sh:
 *   D <starts csv> | <minute> | <expected index>            daily
 *   W <mon starts>;<tue>;..;<sun> | <day> <minute> | <day> <index> or "null"   weekly
 */
public class ScheduleMathTest {
    public static void main(String[] args) throws Exception {
        int ok = 0, fail = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.substring(2).split("\\|");
                String got;
                if (line.charAt(0) == 'D') {
                    got = String.valueOf(ScheduleMath.activeDaily(ints(p[0]), Double.parseDouble(p[1].trim())));
                } else {
                    String[] days = p[0].split(";", -1);
                    int[][] starts = new int[7][];
                    for (int d = 0; d < 7; d++) starts[d] = ints(d < days.length ? days[d] : "");
                    String[] at = p[1].trim().split(" ");
                    int[] a = ScheduleMath.activeWeekly(starts, Integer.parseInt(at[0]), Double.parseDouble(at[1]));
                    got = a == null ? "null" : a[0] + " " + a[1];
                }
                String want = p[2].trim();
                if (got.equals(want)) ok++;
                else {
                    fail++;
                    System.out.println("FAIL " + line + "  -> got " + got);
                }
            }
        }
        System.out.println(ok + " ok, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    private static int[] ints(String csv) {
        List<Integer> l = new ArrayList<>();
        for (String s : csv.trim().split(",")) if (!s.trim().isEmpty()) l.add(Integer.parseInt(s.trim()));
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
