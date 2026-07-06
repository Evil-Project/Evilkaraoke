package org.evilproject.evilkaraoke.server.api;

import java.net.URI;

public final class NeurokaraokeApiUnavailableException extends IllegalStateException {
    private final URI uri;
    private final int attempts;

    public NeurokaraokeApiUnavailableException(URI uri, int attempts, Throwable cause) {
        super("Could not reach " + host(uri) + " after " + attempts
                + " attempts (" + causeName(cause) + ")", cause);
        this.uri = uri;
        this.attempts = attempts;
    }

    public URI uri() {
        return uri;
    }

    public int attempts() {
        return attempts;
    }

    private static String host(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "Neurokaraoke API";
        }
        return uri.getHost();
    }

    private static String causeName(Throwable cause) {
        return cause == null ? "unknown error" : cause.getClass().getSimpleName();
    }
}
