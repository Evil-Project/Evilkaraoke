package org.evilproject.evilkaraoke.common.audio;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;

public final class JavaSoundPcmDecoder {
    private static final int AUDIO_SIGNATURE_BYTES = 512;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int FALLBACK_SAMPLE_RATE = 48_000;
    private static final int FALLBACK_CHANNELS = 2;

    private JavaSoundPcmDecoder() {
    }

    public static AudioInputStream openPcmStream(InputStream source) throws Exception {
        InputStream markable = source.markSupported() ? source : new BufferedInputStream(source, 1 << 16);
        AudioProbe probe = probeAudioKind(markable);
        AudioInputStream encoded;
        if (probe.kind() == EncodedAudioKind.WAV) {
            // Parsed by hand instead of via AudioSystem: generic SPI probing lets the
            // Ogg readers scan the whole stream for a page marker before rejecting it,
            // which over a still-downloading stream blocks until the download ends.
            encoded = openWavStream(markable);
        } else if (probe.kind() != EncodedAudioKind.UNKNOWN) {
            if (probe.kind() == EncodedAudioKind.MP3 && probe.skipBytes() > 0) {
                skipFully(markable, probe.skipBytes());
            }
            encoded = readerFor(probe.kind()).getAudioInputStream(markable);
        } else {
            encoded = AudioSystem.getAudioInputStream(markable);
        }
        return openPcmAudioInputStream(encoded);
    }

