package org.evilproject.evilkaraoke.paper.integration;

import org.evilproject.evilkaraoke.common.model.*;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying core plugin functionality works correctly.
 * Tests queue management, playback state transitions, and volume control
 * without requiring a live API endpoint or Minecraft server.
 */
class PluginIntegrationTest {
    private KaraokeSession session;
    private KaraokeTrack sampleTrack;

    @BeforeEach
    void setup() {
        session = new KaraokeSession();
        sampleTrack = new KaraokeTrack(
                "test-id",
                TrackType.SONG,
                "Test Song",
                "Test Artist",
                new AudioAsset("https://example.com/test.opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(3)
        );
    }

    @Test
    void sessionQueueManagement() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        // Queue tracks
        session.request(sampleTrack, player1, "Player1");
        session.request(sampleTrack, player2, "Player2");

        assertEquals(2, session.requestQueueSize());
        assertEquals(PlaybackState.IDLE, session.state());

        // Start playback
        var next = session.next();
        assertTrue(next.isPresent());
        assertEquals("Player1", next.get().requesterName());
        assertEquals(PlaybackState.PLAYING, session.state());

        // Pause/resume
        session.pause();
        assertEquals(PlaybackState.PAUSED, session.state());

        session.resume();
        assertEquals(PlaybackState.PLAYING, session.state());

        // Skip
        assertTrue(session.skip());
        assertEquals(PlaybackState.IDLE, session.state());

        // Next track should be Player2's
        next = session.next();
        assertTrue(next.isPresent());
        assertEquals("Player2", next.get().requesterName());
    }

    @Test
    void sessionVolumeControl() {
        assertEquals(1.0f, session.getVolume(), 0.01f);

        session.setVolume(0.5f);
        assertEquals(0.5f, session.getVolume(), 0.01f);

        // Clamp to [0.0, 2.0]
        session.setVolume(-0.5f);
        assertEquals(0.0f, session.getVolume(), 0.01f);

        session.setVolume(3.0f);
        assertEquals(2.0f, session.getVolume(), 0.01f);
    }

    @Test
    void sessionStopClearsQueue() {
        session.request(sampleTrack, UUID.randomUUID(), "Player1");
        session.request(sampleTrack, UUID.randomUUID(), "Player2");
        session.next();

        assertTrue(session.current().isPresent());
        assertEquals(1, session.requestQueueSize());

        session.stop();

        assertFalse(session.current().isPresent());
        assertEquals(0, session.requestQueueSize());
        assertEquals(PlaybackState.IDLE, session.state());
    }

    @Test
    void sessionRandomQueue() {
        KaraokeTrack random1 = new KaraokeTrack(
                "random-1", TrackType.SONG, "Random 1", "Artist",
                new AudioAsset("https://example.com/r1.opus", AudioFormat.OPUS),
                null, Duration.ofMinutes(2)
        );
        KaraokeTrack random2 = new KaraokeTrack(
                "random-2", TrackType.SONG, "Random 2", "Artist",
                new AudioAsset("https://example.com/r2.opus", AudioFormat.OPUS),
                null, Duration.ofMinutes(2)
        );

        session.addRandom(random1);
        session.addRandom(random2);

        assertEquals(2, session.randomQueueSize());

        // Requests have priority over random
        session.request(sampleTrack, UUID.randomUUID(), "RequestUser");

        var next = session.next();
        assertTrue(next.isPresent());
        assertEquals(sampleTrack.id(), next.get().track().id());

        // Now random should play
        next = session.next();
        assertTrue(next.isPresent());
        assertEquals("random-1", next.get().track().id());
        assertEquals("Evilkaraoke", next.get().requesterName());
    }

    @Test
    void playbackTargetModes() {
        // Test @a (all players)
        var allPlayers = new PlaybackTarget(
                TargetMode.ALL,
                "@a",
                SoundCategory.MUSIC,
                null,
                1.0f,
                1.0f,
                0.0f
        );
        assertEquals(TargetMode.ALL, allPlayers.mode());
        assertNull(allPlayers.position());

        // Test positional audio
        Position pos = new Position("world", 100, 64, -50);
        var positional = new PlaybackTarget(
                TargetMode.SELECTOR,
                "@a",
                SoundCategory.MUSIC,
                pos,
                1.0f,
                1.0f,
                0.1f
        );
        assertEquals(TargetMode.SELECTOR, positional.mode());
        assertNotNull(positional.position());
        assertEquals(100, positional.position().x());
    }

    @Test
    void playbackStateTransitions() {
        // IDLE -> PLAYING
        assertEquals(PlaybackState.IDLE, session.state());
        session.request(sampleTrack, UUID.randomUUID(), "Player");
        session.next();
        assertEquals(PlaybackState.PLAYING, session.state());

        // PLAYING -> PAUSED
        session.pause();
        assertEquals(PlaybackState.PAUSED, session.state());

        // PAUSED -> PLAYING
        session.resume();
        assertEquals(PlaybackState.PLAYING, session.state());

        // PLAYING -> IDLE (via stop)
        session.stop();
        assertEquals(PlaybackState.IDLE, session.state());
    }

    @Test
    void offsetTracking() {
        session.request(sampleTrack, UUID.randomUUID(), "Player");
        session.next();
        session.startTimer(); // Start timer to simulate client beginning playback

        // Should start at zero
        var offset = session.offset();
        assertTrue(offset.toMillis() < 100, "Offset should be near zero at start");

        // Pausing should capture offset
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.pause();
        var pausedOffset = session.offset();
        assertTrue(pausedOffset.toMillis() >= 0, "Paused offset should be non-negative");

        // Resume should maintain offset
        session.resume();
        var resumedOffset = session.offset();
        assertTrue(resumedOffset.toMillis() >= pausedOffset.toMillis(),
                "Resumed offset should continue from paused offset");
    }
}
