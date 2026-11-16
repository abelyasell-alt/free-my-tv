package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen translucent conversational voice assistant overlay.
 * Illuminates the TV screen edges with audio-reactive aurora light halos while listening
 * and answering naturally via TextToSpeech alongside focusable recommendation cards.
 */
public final class VoiceActivity extends Activity {
    static final String EXTRA_LISTEN = "io.freemytv.voice.LISTEN";
    static final String EXTRA_FROM_ASSIST = "io.freemytv.voice.ASSIST";

    private static final int REQ_MIC = 4041;
    private static final int FOCUS_CARDS = 0;
    private static final int FOCUS_ACTIONS = 1;

    private enum State {
        LISTENING,
        THINKING,
        RESPONDING,
        ERROR
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
    private final List<GeminiAi.Turn> history = new ArrayList<>();
    private final Set<String> suggestedPlainTitles = new HashSet<>();
    private final Map<String, Bitmap> cardBitmaps = new HashMap<>();

    private HaloOverlayView overlay;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    private volatile boolean alive;
    private volatile boolean captureActive;
    private volatile boolean forceStopCapture;
    private boolean ttsReady;
    private boolean ttsSpeaking;
    private boolean usedAudioFallback;
    private boolean sttReadySeen;
    private long listenStartMs;
    private int querySeq;

    private State state = State.LISTENING;
    private String liveTranscript = "";
    private String userAsked = "";
    private String aiSpoken = "";
    private String errorMessage = "";
    private int targetHomeTab = -1;
    private List<Recs.Pick> cards = Collections.emptyList();

    private int focusArea = FOCUS_ACTIONS;
    private int focusedCard = 0;
    private int focusedAction = 0;
    private float targetAudioEnergy = 0f;

    private final Runnable micReadyTimeout = () -> {
        if (!alive || state != State.LISTENING || sttReadySeen) return;
        stopRecognizerOnly();
        startPcmFallback();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alive = true;
        Ui.init(getApplicationContext());
        audioManager = getSystemService(AudioManager.class);
        overlay = new HaloOverlayView(this);
        setContentView(overlay);
        updateWindowBackdrop();
        initTts();
        handleLaunchIntent(getIntent(), true);
    }

    private void setState(State next) {
        state = next;
        updateWindowBackdrop();
    }

    private void updateWindowBackdrop() {
        Window w = getWindow();
        if (w == null) return;
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        boolean listening = state == State.LISTENING;
        int blurPx = Math.round((listening ? 24f : 52f) * density);
        float dimAmount = listening ? 0.44f : 0.78f;

        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = dimAmount;
        if (Build.VERSION.SDK_INT >= 31) {
            if (blurPx > 0) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            lp.setBlurBehindRadius(blurPx);
        }
        w.setAttributes(lp);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) {
            cleanupAudioAndSpeech();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        main.removeCallbacksAndMessages(null);
        cleanupAudioAndSpeech();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
        }
        aiExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
        recycleBitmaps();
        super.onDestroy();
    }

