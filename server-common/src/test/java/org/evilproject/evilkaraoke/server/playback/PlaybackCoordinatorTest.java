package org.evilproject.evilkaraoke.server.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeEndpoints;
import org.evilproject.evilkaraoke.server.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.server.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackCoordinatorTest {
    private FakePlatform platform;
    private PlaybackCoordinator coordinator;
    private KaraokePlayer player;

    @BeforeEach
    void setUp() {
        player = new KaraokePlayer(UUID.randomUUID(), "Steve");
        platform = new FakePlatform(player);
        coordinator = new PlaybackCoordinator(
                platform,
                new ClientRegistry(),
                new NeurokaraokeClient(Logger.getLogger("test"), NeurokaraokeEndpoints.defaults()),
                new EvilkaraokeConfig("@a", "music", 1.0f, 1.0f, 0.0f, 2, 3L, false, true, true, 60, false));
    }

    @Test
    void autoAdvanceIsScheduledFromFirstPlayingStatus() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();

        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        assertNotNull(play);
        FakePlatform.ScheduledTask fallback = platform.onlyScheduledTask();
        assertEquals(200L, fallback.delayTicks());

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));

        assertTrue(platform.cancelledTaskIds().contains(fallback.id()));
        FakePlatform.ScheduledTask autoAdvance = platform.onlyScheduledTask();
        assertTrue(autoAdvance.delayTicks() <= 1_400L && autoAdvance.delayTicks() >= 1_398L,
                "auto-advance should be track duration plus buffer from actual playback start");
    }

    @Test
    void staleStatusDoesNotStartAutoAdvance() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();

        FakePlatform.ScheduledTask fallback = platform.onlyScheduledTask();
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket("stale-playback", ClientPlaybackState.PLAYING, Duration.ZERO, ""));

        assertFalse(platform.cancelledTaskIds().contains(fallback.id()));
        assertEquals(fallback, platform.onlyScheduledTask());
        assertEquals(Duration.ZERO, coordinator.snapshot().offset());
    }

    @Test
    void stoppedStatusFromCurrentPlaybackAdvancesQueue() {
        coordinator.request(track("first", null), player).join();
        coordinator.request(track("second", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);
        assertNotNull(firstPlay);

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.STOPPED, Duration.ZERO, "Finished"));

        AudioCommandPacket secondPlay = platform.lastPacket(AudioCommandType.PLAY);
        assertNotNull(secondPlay);
        assertEquals("second", secondPlay.track().id());
        assertFalse(firstPlay.playbackId().equals(secondPlay.playbackId()));
    }

    @Test
    void fallbackStartsTimerWhenNoClientReportsPlaying() {
        coordinator.request(track("song", Duration.ofSeconds(5)), player).join();

        FakePlatform.ScheduledTask fallback = platform.onlyScheduledTask();
        platform.runScheduled(fallback.id());

        assertTrue(platform.cancelledTaskIds().contains(fallback.id()));
        FakePlatform.ScheduledTask autoAdvance = platform.onlyScheduledTask();
        assertTrue(autoAdvance.delayTicks() <= 300L && autoAdvance.delayTicks() >= 298L,
                "fallback should schedule duration plus buffer after the start timeout fires");
    }

    @Test
    void pauseCancelsAndResumeReschedulesAutoAdvanceWithSamePlaybackId() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        FakePlatform.ScheduledTask firstAutoAdvance = platform.onlyScheduledTask();

        coordinator.pause();

        assertTrue(platform.cancelledTaskIds().contains(firstAutoAdvance.id()));
        assertEquals(play.playbackId(), platform.lastPacket(AudioCommandType.PAUSE).playbackId());

        coordinator.resume();

        FakePlatform.ScheduledTask resumedAutoAdvance = platform.onlyScheduledTask();
        assertTrue(resumedAutoAdvance.delayTicks() <= 1_400L && resumedAutoAdvance.delayTicks() >= 1_398L);
        assertEquals(play.playbackId(), platform.lastPacket(AudioCommandType.RESUME).playbackId());
    }

    @Test
    void syncPlayerUsesCurrentPlaybackId() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        KaraokePlayer lateJoiner = new KaraokePlayer(UUID.randomUUID(), "Alex");

        coordinator.syncPlayer(lateJoiner);

        assertEquals(play.playbackId(), platform.lastPacket(AudioCommandType.PLAY).playbackId());
    }

    @Test
    void moveRequestReordersUpcomingRequestedSongs() {
        coordinator.request(track("current", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("one", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("two", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("three", Duration.ofSeconds(60)), player).join();

        var moved = coordinator.moveRequest(3, 1);

        assertTrue(moved.isPresent());
        assertEquals("three", moved.get().track().id());
        assertEquals(List.of("three", "one", "two"), queuedRequestIds());
    }

    @Test
    void moveRequestRejectsOutOfRangePositions() {
        coordinator.request(track("current", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("one", Duration.ofSeconds(60)), player).join();

        assertTrue(coordinator.moveRequest(2, 1).isEmpty());
        assertEquals(List.of("one"), queuedRequestIds());
    }

    @Test
    void cancelAllByRequesterRemovesOnlyThatPlayersUpcomingRequests() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        coordinator.request(track("current", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("one", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("two", Duration.ofSeconds(60)), alex).join();
        coordinator.request(track("three", Duration.ofSeconds(60)), player).join();

        var removed = coordinator.cancelAllByRequester(player.id());

        assertEquals(List.of("one", "three"), removed.stream().map(queued -> queued.track().id()).toList());
        assertEquals(List.of("two"), queuedRequestIds());
    }

    @Test
    void cancelAllRemovesAllUpcomingQueuedRequests() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        coordinator.request(track("current", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("one", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("two", Duration.ofSeconds(60)), alex).join();

        var removed = coordinator.cancelAll();

        assertEquals(List.of("one", "two"), removed.stream().map(queued -> queued.track().id()).toList());
        assertEquals(List.of(), queuedRequestIds());
    }

    private List<String> queuedRequestIds() {
        return coordinator.snapshot().requests().stream()
                .map(queued -> queued.track().id())
                .toList();
    }

    private static KaraokeTrack track(String id, Duration duration) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                "Song " + id,
                "Artist",
                new AudioAsset("https://audio.example/" + id + ".opus", AudioFormat.OPUS),
                null,
                duration);
    }

    private static final class FakePlatform implements ServerPlaybackPlatform {
        private final List<KaraokePlayer> players = new ArrayList<>();
        private final List<SentPacket> sentPackets = new ArrayList<>();
        private final Map<Integer, ScheduledTask> scheduledTasks = new LinkedHashMap<>();
        private final List<Integer> cancelledTaskIds = new ArrayList<>();
        private int nextTaskId = 1;

        private FakePlatform(KaraokePlayer player) {
            players.add(player);
        }

        @Override
        public void runNow(Runnable task) {
            task.run();
        }

        @Override
        public int runLater(Runnable task, long delayTicks) {
            int id = nextTaskId++;
            scheduledTasks.put(id, new ScheduledTask(id, delayTicks, task));
            return id;
        }

        @Override
        public void cancelTask(int taskId) {
            cancelledTaskIds.add(taskId);
            scheduledTasks.remove(taskId);
        }

        @Override
        public Collection<KaraokePlayer> onlinePlayers() {
            return List.copyOf(players);
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
            sentPackets.add(new SentPacket(player, packet));
        }

        @Override
        public void log(Level level, String message, Throwable error) {
        }

        private ScheduledTask onlyScheduledTask() {
            assertEquals(1, scheduledTasks.size());
            return scheduledTasks.values().iterator().next();
        }

        private void runScheduled(int taskId) {
            ScheduledTask task = scheduledTasks.remove(taskId);
            assertNotNull(task);
            task.task().run();
        }

        private List<Integer> cancelledTaskIds() {
            return cancelledTaskIds;
        }

        private AudioCommandPacket lastPacket(AudioCommandType type) {
            for (int i = sentPackets.size() - 1; i >= 0; i--) {
                AudioCommandPacket packet = sentPackets.get(i).packet();
                if (packet.command() == type) {
                    return packet;
                }
            }
            return null;
        }

        private record SentPacket(KaraokePlayer player, AudioCommandPacket packet) {
        }

        private record ScheduledTask(int id, long delayTicks, Runnable task) {
        }
    }
}
