package io.freemytv;

import java.nio.charset.StandardCharsets;

public final class GeminiVoiceTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int leShort(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static void main(String[] args) {
        // 1. Speech sanitation preserves natural Spanish phrasing while stripping emojis and markdown.
        check("Te recomiendo Severance en Apple TV, te va a encantar."
                .equals(GeminiAi.cleanForSpeech("✨ **Te recomiendo** _Severance_ en Apple TV 🎬, te va a encantar.")),
                "cleanForSpeech failed to strip emojis and markdown cleanly");
        check("¿Qué quieres ver hoy?"
                .equals(GeminiAi.cleanForSpeech("  # ¿Qué   quieres ver hoy?  `🍿` ")),
                "cleanForSpeech failed to normalize whitespace and punctuation");
        check("".equals(GeminiAi.cleanForSpeech(null)), "null input should return empty string");

        // 2. Action normalization and Home tab mapping across voice variants.
        check(GeminiAi.ACTION_SHOW_TV.equals(GeminiAi.normalizeAction("series")), "series -> show_tv");
        check(GeminiAi.ACTION_SHOW_TV.equals(GeminiAi.normalizeAction("SHOW_TV")), "SHOW_TV -> show_tv");
        check(GeminiAi.ACTION_SHOW_MOVIES.equals(GeminiAi.normalizeAction("peliculas")), "peliculas -> show_movies");
        check(GeminiAi.ACTION_SHOW_ALL.equals(GeminiAi.normalizeAction("explore")), "explore -> show_all");
        check(GeminiAi.ACTION_OPEN_APP.equals(GeminiAi.normalizeAction("launch")), "launch -> open_app");
        check(GeminiAi.ACTION_CHAT.equals(GeminiAi.normalizeAction("chat")), "chat -> chat");
        check(GeminiAi.ACTION_RECOMMEND.equals(GeminiAi.normalizeAction(null)), "null -> recommend");

        check(GeminiAi.homeTabForAction(GeminiAi.ACTION_SHOW_ALL) == 0, "show_all maps to tab 0");
        check(GeminiAi.homeTabForAction(GeminiAi.ACTION_SHOW_MOVIES) == 1, "show_movies maps to tab 1");
        check(GeminiAi.homeTabForAction(GeminiAi.ACTION_SHOW_TV) == 2, "show_tv maps to tab 2");
        check(GeminiAi.homeTabForAction(GeminiAi.ACTION_RECOMMEND) == -1, "recommend has no tab override");

        // 3. Markdown/prose JSON unwrapping.
        String wrapped = "Claro:\n```json\n{\"action\":\"show_tv\",\"spoken\":\"Aquí tienes tus series.\"}\n```";
        String unwrapped = GeminiAi.stripMarkdownJson(wrapped);
        check(unwrapped.startsWith("{") && unwrapped.endsWith("}") && unwrapped.contains("\"show_tv\""),
                "stripMarkdownJson failed to isolate object: " + unwrapped);

        // 4. RIFF/WAVE header correctness for 16 kHz mono 16-bit PCM fallback.
        byte[] pcm = new byte[] {0x12, 0x34, (byte) 0xAB, (byte) 0xCD, 0x01, 0x02};
        byte[] wav = GeminiAi.encodeWav(pcm, 16000);
        check(wav.length == 44 + pcm.length, "WAV total length mismatch");
        check("RIFF".equals(new String(wav, 0, 4, StandardCharsets.US_ASCII)), "missing RIFF magic");
        check(leInt(wav, 4) == 36 + pcm.length, "RIFF chunk size mismatch");
        check("WAVE".equals(new String(wav, 8, 4, StandardCharsets.US_ASCII)), "missing WAVE header");
        check("fmt ".equals(new String(wav, 12, 4, StandardCharsets.US_ASCII)), "missing fmt chunk");
        check(leShort(wav, 20) == 1, "audio format must be PCM (1)");
        check(leShort(wav, 22) == 1, "channel count must be mono (1)");
        check(leInt(wav, 24) == 16000, "sample rate mismatch");
        check(leInt(wav, 28) == 32000, "byte rate mismatch");
        check(leShort(wav, 32) == 2, "block align mismatch");
        check(leShort(wav, 34) == 16, "bits per sample mismatch");
        check("data".equals(new String(wav, 36, 4, StandardCharsets.US_ASCII)), "missing data header");
        check(leInt(wav, 40) == pcm.length, "data length mismatch");
        for (int i = 0; i < pcm.length; i++) {
            check(wav[44 + i] == pcm[i], "PCM payload byte mismatch at " + i);
        }

        System.out.println("GeminiVoiceTest: " + assertions + " assertions passed.");
    }
}
