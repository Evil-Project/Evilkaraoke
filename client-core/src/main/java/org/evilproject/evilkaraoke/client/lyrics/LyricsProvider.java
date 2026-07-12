package org.evilproject.evilkaraoke.client.lyrics;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface LyricsProvider {
    CompletableFuture<List<TimedLyric>> lyrics(UUID songId);
}
