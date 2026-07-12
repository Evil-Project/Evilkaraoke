package org.evilproject.evilkaraoke.server.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.core.EvilKaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;

class EvilKaraokeCommandServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void suggestsRequestModes() {
        assertEquals(List.of("id", "url"), EvilKaraokeCommandService.suggest(new String[] {"request", ""}));
        assertEquals(List.of("url"), EvilKaraokeCommandService.suggest(new String[] {"request", "u"}));
    }

    @Test
    void suggestsLyricsActions() {
        assertEquals(List.of("enable", "disable"), EvilKaraokeCommandService.suggest(new String[] {"lyrics", ""}));
        assertEquals(List.of("enable"), EvilKaraokeCommandService.suggest(new String[] {"lyrics", "e"}));
        assertEquals(List.of("disable"), EvilKaraokeCommandService.suggest(new String[] {"lyrics", "D"}));
    }

    @Test
    void suggestsRandomSongQueueActions() {
        assertEquals(List.of("queue", "1"), EvilKaraokeCommandService.suggest(new String[] {"randomsong", ""}));
        assertEquals(List.of("all", "1", "2", "3", "4", "5"), EvilKaraokeCommandService.suggest(new String[] {"randomsong", "queue", ""}));
        assertEquals(List.of("all"), EvilKaraokeCommandService.suggest(new String[] {"randomsong", "queue", "a"}));
    }

    @Test
    void suggestsQueueMove() {
        assertEquals(List.of("move", "cancel", "random", "loop", "previous", "next", "pause", "resume", "stop", "1"), EvilKaraokeCommandService.suggest(new String[] {"queue", ""}));
        assertEquals(List.of("cancel"), EvilKaraokeCommandService.suggest(new String[] {"queue", "c"}));
        assertEquals(List.of("move"), EvilKaraokeCommandService.suggest(new String[] {"queue", "m"}));
        assertEquals(List.of("loop"), EvilKaraokeCommandService.suggest(new String[] {"queue", "l"}));
        assertEquals(List.of("next"), EvilKaraokeCommandService.suggest(new String[] {"queue", "n"}));
        assertEquals(List.of("previous", "pause"), EvilKaraokeCommandService.suggest(new String[] {"queue", "p"}));
        assertEquals(List.of("random", "resume"), EvilKaraokeCommandService.suggest(new String[] {"queue", "r"}));
        assertEquals(List.of("stop"), EvilKaraokeCommandService.suggest(new String[] {"queue", "s"}));
    }

    @Test
    void suggestsCancelAll() {
        assertEquals(List.of("all"), EvilKaraokeCommandService.suggest(new String[] {"queue", "cancel", ""}));
        assertEquals(List.of("all"), EvilKaraokeCommandService.suggest(new String[] {"queue", "cancel", "a"}));
    }

    @Test
    void suggestsCollectionAddArguments() {
        assertEquals(List.of("add", "1"), EvilKaraokeCommandService.suggest(new String[] {"playlist", ""}));
        assertEquals(List.of("add"), EvilKaraokeCommandService.suggest(new String[] {"setlist", "a"}));
        assertEquals(List.of("1", "2", "3", "4", "5"), EvilKaraokeCommandService.suggest(new String[] {"playlist", "add", ""}));
        assertEquals(List.of("1", "2", "3", "4", "5"), EvilKaraokeCommandService.suggest(new String[] {"setlist", "add", "2", ""}));
    }

    @Test
    void suggestsStatsArguments() {
        assertEquals(List.of("me", "user", "server", "top"), EvilKaraokeCommandService.suggest(new String[] {"stats", ""}));
        assertEquals(List.of("users", "songs"), EvilKaraokeCommandService.suggest(new String[] {"stats", "top", ""}));
        assertEquals(List.of("played", "requested"), EvilKaraokeCommandService.suggest(new String[] {"stats", "top", "songs", ""}));
        assertEquals(List.of("time", "song-count", "request-count"), EvilKaraokeCommandService.suggest(new String[] {"stats", "top", "users", ""}));
    }

    @Test
    void suggestionArgsKeepTrailingEmptyToken() {
        assertArrayEquals(new String[] {"request", ""}, EvilKaraokeCommandService.splitArgsForSuggestions("request "));
        assertArrayEquals(new String[] {"request", "u"}, EvilKaraokeCommandService.splitArgsForSuggestions("request u"));
        assertArrayEquals(new String[] {"queue", "move", ""}, EvilKaraokeCommandService.splitArgsForSuggestions("queue move "));
    }

    @Test
    void suggestionTokenStartUsesCurrentTokenOffset() {
        assertEquals(0, EvilKaraokeCommandService.suggestionTokenStart(""));
        assertEquals(0, EvilKaraokeCommandService.suggestionTokenStart("que"));
        assertEquals(6, EvilKaraokeCommandService.suggestionTokenStart("queue "));
        assertEquals(6, EvilKaraokeCommandService.suggestionTokenStart("queue m"));
        assertEquals(11, EvilKaraokeCommandService.suggestionTokenStart("queue move "));
    }

    @Test
    void dynamicSuggestionsUseQueueAndPlayerState() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        FakePlatform platform = new FakePlatform(List.of(steve, alex));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            core.coordinator().request(track("one"), steve).join();
            core.coordinator().request(track("two"), steve).join();
            core.coordinator().request(track("other"), alex).join();

            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.move",
                    "evilkaraoke.command.queue.cancel"));

            assertEquals(List.of("1", "2"), service.suggest(actor, new String[] {"queue", "move", ""}));
            assertEquals(List.of("2"), service.suggest(actor, new String[] {"queue", "move", "1", ""}));
            assertEquals(List.of("all", "1", "2"), service.suggest(actor, new String[] {"queue", "cancel", ""}));
            assertEquals(List.of("Alex"), service.suggest(actor, new String[] {"audience", "a"}));
            assertEquals(List.of("Steve"), service.suggest(actor, new String[] {"stats", "user", "s"}));
        } finally {
            core.disable();
        }
    }

    @Test
    void lyricsRequiresACompatibleClientAndValidAction() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of());

            service.execute(actor, "ek", new String[] {"lyrics"});
            assertEquals(List.of("You need an updated Evilkaraoke client mod to see lyric captions."), actor.messages());

            core.clientRegistry().register(steve.id(), new org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket(
                    org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol.VERSION,
                    "test", "26.2", "test", List.of("opus"), false, true, true, false));
            actor.messages().clear();
            assertEquals(0, service.execute(actor, "ek", new String[] {"lyrics", "enable"}));
            assertEquals(List.of("You need an updated Evilkaraoke client mod to see lyric captions."), actor.messages());

            core.clientRegistry().register(steve.id(), new org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket(
                    org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol.VERSION,
                    "test", "26.2", "test", List.of("opus"), false, true, true, true));
            actor.messages().clear();
            assertEquals(1, service.execute(actor, "ek", new String[] {"lyrics"}));
            assertEquals(List.of(), actor.messages());

            assertEquals(1, service.execute(actor, "ek", new String[] {"lyrics", "ENABLE"}));
            assertEquals(1, service.execute(actor, "ek", new String[] {"lyrics", "disable"}));

            assertEquals(0, service.execute(actor, "ek", new String[] {"lyrics", "invalid"}));
            assertEquals(0, service.execute(actor, "ek", new String[] {"lyrics", "enable", "extra"}));
            assertEquals(List.of(
                    "Usage: /ek lyrics [enable|disable]",
                    "Usage: /ek lyrics [enable|disable]"), actor.messages());
        } finally {
            core.disable();
        }
    }

    @Test
    void statsMeShowsPermissionGroup() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.stats"), "vip");

            service.execute(actor, "ek", new String[] {"stats", "me"});

            assertTrue(actor.messages().contains("Permission group: vip"));
        } finally {
            core.disable();
        }
    }

    @Test
    void queueShowsCoveredByOnSongLines() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(coveredTrack("current", "Current Song", "Original Artist", "Neuro & Evil"), steve).join();
            core.coordinator().request(coveredTrack("queued", "Queued Song", "Original Artist", "Neuro & Evil"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.queue"));

            service.execute(actor, "ek", new String[] {"queue"});

            assertTrue(actor.messages().contains("Now playing: Current Song - Original Artist (covered by Neuro & Evil) (by Steve) | Elapsed: 0:00"));
            assertTrue(actor.messages().contains("Controls: [Previous] [Next] [Random: Off] [Loop: Off]"));
            assertTrue(actor.messages().contains("1. Queued Song - Original Artist (covered by Neuro & Evil) (by Steve) [Loop 1] [Cancel]"));
            assertEquals("Controls: [Previous] [Next] [Random: Off] [Loop: Off]", actor.messages().getLast());
        } finally {
            core.disable();
        }
    }

    @Test
    void queueShowsPlaybackButtonsForCurrentSong() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.pause",
                    "evilkaraoke.command.queue.resume",
                    "evilkaraoke.command.queue.stop"));

            service.execute(actor, "ek", new String[] {"queue"});
            service.execute(actor, "ek", new String[] {"queue", "pause"});
            service.execute(actor, "ek", new String[] {"queue"});

            assertTrue(actor.messages().contains("Now playing: Song current - Artist (by Steve) | Elapsed: 0:00 [Pause] [Stop]"));
            assertTrue(actor.messages().contains("Now playing: Song current - Artist (by Steve) | Elapsed: 0:00 [Resume] [Stop]"));
            assertTrue(actor.messages().contains("Controls: [Previous] [Next] [Random: Off] [Loop: Off]"));
        } finally {
            core.disable();
        }
    }

    @Test
    void queueModeCommandsToggleRandomLoopAndSingleLoop() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            core.coordinator().request(track("queued"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.random",
                    "evilkaraoke.command.queue.loop"));

            service.execute(actor, "ek", new String[] {"queue", "random"});
            service.execute(actor, "ek", new String[] {"queue", "loop"});
            service.execute(actor, "ek", new String[] {"queue", "loop", "1"});
            service.execute(actor, "ek", new String[] {"queue"});

            assertTrue(actor.messages().contains("Random queue order shuffled."));
            assertTrue(actor.messages().contains("Queue loop enabled."));
            assertTrue(actor.messages().contains("Single-song loop enabled: Song queued - Artist"));
            assertTrue(actor.messages().contains("Controls: [Previous] [Next] [Random: On] [Loop: On]"));
            assertTrue(actor.messages().contains("1. Song queued - Artist (by Steve) [Loop 1: On] [Cancel]"));
            assertEquals("Controls: [Previous] [Next] [Random: On] [Loop: On]", actor.messages().getLast());
        } finally {
            core.disable();
        }
    }

    @Test
    void queueCancelRemovesQueuedSong() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            core.coordinator().request(track("queued"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.cancel"));

            service.execute(actor, "ek", new String[] {"queue", "cancel", "1"});

            assertTrue(actor.messages().contains("Removed from queue: Song queued - Artist"));
            assertEquals(0, core.coordinator().queue().size());
        } finally {
            core.disable();
        }
    }

    @Test
    void emptyQueueNavigationCommandsExplainWhyNothingChanged() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.queue"));

            service.execute(actor, "ek", new String[] {"queue", "next"});
            service.execute(actor, "ek", new String[] {"queue", "previous"});

            assertTrue(actor.messages().contains("Nothing is playing and the queue is empty."));
            assertTrue(actor.messages().contains("No previous track is available."));
        } finally {
            core.disable();
        }
    }

    @Test
    void queueRefreshTokenRedrawsUiWithoutConfirmationMessage() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.random"));

            service.execute(actor, "ek", new String[] {"queue", "random", "--refresh=1"});

            assertFalse(actor.messages().contains("Random queue order shuffled."));
            assertTrue(actor.messages().contains("Queue (page 1/1)"));
            assertEquals("Controls: [Previous] [Next] [Random: On] [Loop: Off]", actor.messages().getLast());
        } finally {
            core.disable();
        }
    }

    @Test
    void queueStopRefreshStopsCurrentPlaybackWithoutCancellingUpcomingQueue() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            core.coordinator().request(track("queued"), steve).join();
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.stop"));

            service.execute(actor, "ek", new String[] {"queue", "stop", "--refresh=1"});

            assertFalse(actor.messages().contains("Playback stopped."));
            assertEquals(PlaybackState.IDLE, core.coordinator().snapshot().state());
            assertEquals(List.of("queued"), core.coordinator().snapshot().requests().stream()
                    .map(queued -> queued.track().id())
                    .toList());
            assertTrue(actor.messages().contains("Queue (page 1/1)"));
            assertTrue(actor.messages().contains("1. Song queued - Artist (by Steve) [Loop 1] [Cancel]"));
        } finally {
            core.disable();
        }
    }

    @Test
    void rootControlAliasesAreNotAccepted() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of(
                    "evilkaraoke.command.queue",
                    "evilkaraoke.command.queue.cancel",
                    "evilkaraoke.command.queue.pause",
                    "evilkaraoke.command.queue.resume",
                    "evilkaraoke.command.queue.stop"));

            service.execute(actor, "ek", new String[] {"pause"});
            service.execute(actor, "ek", new String[] {"resume"});
            service.execute(actor, "ek", new String[] {"stop"});
            service.execute(actor, "ek", new String[] {"cancel", "all"});
            service.execute(actor, "ek", new String[] {"previous"});
            service.execute(actor, "ek", new String[] {"next"});

            assertEquals(List.of(
                    "Unknown Evilkaraoke subcommand. Try /ek help.",
                    "Unknown Evilkaraoke subcommand. Try /ek help.",
                    "Unknown Evilkaraoke subcommand. Try /ek help.",
                    "Unknown Evilkaraoke subcommand. Try /ek help.",
                    "Unknown Evilkaraoke subcommand. Try /ek help.",
                    "Unknown Evilkaraoke subcommand. Try /ek help."),
                    actor.messages());
        } finally {
            core.disable();
        }
    }

    @Test
    void statsUserFallsBackToStoredStatsForOfflinePlayer() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            UUID alex = UUID.randomUUID();
            core.statsService().recordRequest(alex, "Alex", "song-a", "Song A");
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.stats"));

            service.execute(actor, "ek", new String[] {"stats", "user", "alex"});

            assertTrue(actor.messages().contains("Evilkaraoke stats for Alex"));
            assertTrue(actor.messages().contains("Songs requested: 1"));
        } finally {
            core.disable();
        }
    }

    @Test
    void requestDoesNotEnforcePerUserQueueLimit() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            for (int i = 0; i < 6; i++) {
                core.coordinator().request(track("song-" + i), steve).join();
            }
            EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.request"));

            service.execute(actor, "ek", new String[] {"request"});

            assertTrue(actor.messages().contains("Usage: /ek request <query> | /ek request id <songId> | /ek request url <https://...> [title]"));
        } finally {
            core.disable();
        }
    }

    @Test
    void randomSongShowsPlaylistAndQueuesSelectedRows() throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/random", exchange -> {
            byte[] response = """
                    [{
                      "id": "random-1",
                      "title": "First Random",
                      "originalArtists": ["Artist One"],
                      "coverArtists": ["Neuro"],
                      "opus": "first.ogg"
                    }, {
                      "id": "random-2",
                      "title": "Second Random",
                      "originalArtists": ["Artist Two"],
                      "coverArtists": ["Evil"],
                      "opus": "second.ogg"
                    }, {
                      "id": "random-3",
                      "title": "Third Random",
                      "originalArtists": ["Artist Three"],
                      "coverArtists": ["Neuro"],
                      "opus": "third.ogg"
                    }, {
                      "id": "random-4",
                      "title": "Fourth Random",
                      "originalArtists": ["Artist Four"],
                      "coverArtists": ["Evil"],
                      "opus": "fourth.ogg"
                    }, {
                      "id": "random-5",
                      "title": "Fifth Random",
                      "originalArtists": ["Artist Five"],
                      "coverArtists": ["Neuro"],
                      "opus": "fifth.ogg"
                    }, {
                      "id": "random-6",
                      "title": "Sixth Random",
                      "originalArtists": ["Artist Six"],
                      "coverArtists": ["Evil"],
                      "opus": "sixth.ogg"
                    }]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(tempDir.resolve("evilkaraoke.json"), """
                    {
                      "api": {
                        "timeoutMillis": 1000,
                        "retries": 0,
                        "retryDelayMillis": 0,
                        "randomUrl": "http://localhost:%d/random",
                        "audioBaseUrl": "https://audio.example/"
                      },
                      "playback": {
                        "randomCacheSize": 2,
                        "requireClientMod": false
                      }
                    }
                    """.formatted(server.getAddress().getPort()), StandardCharsets.UTF_8);
            KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
            KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
            FakePlatform platform = new FakePlatform(List.of(steve, alex));
            EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
            core.enable();
            try {
                EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
                TestActor actor = new TestActor(steve, List.of(
                        "evilkaraoke.command.randomsong",
                        "evilkaraoke.command.request"));

                service.execute(actor, "ek", new String[] {"randomsong"});
                waitUntil(() -> actor.messages().size() >= 7);

                assertEquals("Random songs (page 1/2): [Queue All]", actor.messages().get(0));
                assertEquals("1. First Random - Artist One (covered by Neuro) [Queue]", actor.messages().get(1));
                assertEquals("2. Second Random - Artist Two (covered by Evil) [Queue]", actor.messages().get(2));
                assertEquals("5. Fifth Random - Artist Five (covered by Neuro) [Queue]", actor.messages().get(5));
                assertEquals("[Next]", actor.messages().get(6));
                assertTrue(core.coordinator().snapshot().current() == null);
                assertTrue(core.coordinator().snapshot().requests().isEmpty());

                service.execute(actor, "ek", new String[] {"randomsong", "2"});
                assertTrue(actor.messages().contains("Random songs (page 2/2): [Queue All]"));
                assertTrue(actor.messages().contains("6. Sixth Random - Artist Six (covered by Evil) [Queue]"));

                service.execute(actor, "ek", new String[] {"randomsong", "queue", "6"});

                assertTrue(actor.messages().contains("Queued random song: Sixth Random - Artist Six (covered by Evil)"));
                assertNotNull(core.coordinator().snapshot().current());
                assertEquals("random-6", core.coordinator().snapshot().current().track().id());

                TestActor alexActor = new TestActor(alex, List.of(
                        "evilkaraoke.command.randomsong",
                        "evilkaraoke.command.request"));
                service.execute(alexActor, "ek", new String[] {"randomsong"});
                waitUntil(() -> alexActor.messages().size() >= 7);
                service.execute(alexActor, "ek", new String[] {"randomsong", "queue", "all"});

                assertTrue(alexActor.messages().contains("Queued 6 random song(s)."));
                assertEquals(List.of("random-1", "random-2", "random-3", "random-4", "random-5", "random-6"), core.coordinator().snapshot().requests().stream()
                        .map(queued -> queued.track().id())
                        .toList());
            } finally {
                core.disable();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchQueueAllQueuesLatestShownPage() throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            byte[] response = """
                    {
                      "items": [{
                        "id": "search-1",
                        "title": "First Search",
                        "originalArtists": ["Artist One"],
                        "coverArtists": ["Neuro"],
                        "opus": "first.ogg"
                      }, {
                        "id": "search-2",
                        "title": "Second Search",
                        "originalArtists": ["Artist Two"],
                        "coverArtists": ["Evil"],
                        "opus": "second.ogg"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(tempDir.resolve("evilkaraoke.json"), """
                    {
                      "api": {
                        "timeoutMillis": 1000,
                        "retries": 0,
                        "retryDelayMillis": 0,
                        "searchUrl": "http://localhost:%d/search",
                        "audioBaseUrl": "https://audio.example/"
                      },
                      "playback": {
                        "requireClientMod": false
                      }
                    }
                    """.formatted(server.getAddress().getPort()), StandardCharsets.UTF_8);
            KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
            FakePlatform platform = new FakePlatform(List.of(steve));
            EvilKaraokeServerCore core = new EvilKaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
            core.enable();
            try {
                EvilKaraokeCommandService service = new EvilKaraokeCommandService(core);
                TestActor actor = new TestActor(steve, List.of(
                        "evilkaraoke.command.search",
                        "evilkaraoke.command.request"));

                service.execute(actor, "ek", new String[] {"search", "hello"});
                waitUntil(() -> actor.messages().size() >= 3);

                assertEquals("Results for \"hello\" (page 1): [Queue All]", actor.messages().get(0));
                assertEquals("1. First Search - Artist One (covered by Neuro) [Request]", actor.messages().get(1));
                assertEquals("2. Second Search - Artist Two (covered by Evil) [Request]", actor.messages().get(2));

                service.execute(actor, "ek", new String[] {"search", "queue-all"});

                assertTrue(actor.messages().contains("Queued 2 search result song(s)."));
                assertNotNull(core.coordinator().snapshot().current());
                assertEquals("search-1", core.coordinator().snapshot().current().track().id());
                assertEquals(List.of("search-2"), core.coordinator().snapshot().requests().stream()
                        .map(queued -> queued.track().id())
                        .toList());
            } finally {
                core.disable();
            }
        } finally {
            server.stop(0);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static KaraokeTrack track(String id) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                "Song " + id,
                "Artist",
                new AudioAsset("https://audio.example/" + id + ".opus", AudioFormat.OPUS),
                null,
                Duration.ofSeconds(60));
    }

    private static KaraokeTrack coveredTrack(String id, String title, String artist, String coverArtists) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                title,
                artist,
                coverArtists,
                new AudioAsset("https://audio.example/" + id + ".opus", AudioFormat.OPUS),
                null,
                Duration.ofSeconds(60));
    }

    private record TestActor(KaraokePlayer player, List<String> permissions, String group, List<String> messages) implements CommandActor {
        private TestActor(KaraokePlayer player, List<String> permissions) {
            this(player, permissions, "default");
        }

        private TestActor(KaraokePlayer player, List<String> permissions, String group) {
            this(player, permissions, group, new java.util.ArrayList<>());
        }

        @Override
        public boolean isPlayer() {
            return true;
        }

        @Override
        public UUID playerId() {
            return player.id();
        }

        @Override
        public String name() {
            return player.name();
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }
    }

    private static final class FakePlatform implements ServerPlaybackPlatform {
        private final List<KaraokePlayer> players;

        private FakePlatform(List<KaraokePlayer> players) {
            this.players = players;
        }

        @Override
        public void runNow(Runnable task) {
            task.run();
        }

        @Override
        public int runLater(Runnable task, long delayTicks) {
            return 1;
        }

        @Override
        public void cancelTask(int taskId) {
        }

        @Override
        public Collection<KaraokePlayer> onlinePlayers() {
            return players;
        }

        @Override
        public Optional<KaraokePlayer> player(UUID playerId) {
            return players.stream().filter(player -> player.id().equals(playerId)).findFirst();
        }

        @Override
        public Optional<KaraokePlayer> player(String exactName) {
            return players.stream().filter(player -> player.name().equals(exactName)).findFirst();
        }

        @Override
        public void sendAudio(KaraokePlayer player, ProtocolPacket packet) {
        }

        @Override
        public void log(Level level, String message, Throwable error) {
        }
    }
}
