package org.evilproject.evilkaraoke.server.api;

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
}
