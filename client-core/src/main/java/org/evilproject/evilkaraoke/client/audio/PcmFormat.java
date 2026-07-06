package org.evilproject.evilkaraoke.client.audio;

/**
 * Fixed PCM format Evilkaraoke decodes into before handing buffers to OpenAL.
 * 48 kHz signed 16-bit stereo matches Minecraft's OpenAL mixer and the upstream
 * Neurokaraoke bot's Opus target, keeping resampling on the decoder side.
 */
public final class PcmFormat {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int BYTES_PER_FRAME = CHANNELS * (BITS_PER_SAMPLE / 8);

    private PcmFormat() {
    }
}
