package org.evilproject.evilkaraoke.paper.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.channels.ClosedChannelException;

import org.junit.jupiter.api.Test;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeApiUnavailableException;

class ChatMessagesTest {
    @Test
    void safeErrorDetailTruncatesLargeMessages() {
        String detail = ErrorDetails.safe(new IllegalStateException("x".repeat(10_000)));

        assertTrue(detail.length() < 300);
        assertTrue(detail.endsWith("(truncated; see server log)"));
    }

    @Test
    void apiUnavailableErrorsUseClearChatPrefix() {
        var error = new NeurokaraokeApiUnavailableException(
                URI.create("https://api.neurokaraoke.com/api/songs"),
                4,
                new ClosedChannelException());

        String message = ChatMessages.plain(ChatMessages.error("No match for \"never\"", error));

        assertTrue(message.startsWith(
                "Neurokaraoke API unavailable: Could not reach api.neurokaraoke.com after 4 attempts"));
    }
}
