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

public record EvilKaraokeServerConfig(NeurokaraokeEndpoints api, EvilKaraokeConfig playback) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static EvilKaraokeServerConfig defaults() {
        return new EvilKaraokeServerConfig(NeurokaraokeEndpoints.defaults(), EvilKaraokeConfig.defaults());
    }

    public static EvilKaraokeServerConfig loadOrCreate(Path file, Logger logger) {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                EvilKaraokeServerConfig defaults = defaults();
                defaults.save(file);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return new EvilKaraokeServerConfig(api(root.getAsJsonObject("api")), playback(root.getAsJsonObject("playback"), root.getAsJsonObject("stats"), root.getAsJsonObject("debug")));
            }
        } catch (RuntimeException | IOException ex) {
            logger.log(Level.WARNING, "Failed to load Evilkaraoke config, using defaults", ex);
            return defaults();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.add("api", GSON.toJsonTree(api).getAsJsonObject());
        JsonObject playbackObject = new JsonObject();
        playbackObject.addProperty("defaultTargets", playback.defaultTargets());
        playbackObject.addProperty("defaultSource", playback.defaultSource());
        playbackObject.addProperty("defaultVolume", playback.defaultVolume());
        playbackObject.addProperty("defaultPitch", playback.defaultPitch());
        playbackObject.addProperty("defaultMinVolume", playback.defaultMinVolume());
        playbackObject.addProperty("randomCacheSize", playback.randomCacheSize());
        playbackObject.addProperty("pauseBetweenSongsSeconds", playback.pauseBetweenSongsSeconds());
        playbackObject.addProperty("requireClientMod", playback.requireClientMod());
        playbackObject.addProperty("allowRadio", playback.allowRadio());
        root.add("playback", playbackObject);
        JsonObject stats = new JsonObject();
        stats.addProperty("enabled", playback.statsEnabled());
        stats.addProperty("saveIntervalSeconds", playback.statsSaveIntervalSeconds());
        root.add("stats", stats);
        JsonObject debug = new JsonObject();
        debug.addProperty("logPackets", playback.debugPackets());
        root.add("debug", debug);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
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

    private static EvilKaraokeConfig playback(JsonObject playback, JsonObject stats, JsonObject debug) {
        EvilKaraokeConfig defaults = EvilKaraokeConfig.defaults();
        return new EvilKaraokeConfig(
                stringValue(playback, "defaultTargets", defaults.defaultTargets()),
                stringValue(playback, "defaultSource", defaults.defaultSource()),
                floatValue(playback, "defaultVolume", defaults.defaultVolume()),
                floatValue(playback, "defaultPitch", defaults.defaultPitch()),
                floatValue(playback, "defaultMinVolume", defaults.defaultMinVolume()),
                intValue(playback, "randomCacheSize", defaults.randomCacheSize()),
                longValue(playback, "pauseBetweenSongsSeconds", defaults.pauseBetweenSongsSeconds()),
                booleanValue(playback, "requireClientMod", defaults.requireClientMod()),
                booleanValue(playback, "allowRadio", defaults.allowRadio()),
                booleanValue(stats, "enabled", booleanValue(playback, "statsEnabled", defaults.statsEnabled())),
                intValue(stats, "saveIntervalSeconds", intValue(playback, "statsSaveIntervalSeconds", defaults.statsSaveIntervalSeconds())),
                booleanValue(debug, "logPackets", booleanValue(playback, "debugPackets", defaults.debugPackets())));
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
