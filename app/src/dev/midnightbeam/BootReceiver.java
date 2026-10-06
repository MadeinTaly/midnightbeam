package dev.midnightbeam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Restores the last setting, the phone remote, the schedule and the audio effects after a reboot. BOOT_COMPLETED is delivered
 * to apps one at a time and on some TV boxes reaches the last ones a minute or more after boot, so the
 * receiver has a high priority (manifest).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        boolean neutral = p.getInt("red", 0) == 0 && p.getInt("bright", 100) == 100;
        boolean filter = !p.getBoolean("off", false) && !neutral;
        if (!filter && !p.getBoolean("remote", Compat.isTv(context)) && !p.getBoolean("schedule_on", false)
                && !p.getBoolean("audio_on", false)) return;
        Compat.startForegroundService(context, new Intent(context, DimService.class));
    }
}
