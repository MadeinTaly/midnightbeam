package dev.overlaydimmer;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

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

    static boolean isTv(Context context) {
        UiModeManager ui = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || context.getPackageManager().hasSystemFeature("android.software.leanback");
    }
}
