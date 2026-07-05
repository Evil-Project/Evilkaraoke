package org.evilproject.evilkaraoke.server.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsServiceTest {
    private final Logger logger = Logger.getLogger("test");

    @Test
    void findsStoredUserStatsByNameIgnoringCase(@TempDir Path dir) {
        StatsService service = new StatsService(logger, dir.resolve("stats.json"));
        UUID alex = UUID.randomUUID();

        service.recordRequest(alex, "Alex", "song-1", "First");

        UserStats stats = service.findUserByName("alex").orElseThrow();
        assertEquals(alex, stats.playerId());
        assertEquals(1, stats.songsRequested());
        assertTrue(service.findUserByName("missing").isEmpty());
    }
}
