package dev.midnightbeam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/**
 * Players announce their audio session (ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION, the standard equalizer
 * hand-off): effects attached to that session keep working even when Android pauses the global ones because the
 * app enabled an effect of its own. Registered at run time by DimService (a manifest receiver would not get it).
 */
final class SessionReceiver extends BroadcastReceiver {
    private final DimService service;

    SessionReceiver(DimService service) {
        this.service = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        if (session <= 0) return;
        boolean open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction());
        android.util.Log.i("MidnightBeamAudio", (open ? "open " : "close ") + session + " "
                + intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME));
        service.audioSession(session, open);
    }
}
