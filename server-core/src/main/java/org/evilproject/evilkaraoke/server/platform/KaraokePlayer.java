package org.evilproject.evilkaraoke.server.platform;

import java.util.Objects;
import java.util.UUID;

public record KaraokePlayer(UUID id, String name) {
    public KaraokePlayer {
        Objects.requireNonNull(id, "id");
        name = name == null || name.isBlank() ? id.toString() : name;
    }
}
