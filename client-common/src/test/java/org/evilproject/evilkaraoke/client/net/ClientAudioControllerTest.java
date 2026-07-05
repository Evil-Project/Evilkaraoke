package org.evilproject.evilkaraoke.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.client.audio.AudioBackend;
import org.evilproject.evilkaraoke.client.audio.AudioBackendStatus;
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
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.junit.jupiter.api.Test;

class ClientAudioControllerTest {
    private final JsonPacketCodec codec = new JsonPacketCodec();

    private KaraokeTrack track() {
        return new KaraokeTrack("s1", TrackType.SONG, "Title", "Artist",
                new AudioAsset("https://audio/s1.opus", AudioFormat.OPUS), null, Duration.ofMinutes(2));
    }

    private AudioCommandPacket packet(AudioCommandType type) {
        return new AudioCommandPacket(type, "global", "p1", track(), PlaybackTarget.allPlayers(),
                Duration.ZERO, Instant.EPOCH, "", Duration.ZERO);
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
    void helloPayloadRoundTrips() {
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), new RecordingBackend(), "0.1.0", "26.2", "test");
        assertNotNull(codec.decode(controller.helloPayload()));
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
    void stopAllClearsCurrentPlaybackId() {
        RecordingBackend backend = new RecordingBackend();
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");

        controller.handleAudioPayload(codec.encode(packet(AudioCommandType.PLAY)));
        controller.stopAll();

        assertEquals("none", status(controller).playbackId());
    }

    private ClientStatusPacket status(ClientAudioController controller) {
        return assertInstanceOf(ClientStatusPacket.class, codec.decode(controller.statusPayload()));
    }

    private static final class RecordingBackend implements AudioBackend {
        private final List<String> calls = new ArrayList<>();
        private float lastGameVolume = 1.0f;

        @Override
        public void play(AudioCommandPacket packet) {
            calls.add("play");
        }

        @Override
        public void pause(AudioCommandPacket packet) {
            calls.add("pause");
        }

        @Override
        public void resume(AudioCommandPacket packet) {
            calls.add("resume");
        }

        @Override
        public void stop(AudioCommandPacket packet) {
            calls.add("stop");
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
        public AudioBackendStatus status() {
            return new AudioBackendStatus(ClientPlaybackState.READY, "test");
        }
    }
}
