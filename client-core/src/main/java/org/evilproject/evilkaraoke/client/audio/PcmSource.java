package org.evilproject.evilkaraoke.client.audio;

import java.io.Closeable;

/**
 * Pulls decoded 48 kHz S16LE stereo PCM from an audio source. Implementations
 * own their own network/decoder threads and must be safe to {@link #close()}
 * from the render thread when playback is stopped or skipped.
 */
public interface PcmSource extends Closeable {
    /**
     * Reads up to {@code dst.length} bytes of interleaved PCM.
     *
     * @return number of bytes written, {@code 0} if the source is buffering but
     *         not yet exhausted, or {@code -1} at end of stream.
     */
    int read(byte[] dst) throws Exception;

    /** @return true while the source can still produce audio (streaming/radio). */
    boolean isOpen();
}
