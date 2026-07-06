package org.evilproject.evilkaraoke.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.api.Test;

class AudioUrlValidatorTest {
    @Test
    void acceptsPublicHttpUrl() {
        URI uri = AudioUrlValidator.validatePublicHttpUrl(
                "https://cdn.example.com/audio/song.mp3?token=abc",
                resolvingTo("93.184.216.34"));

        assertEquals("https://cdn.example.com/audio/song.mp3?token=abc", uri.toASCIIString());
    }

    @Test
    void detectsHttpSchemeWithoutTreatingTextAsUrl() {
        assertTrue(AudioUrlValidator.hasHttpScheme("https://example.com/song.mp3"));
        assertTrue(AudioUrlValidator.hasHttpScheme("HTTP://example.com/song.mp3"));
        assertFalse(AudioUrlValidator.hasHttpScheme("never gonna give you up"));
        assertFalse(AudioUrlValidator.hasHttpScheme("ftp://example.com/song.mp3"));
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("file:///tmp/song.mp3", resolvingTo("93.184.216.34")));
    }

    @Test
    void rejectsUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://user:pass@example.com/song.mp3", resolvingTo("93.184.216.34")));
    }

    @Test
    void rejectsFragment() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://example.com/song.mp3#part", resolvingTo("93.184.216.34")));
    }

    @Test
    void rejectsInvalidAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://example.com:99999/song.mp3", resolvingTo("93.184.216.34")));
    }

    @Test
    void rejectsLocalHostnames() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("http://localhost/song.mp3", resolvingTo("93.184.216.34")));
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("http://speaker.local/song.mp3", resolvingTo("93.184.216.34")));
    }

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("http://127.0.0.1/song.mp3", InetAddress::getAllByName));
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://music.example/song.mp3", resolvingTo("10.0.0.4")));
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://music.example/song.mp3", resolvingTo("192.168.1.4")));
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://music.example/song.mp3", resolvingTo("169.254.169.254")));
    }

    @Test
    void rejectsUniqueLocalIpv6Addresses() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://music.example/song.mp3", resolvingTo("fd00::1")));
    }

    @Test
    void acceptsProxyFakeIpForDomainNames() {
        URI uri = AudioUrlValidator.validatePublicHttpUrl(
                "https://audio.neurokaraoke.com/song.ogg",
                resolvingTo("198.18.0.54", "::ffff:0:c612:36"));

        assertEquals("https://audio.neurokaraoke.com/song.ogg", uri.toASCIIString());
    }

    @Test
    void rejectsLiteralProxyFakeIpAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://198.18.0.54/song.ogg", resolvingTo("198.18.0.54")));
        assertThrows(IllegalArgumentException.class,
                () -> AudioUrlValidator.validatePublicHttpUrl("https://[::ffff:0:c612:36]/song.ogg", resolvingTo("::ffff:0:c612:36")));
    }

    private static AudioUrlValidator.AddressResolver resolvingTo(String... addresses) {
        return ignored -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        };
    }
}
