package org.evilproject.evilkaraoke.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.time.Instant;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
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
        assertEquals(Duration.ofSeconds(12), actual.offset());
        assertEquals(Instant.ofEpochMilli(123456789L), actual.serverTime());
    }
}
