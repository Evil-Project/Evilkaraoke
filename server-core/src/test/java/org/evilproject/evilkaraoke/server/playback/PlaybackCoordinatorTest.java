package org.evilproject.evilkaraoke.server.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.audio.AudioStreamRelay;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeEndpoints;
import org.evilproject.evilkaraoke.server.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.server.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.queue.KaraokeSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackCoordinatorTest {
    private FakePlatform platform;
    private PlaybackCoordinator coordinator;
    private KaraokePlayer player;
    private ClientRegistry clientRegistry;
    private RecordingAudioStreamRelay audioStreamRelay;

    @BeforeEach
    void setUp() {
        player = new KaraokePlayer(UUID.randomUUID(), "Steve");
        platform = new FakePlatform(player);
        clientRegistry = new ClientRegistry();
        registerClient(player);
        audioStreamRelay = new RecordingAudioStreamRelay();
        coordinator = new PlaybackCoordinator(
                platform,
                clientRegistry,
                new NeurokaraokeClient(Logger.getLogger("test"), NeurokaraokeEndpoints.defaults()),
                new EvilKaraokeConfig("@a", "music", 1.0f, 1.0f, 0.0f, 2, 3L, false, true, true, 60, false),
                audioStreamRelay);
    }

    @Test
    void clientConfirmedPlaybackUsesWatchdogInsteadOfMetadataCutoff() {
        Instant beforeRequest = Instant.now();
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();

        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        assertNotNull(play);
        assertEquals(AudioDeliveryMode.SERVER_STREAM, play.deliveryMode());
        assertTrue(play.serverTime().isAfter(beforeRequest), "server stream playback should schedule a future shared start time");
        FakePlatform.ScheduledTask fallback = platform.onlyScheduledTask();
        assertEquals(200L, fallback.delayTicks());

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));

        assertTrue(platform.cancelledTaskIds().contains(fallback.id()));
        FakePlatform.ScheduledTask watchdog = platform.onlyScheduledTask();
        assertEquals(1_400L, watchdog.delayTicks());

        platform.runScheduled(watchdog.id());

        assertEquals("song", coordinator.snapshot().current().track().id());
        assertEquals(100L, platform.onlyScheduledTask().delayTicks(),
                "active clients should keep playback alive past the metadata duration");
    }

    @Test
    void serverStreamChunksBroadcastToAllModdedRecipients() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(alex);
        registerClient(alex);

        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();

        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        assertNotNull(play);
        assertEquals(1, audioStreamRelay.relayCalls);
        assertEquals(1, platform.packetCount(player, AudioStreamChunkPacket.class));
        assertEquals(1, platform.packetCount(alex, AudioStreamChunkPacket.class));
    }

    @Test
    void clientsWithoutServerStreamSupportReceiveUrlPlayPacket() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(alex);
        registerClient(alex, List.of("opus", "mp3"));

        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();

        AudioCommandPacket stevePlay = platform.lastPacket(player, AudioCommandType.PLAY);
        AudioCommandPacket alexPlay = platform.lastPacket(alex, AudioCommandType.PLAY);
        assertEquals(AudioDeliveryMode.SERVER_STREAM, stevePlay.deliveryMode());
        assertEquals(AudioDeliveryMode.URL, alexPlay.deliveryMode());
        assertEquals(1, platform.packetCount(player, AudioStreamChunkPacket.class));
        assertEquals(0, platform.packetCount(alex, AudioStreamChunkPacket.class));
    }

    @Test
    void syncPlayerUsesUrlModeWhenClientDoesNotSupportServerStream() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        KaraokePlayer lateJoiner = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(lateJoiner);
        registerClient(lateJoiner, List.of("opus", "mp3"));

        coordinator.syncPlayer(lateJoiner);

        AudioCommandPacket syncPlay = platform.lastPacket(lateJoiner, AudioCommandType.PLAY);
        assertEquals(play.playbackId(), syncPlay.playbackId());
        assertEquals(AudioDeliveryMode.URL, syncPlay.deliveryMode());
        assertEquals(1, audioStreamRelay.relayCalls);
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
    void stoppedStatusFromOneClientDoesNotAdvanceWhileAnotherStartedClientIsActive() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(alex);
        registerClient(alex);
        coordinator.request(track("first", null), player).join();
        coordinator.request(track("second", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        coordinator.handleClientStatus(
                alex.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.STOPPED, Duration.ZERO, "Finished"));

        assertEquals("first", coordinator.snapshot().current().track().id());

        coordinator.handleClientStatus(
                alex.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.STOPPED, Duration.ZERO, "Finished"));

        assertEquals("second", platform.lastPacket(AudioCommandType.PLAY).track().id());
    }

    @Test
    void stoppedStatusFromOneClientDoesNotAdvanceBeforeOtherRecipientReports() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(alex);
        registerClient(alex);
        coordinator.request(track("first", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("second", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.STOPPED, Duration.ZERO, "Finished"));

        assertEquals("first", coordinator.snapshot().current().track().id());
    }

    @Test
    void errorStatusFromOneClientDoesNotAdvanceWhileAnotherStartedClientIsActive() {
        KaraokePlayer alex = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(alex);
        registerClient(alex);
        coordinator.request(track("first", null), player).join();
        coordinator.request(track("second", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);

        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        coordinator.handleClientStatus(
                alex.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.ERROR, Duration.ZERO, "Decode failed"));

        assertEquals("first", coordinator.snapshot().current().track().id());

        coordinator.handleClientStatus(
                alex.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.STOPPED, Duration.ZERO, "Finished"));

        assertEquals("second", platform.lastPacket(AudioCommandType.PLAY).track().id());
    }

    @Test
    void fallbackStartsTimerWhenNoClientReportsPlaying() {
        coordinator.request(track("song", Duration.ofSeconds(5)), player).join();

        FakePlatform.ScheduledTask fallback = platform.onlyScheduledTask();
        platform.runScheduled(fallback.id());

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
        FakePlatform.ScheduledTask watchdog = platform.onlyScheduledTask();

        coordinator.pause();

        assertEquals(play.playbackId(), platform.lastPacket(AudioCommandType.PAUSE).playbackId());
        assertTrue(platform.cancelledTaskIds().contains(watchdog.id()));
        assertEquals(0, platform.scheduledTaskCount());

        coordinator.resume();

        assertEquals(1, platform.scheduledTaskCount());
        assertEquals(play.playbackId(), platform.lastPacket(AudioCommandType.RESUME).playbackId());
    }

    @Test
    void watchdogAdvancesWhenStartedClientDisconnects() {
        coordinator.request(track("first", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("second", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(firstPlay.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        FakePlatform.ScheduledTask watchdog = platform.onlyScheduledTask();

        clientRegistry.unregister(player.id());
        platform.removePlayer(player.id());
        platform.runScheduled(watchdog.id());

        assertEquals("second", coordinator.snapshot().current().track().id());
    }

    @Test
    void stopBroadcastUsesCurrentPlaybackIdAndKeepsUpcomingQueue() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        coordinator.request(track("queued", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);

        coordinator.stop();

        AudioCommandPacket stop = platform.lastPacket(AudioCommandType.STOP);
        assertNotNull(stop);
        assertEquals(play.playbackId(), stop.playbackId());
        assertEquals("song", stop.track().id());
        assertEquals(PlaybackState.IDLE, coordinator.snapshot().state());
        assertEquals(List.of("queued"), queuedRequestIds());
    }

    @Test
    void stopCancelsServerStreamRelayAndSuppressesStaleChunks() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        int chunksBeforeStop = platform.packetCount(player, AudioStreamChunkPacket.class);

        coordinator.stop();

        assertFalse(audioStreamRelay.lastShouldContinue.getAsBoolean());
        audioStreamRelay.lastBroadcaster.accept(AudioStreamChunkPacket.chunk(
                KaraokeSession.GLOBAL_SESSION_ID,
                audioStreamRelay.lastPlaybackId,
                99,
                new byte[] {9},
                1));
        assertEquals(chunksBeforeStop, platform.packetCount(player, AudioStreamChunkPacket.class));
    }

    @Test
    void syncPlayerUsesCurrentPlaybackId() {
        coordinator.request(track("song", Duration.ofSeconds(60)), player).join();
        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(play.playbackId(), ClientPlaybackState.PLAYING, Duration.ZERO, ""));
        KaraokePlayer lateJoiner = new KaraokePlayer(UUID.randomUUID(), "Alex");
        platform.addPlayer(lateJoiner);
        registerClient(lateJoiner);

        coordinator.syncPlayer(lateJoiner);

        AudioCommandPacket syncPlay = platform.lastPacket(AudioCommandType.PLAY);
        assertEquals(play.playbackId(), syncPlay.playbackId());
        assertEquals(AudioDeliveryMode.SERVER_STREAM, syncPlay.deliveryMode());
        assertEquals(2, audioStreamRelay.relayCalls);
    }

    @Test
    void healthyAudienceUsesHigherQualityFallbackForServerStream() {
        platform.setPing(player.id(), 40);

        coordinator.request(trackWithFallback("song"), player).join();

        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        assertEquals("https://audio.example/song-high.wav", play.track().primaryAsset().url());
        assertEquals("https://audio.example/song-low.opus", play.track().fallbackAsset().url());
        assertEquals(play.track(), audioStreamRelay.lastTrack);
    }

    @Test
    void highPingAudienceUsesLowTrafficPrimaryForServerStream() {
        platform.setPing(player.id(), 320);

        coordinator.request(trackWithFallback("song"), player).join();

        AudioCommandPacket play = platform.lastPacket(AudioCommandType.PLAY);
        assertEquals("https://audio.example/song-low.opus", play.track().primaryAsset().url());
        assertEquals("https://audio.example/song-high.wav", play.track().fallbackAsset().url());
        assertEquals(play.track(), audioStreamRelay.lastTrack);
    }

    @Test
    void missingStreamChunksUseLowTrafficPrimaryOnNextTrack() {
        platform.setPing(player.id(), 40);
        coordinator.request(trackWithFallback("first"), player).join();
        coordinator.request(trackWithFallback("second"), player).join();
        AudioCommandPacket firstPlay = platform.lastPacket(AudioCommandType.PLAY);
        coordinator.handleClientStatus(
                player.id(),
                new ClientStatusPacket(
                        firstPlay.playbackId(),
                        ClientPlaybackState.PLAYING,
                        Duration.ZERO,
                        "",
                        16_384L,
                        16_384L,
                        0,
                        1L));

        coordinator.skip();

        AudioCommandPacket secondPlay = platform.lastPacket(AudioCommandType.PLAY);
        assertEquals("second", secondPlay.track().id());
        assertEquals("https://audio.example/second-low.opus", secondPlay.track().primaryAsset().url());
        assertEquals("https://audio.example/second-high.wav", secondPlay.track().fallbackAsset().url());
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

    private void registerClient(KaraokePlayer player) {
        registerClient(player, List.of("opus", "vorbis", "mp3", "stream"));
    }

    private void registerClient(KaraokePlayer player, List<String> supportedCodecs) {
        clientRegistry.register(player.id(), new ClientHelloPacket(
                1,
                "test",
                "1.21.11",
                "test",
                supportedCodecs,
                false,
                true,
                true));
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

    private static KaraokeTrack trackWithFallback(String id) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                "Song " + id,
                "Artist",
                new AudioAsset("https://audio.example/" + id + "-low.opus", AudioFormat.OPUS),
                new AudioAsset("https://audio.example/" + id + "-high.wav", AudioFormat.UNKNOWN),
                Duration.ofSeconds(60));
    }

    private static final class FakePlatform implements ServerPlaybackPlatform {
        private final List<KaraokePlayer> players = new ArrayList<>();
        private final List<SentPacket> sentPackets = new ArrayList<>();
        private final Map<Integer, ScheduledTask> scheduledTasks = new LinkedHashMap<>();
        private final Map<UUID, Integer> pings = new LinkedHashMap<>();
        private final List<Integer> cancelledTaskIds = new ArrayList<>();
        private int nextTaskId = 1;

        private FakePlatform(KaraokePlayer player) {
            players.add(player);
        }

        private void addPlayer(KaraokePlayer player) {
            players.add(player);
        }

        private void removePlayer(UUID playerId) {
            players.removeIf(player -> player.id().equals(playerId));
        }

        private void setPing(UUID playerId, int pingMillis) {
            pings.put(playerId, pingMillis);
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
        public void sendAudio(KaraokePlayer player, ProtocolPacket packet) {
            sentPackets.add(new SentPacket(player, packet));
        }

        @Override
        public int pingMillis(KaraokePlayer player) {
            return pings.getOrDefault(player.id(), -1);
        }

        @Override
        public void log(Level level, String message, Throwable error) {
        }

        private ScheduledTask onlyScheduledTask() {
            assertEquals(1, scheduledTasks.size());
            return scheduledTasks.values().iterator().next();
        }

        private int scheduledTaskCount() {
            return scheduledTasks.size();
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
                ProtocolPacket protocolPacket = sentPackets.get(i).packet();
                if (protocolPacket instanceof AudioCommandPacket packet && packet.command() == type) {
                    return packet;
                }
            }
            return null;
        }

        private AudioCommandPacket lastPacket(KaraokePlayer recipient, AudioCommandType type) {
            for (int i = sentPackets.size() - 1; i >= 0; i--) {
                SentPacket sentPacket = sentPackets.get(i);
                ProtocolPacket protocolPacket = sentPacket.packet();
                if (sentPacket.player().id().equals(recipient.id())
                        && protocolPacket instanceof AudioCommandPacket packet
                        && packet.command() == type) {
                    return packet;
                }
            }
            return null;
        }

        private int packetCount(KaraokePlayer recipient, Class<? extends ProtocolPacket> packetType) {
            int count = 0;
            for (SentPacket sentPacket : sentPackets) {
                if (sentPacket.player().id().equals(recipient.id()) && packetType.isInstance(sentPacket.packet())) {
                    count++;
                }
            }
            return count;
        }

        private record SentPacket(KaraokePlayer player, ProtocolPacket packet) {
        }

        private record ScheduledTask(int id, long delayTicks, Runnable task) {
        }
    }

    private static final class RecordingAudioStreamRelay implements AudioStreamRelay {
        private int relayCalls;
        private String lastPlaybackId;
        private KaraokeTrack lastTrack;
        private Consumer<ProtocolPacket> lastBroadcaster;
        private BooleanSupplier lastShouldContinue;

        @Override
        public CompletableFuture<Void> relay(String sessionId,
                                             String playbackId,
                                             KaraokeTrack track,
                                             PlaybackTarget target,
                                             Duration offset,
                                             Instant serverTime,
                                             String reason,
                                             Consumer<ProtocolPacket> broadcaster,
                                             BooleanSupplier shouldContinue) {
            relayCalls++;
            lastPlaybackId = playbackId;
            lastTrack = track;
            lastBroadcaster = broadcaster;
            lastShouldContinue = shouldContinue;
            broadcaster.accept(AudioStreamChunkPacket.chunk(sessionId, playbackId, 0, new byte[] {1, 2, 3}, 3));
            return CompletableFuture.completedFuture(null);
        }
    }
}
