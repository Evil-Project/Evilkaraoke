package org.evilproject.evilkaraoke.common.model;

import java.util.Objects;

public record AudioAsset(String url, AudioFormat format) {
    public AudioAsset {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(format, "format");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url cannot be blank");
        }
    }
}
