package org.evilproject.evilkaraoke.paper.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsServiceTest {
    private final Logger logger = Logger.getLogger("test");

    @Test
    void recordsRequestsAndPlaysThenRanks(@TempDir Path dir) {
        StatsService service = new StatsService(logger, dir.resolve("stats.json"));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        service.recordRequest(alice, "Alice", "song-1", "First");
        service.recordPlay(alice, "Alice", "song-1", "First", 120);
        service.recordRequest(bob, "Bob", "song-1", "First");
        service.recordPlay(bob, "Bob", "song-2", "Second", 30);

        List<SongStats> topSongs = service.topSongsByPlays(5);
        assertEquals("song-1", topSongs.getFirst().songId());
        assertEquals(2, service.topSongsByRequests(5).getFirst().timesRequested());

        List<UserStats> topByTime = service.topUsersByTime(5);
        assertEquals("Alice", topByTime.getFirst().playerName());

        StatsService.ServerStats server = service.serverStats();
        assertEquals(150, server.totalListenSeconds());
        assertEquals(2, server.totalSongsPlayed());
    }

    @Test
    void persistsAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("stats.json");
        StatsService first = new StatsService(logger, file);
        UUID alice = UUID.randomUUID();
        first.recordPlay(alice, "Alice", "song-1", "First", 90);
        first.save();

        StatsService second = new StatsService(logger, file);
        second.load();
        assertEquals(90, second.user(alice, "Alice").listenSeconds());
    }
}
