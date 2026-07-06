package org.evilproject.evilkaraoke.common.util;

import java.time.Duration;

public final class DurationFormatter {
    private DurationFormatter() {
    }

    public static String mmss(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return "%d:%02d".formatted(minutes, seconds);
    }
}
