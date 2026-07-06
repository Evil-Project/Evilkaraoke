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
        String publicPlaylistUrl,
        String publicPlaylistDetailUrl,
        String artistUrl,
        String audioBaseUrl,
        String imagesBaseUrl
) {
    public static NeurokaraokeEndpoints defaults() {
        return new NeurokaraokeEndpoints(
                8000,
                3,
                2000L,
                "https://api.neurokaraoke.com/api/songs/random",
                "https://api.neurokaraoke.com/api/songs",
                "https://api.neurokaraoke.com/api/songs/",
                "https://api.neurokaraoke.com/api/playlist/",
                "https://api.neurokaraoke.com/api/playlist/public",
                "https://idk.neurokaraoke.com/public/playlist/",
                "https://api.neurokaraoke.com/api/artist/",
                "https://audio.neurokaraoke.com/",
                "https://images.neurokaraoke.com");
    }

    public static NeurokaraokeEndpoints from(FileConfiguration config) {
        NeurokaraokeEndpoints defaults = defaults();
        return new NeurokaraokeEndpoints(
                config.getInt("api.timeoutMillis", defaults.timeoutMillis()),
                config.getInt("api.retries", defaults.retries()),
                config.getLong("api.retryDelayMillis", defaults.retryDelayMillis()),
                config.getString("api.randomUrl", defaults.randomUrl()),
                config.getString("api.searchUrl", defaults.searchUrl()),
                config.getString("api.songUrl", defaults.songUrl()),
                config.getString("api.playlistUrl", defaults.playlistUrl()),
                config.getString("api.publicPlaylistUrl", defaults.publicPlaylistUrl()),
                config.getString("api.publicPlaylistDetailUrl", defaults.publicPlaylistDetailUrl()),
                config.getString("api.artistUrl", defaults.artistUrl()),
                config.getString("api.audioBaseUrl", defaults.audioBaseUrl()),
                config.getString("api.imagesBaseUrl", defaults.imagesBaseUrl())
        );
    }
}
