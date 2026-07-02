package org.evilproject.evilkaraoke.paper.config;

import java.time.Duration;

import org.bukkit.configuration.file.FileConfiguration;

public record EvilkaraokeConfig(
        String defaultTargets,
        String defaultSource,
        float defaultVolume,
        float defaultPitch,
        float defaultMinVolume,
        int randomCacheSize,
        Duration pauseBetweenSongs,
        boolean requireClientMod,
        boolean allowRadio,
        boolean statsEnabled,
        boolean debugPackets
) {
    public static EvilkaraokeConfig from(FileConfiguration config) {
        return new EvilkaraokeConfig(
                config.getString("playback.defaultTargets", "@a"),
                config.getString("playback.defaultSource", "music"),
                (float) config.getDouble("playback.defaultVolume", 1.0D),
                (float) config.getDouble("playback.defaultPitch", 1.0D),
                (float) config.getDouble("playback.defaultMinVolume", 0.0D),
                config.getInt("playback.randomCacheSize", 2),
                Duration.ofSeconds(config.getLong("playback.pauseBetweenSongsSeconds", 3L)),
                config.getBoolean("playback.requireClientMod", true),
                config.getBoolean("playback.allowRadio", true),
                config.getBoolean("stats.enabled", true),
                config.getBoolean("debug.logPackets", false)
        );
    }
}
