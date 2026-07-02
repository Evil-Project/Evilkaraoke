package org.evilproject.evilkaraoke.paper.api;

import org.bukkit.configuration.file.FileConfiguration;

public record NeurokaraokeEndpoints(
        int timeoutMillis,
        int retries,
        long retryDelayMillis,
        String randomUrl,
        String searchUrl,
        String songUrl,
        String playlistUrl,
        String artistUrl,
        String audioBaseUrl,
        String imagesBaseUrl
) {
    public static NeurokaraokeEndpoints from(FileConfiguration config) {
        return new NeurokaraokeEndpoints(
                config.getInt("api.timeoutMillis", 8000),
                config.getInt("api.retries", 3),
                config.getLong("api.retryDelayMillis", 2000L),
                config.getString("api.randomUrl"),
                config.getString("api.searchUrl"),
                config.getString("api.songUrl"),
                config.getString("api.playlistUrl"),
                config.getString("api.artistUrl"),
                config.getString("api.audioBaseUrl"),
                config.getString("api.imagesBaseUrl")
        );
    }
}
