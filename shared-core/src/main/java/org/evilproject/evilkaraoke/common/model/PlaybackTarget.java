package org.evilproject.evilkaraoke.common.model;

import java.util.Objects;

public record PlaybackTarget(
        TargetMode mode,
        String selector,
        SoundCategory category,
        Position position,
        float volume,
        float pitch,
        float minVolume
) {
    public PlaybackTarget {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(category, "category");
        selector = selector == null || selector.isBlank() ? "@a" : selector;
        if (volume < 0.0f) {
            throw new IllegalArgumentException("volume cannot be negative");
        }
        if (pitch <= 0.0f) {
            throw new IllegalArgumentException("pitch must be positive");
        }
        if (minVolume < 0.0f) {
            throw new IllegalArgumentException("minVolume cannot be negative");
        }
    }

    public static PlaybackTarget allPlayers() {
        return new PlaybackTarget(TargetMode.ALL, "@a", SoundCategory.MUSIC, null, 1.0f, 1.0f, 0.0f);
    }
}
