package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Spawns headless assistant sessions that immediately hand off to {@link VoiceActivity} so
 * full-screen halo glow animations and spoken answers render cleanly over any screen.
 */
public final class VoiceAssistantSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new BridgeSession(this);
    }

    private static final class BridgeSession extends VoiceInteractionSession {
        BridgeSession(Context context) {
            super(context);
        }

        @Override
        public void onCreate() {
            super.onCreate();
            setUiEnabled(false);
            setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT);
        }

        @Override
        public void onPrepareShow(Bundle args, int showFlags) {
            super.onPrepareShow(args, showFlags);
            setUiEnabled(false);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Intent intent = new Intent(getContext(), VoiceActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(VoiceActivity.EXTRA_FROM_ASSIST, true);
            try {
                startAssistantActivity(intent);
            } catch (Throwable t) {
                try {
                    getContext().startActivity(intent);
                } catch (Throwable ignored) {}
            }
            hide();
            finish();
        }
    }
}
