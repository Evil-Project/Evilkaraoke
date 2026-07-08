package org.evilproject.evilkaraoke.common.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.junit.jupiter.api.Test;

class PacketDebugFormatterTest {
    @Test
    void describesAudioCommandWithoutDumpingAssetUrls() {
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                "playback-1",
                new KaraokeTrack(
                        "song-1",
                        TrackType.SONG,
                        "Test Song",
                        "Test Artist",
                        new AudioAsset("https://audio.example/song.opus", AudioFormat.OPUS),
                        null,
                        Duration.ofMinutes(3)),
                PlaybackTarget.allPlayers(),
                Duration.ofSeconds(2),
                Instant.EPOCH,
                "debug",
                Duration.ZERO,
                AudioDeliveryMode.SERVER_STREAM);

        String description = PacketDebugFormatter.describe(packet);

        assertTrue(description.contains("AudioCommand command=PLAY"));
        assertTrue(description.contains("playbackId=playback-1"));
        assertTrue(description.contains("delivery=SERVER_STREAM"));
        assertTrue(description.contains("track=song-1/Test Song"));
        assertFalse(description.contains("https://audio.example/song.opus"));
    }

    @Test
    void describesStreamChunkByCountersOnly() {
        AudioStreamChunkPacket packet = AudioStreamChunkPacket.chunk(
                "global",
                "playback-1",
                7,
                new byte[] {1, 2, 3, 4},
                4);

        String description = PacketDebugFormatter.describe(packet);

        assertTrue(description.contains("AudioStreamChunk playbackId=playback-1"));
        assertTrue(description.contains("sequence=7"));
        assertTrue(description.contains("dataChars=8"));
    }
}
