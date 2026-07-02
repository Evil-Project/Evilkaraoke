package org.evilproject.evilkaraoke.client.audio;

import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;

public record AudioBackendStatus(ClientPlaybackState state, String message) {
    public static AudioBackendStatus ready() {
        return new AudioBackendStatus(ClientPlaybackState.READY, "Audio backend ready");
    }

    public static AudioBackendStatus error(String message) {
        return new AudioBackendStatus(ClientPlaybackState.ERROR, message);
    }
}
