package org.evilproject.evilkaraoke.client.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NeurokaraokeLyricsClientTest {
    private static final UUID SONG_ID = UUID.fromString("23b51bed-6827-4556-a427-de75940bfcdf");

    @Test
    void parsesDotNetTimespanCuesAndSortsThem() {
        var lyrics = NeurokaraokeLyricsClient.parseLyrics("""
                [
                  {"time":"00:00:11.8200000","text":"  Second\nline  "},
                  {"time":"00:00:10.0700000","text":"First line"},
                  {"time":"invalid","text":"Ignored"},
                  {"time":"00:00:12.0000000","text":"   "}
                ]
                """);

        assertEquals(2, lyrics.size());
        assertEquals(Duration.ofMillis(10_070), lyrics.get(0).offset());
        assertEquals("First line", lyrics.get(0).text());
        assertEquals(Duration.ofMillis(11_820), lyrics.get(1).offset());
        assertEquals("Second line", lyrics.get(1).text());
    }

    @Test
    void preservesSevenDigitFractionPrecision() {
        assertEquals(Duration.ofNanos(100), NeurokaraokeLyricsClient.parseTimespan("00:00:00.0000001"));
        assertEquals(Duration.ofHours(25).plusMinutes(2).plusSeconds(3),
                NeurokaraokeLyricsClient.parseTimespan("25:02:03.0000000"));
    }

    @Test
    void acceptsAnEmptyLyricsResponse() {
        assertEquals(java.util.List.of(), NeurokaraokeLyricsClient.parseLyrics("[]"));
    }

    @Test
    void rejectsUnsupportedRootData() {
        assertThrows(IllegalStateException.class, () -> NeurokaraokeLyricsClient.parseLyrics("{}"));
    }

    @Test
    void buildsTheDedicatedLyricsEndpoint() {
        assertEquals(
                URI.create("https://api.neurokaraoke.com/api/songs/" + SONG_ID + "/lyrics"),
                NeurokaraokeLyricsClient.lyricsUri(
                        URI.create("https://api.neurokaraoke.com/api/songs/"), SONG_ID));
    }
}
