package org.evilproject.evilkaraoke.common.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record KaraokeTrack(
        String id,
        TrackType type,
        String title,
        String artist,
        String coverArtists,
        AudioAsset primaryAsset,
        AudioAsset fallbackAsset,
        Duration duration
) {
    public KaraokeTrack(String id,
                        TrackType type,
                        String title,
                        String artist,
                        AudioAsset primaryAsset,
                        AudioAsset fallbackAsset,
                        Duration duration) {
        this(id, type, title, artist, null, primaryAsset, fallbackAsset, duration);
    }

    public KaraokeTrack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(primaryAsset, "primaryAsset");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        artist = artist == null || artist.isBlank() ? "Unknown Artist" : artist;
        coverArtists = coverArtists == null || coverArtists.isBlank() ? null : coverArtists;
    }

    public Optional<AudioAsset> fallback() {
        return Optional.ofNullable(fallbackAsset);
    }

    public Optional<String> coveredBy() {
        return Optional.ofNullable(coverArtists);
    }

    public Optional<Duration> finiteDuration() {
        return Optional.ofNullable(duration);
    }
}
