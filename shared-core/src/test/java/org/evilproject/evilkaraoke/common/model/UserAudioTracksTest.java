package org.evilproject.evilkaraoke.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;

import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;
import org.junit.jupiter.api.Test;

class UserAudioTracksTest {
    @Test
    void createsTrackFromPublicUrlWithoutLeakingUrlIntoIdOrTitle() {
        KaraokeTrack track = UserAudioTracks.fromUrl(
                "https://cdn.example.com/audio/Never-Gonna-Give-You-Up.mp3?token=secret",
                null,
                resolvingTo("93.184.216.34"));

        assertTrue(track.id().startsWith("url:"));
        assertFalse(track.id().contains("secret"));
        assertEquals("Never Gonna Give You Up", track.title());
        assertEquals("User URL", track.artist());
        assertEquals(AudioFormat.MP3, track.primaryAsset().format());
        assertEquals("https://cdn.example.com/audio/Never-Gonna-Give-You-Up.mp3?token=secret", track.primaryAsset().url());
        assertNull(track.duration());
    }

    @Test
    void usesCleanProvidedTitle() {
        KaraokeTrack track = UserAudioTracks.fromUrl(
                "https://cdn.example.com/audio/song.ogg",
                "  My\tSong\nTitle  ",
                resolvingTo("93.184.216.34"));

        assertEquals("My Song Title", track.title());
        assertEquals(AudioFormat.OGG, track.primaryAsset().format());
    }

    @Test
    void rejectsUnsafeUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> UserAudioTracks.fromUrl("https://music.example/song.mp3", null, resolvingTo("10.0.0.1")));
    }

    private static AudioUrlValidator.AddressResolver resolvingTo(String address) {
        return ignored -> new InetAddress[] {InetAddress.getByName(address)};
    }
}