    public static AudioFormat pcmFormatFor(AudioFormat source) {
        float sampleRate = source.getSampleRate() > 0 ? source.getSampleRate() : FALLBACK_SAMPLE_RATE;
        int channels = source.getChannels() > 0 ? source.getChannels() : FALLBACK_CHANNELS;
        int frameSize = channels * (BITS_PER_SAMPLE / 8);
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                BITS_PER_SAMPLE,
                channels,
                frameSize,
                sampleRate,
                false);
    }

    private static AudioInputStream openPcmAudioInputStream(AudioInputStream encoded) {
        AudioFormat target = pcmFormatFor(encoded.getFormat());
        for (FormatConversionProvider provider : conversionProviders()) {
            if (provider.isConversionSupported(target, encoded.getFormat())) {
                return provider.getAudioInputStream(target, encoded);
            }
        }
        return AudioSystem.getAudioInputStream(target, encoded);
    }

    private static AudioProbe probeAudioKind(InputStream source) throws java.io.IOException {
        source.mark(AUDIO_SIGNATURE_BYTES);
        byte[] header = source.readNBytes(AUDIO_SIGNATURE_BYTES);
        source.reset();
        if (startsWith(header, 'O', 'g', 'g', 'S')) {
            if (contains(header, "OpusHead")) {
                return new AudioProbe(EncodedAudioKind.OPUS, 0);
            }
            if (contains(header, "\u0001vorbis")) {
                return new AudioProbe(EncodedAudioKind.VORBIS, 0);
            }
        }
        if (startsWith(header, 'R', 'I', 'F', 'F') && header.length >= 12
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E') {
            return new AudioProbe(EncodedAudioKind.WAV, 0);
        }
        if (startsWith(header, 'I', 'D', '3')) {
            return new AudioProbe(EncodedAudioKind.MP3, 0);
        }
        int frameOffset = mpegFrameOffset(header);
        if (frameOffset >= 0) {
            return new AudioProbe(EncodedAudioKind.MP3, frameOffset);
        }
        return new AudioProbe(EncodedAudioKind.UNKNOWN, 0);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int mpegFrameOffset(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (looksLikeMpegFrame(bytes, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksLikeMpegFrame(byte[] bytes, int offset) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return false;
        }
        int version = (b1 >>> 3) & 0x03;
        int layer = (b1 >>> 1) & 0x03;
        int bitrate = (b2 >>> 4) & 0x0F;
        int sampleRate = (b2 >>> 2) & 0x03;
        return version != 0x01
                && layer != 0x00
                && bitrate != 0x00
                && bitrate != 0x0F
                && sampleRate != 0x03;
    }

    private static boolean contains(byte[] bytes, String needle) {
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i <= bytes.length - target.length; i++) {
            boolean matched = true;
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static AudioFileReader readerFor(EncodedAudioKind kind) {
        return switch (kind) {
            case OPUS -> new io.github.jseproject.OpusAudioFileReader();
            case VORBIS -> new io.github.jseproject.VorbisAudioFileReader();
            case MP3 -> new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader();
            case WAV, UNKNOWN -> throw new IllegalArgumentException("No dedicated reader for " + kind);
        };
    }

    private static AudioInputStream openWavStream(InputStream stream) throws java.io.IOException {
        skipFully(stream, 12);
        AudioFormat format = null;
        while (true) {
            byte[] chunkHeader = stream.readNBytes(8);
            if (chunkHeader.length < 8) {
                throw new EOFException("WAV stream ended before its data chunk");
            }
            long chunkSize = leUnsignedInt(chunkHeader, 4);
            if (chunkHeader[0] == 'f' && chunkHeader[1] == 'm' && chunkHeader[2] == 't' && chunkHeader[3] == ' ') {
                if (chunkSize < 16 || chunkSize > AUDIO_SIGNATURE_BYTES) {
                    throw new java.io.IOException("Unsupported WAV fmt chunk of " + chunkSize + " bytes");
                }
                byte[] fmt = stream.readNBytes((int) chunkSize);
                if (fmt.length < 16) {
                    throw new EOFException("WAV stream ended inside its fmt chunk");
                }
                format = wavFormat(fmt);
                skipPadding(stream, chunkSize);
            } else if (chunkHeader[0] == 'd' && chunkHeader[1] == 'a' && chunkHeader[2] == 't' && chunkHeader[3] == 'a') {
                if (format == null) {
                    throw new java.io.IOException("WAV stream has no fmt chunk before its data chunk");
                }
                long frames = format.getFrameSize() > 0 ? chunkSize / format.getFrameSize() : AudioSystem.NOT_SPECIFIED;
                return new AudioInputStream(stream, format, frames);
            } else {
                skipChunk(stream, chunkSize);
                skipPadding(stream, chunkSize);
            }
        }
    }

    private static AudioFormat wavFormat(byte[] fmt) throws java.io.IOException {
        int formatCode = leUnsignedShort(fmt, 0);
        // 1 = integer PCM; 0xFFFE = WAVE_FORMAT_EXTENSIBLE, treated as PCM. Anything
        // else (float, ADPCM, embedded MP3, ...) is not worth decoding by hand.
        if (formatCode != 1 && formatCode != 0xFFFE) {
            throw new java.io.IOException("Unsupported WAV format code " + formatCode);
        }
        int channels = leUnsignedShort(fmt, 2);
        long sampleRate = leUnsignedInt(fmt, 4);
        int bitsPerSample = leUnsignedShort(fmt, 14);
        if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || bitsPerSample % 8 != 0) {
            throw new java.io.IOException("Unsupported WAV fmt values");
        }
        int frameSize = channels * (bitsPerSample / 8);
        AudioFormat.Encoding encoding = bitsPerSample == 8
                ? AudioFormat.Encoding.PCM_UNSIGNED
                : AudioFormat.Encoding.PCM_SIGNED;
        return new AudioFormat(encoding, sampleRate, bitsPerSample, channels, frameSize, sampleRate, false);
    }

    private static void skipChunk(InputStream stream, long chunkSize) throws java.io.IOException {
        long remaining = chunkSize;
        while (remaining > 0) {
            int step = (int) Math.min(remaining, Integer.MAX_VALUE);
            skipFully(stream, step);
            remaining -= step;
        }
    }

    private static void skipPadding(InputStream stream, long chunkSize) throws java.io.IOException {
        if ((chunkSize & 1L) != 0L) {
            skipFully(stream, 1);
        }
    }

    private static int leUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long leUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static FormatConversionProvider[] conversionProviders() {
        return new FormatConversionProvider[] {
                new io.github.jseproject.OpusFormatConversionProvider(),
                new io.github.jseproject.VorbisFormatConversionProvider(),
                new javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider()
        };
    }

    private static void skipFully(InputStream stream, int bytes) throws java.io.IOException {
        int remaining = bytes;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (stream.read() == -1) {
                throw new EOFException("Unexpected end of audio stream while seeking MPEG frame");
            }
            remaining--;
        }
    }

    private record AudioProbe(EncodedAudioKind kind, int skipBytes) {
    }

    private enum EncodedAudioKind {
        OPUS,
        VORBIS,
        MP3,
        WAV,
        UNKNOWN
    }
}
