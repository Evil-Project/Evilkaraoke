package org.evilproject.evilkaraoke.common.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;

import org.evilproject.evilkaraoke.common.audio.ServerStreamQualitySelector.ClientHealth;
import org.evilproject.evilkaraoke.common.audio.ServerStreamQualitySelector.StreamQuality;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.junit.jupiter.api.Test;

class ServerStreamQualitySelectorTest {
    @Test
    void healthyAudienceUsesHigherQualityFallbackAsset() {
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                track(),
                List.of(new ClientHealth(40, 10_000L, 9_500L, 500, 0L)));

        assertEquals(StreamQuality.HIGH_QUALITY, selection.quality());
        assertEquals("https://audio.example/high.wav", selection.track().primaryAsset().url());
        assertEquals("https://audio.example/low.opus", selection.track().fallbackAsset().url());
    }

    @Test
    void highPingUsesLowTrafficPrimaryAsset() {
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                track(),
                List.of(new ClientHealth(250, 10_000L, 9_500L, 500, 0L)));

        assertEquals(StreamQuality.LOW_TRAFFIC, selection.quality());
        assertEquals("https://audio.example/low.opus", selection.track().primaryAsset().url());
        assertEquals("high-ping", selection.reason());
    }

    @Test
    void missingChunksUseLowTrafficPrimaryAsset() {
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                track(),
                List.of(new ClientHealth(40, 10_000L, 9_500L, 500, 1L)));

        assertEquals(StreamQuality.LOW_TRAFFIC, selection.quality());
        assertEquals("https://audio.example/low.opus", selection.track().primaryAsset().url());
        assertEquals("missing-stream-chunks", selection.reason());
    }

    @Test
    void unknownClientHealthDefaultsToHighQuality() {
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                track(),
                List.of(new ClientHealth(-1, 0L, 0L, 0, 0L)));

        assertEquals(StreamQuality.HIGH_QUALITY, selection.quality());
        assertEquals("https://audio.example/high.wav", selection.track().primaryAsset().url());
    }

    @Test
    void noClientHealthDefaultsToHighQuality() {
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(track(), List.of());

        assertEquals(StreamQuality.HIGH_QUALITY, selection.quality());
        assertEquals("https://audio.example/high.wav", selection.track().primaryAsset().url());
    }

    @Test
    void singleAssetTrackKeepsPrimaryAsset() {
        KaraokeTrack singleAsset = new KaraokeTrack(
                "song",
                TrackType.SONG,
                "Song",
                "Artist",
                new AudioAsset("https://audio.example/only.opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(3));

        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                singleAsset,
                List.of(new ClientHealth(40, 10_000L, 9_500L, 500, 0L)));

        assertEquals(StreamQuality.LOW_TRAFFIC, selection.quality());
        assertEquals("https://audio.example/only.opus", selection.track().primaryAsset().url());
    }

    private static KaraokeTrack track() {
        return new KaraokeTrack(
                "song",
                TrackType.SONG,
                "Song",
                "Artist",
                new AudioAsset("https://audio.example/low.opus", AudioFormat.OPUS),
                new AudioAsset("https://audio.example/high.wav", AudioFormat.UNKNOWN),
                Duration.ofMinutes(3));
    }
}