    private void handleLaunchIntent(Intent intent, boolean allowAutoListen) {
        if (!GeminiAi.hasKey(this)) {
            setState(State.ERROR);
            aiSpoken = "";
            errorMessage = "Falta configurar tu clave de Gemini IA en Ajustes.";
            focusArea = FOCUS_ACTIONS;
            focusedAction = 0;
            overlay.invalidate();
            return;
        }
        boolean listen = intent == null || intent.getBooleanExtra(EXTRA_LISTEN, true)
                || intent.getBooleanExtra(EXTRA_FROM_ASSIST, false)
                || Intent.ACTION_ASSIST.equals(intent.getAction())
                || Intent.ACTION_VOICE_COMMAND.equals(intent.getAction());
        if (listen && allowAutoListen) {
            beginListening();
        }
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (!alive || status != TextToSpeech.SUCCESS || tts == null) return;
            Locale[] spanishLocales = {
                new Locale("es", "CO"),
                new Locale("es", "419"),
                new Locale("es", "ES"),
                new Locale("es")
            };
            for (Locale loc : spanishLocales) {
                int res = tts.isLanguageAvailable(loc);
                if (res >= TextToSpeech.LANG_AVAILABLE) {
                    tts.setLanguage(loc);
                    break;
                }
            }
            tts.setSpeechRate(1.03f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    main.post(() -> ttsSpeaking = true);
                }

                @Override
                public void onDone(String utteranceId) {
                    main.post(() -> ttsSpeaking = false);
                }

                @Override
                public void onError(String utteranceId) {
                    main.post(() -> ttsSpeaking = false);
                }
            });
            ttsReady = true;
            if (state == State.RESPONDING && !aiSpoken.isEmpty()) {
                speak(aiSpoken);
            }
        });
    }

    private void speak(String text) {
        String clean = GeminiAi.cleanForSpeech(text);
        if (clean.isEmpty() || tts == null || !ttsReady) return;
        requestDuckFocus();
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fmv_voice_" + System.nanoTime());
    }

    private void stopSpeaking() {
        ttsSpeaking = false;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {}
        }
    }

    private void requestDuckFocus() {
        if (audioManager == null) return;
        if (focusRequest == null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
        }
        try {
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {}
    }

    private void abandonDuckFocus() {
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {}
        }
    }

    private void beginListening() {
        cleanupAudioAndSpeech();
        stopSpeaking();
        requestDuckFocus();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState(State.ERROR);
            errorMessage = "Necesito permiso de micrófono para escucharte. Acéptalo en pantalla.";
            overlay.invalidate();
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        setState(State.LISTENING);
        usedAudioFallback = false;
        sttReadySeen = false;
        liveTranscript = "";
        errorMessage = "";
        targetAudioEnergy = 0.14f;
        listenStartMs = android.os.SystemClock.uptimeMillis();
        overlay.invalidate();

        ComponentName sysRec = findSystemRecognizer(this);
        if (sysRec == null && !SpeechRecognizer.isRecognitionAvailable(this)) {
            startPcmFallback();
            return;
        }

        try {
            recognizer = sysRec != null
                    ? SpeechRecognizer.createSpeechRecognizer(this, sysRec)
                    : SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
            Intent recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            String langTag = Locale.getDefault().getLanguage().equals("es")
                    ? Locale.getDefault().toLanguageTag() : "es-419";
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag);
            recIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
            recIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            recognizer.startListening(recIntent);
            main.postDelayed(micReadyTimeout, 2500L);
        } catch (Throwable t) {
            AppLog.w("stt start: " + t.getMessage());
            stopRecognizerOnly();
            startPcmFallback();
        }
    }

    private static ComponentName findSystemRecognizer(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        String self = c.getPackageName();
        ComponentName preferred = null;
        ComponentName fallback = null;
        for (ResolveInfo ri : services) {
            if (ri.serviceInfo == null) continue;
            String pkg = ri.serviceInfo.packageName;
            if (self.equals(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.serviceInfo.name);
            if ("com.google.android.tts".equals(pkg)
                    || "com.google.android.googlequicksearchbox".equals(pkg)
                    || "com.google.android.katniss".equals(pkg)) {
                preferred = cn;
                break;
            }
            if (fallback == null) fallback = cn;
        }
        return preferred != null ? preferred : fallback;
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            sttReadySeen = true;
            main.removeCallbacks(micReadyTimeout);
            targetAudioEnergy = Math.max(targetAudioEnergy, 0.22f);
        }

        @Override
        public void onBeginningOfSpeech() {
            sttReadySeen = true;
            main.removeCallbacks(micReadyTimeout);
            targetAudioEnergy = Math.max(targetAudioEnergy, 0.38f);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            float norm = Math.max(0.08f, Math.min(1f, (rmsdB + 2f) / 10f));
            targetAudioEnergy = norm;
        }

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            targetAudioEnergy = 0.2f;
        }

        @Override
        public void onError(int error) {
            main.removeCallbacks(micReadyTimeout);
            if (!alive || state != State.LISTENING) return;
            long elapsed = android.os.SystemClock.uptimeMillis() - listenStartMs;
            String partial = liveTranscript.trim();
            stopRecognizerOnly();

            if (!partial.isEmpty()) {
                submitTextQuery(partial);
                return;
            }
            boolean quickFail = elapsed < 1100L;
            boolean recoverableWithAudio = !usedAudioFallback && (quickFail
                    || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_AUDIO
                    || error == SpeechRecognizer.ERROR_SERVER
                    || error == SpeechRecognizer.ERROR_NETWORK
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            if (recoverableWithAudio) {
                startPcmFallback();
                return;
            }
            if (!cards.isEmpty() || !aiSpoken.isEmpty()) {
                setState(State.RESPONDING);
                targetAudioEnergy = 0f;
                overlay.invalidate();
                return;
            }
            setState(State.ERROR);
            targetAudioEnergy = 0f;
            errorMessage = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "No alcancé a escucharte. Presiona Hablar o el botón del micrófono."
                    : "No pude usar el dictado ahora. Intenta hablar de nuevo o escribir.";
            focusArea = FOCUS_ACTIONS;
            focusedAction = 0;
            overlay.invalidate();
        }

        @Override
        public void onResults(Bundle results) {
            main.removeCallbacks(micReadyTimeout);
            stopRecognizerOnly();
            ArrayList<String> matches = results == null
                    ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : liveTranscript.trim();
            if (best.isEmpty()) {
                setState(State.ERROR);
                targetAudioEnergy = 0f;
                errorMessage = "No entendí lo que dijiste. ¿Lo intentamos otra vez?";
                focusArea = FOCUS_ACTIONS;
                focusedAction = 0;
                overlay.invalidate();
                return;
            }
            submitTextQuery(best);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> partial = partialResults == null
                    ? null : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (partial != null && !partial.isEmpty()) {
                String t = partial.get(0).trim();
                if (!t.isEmpty()) {
                    sttReadySeen = true;
                    liveTranscript = t;
                    overlay.invalidate();
                }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    };

    private void startPcmFallback() {
        usedAudioFallback = true;
        forceStopCapture = false;
        captureActive = true;
        setState(State.LISTENING);
        liveTranscript = "Escuchando por micrófono...";
        overlay.invalidate();

        final int sampleRate = 16000;
        new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, 4096);
            AudioRecord rec = openAudioRecord(sampleRate, bufSize);
            if (rec == null) {
                captureActive = false;
                main.post(() -> {
                    if (!alive || state != State.LISTENING) return;
                    setState(State.ERROR);
                    targetAudioEnergy = 0f;
                    errorMessage = "No pude abrir el micrófono del control.";
                    focusArea = FOCUS_ACTIONS;
                    focusedAction = 0;
                    overlay.invalidate();
                });
                return;
            }

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            short[] frame = new short[1024];
            byte[] bytes = new byte[frame.length * 2];
            long started = System.currentTimeMillis();
            long lastVoiceAt = started;
            boolean heardSpeech = false;

            try {
                rec.startRecording();
                while (alive && captureActive && !forceStopCapture) {
                    int n = rec.read(frame, 0, frame.length);
                    if (n <= 0) continue;
                    double sumSq = 0;
                    for (int i = 0; i < n; i++) {
                        short s = frame[i];
                        sumSq += s * (double) s;
                        bytes[i * 2] = (byte) (s & 0xFF);
                        bytes[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                    }
                    pcmOut.write(bytes, 0, n * 2);

                    double rms = Math.sqrt(sumSq / n) / 32768.0;
                    float visual = (float) Math.max(0.10, Math.min(1.0, (rms - 0.008) * 9.0));
                    targetAudioEnergy = visual;

                    long now = System.currentTimeMillis();
                    if (rms > 0.025) {
                        heardSpeech = true;
                        lastVoiceAt = now;
                    } else if (heardSpeech && now - lastVoiceAt > 1550L) {
                        break;
                    } else if (!heardSpeech && now - started > 5500L) {
                        break;
                    }
                    if (now - started > 11000L) break;
                }
            } catch (Throwable t) {
                AppLog.w("pcm record: " + t.getMessage());
            } finally {
                captureActive = false;
                try {
                    rec.stop();
                } catch (Exception ignored) {}
                rec.release();
            }

            final boolean spoke = heardSpeech || forceStopCapture;
            final byte[] rawPcm = pcmOut.toByteArray();
            main.post(() -> {
                if (!alive) return;
                targetAudioEnergy = 0f;
                if (!spoke || rawPcm.length < 3200) {
                    setState(State.ERROR);
                    errorMessage = "No escuché tu voz. Toca Hablar o presiona el botón del micrófono.";
                    focusArea = FOCUS_ACTIONS;
                    focusedAction = 0;
                    overlay.invalidate();
                    return;
                }
                submitAudioQuery(GeminiAi.encodeWav(rawPcm, sampleRate));
            });
        }, "fmv-voice-rec").start();
    }

    private AudioRecord openAudioRecord(int sampleRate, int bufSize) {
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord rec = new AudioRecord(
                        src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) return rec;
                rec.release();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void stopRecognizerOnly() {
        main.removeCallbacks(micReadyTimeout);
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void cleanupAudioAndSpeech() {
        captureActive = false;
        forceStopCapture = true;
        stopRecognizerOnly();
        abandonDuckFocus();
    }

    private void submitTextQuery(String prompt) {
        cleanupAudioAndSpeech();
        setState(State.THINKING);
        userAsked = prompt;
        liveTranscript = "";
        errorMessage = "";
        final int seq = ++querySeq;
        final List<GeminiAi.Turn> snapshot = new ArrayList<>(history);
        final Set<String> excluded = new HashSet<>(suggestedPlainTitles);
        overlay.invalidate();

        aiExecutor.execute(() -> {
            try {
                GeminiAi.AiReply reply = GeminiAi.converseText(this, prompt, snapshot, excluded);
                onAiReply(seq, prompt, reply);
            } catch (Exception e) {
                onAiError(seq, e);
            }
        });
    }

    private void submitAudioQuery(byte[] wavBytes) {
        cleanupAudioAndSpeech();
        setState(State.THINKING);
        userAsked = "Procesando tu voz...";
        liveTranscript = "";
        errorMessage = "";
        final int seq = ++querySeq;
        final List<GeminiAi.Turn> snapshot = new ArrayList<>(history);
        final Set<String> excluded = new HashSet<>(suggestedPlainTitles);
        overlay.invalidate();

        aiExecutor.execute(() -> {
            try {
                GeminiAi.AiReply reply = GeminiAi.converseAudio(this, wavBytes, snapshot, excluded);
                String heard = reply.transcript.isEmpty() ? "(Consulta por voz)" : reply.transcript;
                onAiReply(seq, heard, reply);
            } catch (Exception e) {
                onAiError(seq, e);
            }
        });
    }

    private void onAiReply(int seq, String heardUserText, GeminiAi.AiReply reply) {
        List<Recs.Pick> resolved = new ArrayList<>(reply.picks);
        Set<String> seen = new HashSet<>();
        for (Recs.Pick p : resolved) {
            String k = Recs.plain(p.title);
            if (!k.isEmpty()) seen.add(k);
        }
        int tab = GeminiAi.homeTabForAction(reply.action);
        if (tab >= 0) {
            List<Recs.Pick> homeCards = GeminiAi.homeRecommendations(this, reply.action);
            for (Recs.Pick p : homeCards) {
                String k = Recs.plain(p.title);
                if (!k.isEmpty() && !seen.add(k)) continue;
                resolved.add(p);
            }
        }
        if (resolved.size() > 6) resolved = resolved.subList(0, 6);
        final List<Recs.Pick> finalCards = resolved;
        final String spoken = reply.spoken.isEmpty() ? "Aquí tienes lo que encontré." : reply.spoken;

        main.post(() -> {
            if (!alive || seq != querySeq) return;
            history.add(new GeminiAi.Turn("user", heardUserText));
            history.add(new GeminiAi.Turn("model", spoken));
            for (Recs.Pick p : finalCards) {
                String k = Recs.plain(p.title);
                if (!k.isEmpty()) suggestedPlainTitles.add(k);
            }
            userAsked = heardUserText;
            aiSpoken = spoken;
            cards = finalCards;
            targetHomeTab = tab;
            focusedCard = 0;

            if (GeminiAi.ACTION_OPEN_APP.equals(reply.action) && !reply.appName.isEmpty()) {
                setState(State.RESPONDING);
                focusArea = FOCUS_ACTIONS;
                focusedAction = 0;
                overlay.invalidate();
                speak(spoken);
                main.postDelayed(() -> {
                    if (!alive) return;
                    if (GeminiAi.openMatchingApp(VoiceActivity.this, reply.appName)) {
                        finish();
                    }
                }, 650L);
                return;
            }

            setState(State.RESPONDING);
            focusArea = finalCards.isEmpty() ? FOCUS_ACTIONS : FOCUS_CARDS;
            focusedAction = 0;
            overlay.resetCardScroll();
            loadThumbnailsAsync(finalCards);
            overlay.invalidate();
            speak(spoken);
        });
    }

    private void onAiError(int seq, Exception e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        AppLog.w("voice ai: " + raw);
        main.post(() -> {
            if (!alive || seq != querySeq) return;
            setState(State.ERROR);
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("timeout") || lower.contains("timed out")) {
                errorMessage = "Gemini tardó un poco más de lo normal en responder. Probemos de nuevo con Hablar o Escribir.";
            } else if (lower.contains("api key") || lower.contains("401") || lower.contains("403")) {
                errorMessage = "La clave de Gemini necesita revisarse en Ajustes.";
            } else {
                String detail = raw.isEmpty() ? "" : " (" + raw + ")";
                errorMessage = "No pude comunicarme con Gemini en este momento" + detail + ".";
            }
            focusArea = FOCUS_ACTIONS;
            focusedAction = 0;
            overlay.invalidate();
            speak("Tuve un problema temporal al comunicarme con Gemini. Intentemos de nuevo.");
        });
    }

    private void loadThumbnailsAsync(List<Recs.Pick> list) {
        if (list == null || list.isEmpty()) return;
        float s = Math.min(
                getResources().getDisplayMetrics().widthPixels / 1920f,
                getResources().getDisplayMetrics().heightPixels / 1080f);
        if (s <= 0f) s = 1f;
        int w = Math.max(180, Math.round(280f * s));
        int h = Math.max(260, Math.round(408f * s));
        Recs recs = Recs.get(getApplicationContext());
        for (Recs.Pick p : list) {
            final String key = thumbKey(p);
            if (cardBitmaps.containsKey(key)) continue;
            thumbExecutor.execute(() -> {
                Bitmap b = recs.cardThumb(p, w, h);
                if (b == null) return;
                main.post(() -> {
                    if (!alive) {
                        b.recycle();
                        return;
                    }
                    Bitmap prev = cardBitmaps.put(key, b);
                    if (prev != null && prev != b && !prev.isRecycled()) prev.recycle();
                    overlay.invalidate();
                });
            });
        }
    }

    private static String thumbKey(Recs.Pick p) {
        return p.kind + ":" + p.id + ":" + p.title;
    }

    private void recycleBitmaps() {
        for (Bitmap b : cardBitmaps.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cardBitmaps.clear();
    }

    private void openHomeAtTab(int tabIndex) {
        stopSpeaking();
        abandonDuckFocus();
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(HomeActivity.EXTRA_TAB, tabIndex);
        startActivity(i);
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void openTextInputDialog() {
        stopSpeaking();
        cleanupAudioAndSpeech();
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Escribe qué te gustaría ver");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 4);
        EditText input = new EditText(this);
        input.setHint("Ej: ¿Qué serie me recomiendas hoy?");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        layout.addView(input);
        b.setView(layout);
        b.setPositiveButton("Preguntar", (d, w) -> {
            String q = input.getText() == null ? "" : input.getText().toString().trim();
            if (!q.isEmpty()) submitTextQuery(q);
        });
        b.setNegativeButton("Cancelar", null);
        AlertDialog dlg = b.create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            String q = input.getText() == null ? "" : input.getText().toString().trim();
            if (!q.isEmpty()) {
                dlg.dismiss();
                submitTextQuery(q);
                return true;
            }
            return false;
        });
        dlg.show();
        input.requestFocus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginListening();
            } else {
                setState(State.ERROR);
                errorMessage = "Sin permiso de micrófono solo puedo ayudarte escribiendo.";
                focusArea = FOCUS_ACTIONS;
                focusedAction = 1;
                overlay.invalidate();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST
                || code == KeyEvent.KEYCODE_SEARCH) {
            if (down && event.getRepeatCount() == 0) beginListening();
            return true;
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                if (state == State.LISTENING && !cards.isEmpty()) {
                    cleanupAudioAndSpeech();
                    setState(State.RESPONDING);
                    targetAudioEnergy = 0f;
                    overlay.invalidate();
                } else {
                    stopSpeaking();
                    cleanupAudioAndSpeech();
                    finish();
                    overridePendingTransition(0, android.R.anim.fade_out);
                }
            }
            return true;
        }

        if (!down) return super.dispatchKeyEvent(event);

        if (state == State.LISTENING) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (usedAudioFallback) {
                    forceStopCapture = true;
                } else if (recognizer != null) {
                    try {
                        recognizer.stopListening();
                    } catch (Exception ignored) {}
                }
                return true;
            }
            return true;
        }

        if (state == State.THINKING) return true;

        int actionCount = actionLabels().length;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusArea == FOCUS_CARDS && !cards.isEmpty() && focusedCard > 0) {
                    focusedCard--;
                    overlay.invalidate();
                } else if (focusArea == FOCUS_ACTIONS && focusedAction > 0) {
                    focusedAction--;
                    overlay.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusArea == FOCUS_CARDS && !cards.isEmpty() && focusedCard < cards.size() - 1) {
                    focusedCard++;
                    overlay.invalidate();
                } else if (focusArea == FOCUS_ACTIONS && focusedAction < actionCount - 1) {
                    focusedAction++;
                    overlay.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusArea == FOCUS_CARDS) {
                    focusArea = FOCUS_ACTIONS;
                    focusedAction = 0;
                    overlay.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusArea == FOCUS_ACTIONS && !cards.isEmpty()) {
                    focusArea = FOCUS_CARDS;
                    overlay.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activateFocusedControl();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private String[] actionLabels() {
        boolean showHomeBtn = !cards.isEmpty() || targetHomeTab >= 0;
        if (showHomeBtn) {
            String tabLabel = targetHomeTab == 2 ? "Ver Series en inicio"
                    : targetHomeTab == 1 ? "Ver Películas en inicio"
                    : "Ver en inicio";
            return new String[] {"Hablar", tabLabel, "Escribir"};
        }
        return new String[] {"Hablar", "Escribir"};
    }

    private void activateFocusedControl() {
        if (focusArea == FOCUS_CARDS && !cards.isEmpty() && focusedCard < cards.size()) {
            Recs.Pick p = cards.get(focusedCard);
            stopSpeaking();
            abandonDuckFocus();
            if (GeminiAi.openPick(this, p)) {
                finish();
            } else {
                errorMessage = "No pude abrir " + p.title + ".";
                setState(State.ERROR);
                focusArea = FOCUS_ACTIONS;
                focusedAction = 0;
                overlay.invalidate();
            }
            return;
        }
        String[] actions = actionLabels();
        int idx = Math.max(0, Math.min(actions.length - 1, focusedAction));
        String label = actions[idx];
        if ("Hablar".equals(label)) {
            beginListening();
        } else if ("Escribir".equals(label)) {
            openTextInputDialog();
        } else {
            openHomeAtTab(targetHomeTab >= 0 ? targetHomeTab : 0);
        }
    }

    /**
     * Custom GPU-friendly halo overlay rendering futuristic liquid-titanium perimeter halos,
     * reactive silver waveform bars, frosted obsidian glass cards, and crisp poster artwork.
     */
    private final class HaloOverlayView extends View implements Choreographer.FrameCallback {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Matrix shaderMatrix = new Matrix();
        private final Rect srcRect = new Rect();
        private final RectF rect = new RectF();
        private final RectF glowRect = new RectF();
        private final Path clipPath = new Path();

        private SweepGradient rimSweep;
        private LinearGradient bgScrim;
        private LinearGradient solidScrim;
        private int lastW, lastH;
        private float smoothEnergy = 0f;
        private float phase = 0f;
        private float scrollX = 0f;
        private long lastFrameNs = 0L;

        private final int[] HALO_COLORS = {
            0xFFF8FAFC, // pure silver-white specular
            0xFFCBD5E1, // brushed platinum
            0xFF64748B, // gunmetal slate
            0xFFE2E8F0, // cool chrome mist
            0xFF475569, // deep titanium shadow
            0xFFF8FAFC
        };

        HaloOverlayView(Context context) {
            super(context);
            rimPaint.setStyle(Paint.Style.STROKE);
        }

        void resetCardScroll() {
            scrollX = 0f;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            lastFrameNs = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(this);
            super.onDetachedFromWindow();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!alive || !isAttachedToWindow()) return;
            float dt = lastFrameNs == 0L ? 0.016f : Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1e9f);
            lastFrameNs = frameTimeNanos;

            float speed = state == State.LISTENING ? (1.25f + smoothEnergy * 1.4f)
                    : state == State.THINKING ? 2.2f
                    : ttsSpeaking ? 1.15f : 0.42f;
            phase = (phase + dt * speed) % (float) (Math.PI * 200);

            float goalEnergy = state == State.LISTENING ? targetAudioEnergy
                    : state == State.THINKING ? (0.40f + 0.12f * (float) Math.sin(phase * 3.1f))
                    : ttsSpeaking ? (0.32f + 0.14f * (float) Math.sin(phase * 4.5f))
                    : 0.12f;
            float lerpRate = goalEnergy > smoothEnergy ? 14f : 7f;
            smoothEnergy += (goalEnergy - smoothEnergy) * Math.min(1f, dt * lerpRate);

            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            ensureShaders(w, h);

            float s = Math.min(w / 1920f, h / 1080f);
            float haloStrength = state == State.LISTENING ? (0.58f + smoothEnergy * 0.42f)
                    : state == State.THINKING ? 0.64f
                    : state == State.ERROR ? 0.45f
                    : ttsSpeaking ? 0.54f : 0.30f;

            // 1. Frosted obsidian-graphite backdrop scrim (deeper during cards/errors so posters stand out)
            paint.setShader(state == State.LISTENING ? bgScrim : solidScrim);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // 2. Soft silver/titanium edge washes animated cleanly with voice energy
            drawEdgeHalos(canvas, w, h, s, haloStrength);

            // 3. Diffused monochrome ambient blooms framing the screen
            drawCornerHalos(canvas, w, h, s, haloStrength);

            // 4. Dual-layer liquid titanium perimeter light halo + hairline border
            drawEdgeRim(canvas, w, h, s, haloStrength);

            // 5. Foreground content by current state
            if (state == State.LISTENING) {
                drawListeningPill(canvas, w, h, s);
            } else if (state == State.THINKING) {
                drawThinkingPill(canvas, w, h, s);
            } else {
                drawResultsContent(canvas, w, h, s);
            }
        }

        private void ensureShaders(int w, int h) {
            if (w == lastW && h == lastH) return;
            lastW = w;
            lastH = h;
            bgScrim = new LinearGradient(
                    0, 0, 0, h,
                    new int[] {0xA807090D, 0x8F090C11, 0xCE06080B},
                    new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
            solidScrim = new LinearGradient(
                    0, 0, 0, h,
                    new int[] {0xE606080C, 0xDB080A0F, 0xF2040609},
                    new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
            rimSweep = new SweepGradient(w / 2f, h / 2f, HALO_COLORS, null);
        }

        private void drawEdgeHalos(Canvas canvas, int w, int h, float s, float strength) {
            boolean err = state == State.ERROR;
            float depthY = h * (0.14f + smoothEnergy * 0.09f);
            float depthX = w * (0.09f + smoothEnergy * 0.05f);
            float wave = 0.5f + 0.5f * (float) Math.sin(phase * 1.4f);

            int cTop = err ? lerpArgb(0xFFE11D48, 0xFF94A3B8, 0.45f) : lerpArgb(0xFFF1F5F9, 0xFF94A3B8, wave);
            int cBot = err ? lerpArgb(0xFFFB7185, 0xFF64748B, 0.45f) : lerpArgb(0xFFCBD5E1, 0xFF64748B, wave);
            int cSide = err ? 0xFF94A3B8 : lerpArgb(0xFFE2E8F0, 0xFF64748B, 1f - wave);

            int alphaTop = Math.round(78f * strength);
            int alphaBot = Math.round(115f * strength);
            int alphaSide = Math.round(68f * strength);

            paint.setShader(new LinearGradient(0, 0, 0, depthY,
                    withAlpha(cTop, alphaTop), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, depthY, paint);

            paint.setShader(new LinearGradient(0, h, 0, h - depthY * 1.15f,
                    withAlpha(cBot, alphaBot), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - depthY * 1.15f, w, h, paint);

            paint.setShader(new LinearGradient(0, 0, depthX, 0,
                    withAlpha(cSide, alphaSide), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, depthX, h, paint);

            paint.setShader(new LinearGradient(w, 0, w - depthX, 0,
                    withAlpha(cTop, alphaSide), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(w - depthX, 0, w, h, paint);
            paint.setShader(null);
        }

        private void drawCornerHalos(Canvas canvas, int w, int h, float s, float strength) {
            boolean err = state == State.ERROR;
            float baseR = Math.min(w, h) * (0.40f + smoothEnergy * 0.22f);
            float[][] anchors = {
                {w * (0.14f + 0.08f * (float) Math.sin(phase * 0.8f)), h * 0.04f},
                {w * (0.86f + 0.08f * (float) Math.cos(phase * 0.9f)), h * 0.06f},
                {w * (0.20f + 0.10f * (float) Math.cos(phase * 1.0f)), h * 0.98f},
                {w * (0.82f + 0.10f * (float) Math.sin(phase * 1.1f)), h * 0.96f}
            };
            int[] orbColors = err
                    ? new int[] {0xFFFB7185, 0xFF94A3B8, 0xFFFCA5A5, 0xFF64748B}
                    : new int[] {0xFFF8FAFC, 0xFF94A3B8, 0xFFE2E8F0, 0xFF64748B};
            int peakAlpha = Math.round(92f * strength);
            for (int i = 0; i < 4; i++) {
                float cx = anchors[i][0];
                float cy = anchors[i][1];
                int col = orbColors[i];
                paint.setShader(new RadialGradient(cx, cy, baseR,
                        new int[] {withAlpha(col, peakAlpha), withAlpha(col, peakAlpha / 3), 0x00000000},
                        new float[] {0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, baseR, paint);
            }
            paint.setShader(null);
        }

        private void drawEdgeRim(Canvas canvas, int w, int h, float s, float strength) {
            shaderMatrix.setRotate((phase * 38f) % 360f, w / 2f, h / 2f);
            rimSweep.setLocalMatrix(shaderMatrix);
            rimPaint.setShader(rimSweep);

            // Outer diffused light halo
            float outerW = (10f + smoothEnergy * 9f) * s;
            rimPaint.setStrokeWidth(outerW);
            rimPaint.setAlpha(Math.round(72f * Math.min(1f, strength + 0.10f)));
            float outerInset = outerW / 2f;
            rect.set(outerInset, outerInset, w - outerInset, h - outerInset);
            canvas.drawRoundRect(rect, 28f * s, 28f * s, rimPaint);

            // Crisp specular silver hairline
            float innerW = (2.4f + smoothEnergy * 2.6f) * s;
            rimPaint.setStrokeWidth(innerW);
            rimPaint.setAlpha(Math.round(218f * Math.min(1f, strength + 0.15f)));
            float innerInset = innerW / 2f;
            rect.set(innerInset, innerInset, w - innerInset, h - innerInset);
            canvas.drawRoundRect(rect, 28f * s, 28f * s, rimPaint);

            rimPaint.setShader(null);
            rimPaint.setAlpha(255);
        }

        private void drawListeningPill(Canvas canvas, int w, int h, float s) {
            float pillW = Math.min(w * 0.72f, 1180f * s);
            float pillH = 148f * s;
            float left = (w - pillW) / 2f;
            float top = h - pillH - 92f * s;
            rect.set(left, top, left + pillW, top + pillH);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEB0B0E14);
            canvas.drawRoundRect(rect, 38f * s, 38f * s, paint);

            shaderMatrix.setRotate((phase * 48f) % 360f, w / 2f, h / 2f);
            rimSweep.setLocalMatrix(shaderMatrix);
            rimPaint.setShader(rimSweep);
            rimPaint.setStrokeWidth(2.4f * s);
            rimPaint.setAlpha(210);
            canvas.drawRoundRect(rect, 38f * s, 38f * s, rimPaint);
            rimPaint.setShader(null);
            rimPaint.setAlpha(255);

            // Monochrome titanium waveform bars inside ambient silver ring
            float orbCx = left + 76f * s;
            float orbCy = top + pillH / 2f;
            drawVoiceBars(canvas, orbCx, orbCy, s);

            // Title / streaming live caption
            String caption = liveTranscript.isEmpty() ? "Te escucho… dime qué quieres ver" : "«" + liveTranscript + "»";
            float textX = left + 144f * s;
            float maxTextW = pillW - 180f * s;
            textPaint.setTypeface(liveTranscript.isEmpty() ? Ui.displayMedium : Ui.displaySemibold);
            textPaint.setTextSize(34f * s);
            textPaint.setColor(liveTranscript.isEmpty() ? 0xE6E2E8F0 : 0xFFF8FAFC);
            drawEllipsizedSingleLine(canvas, caption, textX, orbCy + Ui.halfCap(textPaint) - 6f * s, maxTextW, textPaint);

            textPaint.setTypeface(Ui.regular);
            textPaint.setTextSize(21f * s);
            textPaint.setColor(0x9994A3B8);
            canvas.drawText("Habla tranquilo · Presiona Atrás para cerrar", textX, orbCy + 42f * s, textPaint);
        }

        private void drawThinkingPill(Canvas canvas, int w, int h, float s) {
            float pillW = Math.min(w * 0.68f, 1100f * s);
            float pillH = 148f * s;
            float left = (w - pillW) / 2f;
            float top = h - pillH - 92f * s;
            rect.set(left, top, left + pillW, top + pillH);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEB0B0E14);
            canvas.drawRoundRect(rect, 38f * s, 38f * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * s);
            paint.setColor(0x66CBD5E1);
            canvas.drawRoundRect(rect, 38f * s, 38f * s, paint);
            paint.setStyle(Paint.Style.FILL);

            // Calm silver-white orbiting indicator
            float orbCx = left + 74f * s;
            float orbCy = top + pillH / 2f;
            paint.setColor(0x26CBD5E1);
            canvas.drawCircle(orbCx, orbCy, 30f * s, paint);
            float a = phase * 3.8f;
            paint.setColor(0xFFF8FAFC);
            canvas.drawCircle(orbCx + 16f * s * (float) Math.cos(a), orbCy + 16f * s * (float) Math.sin(a), 6.5f * s, paint);
            paint.setColor(0xFF94A3B8);
            canvas.drawCircle(orbCx - 16f * s * (float) Math.cos(a), orbCy - 16f * s * (float) Math.sin(a), 5.5f * s, paint);

            float textX = left + 136f * s;
            textPaint.setTypeface(Ui.displaySemibold);
            textPaint.setTextSize(32f * s);
            textPaint.setColor(0xFFF8FAFC);
            drawEllipsizedSingleLine(canvas, "Buscando tus recomendaciones…", textX, orbCy - 4f * s, pillW - 170f * s, textPaint);

            if (!userAsked.isEmpty()) {
                textPaint.setTypeface(Ui.regular);
                textPaint.setTextSize(23f * s);
                textPaint.setColor(0xB3CBD5E1);
                drawEllipsizedSingleLine(canvas, "«" + userAsked + "»", textX, orbCy + 34f * s, pillW - 170f * s, textPaint);
            }
        }

        private void drawResultsContent(Canvas canvas, int w, int h, float s) {
            float marginX = 96f * s;
            float contentW = w - marginX * 2f;
            float topY = 54f * s;

            // 1. Conversational response glass card
            String bodyText = state == State.ERROR ? errorMessage : aiSpoken;
            float cardPadX = 36f * s;
            float cardPadY = 22f * s;
            float maxSpeechW = contentW - cardPadX * 2f;

            textPaint.setTypeface(Ui.displayMedium);
            textPaint.setTextSize(29f * s);
            textPaint.setColor(0xFFF8FAFC);
            StaticLayout bodyLayout = StaticLayout.Builder.obtain(
                    bodyText, 0, bodyText.length(), textPaint, Math.round(maxSpeechW))
                    .setMaxLines(4)
                    .setLineSpacing(5f * s, 1.0f)
                    .build();

            float headerH = userAsked.isEmpty() ? 0f : 32f * s;
            float panelH = cardPadY * 2f + headerH + bodyLayout.getHeight();
            rect.set(marginX, topY, marginX + contentW, topY + panelH);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEB0C0F16);
            canvas.drawRoundRect(rect, 28f * s, 28f * s, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * s);
            paint.setColor(state == State.ERROR ? 0x73FB7185 : 0x4DCBD5E1);
            canvas.drawRoundRect(rect, 28f * s, 28f * s, paint);
            paint.setStyle(Paint.Style.FILL);

            float textY = topY + cardPadY;
            if (!userAsked.isEmpty()) {
                textPaint.setTypeface(Ui.medium);
                textPaint.setTextSize(21f * s);
                String hint = "Botón de voz o Hablar para seguir";
                float hintW = textPaint.measureText(hint);
                textPaint.setColor(0x7A94A3B8);
                canvas.drawText(hint, marginX + contentW - cardPadX - hintW, textY + 18f * s, textPaint);

                textPaint.setColor(0xB3CBD5E1);
                drawEllipsizedSingleLine(canvas, "Tú: «" + userAsked + "»",
                        marginX + cardPadX, textY + 18f * s, maxSpeechW - hintW - 28f * s, textPaint);
                textY += headerH;
            }
            textPaint.setTypeface(Ui.displayMedium);
            textPaint.setTextSize(29f * s);
            textPaint.setColor(0xFFF8FAFC);
            canvas.save();
            canvas.translate(marginX + cardPadX, textY);
            bodyLayout.draw(canvas);
            canvas.restore();

            // 2. Unobstructed crisp Poster Cards Row with metadata cleanly below each poster
            float rowTop = topY + panelH + 34f * s;
            if (!cards.isEmpty()) {
                float posterW = 280f * s;
                float posterH = 408f * s;
                float gap = 34f * s;
                float labelH = 74f * s;
                float totalRowW = cards.size() * posterW + Math.max(0, cards.size() - 1) * gap;
                float baseLeft = marginX;
                if (totalRowW <= contentW) {
                    baseLeft = marginX + (contentW - totalRowW) / 2f;
                    scrollX = 0f;
                } else {
                    float targetScroll = 0f;
                    float focusedLeft = focusedCard * (posterW + gap);
                    if (focusedLeft + posterW > contentW) {
                        targetScroll = focusedLeft + posterW - contentW + 40f * s;
                    }
                    scrollX += (targetScroll - scrollX) * 0.22f;
                }

                canvas.save();
                canvas.clipRect(0, rowTop - 28f * s, w, rowTop + posterH + labelH + 20f * s);
                for (int i = 0; i < cards.size(); i++) {
                    float left = baseLeft + i * (posterW + gap) - scrollX;
                    if (left + posterW < marginX - 60f * s || left > w) continue;
                    boolean focused = focusArea == FOCUS_CARDS && i == focusedCard;
                    drawRecommendationCard(canvas, cards.get(i), left, rowTop, posterW, posterH, focused, s);
                }
                canvas.restore();
            }

            // 3. Bottom Action Pills ("Hablar", "Ver en inicio", "Escribir")
            drawActionBar(canvas, w, h, s);
        }

        private void drawRecommendationCard(Canvas canvas, Recs.Pick p, float x, float y, float cw, float ph,
                                            boolean focused, float s) {
            float scale = focused ? 1.06f : 1.0f;
            float cx = x + cw / 2f;
            float cy = y + ph / 2f;

            canvas.save();
            canvas.scale(scale, scale, cx, cy);

            rect.set(x, y, x + cw, y + ph);
            float radius = 20f * s;

            // Soft volumetric silver halo when focused
            if (focused) {
                glowRect.set(x - 7f * s, y - 7f * s, x + cw + 7f * s, y + ph + 7f * s);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(9f * s);
                paint.setColor(0x45E2E8F0);
                canvas.drawRoundRect(glowRect, radius + 5f * s, radius + 5f * s, paint);

                glowRect.set(x - 3f * s, y - 3f * s, x + cw + 3f * s, y + ph + 3f * s);
                paint.setStrokeWidth(3.2f * s);
                paint.setColor(0xFFF8FAFC);
                canvas.drawRoundRect(glowRect, radius + 2f * s, radius + 2f * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            Bitmap bmp = cardBitmaps.get(thumbKey(p));
            if (bmp != null && !bmp.isRecycled()) {
                computeCenterCropSrc(bmp.getWidth(), bmp.getHeight(), cw / ph, srcRect);
                canvas.drawBitmap(bmp, srcRect, rect, bitmapPaint);
            } else {
                paint.setShader(new LinearGradient(x, y, x + cw, y + ph,
                        new int[] {0xFF1E2430, 0xFF0E1219}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(null);
            }

            // Sleek obsidian glass Service Pill Badge at top-left (leaving poster artwork completely open)
            String svc = Recs.serviceName(p);
            if (svc.isEmpty()) svc = "com.stremio.one".equals(p.pkg) ? "Stremio" : "TV";
            textPaint.setTypeface(Ui.semibold);
            textPaint.setTextSize(17f * s);
            float badgeW = Math.min(cw - 24f * s, textPaint.measureText(svc) + 24f * s);
            RectF badgeRect = new RectF(x + 12f * s, y + 12f * s, x + 12f * s + badgeW, y + 40f * s);
            paint.setColor("Stremio".equals(svc) ? 0xD9312E4F : 0xD910151F);
            canvas.drawRoundRect(badgeRect, 14f * s, 14f * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * s);
            paint.setColor(0x66CBD5E1);
            canvas.drawRoundRect(badgeRect, 14f * s, 14f * s, paint);
            paint.setStyle(Paint.Style.FILL);

            textPaint.setColor(0xFFF8FAFC);
            drawEllipsizedSingleLine(canvas, svc, badgeRect.left + 12f * s, badgeRect.top + 20f * s, badgeW - 20f * s, textPaint);
            canvas.restore();

            // Subtle hairline border around artwork frame
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(focused ? 2.2f * s : 1.2f * s);
            paint.setColor(focused ? 0xFFF8FAFC : 0x38CBD5E1);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);

            // Title & metadata cleanly below poster so artwork is never hidden
            float titleY = y + ph + 30f * s;
            textPaint.setTypeface(Ui.displaySemibold);
            textPaint.setTextSize(22f * s);
            textPaint.setColor(focused ? 0xFFFFFFFF : 0xFFE2E8F0);
            drawEllipsizedSingleLine(canvas, p.title == null ? "" : p.title, x + 2f * s, titleY, cw - 4f * s, textPaint);

            String sub = joinMetaAndTag(p.meta, p.tag);
            if (!sub.isEmpty()) {
                textPaint.setTypeface(Ui.regular);
                textPaint.setTextSize(17f * s);
                textPaint.setColor(focused ? 0xFFCBD5E1 : 0xFF94A3B8);
                drawEllipsizedSingleLine(canvas, sub, x + 2f * s, titleY + 25f * s, cw - 4f * s, textPaint);
            }
            canvas.restore();
        }

        private void computeCenterCropSrc(int bw, int bh, float dstAspect, Rect out) {
            if (bw <= 0 || bh <= 0 || dstAspect <= 0f) {
                out.set(0, 0, Math.max(1, bw), Math.max(1, bh));
                return;
            }
            float srcAspect = (float) bw / bh;
            if (srcAspect > dstAspect) {
                int cropW = Math.max(1, Math.round(bh * dstAspect));
                int left = (bw - cropW) / 2;
                out.set(left, 0, left + cropW, bh);
            } else {
                int cropH = Math.max(1, Math.round(bw / dstAspect));
                int top = (bh - cropH) / 2;
                out.set(0, top, bw, top + cropH);
            }
        }

        private String joinMetaAndTag(String meta, String tag) {
            String m = meta == null ? "" : meta.trim();
            String t = tag == null ? "" : tag.trim();
            if (t.isEmpty()) return m;
            if (m.isEmpty()) return t;
            if (t.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT))) return t;
            if (m.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT))) return m;
            return m + " · " + t;
        }

        private void drawActionBar(Canvas canvas, int w, int h, float s) {
            String[] labels = actionLabels();
            float gap = 22f * s;
            float pillH = 66f * s;
            float top = h - pillH - 46f * s;

            textPaint.setTypeface(Ui.semibold);
            textPaint.setTextSize(24f * s);
            float[] widths = new float[labels.length];
            float totalW = 0f;
            for (int i = 0; i < labels.length; i++) {
                float extraIcon = i == 0 ? 42f * s : 0f;
                widths[i] = textPaint.measureText(labels[i]) + 62f * s + extraIcon;
                totalW += widths[i];
            }
            totalW += gap * Math.max(0, labels.length - 1);

            float x = (w - totalW) / 2f;
            for (int i = 0; i < labels.length; i++) {
                boolean focused = focusArea == FOCUS_ACTIONS && focusedAction == i;
                rect.set(x, top, x + widths[i], top + pillH);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(focused ? 0xF5F8FAFC : 0xD110141D);
                canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(focused ? 2.4f * s : 1.4f * s);
                paint.setColor(focused ? 0xFFFFFFFF : 0x4DCBD5E1);
                canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, paint);
                paint.setStyle(Paint.Style.FILL);

                float tx = x + 30f * s;
                if (i == 0) {
                    drawMiniMic(canvas, tx + 10f * s, top + pillH / 2f, s, focused ? 0xFF0A0D12 : 0xFFE2E8F0);
                    tx += 38f * s;
                }
                textPaint.setTypeface(Ui.semibold);
                textPaint.setTextSize(24f * s);
                textPaint.setColor(focused ? 0xFF0A0D12 : 0xFFF1F5F9);
                canvas.drawText(labels[i], tx, top + pillH / 2f + Ui.halfCap(textPaint), textPaint);
                x += widths[i] + gap;
            }
        }

        private void drawVoiceBars(Canvas canvas, float cx, float cy, float s) {
            paint.setColor(0x26E2E8F0);
            canvas.drawCircle(cx, cy, (36f + smoothEnergy * 18f) * s, paint);

            int[] barColors = {0xFF94A3B8, 0xFFCBD5E1, 0xFFF8FAFC, 0xFFCBD5E1, 0xFF94A3B8};
            float barW = 7f * s;
            float spacing = 12f * s;
            float startX = cx - (2f * spacing);
            for (int i = 0; i < 5; i++) {
                float wave = 0.5f + 0.5f * (float) Math.sin(phase * 6.0f + i * 1.15f);
                float maxBar = (i == 2 ? 52f : (i == 1 || i == 3 ? 42f : 30f)) * s;
                float bh = (14f * s) + maxBar * Math.min(1f, 0.25f + smoothEnergy * 1.1f) * wave;
                rect.set(startX + i * spacing - barW / 2f, cy - bh / 2f,
                        startX + i * spacing + barW / 2f, cy + bh / 2f);
                paint.setColor(barColors[i]);
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            }
        }

        private void drawMiniMic(Canvas canvas, float cx, float cy, float s, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            RectF micBody = new RectF(cx - 5.5f * s, cy - 12f * s, cx + 5.5f * s, cy + 2f * s);
            canvas.drawRoundRect(micBody, 5.5f * s, 5.5f * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * s);
            RectF cup = new RectF(cx - 9.5f * s, cy - 5f * s, cx + 9.5f * s, cy + 7f * s);
            canvas.drawArc(cup, 0, 180, false, paint);
            canvas.drawLine(cx, cy + 7f * s, cx, cy + 12f * s, paint);
            canvas.drawLine(cx - 5f * s, cy + 12f * s, cx + 5f * s, cy + 12f * s, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawEllipsizedSingleLine(Canvas canvas, String text, float x, float y, float maxW, TextPaint tp) {
            if (text == null || text.isEmpty() || maxW <= 0) return;
            String draw = android.text.TextUtils.ellipsize(text, tp, maxW, android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(draw, x, y, tp);
        }

        private int withAlpha(int color, int alpha) {
            int a = Math.max(0, Math.min(255, alpha));
            return (a << 24) | (color & 0x00FFFFFF);
        }

        private int lerpArgb(int c0, int c1, float t) {
            float cl = Math.max(0f, Math.min(1f, t));
            int a = Math.round(((c0 >>> 24) & 255) + (((c1 >>> 24) & 255) - ((c0 >>> 24) & 255)) * cl);
            int r = Math.round(((c0 >>> 16) & 255) + (((c1 >>> 16) & 255) - ((c0 >>> 16) & 255)) * cl);
            int g = Math.round(((c0 >>> 8) & 255) + (((c1 >>> 8) & 255) - ((c0 >>> 8) & 255)) * cl);
            int b = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * cl);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
