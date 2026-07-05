package org.evilproject.evilkaraoke.server.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.server.core.EvilkaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvilkaraokeCommandServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void suggestsRequestModes() {
        assertEquals(List.of("id", "url"), EvilkaraokeCommandService.suggest(new String[] {"request", ""}));
        assertEquals(List.of("url"), EvilkaraokeCommandService.suggest(new String[] {"request", "u"}));
    }

    @Test
    void suggestsQueueMove() {
        assertEquals(List.of("move", "1"), EvilkaraokeCommandService.suggest(new String[] {"queue", ""}));
        assertEquals(List.of("move"), EvilkaraokeCommandService.suggest(new String[] {"queue", "m"}));
    }

    @Test
    void suggestsCancelAll() {
        assertEquals(List.of("all"), EvilkaraokeCommandService.suggest(new String[] {"cancel", ""}));
        assertEquals(List.of("all"), EvilkaraokeCommandService.suggest(new String[] {"cancel", "a"}));
    }

    @Test
    void suggestsCollectionAddArguments() {
        assertEquals(List.of("add", "1"), EvilkaraokeCommandService.suggest(new String[] {"playlist", ""}));
        assertEquals(List.of("add"), EvilkaraokeCommandService.suggest(new String[] {"setlist", "a"}));
        assertEquals(List.of("1", "2", "3", "4", "5"), EvilkaraokeCommandService.suggest(new String[] {"playlist", "add", ""}));
        assertEquals(List.of("1", "2", "3", "4", "5"), EvilkaraokeCommandService.suggest(new String[] {"setlist", "add", "2", ""}));
    }

    @Test
    void suggestsStatsArguments() {
        assertEquals(List.of("me", "user", "server", "top"), EvilkaraokeCommandService.suggest(new String[] {"stats", ""}));
        assertEquals(List.of("users", "songs"), EvilkaraokeCommandService.suggest(new String[] {"stats", "top", ""}));
        assertEquals(List.of("played", "requested"), EvilkaraokeCommandService.suggest(new String[] {"stats", "top", "songs", ""}));
        assertEquals(List.of("time", "song-count", "request-count"), EvilkaraokeCommandService.suggest(new String[] {"stats", "top", "users", ""}));
    }

    @Test
    void suggestionArgsKeepTrailingEmptyToken() {
        assertArrayEquals(new String[] {"request", ""}, EvilkaraokeCommandService.splitArgsForSuggestions("request "));
        assertArrayEquals(new String[] {"request", "u"}, EvilkaraokeCommandService.splitArgsForSuggestions("request u"));
        assertArrayEquals(new String[] {"queue", "move", ""}, EvilkaraokeCommandService.splitArgsForSuggestions("queue move "));
    }

    @Test
    void suggestionTokenStartUsesCurrentTokenOffset() {
        assertEquals(0, EvilkaraokeCommandService.suggestionTokenStart(""));
        assertEquals(0, EvilkaraokeCommandService.suggestionTokenStart("que"));
        assertEquals(6, EvilkaraokeCommandService.suggestionTokenStart("queue "));
        assertEquals(6, EvilkaraokeCommandService.suggestionTokenStart("queue m"));
        assertEquals(11, EvilkaraokeCommandService.suggestionTokenStart("queue move "));
    }

    @Test
    void dynamicSuggestionsUseQueueAndPlayerState() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        FakePlatform platform = new FakePlatform(List.of(steve, alex));
        EvilkaraokeServerCore core = new EvilkaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            core.coordinator().request(track("current"), steve).join();
            core.coordinator().request(track("one"), steve).join();
            core.coordinator().request(track("two"), steve).join();
            core.coordinator().request(track("other"), alex).join();

            EvilkaraokeCommandService service = new EvilkaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.queue", "evilkaraoke.command.cancel"));

            assertEquals(List.of("1", "2"), service.suggest(actor, new String[] {"queue", "move", ""}));
            assertEquals(List.of("2"), service.suggest(actor, new String[] {"queue", "move", "1", ""}));
            assertEquals(List.of("all", "1", "2"), service.suggest(actor, new String[] {"cancel", ""}));
            assertEquals(List.of("Alex"), service.suggest(actor, new String[] {"audience", "a"}));
            assertEquals(List.of("Steve"), service.suggest(actor, new String[] {"stats", "user", "s"}));
        } finally {
            core.disable();
        }
    }

    @Test
    void statsMeShowsPermissionGroup() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilkaraokeServerCore core = new EvilkaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            EvilkaraokeCommandService service = new EvilkaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.stats"), "vip");

            service.execute(actor, "ek", new String[] {"stats", "me"});

            assertTrue(actor.messages().contains("Permission group: vip"));
        } finally {
            core.disable();
        }
    }

    @Test
    void statsUserFallsBackToStoredStatsForOfflinePlayer() {
        KaraokePlayer steve = new KaraokePlayer(UUID.randomUUID(), "Steve");
        FakePlatform platform = new FakePlatform(List.of(steve));
        EvilkaraokeServerCore core = new EvilkaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            UUID alex = UUID.randomUUID();
            core.statsService().recordRequest(alex, "Alex", "song-a", "Song A");
            EvilkaraokeCommandService service = new EvilkaraokeCommandService(core);
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
        EvilkaraokeServerCore core = new EvilkaraokeServerCore(Logger.getLogger("test"), tempDir, platform);
        core.enable();
        try {
            for (int i = 0; i < 6; i++) {
                core.coordinator().request(track("song-" + i), steve).join();
            }
            EvilkaraokeCommandService service = new EvilkaraokeCommandService(core);
            TestActor actor = new TestActor(steve, List.of("evilkaraoke.command.request"));

            service.execute(actor, "ek", new String[] {"request"});

            assertTrue(actor.messages().contains("Usage: /ek request <query> | /ek request id <songId> | /ek request url <https://...> [title]"));
        } finally {
            core.disable();
        }
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
        public void sendAudio(KaraokePlayer player, AudioCommandPacket packet) {
        }

        @Override
        public void log(Level level, String message, Throwable error) {
        }
    }
}
