package org.evilproject.evilkaraoke.common.protocol;

import java.util.Locale;
import java.util.Optional;

public enum LyricsDisplayAction {
    TOGGLE("lyrics-toggle"),
    ENABLE("lyrics-enable"),
    DISABLE("lyrics-disable");

    private final String reason;

    LyricsDisplayAction(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    public boolean apply(boolean current) {
        return switch (this) {
            case TOGGLE -> !current;
            case ENABLE -> true;
            case DISABLE -> false;
        };
    }

    public static Optional<LyricsDisplayAction> parseCommandArgument(String argument) {
        if (argument == null || argument.isBlank()) {
            return Optional.of(TOGGLE);
        }
        return switch (argument.strip().toLowerCase(Locale.ROOT)) {
            case "enable" -> Optional.of(ENABLE);
            case "disable" -> Optional.of(DISABLE);
            default -> Optional.empty();
        };
    }

    public static Optional<LyricsDisplayAction> fromReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return Optional.empty();
        }
        for (LyricsDisplayAction action : values()) {
            if (action.reason.equalsIgnoreCase(reason.strip())) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
