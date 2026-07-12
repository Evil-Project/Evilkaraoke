package org.evilproject.evilkaraoke.common.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.junit.jupiter.api.Test;

class JsonPacketCodecTest {
    private final JsonPacketCodec codec = new JsonPacketCodec();

    @Test
    void roundTripsAudioCommandPacket() {
        KaraokeTrack track = new KaraokeTrack(
                "song-1",
                TrackType.SONG,
                "Test Song",
                "Test Artist",
                "Test Cover",
                new AudioAsset("https://audio.example/song.opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(3)
        );
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "playback-1",
                track,
                PlaybackTarget.allPlayers(),
                Duration.ofSeconds(12),
                Instant.ofEpochMilli(123456789L),
                "",
                Duration.ZERO
        );

        ProtocolPacket decoded = codec.decode(codec.encode(packet));

        AudioCommandPacket actual = assertInstanceOf(AudioCommandPacket.class, decoded);
        assertEquals(AudioCommandType.PLAY, actual.command());
        assertEquals("global", actual.sessionId());
        assertEquals("playback-1", actual.playbackId());
        assertEquals("Test Song", actual.track().title());
        assertEquals("Test Cover", actual.track().coveredBy().orElseThrow());
        assertEquals(Duration.ofSeconds(12), actual.offset());
        assertEquals(Instant.ofEpochMilli(123456789L), actual.serverTime());
        assertEquals(AudioDeliveryMode.URL, actual.deliveryMode());
    }

    @Test
    void roundTripsServerStreamAudioCommandPacket() {
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "playback-1",
                null,
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO,
                AudioDeliveryMode.SERVER_STREAM
        );

        ProtocolPacket decoded = codec.decode(codec.encode(packet));

        AudioCommandPacket actual = assertInstanceOf(AudioCommandPacket.class, decoded);
        assertEquals(AudioDeliveryMode.SERVER_STREAM, actual.deliveryMode());
    }

    @Test
    void roundTripsAudioStreamChunkPacket() {
        AudioStreamChunkPacket packet = AudioStreamChunkPacket.chunk("global", "playback-1", 7, new byte[] {1, 2, 3}, 3);

        ProtocolPacket decoded = codec.decode(codec.encode(packet));

        AudioStreamChunkPacket actual = assertInstanceOf(AudioStreamChunkPacket.class, decoded);
        assertEquals("global", actual.sessionId());
        assertEquals("playback-1", actual.playbackId());
        assertEquals(7, actual.sequence());
        assertArrayEquals(new byte[] {1, 2, 3}, actual.decodedData());
    }

    @Test
    void roundTripsClientStatusStreamTelemetry() {
        ClientStatusPacket packet = new ClientStatusPacket(
                "playback-1",
                ClientPlaybackState.PLAYING,
                Duration.ofSeconds(4),
                "playing",
                4_096L,
                2_048L,
                2_048,
                1L);

        ProtocolPacket decoded = codec.decode(codec.encode(packet));

        ClientStatusPacket actual = assertInstanceOf(ClientStatusPacket.class, decoded);
        assertEquals("playback-1", actual.playbackId());
        assertEquals(ClientPlaybackState.PLAYING, actual.state());
        assertEquals(Duration.ofSeconds(4), actual.offset());
        assertEquals(4_096L, actual.streamBytesReceived());
        assertEquals(2_048L, actual.streamBytesRead());
        assertEquals(2_048, actual.streamQueuedBytes());
        assertEquals(1L, actual.streamMissingChunks());
    }

    @Test
    void legacyHelloWithoutLyricsCapabilityDefaultsToUnsupported() {
        ProtocolPacket decoded = codec.decode("""
                {"version":1,"type":"hello","payload":{
                  "protocolVersion":1,
                  "modVersion":"1.0.0",
                  "minecraftVersion":"26.2",
                  "loader":"fabric",
                  "supportedCodecs":["opus"],
                  "supportsPositionalAudio":true,
                  "supportsPitch":false,
                  "supportsRadioStreams":true
                }}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ClientHelloPacket hello = assertInstanceOf(ClientHelloPacket.class, decoded);
        assertFalse(hello.supportsLyrics());
    }

    @Test
    void invalidPayloadThrowsPacketCodecException() {
        assertThrows(PacketCodecException.class, () -> codec.decode("{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
