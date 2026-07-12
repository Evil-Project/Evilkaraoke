package org.evilproject.evilkaraoke.client.audio;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;

public interface AudioBackend {
    void play(AudioCommandPacket packet);

    void playStream(AudioCommandPacket packet, InputStream source);

    default void playPcmStream(AudioCommandPacket packet, InputStream source, float sampleRate, int channels, int bitsPerSample) {
        playStream(packet, source);
    }

    void pause(AudioCommandPacket packet);

    void resume(AudioCommandPacket packet);

    void stop(AudioCommandPacket packet);

    /** Adjusts output gain (0..1) without restarting playback. */
    default void setVolume(float linearGain) {
    }

    /** Applies the local Minecraft sound-slider gain (0..1) without restarting playback. */
    default void setGameVolume(float linearGain) {
    }

    /** Returns the current position in the complete track timeline when available. */
    default Optional<Duration> playbackPosition() {
        return Optional.empty();
    }

    AudioBackendStatus status();
}
