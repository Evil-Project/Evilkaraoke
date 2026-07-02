package org.evilproject.evilkaraoke.client.audio;

import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;

public interface AudioBackend {
    void play(AudioCommandPacket packet);

    void pause(AudioCommandPacket packet);

    void resume(AudioCommandPacket packet);

    void stop(AudioCommandPacket packet);

    /** Adjusts output gain (0..1) without restarting playback. */
    default void setVolume(float linearGain) {
    }

    AudioBackendStatus status();
}
