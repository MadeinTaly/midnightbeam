package dev.midnightbeam;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/** API 26 calls reached by reflection, because the build uses the API 23 android.jar. */
final class Compat {
    private Compat() {
    }

    static void startForegroundService(Context context, Intent intent) {
        try {
            Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Touches pass through overlays with window alpha up to this value (Android 12+). */
    static float maxObscuringOpacity(Context context) {
        try {
            Object im = context.getSystemService("input");
            return ((Float) im.getClass().getMethod("getMaximumObscuringOpacityForTouch").invoke(im)).floatValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0.8f;
        }
    }

    static Intent accessibilitySettings() {
        return new Intent("android.settings.ACCESSIBILITY_SETTINGS");
    }

    static boolean needsAccessibilityHint() {
        return Build.VERSION.SDK_INT >= 31 && DimAccessibilityService.instance == null;
    }

    /**
     * Rough kind of this device for the phone app's icons: "projector", "tv", "tablet" or "phone". A TV box counts as
     * a projector when its build names or an installed package say so (projector firmwares ship a projector service).
     */
    static String deviceType(Context context) {
        if (isTv(context)) {
            String id = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL + " " + Build.PRODUCT + " " + Build.DEVICE)
                    .toLowerCase(Locale.ROOT);
            if (id.contains("proj")) return "projector";
            try {
                for (ApplicationInfo a : context.getPackageManager().getInstalledApplications(0)) {
                    if (a.packageName.toLowerCase(Locale.ROOT).contains("projector")) return "projector";
                }
            } catch (RuntimeException ignored) {
            }
            return "tv";
        }
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? "tablet" : "phone";
    }

    static boolean isTv(Context context) {
        UiModeManager ui = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || context.getPackageManager().hasSystemFeature("android.software.leanback");
    }
}
