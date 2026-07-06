package org.evilproject.evilkaraoke.client.audio;

import java.io.InputStream;

import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;

public interface AudioBackend {
    void play(AudioCommandPacket packet);

    void playStream(AudioCommandPacket packet, InputStream source);

    void pause(AudioCommandPacket packet);

    void resume(AudioCommandPacket packet);

    void stop(AudioCommandPacket packet);

    /** Adjusts output gain (0..1) without restarting playback. */
    default void setVolume(float linearGain) {
    }

    /** Applies the local Minecraft sound-slider gain (0..1) without restarting playback. */
    default void setGameVolume(float linearGain) {
    }

    AudioBackendStatus status();
}
