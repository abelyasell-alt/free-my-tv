package io.freemytv;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Declares a valid {@link RecognitionService} component for {@code voice_interaction_service.xml}
 * metadata requirements while leaving system speech recognition bound to Google's recognizer.
 */
public final class VoiceRecognitionBridgeService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        if (listener == null) return;
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {}
    }

    @Override
    protected void onCancel(Callback listener) {
        // Nothing active to cancel.
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (listener == null) return;
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {}
    }
}
