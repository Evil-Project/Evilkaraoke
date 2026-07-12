package org.evilproject.evilkaraoke.client.lyrics;

import java.time.Duration;
import java.util.Objects;

public record TimedLyric(Duration offset, String text) {
    public TimedLyric {
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(text, "text");
        if (offset.isNegative()) {
            throw new IllegalArgumentException("offset cannot be negative");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
    }
}
