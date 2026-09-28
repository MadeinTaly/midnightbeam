package dev.overlaydimmer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Restores the last setting after a reboot. BOOT_COMPLETED is delivered to apps one at a time and on some
 * TV boxes reaches the last ones a minute or more after boot, so the receiver has a high priority (manifest).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        boolean neutral = p.getInt("red", 0) == 0 && p.getInt("bright", 100) == 100;
        if (p.getBoolean("off", false) || neutral) return;
        Compat.startForegroundService(context, new Intent(context, DimService.class));
    }
}
