package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conversational Gemini AI engine and voice recommender for Free My TV.
 * Grounds answers in the user's Trakt.tv taste profile and installed streaming apps
 * (Netflix, Prime Video, Disney+, Max, Apple TV, Paramount+) with Stremio as fallback.
 */
final class GeminiAi {
    private GeminiAi() {}

    static final String ACTION_RECOMMEND = "recommend";
    static final String ACTION_SHOW_TV = "show_tv";
    static final String ACTION_SHOW_MOVIES = "show_movies";
    static final String ACTION_SHOW_ALL = "show_all";
    static final String ACTION_OPEN_APP = "open_app";
    static final String ACTION_CHAT = "chat";

    private static final String[] MODELS = {
        "gemini-3.5-flash-lite",
        "gemini-3-flash",
        "gemini-3-flash-preview",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash"
    };
    private static volatile String cachedModel;

    /** One conversational exchange turn preserved in multi-turn voice sessions. */
    static final class Turn {
        final String role;
        final String text;

        Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /** Structured response returned from Gemini and resolved against local TV apps / Recs. */
    static final class AiReply {
        final String spoken;
        final String transcript;
        final String action;
        final String appName;
        final List<Recs.Pick> picks;

        AiReply(String spoken, String transcript, String action, String appName, List<Recs.Pick> picks) {
            this.spoken = spoken;
            this.transcript = transcript;
            this.action = action;
            this.appName = appName;
            this.picks = picks;
        }
    }

    static boolean hasKey(Context c) {
        return !apiKey(c).isEmpty();
    }

    static String apiKey(Context c) {
        String k = Secrets.get(c, Secrets.GEMINI_KEY).trim();
        if (!k.isEmpty()) return k;
        return c.getApplicationContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getString("gemini_key", "").trim();
    }

    /** Launches the full-screen glowing halo voice assistant overlay. */
    static void openVoice(Activity a, HomeView ignoredHome) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        Intent intent = new Intent(a, VoiceActivity.class);
        intent.putExtra(VoiceActivity.EXTRA_LISTEN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(intent);
        a.overridePendingTransition(android.R.anim.fade_in, 0);
    }

