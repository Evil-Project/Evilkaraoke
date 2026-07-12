package org.evilproject.evilkaraoke.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.client.audio.AudioBackend;
import org.evilproject.evilkaraoke.client.audio.AudioBackendStatus;
import org.evilproject.evilkaraoke.client.lyrics.LyricsPlaybackController;
import org.evilproject.evilkaraoke.client.lyrics.TimedLyric;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.LyricsDisplayAction;
import org.junit.jupiter.api.Test;

class ClientAudioControllerTest {
    private final JsonPacketCodec codec = new JsonPacketCodec();

    private KaraokeTrack track() {
        return new KaraokeTrack("s1", TrackType.SONG, "Title", "Artist",
                new AudioAsset("https://audio/s1.opus", AudioFormat.OPUS), null, Duration.ofMinutes(2));
    }

    private AudioCommandPacket packet(AudioCommandType type) {
        return new AudioCommandPacket(type, "global", "p1", track(), PlaybackTarget.allPlayers(),
                Duration.ZERO, Instant.EPOCH,
                type == AudioCommandType.LYRICS ? LyricsDisplayAction.TOGGLE.reason() : "",
                Duration.ZERO);
    }

    private AudioCommandPacket lyricsPacket(LyricsDisplayAction action) {
        return new AudioCommandPacket(AudioCommandType.LYRICS, "global", "p1", null, null,
                Duration.ZERO, Instant.EPOCH, action.reason(), Duration.ZERO);
    }

    private AudioCommandPacket serverStreamPacket() {
        return serverStreamPacket("stream-1");
    }

    private AudioCommandPacket serverStreamPacket(String playbackId) {
        return new AudioCommandPacket(AudioCommandType.PLAY, "global", playbackId, track(), PlaybackTarget.allPlayers(),
                Duration.ZERO, Instant.EPOCH, "", Duration.ZERO, AudioDeliveryMode.SERVER_STREAM);
    }

