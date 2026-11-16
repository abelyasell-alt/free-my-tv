package io.freemytv;

import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

/**
 * Registers Free My TV as an Android Digital Assistant so pressing the remote's voice/mic button
 * can summon our conversational halo voice assistant directly.
 */
public final class VoiceAssistantService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        // Skip screen scraping and screenshots for faster, private wake-up on the remote mic button.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST
                | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
