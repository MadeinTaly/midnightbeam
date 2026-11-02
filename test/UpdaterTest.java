package dev.midnightbeam;

public class UpdaterTest {
    public static void main(String[] args) {
        lt("1.9.0", "1.10.0");
        eq("v1.9.0", "1.9.0");
        lt("0.3.0-beta.1", "0.3.0-beta.2");
        lt("0.3.0-beta.2", "0.3.0");
        lt("0.3.0-beta.9", "0.3.0");
        lt("1.9.0", "1.9.1");
        lt("0.3.0-beta.9", "0.3.0-beta.10");
        System.out.println("UpdaterTest ok");
    }

    private static void lt(String a, String b) {
        if (Updater.compare(a, b) >= 0 || Updater.compare(b, a) <= 0) throw new AssertionError(a + " < " + b);
    }

    private static void eq(String a, String b) {
        if (Updater.compare(a, b) != 0 || Updater.compare(b, a) != 0) throw new AssertionError(a + " == " + b);
    }
}