    @Test
    void routesEachCommandToBackend() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PAUSE)));
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.RESUME)));
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.VOLUME)));
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.STOP)));

        assertEquals(List.of("play", "pause", "resume", "volume", "stop"), backend.calls);
    }

    @Test
    void serverStreamPlayUsesPacketFedInputStream() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 0, new byte[] {1, 2, 3}, 3)));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.end("global", "stream-1", 1)));

        assertEquals(List.of("play-stream"), backend.calls);
        ClientStatusPacket beforeRead = status(controller);
        assertEquals("stream-1", beforeRead.playbackId());
        assertEquals(3L, beforeRead.streamBytesReceived());
        assertEquals(0L, beforeRead.streamBytesRead());
        assertEquals(3, beforeRead.streamQueuedBytes());
        assertEquals(0L, beforeRead.streamMissingChunks());
        assertNotNull(backend.serverStream);
        assertEquals(1, backend.serverStream.read());
        assertEquals(2, backend.serverStream.read());
        ClientStatusPacket afterPartialRead = status(controller);
        assertEquals(3L, afterPartialRead.streamBytesReceived());
        assertEquals(2L, afterPartialRead.streamBytesRead());
        assertEquals(1, afterPartialRead.streamQueuedBytes());
        assertEquals(3, backend.serverStream.read());
        assertEquals(-1, backend.serverStream.read());
    }

    @Test
    void missingServerStreamChunksFailStreamInsteadOfCorruptingDecoder() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 0, new byte[] {1}, 1)));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 3, new byte[] {1}, 1)));

        ClientStatusPacket status = status(controller);
        assertEquals(2L, status.streamMissingChunks());
        assertEquals(1L, status.streamBytesReceived());
        assertThrows(IOException.class, () -> backend.serverStream.read());
    }

    @Test
    void firstChunkOfRejoinSyncStreamBaselinesSequenceInsteadOfFailing() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        // A rejoining client attaches to the global stream mid-track: the first
        // chunk it sees carries whatever sequence the relay is at, not 0.
        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 42, new byte[] {1, 2}, 2)));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 43, new byte[] {3}, 1)));

        ClientStatusPacket status = status(controller);
        assertEquals(0L, status.streamMissingChunks());
        assertEquals(3L, status.streamBytesReceived());
        assertEquals(List.of("play-stream"), backend.calls);
        assertEquals(1, backend.serverStream.read());
    }

    @Test
    void invalidServerStreamChunkFailsStreamInsteadOfHanging() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(new AudioStreamChunkPacket("global", "stream-1", 0, "%%%", false, "")));

        assertThrows(IOException.class, () -> backend.serverStream.read());
    }

    @Test
    void stopForServerStreamClosesPacketStreamAndClearsPlayback() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 0, new byte[] {1}, 1)));
        InputStream serverStream = backend.serverStream;
        assertNotNull(serverStream);

        controller.handleAudioPayload(codec.encode(new AudioCommandPacket(
                AudioCommandType.STOP,
                "global",
                "stream-1",
                track(),
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "stop",
                Duration.ZERO)));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-1", 1, new byte[] {1}, 1)));

        assertEquals(List.of("play-stream", "stop"), backend.calls);
        assertEquals("none", status(controller).playbackId());
        assertEquals(-1, serverStream.read());
    }

    @Test
    void pendingServerStreamReportsBufferingInsteadOfPreviousTerminalState() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        // Previous track finished; the backend is parked in a terminal state.
        backend.status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "Finished");

        // New server-stream track dispatched, but no chunk has arrived yet. The
        // status tick must not pair the new playbackId with the stale STOPPED
        // state, or the server would treat the new track as finished and skip
        // through the whole queue.
        controller.handleAudioPayload(codec.encode(serverStreamPacket("stream-2")));

        ClientStatusPacket status = status(controller);
        assertEquals("stream-2", status.playbackId());
        assertEquals(ClientPlaybackState.BUFFERING, status.state());
        assertTrue(controller.isPlaybackSessionActive());

        // Once the stream starts, the real backend state takes over again.
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", "stream-2", 0, new byte[] {1}, 1)));
        assertEquals(ClientPlaybackState.PLAYING, status(controller).state());
    }

    @Test
    void serverStreamErrorBeforeFirstChunkIsReportedToServer() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.error("global", "stream-1", 0, "HTTP 404 for audio URL")));

        ClientStatusPacket status = status(controller);
        assertEquals("stream-1", status.playbackId());
        assertEquals(ClientPlaybackState.ERROR, status.state());
    }

    @Test
    void serverStreamEndBeforeFirstChunkIsReportedAsStopped() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(serverStreamPacket()));
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.end("global", "stream-1", 0)));

        ClientStatusPacket status = status(controller);
        assertEquals("stream-1", status.playbackId());
        assertEquals(ClientPlaybackState.STOPPED, status.state());
    }

    @Test
    void helloPayloadRoundTrips() {
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), new RecordingBackend(), "0.1.0", "26.2", "test");
        var hello = assertInstanceOf(
                org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket.class,
                codec.decode(controller.helloPayload()));
        assertTrue(hello.supportsLyrics());
    }

    @Test
    void appliesMinecraftSoundSettingsToBackend() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.setGameVolume(0.25f);

        assertEquals(List.of("game-volume"), backend.calls);
        assertEquals(0.25f, backend.lastGameVolume);
    }

    @Test
    void tracksPacketSoundCategoryForMinecraftVolume() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        PlaybackTarget target = new PlaybackTarget(TargetMode.ALL, "@a", SoundCategory.RECORD, null, 1.0f, 1.0f, 0.0f);
        AudioCommandPacket packet = new AudioCommandPacket(AudioCommandType.PLAY, "global", "p1", track(), target,
                Duration.ZERO, Instant.EPOCH, "", Duration.ZERO);

        controller.handleAudioPayload(codec.encode(packet));

        assertEquals(SoundCategory.RECORD, controller.soundCategory());
    }

    @Test
    void statusPayloadUsesCurrentPlaybackIdAndClearsOnStop() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        assertEquals("p1", status(controller).playbackId());

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.STOP)));
        assertEquals("none", status(controller).playbackId());
    }

    @Test
    void staleControlPacketsDoNotAffectCurrentPlayback() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        controller.handleAudioPayload(codec.encode(new AudioCommandPacket(
                AudioCommandType.STOP,
                "global",
                "old-playback",
                track(),
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "stale",
                Duration.ZERO)));
        controller.handleAudioPayload(codec.encode(new AudioCommandPacket(
                AudioCommandType.PAUSE,
                "global",
                "old-playback",
                track(),
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "stale",
                Duration.ZERO)));

        assertEquals(List.of("play"), backend.calls);
        assertEquals("p1", status(controller).playbackId());
    }

    @Test
    void staleVolumePacketsDoNotMuteCurrentPlayback() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        PlaybackTarget mutedTarget = new PlaybackTarget(TargetMode.ALL, "@a", SoundCategory.RECORD, null, 0.0f, 1.0f, 0.0f);

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        controller.handleAudioPayload(codec.encode(new AudioCommandPacket(
                AudioCommandType.VOLUME,
                "global",
                "old-playback",
                track(),
                mutedTarget,
                Duration.ZERO,
                Instant.EPOCH,
                "stale",
                Duration.ZERO)));

        assertEquals(List.of("play"), backend.calls);
    }

    @Test
    void duplicatePlayForCurrentPlaybackDoesNotRestartAudioOrRepeatPlayCallback() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        AtomicInteger playCallbackCount = new AtomicInteger();
        controller.setOnPlay(track -> playCallbackCount.incrementAndGet());
        byte[] playPayload = codec.encode(packet(AudioCommandType.PLAY));

        controller.handleAudioPayload(playPayload);
        controller.handleAudioPayload(playPayload);

        assertEquals(List.of("play"), backend.calls);
        assertEquals(1, playCallbackCount.get());
    }

    @Test
    void duplicatePlayForTerminalCurrentPlaybackCanRestartAudio() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        byte[] playPayload = codec.encode(packet(AudioCommandType.PLAY));

        controller.handleAudioPayload(playPayload);
        backend.status = new AudioBackendStatus(ClientPlaybackState.ERROR, "decode failed");
        controller.handleAudioPayload(playPayload);

        assertEquals(List.of("play", "play"), backend.calls);
    }

    @Test
    void tracklessPlayStillResetsLoaderUi() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        AtomicInteger playCallbackCount = new AtomicInteger();
        controller.setOnPlay(track -> playCallbackCount.incrementAndGet());
        AudioCommandPacket play = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "trackless",
                null,
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO);

        controller.handleAudioPayload(codec.encode(play));

        assertEquals(1, playCallbackCount.get());
    }

    @Test
    void playAfterDisconnectCanReuseServerPlaybackId() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        AtomicInteger playCallbackCount = new AtomicInteger();
        controller.setOnPlay(track -> playCallbackCount.incrementAndGet());
        byte[] playPayload = codec.encode(packet(AudioCommandType.PLAY));

        controller.handleAudioPayload(playPayload);
        controller.stopAll();
        controller.handleAudioPayload(playPayload);

        assertEquals(List.of("play", "stop", "play"), backend.calls);
        assertEquals(2, playCallbackCount.get());
    }

    @Test
    void stopAllClearsCurrentPlaybackId() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        controller.stopAll();

        assertEquals("none", status(controller).playbackId());
    }

    @Test
    void playbackSessionIsActiveOnlyForLivePlaybackStates() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        assertFalse(controller.isPlaybackSessionActive());

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));

        backend.status = new AudioBackendStatus(ClientPlaybackState.BUFFERING, "buffering");
        assertTrue(controller.isPlaybackSessionActive());
        backend.status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "playing");
        assertTrue(controller.isPlaybackSessionActive());
        backend.status = new AudioBackendStatus(ClientPlaybackState.PAUSED, "paused");
        assertTrue(controller.isPlaybackSessionActive());
        backend.status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "finished");
        assertFalse(controller.isPlaybackSessionActive());
        backend.status = AudioBackendStatus.error("failed");
        assertFalse(controller.isPlaybackSessionActive());

        controller.stopAll();
        backend.status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "stale");
        assertFalse(controller.isPlaybackSessionActive());
    }

    @Test
    void ticksLyricsFromTheBackendPlaybackPosition() {
        RecordingBackend backend = new RecordingBackend();
        LyricsPlaybackController lyrics = new LyricsPlaybackController(
                Logger.getLogger("test"),
                songId -> java.util.concurrent.CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(10), "Current lyric"))));
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), backend, "0.1.0", "26.2", "test", lyrics);
        List<String> rendered = new ArrayList<>();
        controller.setOnLyric(rendered::add);
        KaraokeTrack apiTrack = new KaraokeTrack(
                "23b51bed-6827-4556-a427-de75940bfcdf",
                TrackType.SONG,
                "Title",
                "Artist",
                new AudioAsset("https://audio/s1.opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(2));
        AudioCommandPacket play = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "lyrics-playback",
                apiTrack,
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO);

        controller.handleAudioPayload(codec.encode(play));
        backend.playbackPosition = Duration.ofSeconds(11);
        // Lyrics are disabled by default, so ticking renders nothing.
        assertFalse(controller.lyricsEnabled());
        controller.tickLyrics();
        assertEquals(List.of(), rendered);
        // Toggling on resumes rendering from the playback position.
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.LYRICS)));
        assertTrue(controller.lyricsEnabled());
        controller.tickLyrics();

        assertEquals(List.of("Current lyric"), rendered);
    }

    @Test
    void lyricsCommandTogglesStateAndNotifiesCallback() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        List<Boolean> toggles = new ArrayList<>();
        controller.setOnLyricsToggled(toggles::add);

        assertFalse(controller.lyricsEnabled());
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.LYRICS)));
        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.LYRICS)));

        assertEquals(List.of(true, false), toggles);
        assertFalse(controller.lyricsEnabled());
    }

    @Test
    void lyricsEnableAndDisableCommandsAreIdempotent() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        List<Boolean> changes = new ArrayList<>();
        controller.setOnLyricsToggled(changes::add);

        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.ENABLE)));
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.ENABLE)));
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.DISABLE)));
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.DISABLE)));

        assertEquals(List.of(true, true, false, false), changes);
        assertFalse(controller.lyricsEnabled());
    }

    @Test
    void reEnablingLyricsRestoresTheCurrentCue() {
        RecordingBackend backend = new RecordingBackend();
        LyricsPlaybackController lyrics = new LyricsPlaybackController(
                Logger.getLogger("test"),
                songId -> java.util.concurrent.CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(10), "Current lyric"),
                        new TimedLyric(Duration.ofSeconds(30), "Later lyric"))));
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), backend, "0.1.0", "26.2", "test", lyrics);
        List<String> events = new ArrayList<>();
        controller.setOnLyric(text -> events.add("show:" + text));
        controller.setOnLyricClear(() -> events.add("clear"));
        KaraokeTrack apiTrack = new KaraokeTrack(
                "23b51bed-6827-4556-a427-de75940bfcdf",
                TrackType.SONG,
                "Title",
                "Artist",
                new AudioAsset("https://audio/s1.opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(2));

        controller.handleAudioPayload(codec.encode(new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "lyrics-playback",
                apiTrack,
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO)));
        backend.playbackPosition = Duration.ofSeconds(15);
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.ENABLE)));
        controller.tickLyrics();
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.DISABLE)));
        controller.handleAudioPayload(codec.encode(lyricsPacket(LyricsDisplayAction.ENABLE)));
        controller.tickLyrics();

        assertEquals(List.of("show:Current lyric", "clear", "show:Current lyric"), events);
    }

    @Test
    void unknownLyricsActionDoesNotChangeOrPersistState() {
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), new RecordingBackend(), "0.1.0", "26.2", "test");
        List<Boolean> changes = new ArrayList<>();
        controller.setOnLyricsToggled(changes::add);
        AudioCommandPacket unknown = new AudioCommandPacket(
                AudioCommandType.LYRICS,
                "global",
                "p1",
                null,
                null,
                Duration.ZERO,
                Instant.EPOCH,
                "lyrics-future-mode",
                Duration.ZERO);

        controller.handleAudioPayload(codec.encode(unknown));

        assertFalse(controller.lyricsEnabled());
        assertEquals(List.of(), changes);
    }

    @Test
    void persistedLyricsPreferenceCanInitializeTheController() {
        ClientAudioController controller = new ClientAudioController(
                Logger.getLogger("test"), new RecordingBackend(), "0.1.0", "26.2", "test");

        controller.setLyricsEnabled(true);

        assertTrue(controller.lyricsEnabled());
    }

    private ClientStatusPacket status(ClientAudioController controller) {
        return assertInstanceOf(ClientStatusPacket.class, codec.decode(controller.statusPayload()));
    }

    private static final class RecordingBackend implements AudioBackend {
        private final List<String> calls = new ArrayList<>();
        private float lastGameVolume = 1.0f;
        private AudioBackendStatus status = AudioBackendStatus.ready();
        private InputStream serverStream;
        private Duration playbackPosition;

        @Override
        public void play(AudioCommandPacket packet) {
            calls.add("play");
            status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "playing");
        }

        @Override
        public void playStream(AudioCommandPacket packet, InputStream source) {
            calls.add("play-stream");
            serverStream = source;
            status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "playing");
        }

        @Override
        public void pause(AudioCommandPacket packet) {
            calls.add("pause");
            status = new AudioBackendStatus(ClientPlaybackState.PAUSED, "paused");
        }

        @Override
        public void resume(AudioCommandPacket packet) {
            calls.add("resume");
            status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "playing");
        }

        @Override
        public void stop(AudioCommandPacket packet) {
            calls.add("stop");
            status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "stopped");
        }

        @Override
        public void setVolume(float linearGain) {
            calls.add("volume");
        }

        @Override
        public void setGameVolume(float linearGain) {
            calls.add("game-volume");
            lastGameVolume = linearGain;
        }

        @Override
        public Optional<Duration> playbackPosition() {
            return Optional.ofNullable(playbackPosition);
        }

        @Override
        public AudioBackendStatus status() {
            return status;
        }
    }
}