    /** Called by Recs when generating automatic background AI picks for the Home Screen. */
    static List<Recs.Pick> fetchAutoPicks(Context c, String traktTaste) throws Exception {
        if (!hasKey(c)) return new ArrayList<>();
        Context app = c.getApplicationContext();
        String system = buildSystemInstruction(traktTaste, installedServicesText(app), false, Collections.emptySet(), 8);
        JSONArray contents = new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text",
                        "Recomiéndame 8 títulos nuevos o imperdibles (4 películas y 4 series) que encajen con mis gustos de Trakt y que NUNCA haya visto. Devuelve action='recommend'."))));
        JSONObject body = buildRequestBody(system, contents, 0.7);
        String raw = askGeminiRaw(app, body);
        AiReply reply = resolveAndFilterReply(app, raw, Collections.emptySet(), "");
        return reply.picks;
    }

    /** Runs a conversational text turn with multi-turn memory, Trakt unseen filtering, and optional picks. */
    static AiReply converseText(Context c, String userMessage, List<Turn> history, Set<String> excludedPlainTitles) throws Exception {
        Context app = c.getApplicationContext();
        Set<String> excluded = excludedPlainTitles == null ? Collections.emptySet() : excludedPlainTitles;
        String taste = Trakt.tasteSummary(app);
        String system = buildSystemInstruction(taste, installedServicesText(app), false, excluded, 4);
        JSONArray contents = buildHistoryContents(history);
        contents.put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userMessage))));

        JSONObject body = buildRequestBody(system, contents, 0.68);
        String raw = askGeminiRaw(app, body);
        AiReply reply = resolveAndFilterReply(app, raw, excluded, "Aquí tienes algunas opciones que encontré para ti.");
        syncAutoPicks(app, reply);
        return reply;
    }

    /** Runs a conversational voice turn from recorded 16 kHz mono WAV audio when SpeechRecognizer is unavailable. */
    static AiReply converseAudio(Context c, byte[] wavBytes, List<Turn> history, Set<String> excludedPlainTitles) throws Exception {
        Context app = c.getApplicationContext();
        Set<String> excluded = excludedPlainTitles == null ? Collections.emptySet() : excludedPlainTitles;
        String taste = Trakt.tasteSummary(app);
        String system = buildSystemInstruction(taste, installedServicesText(app), true, excluded, 4);
        JSONArray contents = buildHistoryContents(history);
        String b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP);
        JSONArray userParts = new JSONArray()
                .put(new JSONObject().put("text",
                        "Escucha el audio en español del usuario, transcríbelo fielmente en 'transcript' y responde siguiendo las reglas JSON."))
                .put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", "audio/wav")
                        .put("data", b64)));
        contents.put(new JSONObject().put("role", "user").put("parts", userParts));

        JSONObject body = buildRequestBody(system, contents, 0.6);
        String raw = askGeminiRaw(app, body);
        AiReply reply = resolveAndFilterReply(app, raw, excluded, "Aquí tienes algunas opciones que encontré para ti.");
        syncAutoPicks(app, reply);
        return reply;
    }

    private static AiReply resolveAndFilterReply(Context app, String jsonText, Set<String> excludedPlainTitles,
                                                 String fallbackSummary) throws JSONException {
        JSONObject obj = new JSONObject(stripMarkdownJson(jsonText));
        String spoken = obj.optString("spoken", "").trim();
        if (spoken.isEmpty()) spoken = obj.optString("summary", fallbackSummary).trim();
        String transcript = obj.optString("transcript", "").trim();
        String action = normalizeAction(obj.optString("action", ACTION_RECOMMEND));
        String appName = obj.optString("app_name", obj.optString("app", "")).trim();

        List<Recs.Pick> resolvedPicks = new ArrayList<>();
        Recs recs = Recs.get(app);
        JSONArray items = obj.optJSONArray("picks");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String t = item.optString("title", "").trim();
                String orig = item.optString("original_title", t).trim();
                if (isExcluded(app, excludedPlainTitles, null, 0, t, orig)) continue;
                Recs.Pick pick = recs.resolveAiPick(item);
                if (pick == null) continue;
                if (isExcluded(app, excludedPlainTitles, pick.kind, pick.id, pick.title, orig)) continue;
                resolvedPicks.add(pick);
            }
        }
        return new AiReply(spoken, transcript, action, appName, resolvedPicks);
    }

    private static boolean isExcluded(Context app, Set<String> sessionExcluded, String kind, int id, String title, String orig) {
        if (Trakt.isWatched(app, kind, id, title, orig)) return true;
        if (sessionExcluded != null && !sessionExcluded.isEmpty()) {
            if (title != null && !title.isEmpty() && sessionExcluded.contains(Recs.plain(title))) return true;
            if (orig != null && !orig.isEmpty() && sessionExcluded.contains(Recs.plain(orig))) return true;
        }
        return false;
    }

    private static void syncAutoPicks(Context app, AiReply reply) {
        if (ACTION_RECOMMEND.equals(reply.action) && !reply.picks.isEmpty()) {
            Recs.get(app).setAiPicks(reply.picks);
        }
    }

    /** Pulls Home Screen recommendations matching show_tv / show_movies / show_all voice intents. */
    static List<Recs.Pick> homeRecommendations(Context c, String action) {
        Recs recs = Recs.get(c.getApplicationContext());
        if (ACTION_SHOW_TV.equals(action)) return recs.now("tv");
        if (ACTION_SHOW_MOVIES.equals(action)) return recs.now("movie");
        if (ACTION_SHOW_ALL.equals(action)) return recs.now(null);
        return new ArrayList<>();
    }

    /** Maps show_tv / show_movies / show_all actions to HomeView tab indices, or -1 if none. */
    static int homeTabForAction(String action) {
        if (ACTION_SHOW_ALL.equals(action)) return 0;
        if (ACTION_SHOW_MOVIES.equals(action)) return 1;
        if (ACTION_SHOW_TV.equals(action)) return 2;
        return -1;
    }

    /** Launches a recommended Pick into its target streaming app or Stremio fallback. */
    static boolean openPick(Activity a, Recs.Pick p) {
        if (a == null || p == null || p.pkg == null) return false;
        HomeApps.Item item = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
        if (HomeApps.open(a, item, null)) return true;
        // Only retry Stremio search when the pick itself was routed to Stremio; never hijack streaming-app picks.
        if ("com.stremio.one".equals(p.pkg)) {
            try {
                Intent stremio = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("stremio:///search?search=" + URLEncoder.encode(p.title, "UTF-8").replace("+", "%20")))
                        .setPackage("com.stremio.one")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(stremio);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Opens an installed TV app whose display name best matches the voice request. */
    static boolean openMatchingApp(Activity a, String requestedName) {
        if (a == null || requestedName == null) return false;
        String needle = Recs.plain(requestedName);
        if (needle.isEmpty()) return false;
        List<String[]> candidates = HomeApps.shelfCandidates(a);
        String bestPkg = null;
        String bestLabel = null;
        int bestScore = 0;
        for (String[] app : candidates) {
            String pkg = app[0];
            String label = app[1];
            String plainLabel = Recs.plain(label);
            int score = 0;
            if (plainLabel.equals(needle)) score = 100;
            else if (plainLabel.contains(needle) || needle.contains(plainLabel)) score = 70;
            else if (pkg.toLowerCase(Locale.ROOT).contains(needle.replace(" ", ""))) score = 50;
            if (score > bestScore) {
                bestScore = score;
                bestPkg = pkg;
                bestLabel = label;
            }
        }
        if (bestPkg == null) return false;
        return HomeApps.open(a, HomeApps.app(bestPkg, bestLabel), null);
    }

    /** Parses Gemini JSON output into a normalized AiReply; passing null recs skips external resolution for tests. */
    static AiReply parseAiReply(String jsonText, Recs recs, String fallbackSummary) throws JSONException {
        JSONObject obj = new JSONObject(stripMarkdownJson(jsonText));
        String spoken = obj.optString("spoken", "").trim();
        if (spoken.isEmpty()) spoken = obj.optString("summary", fallbackSummary).trim();
        String transcript = obj.optString("transcript", "").trim();
        String action = normalizeAction(obj.optString("action", ACTION_RECOMMEND));
        String appName = obj.optString("app_name", obj.optString("app", "")).trim();

        List<Recs.Pick> resolvedPicks = new ArrayList<>();
        JSONArray items = obj.optJSONArray("picks");
        if (items != null && recs != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                Recs.Pick pick = recs.resolveAiPick(item);
                if (pick != null) resolvedPicks.add(pick);
            }
        }
        return new AiReply(spoken, transcript, action, appName, resolvedPicks);
    }

    /** Normalizes Gemini's action string into one of the ACTION_* constants. */
    static String normalizeAction(String raw) {
        if (raw == null) return ACTION_RECOMMEND;
        String a = raw.trim().toLowerCase(Locale.ROOT);
        if (a.equals(ACTION_SHOW_TV) || a.equals("series") || a.equals("shows") || a.equals("tv")) {
            return ACTION_SHOW_TV;
        }
        if (a.equals(ACTION_SHOW_MOVIES) || a.equals("movies") || a.equals("peliculas")) {
            return ACTION_SHOW_MOVIES;
        }
        if (a.equals(ACTION_SHOW_ALL) || a.equals("explore") || a.equals("all") || a.equals("home")) {
            return ACTION_SHOW_ALL;
        }
        if (a.equals(ACTION_OPEN_APP) || a.equals("open") || a.equals("launch")) {
            return ACTION_OPEN_APP;
        }
        if (a.equals(ACTION_CHAT)) return ACTION_CHAT;
        return ACTION_RECOMMEND;
    }

    /** Strips emojis, markdown markers, and awkward symbols so TextToSpeech reads naturally. */
    static String cleanForSpeech(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D) continue;
            if ((cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)) continue;
            if (cp == '*' || cp == '_' || cp == '`' || cp == '#') continue;
            sb.appendCodePoint(cp);
        }
        return sb.toString()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.;:!?])", "$1")
                .trim();
    }

    /** Wraps raw signed 16-bit mono PCM bytes with a standard 44-byte RIFF/WAVE header. */
    static byte[] encodeWav(byte[] pcm16Mono, int sampleRate) {
        byte[] pcm = pcm16Mono == null ? new byte[0] : pcm16Mono;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int dataSize = pcm.length;
        int riffSize = 36 + dataSize;
        byte[] out = new byte[44 + dataSize];

        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        putLeInt(out, 4, riffSize);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        putLeInt(out, 16, 16);
        putLeShort(out, 20, 1);
        putLeShort(out, 22, channels);
        putLeInt(out, 24, sampleRate);
        putLeInt(out, 28, byteRate);
        putLeShort(out, 32, blockAlign);
        putLeShort(out, 34, bitsPerSample);
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        putLeInt(out, 40, dataSize);
        System.arraycopy(pcm, 0, out, 44, dataSize);
        return out;
    }

    private static void putLeInt(byte[] buf, int off, int val) {
        buf[off] = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >>> 8) & 0xFF);
        buf[off + 2] = (byte) ((val >>> 16) & 0xFF);
        buf[off + 3] = (byte) ((val >>> 24) & 0xFF);
    }

    private static void putLeShort(byte[] buf, int off, int val) {
        buf[off] = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >>> 8) & 0xFF);
    }

    private static JSONArray buildHistoryContents(List<Turn> history) throws JSONException {
        JSONArray contents = new JSONArray();
        if (history == null) return contents;
        int start = Math.max(0, history.size() - 8);
        for (int i = start; i < history.size(); i++) {
            Turn t = history.get(i);
            if (t == null || t.text == null || t.text.isEmpty()) continue;
            contents.put(new JSONObject()
                    .put("role", "model".equals(t.role) ? "model" : "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", t.text))));
        }
        return contents;
    }

    private static JSONObject buildRequestBody(String system, JSONArray contents, double temperature) throws JSONException {
        return new JSONObject()
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", new JSONObject()
                        .put("temperature", temperature)
                        .put("responseMimeType", "application/json"));
    }

    private static String installedServicesText(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : Recs.installedServices(c)) {
            if (Prefs.serviceOff(s[0])) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s[0]).append(" (").append(s[1]).append(")");
        }
        return sb.toString();
    }

    private static String buildSystemInstruction(String traktTaste, String installedServices,
                                                 boolean withAudioTranscript, Set<String> sessionExcluded, int maxPicks) {
        Calendar now = Calendar.getInstance();
        int currentYear = now.get(Calendar.YEAR);
        String currentDate = new SimpleDateFormat("MMMM 'de' yyyy", new Locale("es", "ES")).format(now.getTime());

        String tasteBlock = (traktTaste == null || traktTaste.isEmpty())
                ? "El usuario aún no ha sincronizado su historial de Trakt o está vacío; recomienda títulos excelentes y variados."
                : "PERFIL REAL EN TRAKT.TV DEL USUARIO (NO repitas NUNCA nada de 'YA VISTO' ni estilos de 'NO ME GUSTÓ'; úsalo para personalizar al máximo):\n"
                        + traktTaste;

        String sessionBlock = (sessionExcluded == null || sessionExcluded.isEmpty())
                ? ""
                : "\nTÍTULOS YA MOSTRADOS EN ESTA CHARLA (NO los repitas salvo que los pida explícitamente): "
                        + String.join(", ", sessionExcluded) + "\n";

        String transcriptRule = withAudioTranscript
                ? "  \"transcript\": \"Transcripción exacta en español de lo que escuchaste en el audio del usuario\",\n"
                : "";

        return "FECHA ACTUAL: " + currentDate + " (año en curso: " + currentYear + ").\n"
                + "Eres el compañero experto en cine y series del usuario (" + Recs.REGION + ") en Free My TV.\n"
                + "PERSONALIDAD Y CONVERSACIÓN HUMANA: Habla en español como un amigo cercano conversando en la sala (cálido, natural, directo, sin emojis ni viñetas ni tono robótico).\n"
                + "Cuando recomiendes algo ('recommend'), menciona 1 o 2 opciones clave en 'spoken' de forma natural y SIEMPRE cierra preguntando con naturalidad si le convence o si quiere ajustar algo — por ejemplo cambiar el año (más nuevas de " + currentYear + "/" + (currentYear - 1) + " o clásicas), otro género, duración o plataforma.\n"
                + "REGLA DE ESTRENOS / AÑO: Si pide películas o series nuevas, recientes o actuales, prioriza estrenos de " + currentYear + " y " + (currentYear - 1) + " (o " + (currentYear - 2) + " si hace falta), nunca títulos viejos salvo que los pida.\n\n"
                + tasteBlock + "\n"
                + sessionBlock + "\n"
                + "PLATAFORMAS INSTALADAS EN SU TV (claves válidas para 'service'): "
                + (installedServices.isEmpty() ? "netflix, prime, disney, max, appletv, paramount" : installedServices) + ".\n"
                + "REGLA DE PLATAFORMAS: Prioriza títulos incluidos por suscripción en sus apps instaladas en " + Recs.REGION
                + " y pon su clave exacta en 'service'. Usa 'stremio' únicamente cuando no esté incluido en ninguna de esas apps.\n\n"
                + "ELECCIÓN DE 'action':\n"
                + "- 'recommend': Recomendaciones personalizadas, ajustes de año/género/ritmo, seguimiento ('¿qué serie me recomiendas hoy?', 'películas nuevas que no haya visto', 'dame algo de este año', 'otra parecida pero más corta'). Devuelve máximo " + maxPicks + " títulos en 'picks'.\n"
                + "- 'show_tv': SOLO si pide ver explícitamente su lista/pestaña de recomendaciones de series o TV.\n"
                + "- 'show_movies': SOLO si pide ver explícitamente su lista/pestaña de recomendaciones de películas.\n"
                + "- 'show_all': SOLO si pide ver todas sus recomendaciones o volver a Explorar.\n"
                + "- 'open_app': Si pide abrir una aplicación del televisor ('abre Netflix', 'pon Stremio'); pon el nombre en 'app_name'.\n"
                + "- 'chat': Si hace una pregunta general o conversa sin necesitar tarjetas nuevas.\n\n"
                + "Devuelve ÚNICAMENTE un objeto JSON válido con esta estructura exacta:\n"
                + "{\n"
                + transcriptRule
                + "  \"action\": \"recommend | show_tv | show_movies | show_all | open_app | chat\",\n"
                + "  \"spoken\": \"Respuesta cálida y humana en español pensada para decirse en voz alta (2 frases fluidas con pregunta final de seguimiento cuando corresponda, sin emojis ni markdown)\",\n"
                + "  \"app_name\": \"Solo si action=open_app; si no, cadena vacía\",\n"
                + "  \"picks\": [\n"
                + "    {\n"
                + "      \"title\": \"Título conocido en Latinoamérica o español\",\n"
                + "      \"original_title\": \"Título original exacto\",\n"
                + "      \"year\": " + currentYear + ",\n"
                + "      \"kind\": \"movie o tv\",\n"
                + "      \"imdb_id\": \"ttXXXXXXX\",\n"
                + "      \"genre\": \"Género principal en español\",\n"
                + "      \"duration\": \"ej. 1h 55m o 2 temporadas\",\n"
                + "      \"service\": \"netflix | prime | disney | max | appletv | paramount | stremio\",\n"
                + "      \"why\": \"Por qué encaja con su perfil (máx 60 caracteres)\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";
    }

    private static String askGeminiRaw(Context c, JSONObject bodyTemplate) throws Exception {
        String key = apiKey(c);
        if (key.isEmpty()) throw new IOException("Falta la clave de Gemini IA");

        String preferred = cachedModel;
        List<String> order = new ArrayList<>();
        if (preferred != null) order.add(preferred);
        for (String m : MODELS) if (!m.equals(preferred)) order.add(m);

        IOException lastError = null;
        boolean allRejected = true;
        for (String model : order) {
            try {
                String text = callModelRaw(model, key, bodyTemplate);
                cachedModel = model;
                return text;
            } catch (IOException e) {
                lastError = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (!msg.contains("HTTP 404") && !msg.contains("HTTP 400")) allRejected = false;
                if (model.equals(cachedModel)) cachedModel = null;
                AppLog.w("gemini " + model + ": " + msg);
                // Retry once quickly if the preferred live model suffered a transient network timeout.
                if (msg.contains("timeout") || msg.contains("timed out")) {
                    try {
                        String text = callModelRaw(model, key, bodyTemplate);
                        cachedModel = model;
                        return text;
                    } catch (IOException retryErr) {
                        lastError = retryErr;
                        AppLog.w("gemini " + model + " retry: " + retryErr.getMessage());
                    }
                }
            }
        }
        if (allRejected) {
            for (String discovered : discoverFlashModels(key)) {
                if (order.contains(discovered)) continue;
                try {
                    String text = callModelRaw(discovered, key, bodyTemplate);
                    cachedModel = discovered;
                    return text;
                } catch (IOException e) {
                    lastError = e;
                    AppLog.w("gemini discovered " + discovered + ": " + e.getMessage());
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Sin respuesta de Gemini");
    }

    private static List<String> discoverFlashModels(String key) {
        List<String> out = new ArrayList<>();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + URLEncoder.encode(key, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            String resp = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
            if (code != 200) return out;
            JSONArray models = new JSONObject(resp).optJSONArray("models");
            if (models == null) return out;
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "").replace("models/", "");
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean gen = false;
                if (methods != null) {
                    for (int j = 0; j < methods.length(); j++) {
                        if ("generateContent".equals(methods.optString(j))) {
                            gen = true;
                            break;
                        }
                    }
                }
                if (gen && name.contains("gemini") && name.contains("flash")) out.add(name);
            }
        } catch (Exception e) {
            AppLog.w("gemini model discovery: " + e.getMessage());
        }
        return out;
    }

    private static String callModelRaw(String model, String key, JSONObject bodyTemplate) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(45000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject(bodyTemplate.toString());
        if (model.startsWith("gemini-2.5")) {
            body.getJSONObject("generationConfig")
                    .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        }

        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readStream(in);
        conn.disconnect();
        if (code != 200) throw new IOException("HTTP " + code);

        JSONObject root = new JSONObject(resp);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) throw new IOException("Respuesta vacía de Gemini");
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) throw new IOException("Sin contenido en Gemini");

        String text = "";
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.optBoolean("thought", false)) continue;
            String t = part.optString("text", "").trim();
            if (!t.isEmpty()) text = t;
        }
        if (text.isEmpty()) text = parts.getJSONObject(parts.length() - 1).optString("text", "").trim();
        return text;
    }

    static String stripMarkdownJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return t.trim();
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
