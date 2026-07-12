package org.evilproject.evilkaraoke.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class LyricsDisplayActionTest {
    @Test
    void parsesCommandArgumentsCaseInsensitively() {
        assertEquals(Optional.of(LyricsDisplayAction.TOGGLE), LyricsDisplayAction.parseCommandArgument(null));
        assertEquals(Optional.of(LyricsDisplayAction.TOGGLE), LyricsDisplayAction.parseCommandArgument(""));
        assertEquals(Optional.of(LyricsDisplayAction.ENABLE), LyricsDisplayAction.parseCommandArgument("EnAbLe"));
        assertEquals(Optional.of(LyricsDisplayAction.DISABLE), LyricsDisplayAction.parseCommandArgument("DISABLE"));
        assertEquals(Optional.empty(), LyricsDisplayAction.parseCommandArgument("toggle"));
        assertEquals(Optional.empty(), LyricsDisplayAction.parseCommandArgument("unknown"));
    }

    @Test
    void mapsStableWireReasonsAndRejectsUnknownActions() {
        assertEquals("lyrics-toggle", LyricsDisplayAction.TOGGLE.reason());
        assertEquals("lyrics-enable", LyricsDisplayAction.ENABLE.reason());
        assertEquals("lyrics-disable", LyricsDisplayAction.DISABLE.reason());
        assertEquals(Optional.of(LyricsDisplayAction.ENABLE), LyricsDisplayAction.fromReason("LYRICS-ENABLE"));
        assertEquals(Optional.of(LyricsDisplayAction.DISABLE), LyricsDisplayAction.fromReason("lyrics-disable"));
        assertEquals(Optional.empty(), LyricsDisplayAction.fromReason(null));
        assertEquals(Optional.empty(), LyricsDisplayAction.fromReason(""));
        assertEquals(Optional.empty(), LyricsDisplayAction.fromReason("future-action"));
    }

    @Test
    void appliesToggleAndIdempotentExplicitStates() {
        assertFalse(LyricsDisplayAction.TOGGLE.apply(true));
        assertTrue(LyricsDisplayAction.TOGGLE.apply(false));
        assertTrue(LyricsDisplayAction.ENABLE.apply(false));
        assertTrue(LyricsDisplayAction.ENABLE.apply(true));
        assertFalse(LyricsDisplayAction.DISABLE.apply(true));
        assertFalse(LyricsDisplayAction.DISABLE.apply(false));
    }
}
