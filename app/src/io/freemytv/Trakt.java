package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trakt (trakt.tv), only for someone who signs in with their own account: the picks start with what they're in the
 * middle of (continue watching, the next episode), bring in their watchlist and Trakt's recommendations, and leave out
 * what they've watched. It only reads. Needs the person's own API app (config.properties "trakt_client_id"); without
 * it, or until they sign in, nothing here runs and the picks stay TMDB's.
 *
 * Signing in is Trakt's device code with PKCE: the TV shows a short code, they type it at trakt.tv/activate on their
 * phone. Only the Client ID is needed, so no secret is ever built into the APK (Trakt made it optional, trakt-api#974).
 * The tokens stay in this process's SharedPreferences (:home, where Settings and the home screen both run), and
 * only :home uses them: on Android 11 to 12L the screensaver runs in the main process (ScreensaverAlone), where a
 * renewal from its own copy would leave :home's refresh token spent, and so signed out.
 */
final class Trakt {
    private Trakt() {}

    private static final String API = "https://api.trakt.tv";
    /** Watchlist titles read, per kind: candidates (Recs looks them up on TMDB only until it has enough). */
    static final int WATCHLIST_MAX = 12;
    /** Trakt's recommendations taken, per kind. */
    static final int RECOMMENDED_MAX = 10;
    /** Shows watched most recently whose next episode is looked up (one Trakt request each). */
    private static final int UP_NEXT_SHOWS = 8;

    /** Something they're in the middle of: a paused movie or episode, or a show's next episode. */
    static final class Progress {
        final String key;
        final int season, episode;     // 0 for a movie
        final boolean paused;          // stopped partway (Trakt's playback), not the next one up

        Progress(String key, int season, int episode, boolean paused) {
            this.key = key;
            this.season = season;
            this.episode = episode;
            this.paused = paused;
        }
    }

    /** What the picks need, as Recs keys ("movie:603", "tv:1396"). */
    static final class Lists {
        final Set<String> watched = new HashSet<>();
        /** Trakt's last change to the account (marked watched, added to the watchlist…) when these were read. */
        String activity = "";
        final List<Progress> progress = new ArrayList<>();
        final List<String> watchlistMovies = new ArrayList<>(), watchlistShows = new ArrayList<>();
        final List<String> recommendedMovies = new ArrayList<>(), recommendedShows = new ArrayList<>();
        final List<String> tasteTitles = new ArrayList<>();
        final List<String> likedTitles = new ArrayList<>();
        final List<String> dislikedTitles = new ArrayList<>();
        final List<String> recentWatchedTitles = new ArrayList<>();
        final List<String> watchlistTitles = new ArrayList<>();
        final Map<String, String> imdbIds = new HashMap<>();
        final Map<String, String> titles = new HashMap<>();
    }

    // Sign-in in progress (Settings shows it in the Trakt row); null when none.
    private static volatile String code, url, message = "";
    private static volatile boolean signingIn;
    /** Its own lock: a token renewal (network, on the picks' thread) never holds up OK on the Trakt row. */
    private static final Object SIGN_IN = new Object();

    /** The person put their own Trakt API app in config.properties (and this is :home: see above). */
    static boolean available() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty() && inHome();
    }

    private static volatile Boolean home;

    private static boolean inHome() {
        if (home == null) {
            String name = "";
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] b = new byte[128];
                int n = Math.max(0, in.read(b)), end = 0;
                while (end < n && b[end] != 0) end++;
                name = new String(b, 0, end, StandardCharsets.US_ASCII);
            } catch (IOException ignored) {
            }
            home = name.endsWith(":home");
        }
        return home;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("trakt", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return available() && !sp(c).getString("refresh", "").isEmpty();
    }

    /** The account's name ("" if not signed in). */
    static String user(Context c) {
        return signedIn(c) ? sp(c).getString("user", "") : "";
    }

    /** Changes when the account does: the picks are fetched again. */
    static long stamp(Context c) {
        return signedIn(c) ? 31L * sp(c).getString("user", "").hashCode() + 1 : 0;
    }

    /** The code to type at the address, while signing in; else null. */
    static String code() { return signingIn ? code : null; }
    static String url() { return url; }

    /** Asking Trakt for a code, or waiting for it to be typed. */
    static boolean busy() { return signingIn; }

    /** How the last sign-in went ("" when there's nothing to say). */
    static String message() { return message; }

    // MARK: - Sign in and out

    /** Starts Trakt's device sign-in in the background; `changed` runs (on that thread) at every step. */
    static void signIn(Context c, Runnable changed) {
        synchronized (SIGN_IN) {
            if (signingIn || !available()) return;
            signingIn = true;
        }
        code = null;
        message = "";
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                // PKCE: a one-time secret for this sign-in; Trakt gets its hash now and the secret itself with the code.
                byte[] random = new byte[48];
                new SecureRandom().nextBytes(random);
                String verifier = base64Url(random);
                String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                JSONObject start = new JSONObject(request("POST", "/oauth/device/code", new JSONObject().put("client_id", clientId())
                        .put("code_challenge", challenge).put("code_challenge_method", "S256"), null, 200));
                code = start.getString("user_code");
                url = start.optString("verification_url", "https://trakt.tv/activate").replaceFirst("^https?://", "");
                changed.run();
                String deviceCode = start.getString("device_code");
                long interval = Math.max(5, start.optLong("interval", 5)) * 1000L;
                long until = System.currentTimeMillis() + start.optLong("expires_in", 600) * 1000L;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(interval);
                    Response r = send("POST", "/oauth/device/token", new JSONObject().put("code", deviceCode)
                            .put("client_id", clientId()).put("code_verifier", verifier), null);
                    if (r.code == 200) {
                        save(app, new JSONObject(r.body));
                        String name = "";
                        try {
                            name = new JSONObject(get(app, "/users/settings")).getJSONObject("user").optString("username");
                        } catch (Exception e) {
                            AppLog.w("trakt user: " + e.getMessage());
                        }
                        sp(app).edit().putString("user", name).apply();
                        Prefs.version++;
                        AppLog.w("trakt: signed in");
                        return;
                    }
                    if (r.code == 400) continue;                     // not typed yet
                    if (r.code == 429) { interval += 1000; continue; }  // too fast
                    if (r.code == 418) { message = Ui.tr("Sign-in declined on Trakt."); return; }
                    if (r.code == 410) break;
                    throw new IOException("HTTP " + r.code);
                }
                message = Ui.tr("The code expired. Press OK for a new one.");
            } catch (Exception e) {
                message = Ui.trf("Couldn't sign in: {error}", "error", e.getMessage());
                AppLog.w("trakt sign-in: " + e.getMessage());
            } finally {
                signingIn = false;
                changed.run();
            }
        }, "trakt").start();
    }

    /** Forgets the account here and tells Trakt to drop the token (in the background, best effort). */
    static void signOut(Context c) {
        SharedPreferences sp = sp(c);
        String token = sp.getString("access", "");
        sp.edit().clear().apply();
        message = "";
        Prefs.version++;
        if (token.isEmpty()) return;
        new Thread(() -> {
            try {
                send("POST", "/oauth/revoke", new JSONObject().put("token", token).put("client_id", clientId()), null);
            } catch (Exception ignored) {
            }
        }, "trakt").start();
    }

    private static void save(Context c, JSONObject t) {
        long created = t.optLong("created_at", System.currentTimeMillis() / 1000);
        sp(c).edit().putString("access", t.optString("access_token")).putString("refresh", t.optString("refresh_token"))
                .putLong("expires", (created + t.optLong("expires_in", 86400)) * 1000L).apply();
    }

    /** A valid access token, renewed when it's about to expire (Trakt's last a day). Off the UI thread. */
    private static synchronized String token(Context c) throws Exception {
        SharedPreferences sp = sp(c);
        String refresh = sp.getString("refresh", "");
        if (refresh.isEmpty()) throw new IOException("not signed in");
        if (System.currentTimeMillis() < sp.getLong("expires", 0) - 3600_000L) return sp.getString("access", "");
        Response r = send("POST", "/oauth/token", new JSONObject().put("refresh_token", refresh).put("client_id", clientId())
                .put("redirect_uri", "http://localhost").put("grant_type", "refresh_token"), null);
        if (r.code == 400 || r.code == 401) {
            // The person disconnected the app on trakt.tv (or the token is gone): signed out here too.
            sp.edit().clear().apply();
            Prefs.version++;
            throw new IOException("Trakt signed this TV out");
        }
        if (r.code != 200) throw new IOException("Trakt returned HTTP " + r.code);
        // Signed out (or in again) while this was on its way: the new token isn't theirs anymore.
        if (!refresh.equals(sp.getString("refresh", ""))) throw new IOException("not signed in");
        save(c, new JSONObject(r.body));
        return sp.getString("access", "");
    }

    // MARK: - Lists

    /** Progress, watched titles, the watchlist (newest first) and recommendations, from TMDB ids. Off the UI thread. */
    static Lists lists(Context c) throws Exception {
        Lists out = new Lists();
        try {
            out.activity = activity(c);
        } catch (Exception e) {
            AppLog.w("trakt activity: " + e.getMessage());
        }
        Map<String, String> watchedAt = new HashMap<>();
        List<Seen> movies = watched(c, "/sync/watched/movies", "movie", out.watched, watchedAt, out);
        List<Seen> shows = watched(c, "/sync/watched/shows?extended=noseasons", "show", out.watched, watchedAt, out);
        try {
            ratings(c, "/sync/ratings/movies/8,9,10", "movie", out.likedTitles, 30);
            ratings(c, "/sync/ratings/shows/8,9,10", "show", out.likedTitles, 30);
            ratings(c, "/sync/ratings/movies/1,2,3,4,5", "movie", out.dislikedTitles, 20);
            ratings(c, "/sync/ratings/shows/1,2,3,4,5", "show", out.dislikedTitles, 20);
        } catch (Exception e) {
            AppLog.w("trakt ratings: " + e.getMessage());
        }
        out.tasteTitles.addAll(out.likedTitles);
        addRecentSeenToTaste(shows, out.tasteTitles, 15);
        addRecentSeenToTaste(movies, out.tasteTitles, 15);
        addRecentSeenToTaste(movies, out.recentWatchedTitles, 45);
        addRecentSeenToTaste(shows, out.recentWatchedTitles, 35);
        // Each part on its own: one Trakt endpoint failing leaves the others.
        try {
            playback(c, out, watchedAt);
        } catch (Exception e) {
            AppLog.w("trakt playback: " + e.getMessage());
        }
        try {
            upNext(c, shows, out);
        } catch (Exception e) {
            AppLog.w("trakt up next: " + e.getMessage());
        }
        watchlist(c, "/sync/watchlist/movies", "movie", out.watchlistMovies, out);
        watchlist(c, "/sync/watchlist/shows", "show", out.watchlistShows, out);
        try {
            recommended(c, "/recommendations/movies?ignore_collected=true&limit=" + RECOMMENDED_MAX * 2, "movie", out.recommendedMovies, out);
            recommended(c, "/recommendations/shows?ignore_collected=true&limit=" + RECOMMENDED_MAX * 2, "show", out.recommendedShows, out);
        } catch (Exception e) {
            AppLog.w("trakt recommendations: " + e.getMessage());
        }
        String rich = formatRichTaste(out);
        Set<String> plainSeen = new HashSet<>();
        for (Seen s : movies) if (s.title != null && !s.title.isEmpty()) plainSeen.add(Recs.plain(s.title));
        for (Seen s : shows) if (s.title != null && !s.title.isEmpty()) plainSeen.add(Recs.plain(s.title));
        sp(c).edit()
                .putString("taste", String.join(", ", out.tasteTitles))
                .putString("tasteRich", rich)
                .putInt("tasteVer", 2)
                .putStringSet("watchedKeys", new HashSet<>(out.watched))
                .putStringSet("watchedPlainTitles", plainSeen)
                .putLong("tasteAt", System.currentTimeMillis())
                .apply();
        return out;
    }

    /** Returns a structured taste + exclusion summary from the user's Trakt profile for Gemini AI. */
    static String tasteSummary(Context c) {
        if (!signedIn(c)) return "";
        SharedPreferences s = sp(c);
        String cached = s.getString("tasteRich", "");
        long age = System.currentTimeMillis() - s.getLong("tasteAt", 0);
        if (s.getInt("tasteVer", 0) >= 2 && !cached.isEmpty() && age >= 0 && age < 3600_000L) return cached;
        try {
            Lists l = lists(c);
            String rich = formatRichTaste(l);
            if (!rich.isEmpty()) return rich;
        } catch (Exception e) {
            AppLog.w("trakt taste: " + e.getMessage());
        }
        return !cached.isEmpty() ? cached : s.getString("taste", "");
    }

    /** True if the title or TMDB id is already present in the user's Trakt watched history. */
    static boolean isWatched(Context c, String kind, int tmdbId, String title, String origTitle) {
        if (!signedIn(c)) return false;
        SharedPreferences s = sp(c);
        if (tmdbId > 0 && kind != null) {
            Set<String> keys = s.getStringSet("watchedKeys", null);
            if (keys != null && keys.contains(kind + ":" + tmdbId)) return true;
        }
        Set<String> titles = s.getStringSet("watchedPlainTitles", null);
        if (titles == null || titles.isEmpty()) return false;
        if (title != null && !title.isEmpty() && titles.contains(Recs.plain(title))) return true;
        return origTitle != null && !origTitle.isEmpty() && titles.contains(Recs.plain(origTitle));
    }

    private static String formatRichTaste(Lists l) {
        StringBuilder sb = new StringBuilder();
        if (!l.likedTitles.isEmpty()) {
            sb.append("LE ENCANTÓ (8-10/10 en Trakt): ").append(String.join(", ", l.likedTitles)).append("\n");
        }
        if (!l.dislikedTitles.isEmpty()) {
            sb.append("NO LE GUSTÓ (1-5/10 en Trakt, evita ese estilo o títulos parecidos): ")
                    .append(String.join(", ", l.dislikedTitles)).append("\n");
        }
        if (!l.watchlistTitles.isEmpty()) {
            sb.append("TIENE EN SU WATCHLIST (puedes incluir alguna si coincide): ")
                    .append(String.join(", ", l.watchlistTitles)).append("\n");
        }
        if (!l.recentWatchedTitles.isEmpty()) {
            sb.append("YA VISTO EN TRAKT (PROHIBIDO volver a recomendar cualquiera de estos): ")
                    .append(String.join(", ", l.recentWatchedTitles));
        }
        return sb.toString().trim();
    }

    private static void addRecentSeenToTaste(List<Seen> list, List<String> dest, int max) {
        List<Seen> copy = new ArrayList<>(list);
        Collections.sort(copy, (x, y) -> y.at.compareTo(x.at));
        int count = 0;
        for (Seen s : copy) {
            if (count >= max) break;
            if (s.title != null && !s.title.isEmpty()) {
                String label = s.year > 0 ? s.title + " (" + s.year + ")" : s.title;
                if (!dest.contains(label) && !dest.contains(s.title)) {
                    dest.add(label);
                    count++;
                }
            }
        }
    }

    /** A show from /sync/watched/shows: what upNext needs. */
    private static final class Seen {
        String key, at, title, imdb;
        int trakt, year;
    }

    /**
     * Also when each was last watched or marked watched (`at`): marking a show watched "on its release date" leaves
     * last_watched_at in the past, but last_updated_at is when it was marked. Read as it arrives (a long history is
     * megabytes of JSON): only the ids and the two dates are kept.
     */
    private static List<Seen> watched(Context c, String path, String type, Set<String> out, Map<String, String> at, Lists lists) throws Exception {
        List<Seen> seen = new ArrayList<>();
        stream(c, path, r -> {
            r.beginArray();
            while (r.hasNext()) {
                String watchedAt = "", updatedAt = "", itemTitle = "", imdb = "";
                int tmdb = 0, trakt = 0, year = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String name = r.nextName();
                    if (name.equals("last_watched_at")) watchedAt = string(r);
                    else if (name.equals("last_updated_at")) updatedAt = string(r);
                    else if (name.equals(type) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String field = r.nextName();
                            if (field.equals("title")) itemTitle = string(r);
                            else if (field.equals("year")) year = number(r);
                            else if (field.equals("ids") && r.peek() == JsonToken.BEGIN_OBJECT) {
                                r.beginObject();
                                while (r.hasNext()) {
                                    String id = r.nextName();
                                    if (id.equals("tmdb")) tmdb = number(r);
                                    else if (id.equals("trakt")) trakt = number(r);
                                    else if (id.equals("imdb")) imdb = string(r);
                                    else r.skipValue();
                                }
                                r.endObject();
                            } else {
                                r.skipValue();
                            }
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                if (tmdb <= 0) continue;
                Seen item = new Seen();
                item.key = (type.equals("show") ? "tv" : "movie") + ":" + tmdb;
                item.at = watchedAt.compareTo(updatedAt) > 0 ? watchedAt : updatedAt;
                item.title = itemTitle;
                item.imdb = imdb;
                item.trakt = trakt;
                item.year = year;
                out.add(item.key);
                at.put(item.key, item.at);
                if (!imdb.isEmpty()) lists.imdbIds.put(item.key, imdb);
                if (!itemTitle.isEmpty()) lists.titles.put(item.key, itemTitle);
                seen.add(item);
            }
            r.endArray();
        });
        return seen;
    }

    private static String string(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.STRING) return r.nextString();
        r.skipValue();
        return "";
    }

    private static int number(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NUMBER) return r.nextInt();
        r.skipValue();
        return 0;
    }

    /** When the account last changed on Trakt (ISO 8601, one small request): the picks are fetched again if it moves. */
    static String activity(Context c) throws Exception {
        return new JSONObject(get(c, "/sync/last_activities")).optString("all");
    }

    /**
     * Movies and episodes stopped partway, the most recent first. Trakt keeps one there after it's marked watched:
     * one watched since it was paused is left out (a show's next episode then comes from upNext).
     */
    private static void playback(Context c, Lists out, Map<String, String> watchedAt) throws Exception {
        JSONArray a = new JSONArray(get(c, "/sync/playback?limit=20"));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("paused_at").compareTo(x.optString("paused_at")));
        for (JSONObject item : items) {
            boolean movie = "movie".equals(item.optString("type"));
            String key = key(item, movie ? "movie" : "show", out);
            JSONObject ep = movie ? null : item.optJSONObject("episode");
            if (key == null || has(out.progress, key)) continue;
            String watched = watchedAt.get(key);
            if (watched != null && watched.compareTo(item.optString("paused_at")) >= 0) continue;
            out.progress.add(new Progress(key, ep == null ? 0 : ep.optInt("season"), ep == null ? 0 : ep.optInt("number"), true));
        }
    }

    /** The next episode of the shows watched most recently, when one is out. */
    private static void upNext(Context c, List<Seen> shows, Lists out) throws Exception {
        List<Seen> items = new ArrayList<>(shows);
        Collections.sort(items, (x, y) -> y.at.compareTo(x.at));
        int asked = 0;
        for (Seen item : items) {
            if (asked >= UP_NEXT_SHOWS) break;
            if (item.trakt <= 0 || has(out.progress, item.key)) continue;
            asked++;
            JSONObject progress = new JSONObject(get(c, "/shows/" + item.trakt + "/progress/watched?specials=false&count_specials=false"));
            JSONObject next = progress.optJSONObject("next_episode");
            if (next == null || progress.optInt("completed") >= progress.optInt("aired")) continue;
            out.progress.add(new Progress(item.key, next.optInt("season"), next.optInt("number"), false));
        }
    }

    private static boolean has(List<Progress> list, String key) {
        for (Progress p : list) if (p.key.equals(key)) return true;
        return false;
    }

    /** Rated titles for Gemini AI taste profile (likes and dislikes). */
    private static void ratings(Context c, String path, String type, List<String> dest, int limit) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        for (int i = 0; i < a.length() && dest.size() < limit; i++) {
            JSONObject item = a.optJSONObject(i);
            JSONObject o = item == null ? null : item.optJSONObject(type);
            if (o == null) continue;
            String title = o.optString("title", "");
            int year = o.optInt("year", 0);
            int rating = item.optInt("rating", 0);
            if (!title.isEmpty()) {
                String label = year > 0 ? title + " (" + year + ")" : title;
                String entry = rating > 0 ? label + " [" + rating + "/10]" : label;
                if (!dest.contains(entry) && !dest.contains(title)) dest.add(entry);
            }
        }
    }

    /** Trakt's recommendations for the account, in its order. */
    private static void recommended(Context c, String path, String type, List<String> out, Lists lists) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        for (int i = 0; i < a.length() && out.size() < RECOMMENDED_MAX; i++) {
            String key = key(a.optJSONObject(i), type, lists);
            if (key != null && !out.contains(key)) out.add(key);
        }
    }

    /** The newest additions first (listed_at is ISO 8601 in UTC, so it sorts as text). */
    private static void watchlist(Context c, String path, String type, List<String> out, Lists lists) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("listed_at").compareTo(x.optString("listed_at")));
        for (JSONObject item : items) {
            if (out.size() >= WATCHLIST_MAX) break;
            String key = key(item, type, lists);
            if (key != null && !out.contains(key)) {
                out.add(key);
                String t = lists.titles.get(key);
                if (t != null && !t.isEmpty() && !lists.watchlistTitles.contains(t)) {
                    lists.watchlistTitles.add(t);
                    if (lists.tasteTitles.size() < 60 && !lists.tasteTitles.contains(t)) {
                        lists.tasteTitles.add(t + " (en mi Watchlist)");
                    }
                }
            }
        }
    }

    /**
     * {"movie": {"ids": {"tmdb": 603}}} → "movie:603"; Trakt's "show" is TMDB's "tv". The recommendations come without
     * the wrapper ({"ids": …}). Null without a TMDB id.
     */
    private static String key(JSONObject item, String type, Lists lists) {
        JSONObject o = item == null ? null : item.has(type) ? item.optJSONObject(type) : item;
        JSONObject ids = o == null ? null : o.optJSONObject("ids");
        int tmdb = ids == null ? 0 : ids.optInt("tmdb");
        if (tmdb <= 0) return null;
        String k = (type.equals("show") ? "tv" : "movie") + ":" + tmdb;
        if (lists != null) {
            String imdb = ids.optString("imdb", "");
            String title = o.optString("title", "");
            if (!imdb.isEmpty()) lists.imdbIds.put(k, imdb);
            if (!title.isEmpty()) lists.titles.put(k, title);
        }
        return k;
    }

    // MARK: - HTTP

    private static final class Response {
        int code;
        String body = "";
    }

    private static String clientId() {
        return Config.TRAKT_CLIENT_ID.trim();
    }

    private static String base64Url(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String get(Context c, String path) throws Exception {
        return request("GET", path, null, token(c), 200);
    }

    private interface Parser {
        void read(JsonReader r) throws IOException;
    }

    /** A GET read as it arrives, without the whole body in memory. */
    private static void stream(Context c, String path, Parser parser) throws Exception {
        HttpURLConnection h = open("GET", path, token(c));
        try {
            int code = h.getResponseCode();
            if (code == 401) throw new IOException("Trakt refused the key or the sign-in");
            if (code != 200) throw new IOException("Trakt returned HTTP " + code);
            try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(h.getInputStream()), StandardCharsets.UTF_8))) {
                parser.read(r);
            }
        } finally {
            h.disconnect();
        }
    }

    private static String request(String method, String path, JSONObject body, String token, int want) throws IOException {
        Response r = send(method, path, body, token);
        if (r.code == 401) throw new IOException("Trakt refused the key or the sign-in");
        if (r.code != want) throw new IOException("Trakt returned HTTP " + r.code);
        return r.body;
    }

    private static HttpURLConnection open(String method, String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // HttpURLConnection keeps custom headers across hosts: the token must not follow a redirect
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId());
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static Response send(String method, String path, JSONObject body, String token) throws IOException {
        HttpURLConnection c = open(method, path, token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = s.read(buffer)) > 0) bytes.write(buffer, 0, n);
                    r.body = bytes.toString(StandardCharsets.UTF_8.name());
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }
}
