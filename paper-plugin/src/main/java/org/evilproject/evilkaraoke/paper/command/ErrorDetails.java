package org.evilproject.evilkaraoke.paper.command;

import java.util.concurrent.CompletionException;

final class ErrorDetails {
    private static final int MAX_ERROR_DETAIL_CHARS = 240;

    private ErrorDetails() {
    }

    static String safe(Throwable error) {
        error = unwrap(error);
        String detail = error == null ? "" : error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        if (detail.length() <= MAX_ERROR_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_ERROR_DETAIL_CHARS - 31) + "... (truncated; see server log)";
    }

    static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
