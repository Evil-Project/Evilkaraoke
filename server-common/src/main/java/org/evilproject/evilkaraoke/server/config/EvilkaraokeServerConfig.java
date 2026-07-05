package org.evilproject.evilkaraoke.server.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeEndpoints;

public record EvilkaraokeServerConfig(NeurokaraokeEndpoints api, EvilkaraokeConfig playback) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static EvilkaraokeServerConfig defaults() {
        return new EvilkaraokeServerConfig(NeurokaraokeEndpoints.defaults(), EvilkaraokeConfig.defaults());
    }

    public static EvilkaraokeServerConfig loadOrCreate(Path file, Logger logger) {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                EvilkaraokeServerConfig defaults = defaults();
                defaults.save(file);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return new EvilkaraokeServerConfig(api(root.getAsJsonObject("api")), playback(root.getAsJsonObject("playback"), root.getAsJsonObject("stats"), root.getAsJsonObject("debug")));
            }
        } catch (RuntimeException | IOException ex) {
            logger.log(Level.WARNING, "Failed to load Evilkaraoke config, using defaults", ex);
            return defaults();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    private static NeurokaraokeEndpoints api(JsonObject object) {
        NeurokaraokeEndpoints defaults = NeurokaraokeEndpoints.defaults();
        if (object == null) {
            return defaults;
        }
        return new NeurokaraokeEndpoints(
                intValue(object, "timeoutMillis", defaults.timeoutMillis()),
                intValue(object, "retries", defaults.retries()),
                longValue(object, "retryDelayMillis", defaults.retryDelayMillis()),
                stringValue(object, "randomUrl", defaults.randomUrl()),
                stringValue(object, "searchUrl", defaults.searchUrl()),
                stringValue(object, "songUrl", defaults.songUrl()),
                stringValue(object, "playlistUrl", defaults.playlistUrl()),
                stringValue(object, "publicPlaylistUrl", defaults.publicPlaylistUrl()),
                stringValue(object, "publicPlaylistDetailUrl", defaults.publicPlaylistDetailUrl()),
                stringValue(object, "artistUrl", defaults.artistUrl()),
                stringValue(object, "audioBaseUrl", defaults.audioBaseUrl()),
                stringValue(object, "imagesBaseUrl", defaults.imagesBaseUrl()));
    }

    private static EvilkaraokeConfig playback(JsonObject playback, JsonObject stats, JsonObject debug) {
        EvilkaraokeConfig defaults = EvilkaraokeConfig.defaults();
        return new EvilkaraokeConfig(
                stringValue(playback, "defaultTargets", defaults.defaultTargets()),
                stringValue(playback, "defaultSource", defaults.defaultSource()),
                floatValue(playback, "defaultVolume", defaults.defaultVolume()),
                floatValue(playback, "defaultPitch", defaults.defaultPitch()),
                floatValue(playback, "defaultMinVolume", defaults.defaultMinVolume()),
                intValue(playback, "randomCacheSize", defaults.randomCacheSize()),
                longValue(playback, "pauseBetweenSongsSeconds", defaults.pauseBetweenSongsSeconds()),
                booleanValue(playback, "requireClientMod", defaults.requireClientMod()),
                booleanValue(playback, "allowRadio", defaults.allowRadio()),
                booleanValue(stats, "enabled", defaults.statsEnabled()),
                intValue(stats, "saveIntervalSeconds", defaults.statsSaveIntervalSeconds()),
                booleanValue(debug, "logPackets", defaults.debugPackets()));
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
    }

    private static float floatValue(JsonObject object, String key, float fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsFloat() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }
}
