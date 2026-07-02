package org.evilproject.evilkaraoke.paper.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatMessagesTest {
    @Test
    void safeErrorDetailTruncatesLargeMessages() {
        String detail = ErrorDetails.safe(new IllegalStateException("x".repeat(10_000)));

        assertTrue(detail.length() < 300);
        assertTrue(detail.endsWith("(truncated; see server log)"));
    }
}
