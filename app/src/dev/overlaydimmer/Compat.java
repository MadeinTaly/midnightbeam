package dev.overlaydimmer;

import android.content.Context;
import android.content.Intent;

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
}
