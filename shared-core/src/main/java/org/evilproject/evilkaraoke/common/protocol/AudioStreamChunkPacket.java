package org.evilproject.evilkaraoke.common.protocol;

import java.util.Base64;
import java.util.Objects;

public record AudioStreamChunkPacket(
        String sessionId,
        String playbackId,
        int sequence,
        String data,
        float sampleRate,
        int channels,
        int bitsPerSample,
        boolean end,
        String error
) implements ProtocolPacket {
    public AudioStreamChunkPacket {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playbackId, "playbackId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence cannot be negative");
        }
        data = data == null ? "" : data;
        sampleRate = sampleRate > 0.0f ? sampleRate : 48_000.0f;
        channels = channels > 0 ? channels : 2;
        bitsPerSample = bitsPerSample > 0 ? bitsPerSample : 16;
        error = error == null ? "" : error;
    }

    public AudioStreamChunkPacket(String sessionId, String playbackId, int sequence, String data, boolean end, String error) {
        this(sessionId, playbackId, sequence, data, 48_000.0f, 2, 16, end, error);
    }

    public static AudioStreamChunkPacket chunk(String sessionId, String playbackId, int sequence, byte[] data, int length) {
        return chunk(sessionId, playbackId, sequence, data, length, 48_000.0f, 2, 16);
    }

    public static AudioStreamChunkPacket chunk(
            String sessionId,
            String playbackId,
            int sequence,
            byte[] data,
            int length,
            float sampleRate,
            int channels,
            int bitsPerSample) {
        byte[] exact = length == data.length ? data : java.util.Arrays.copyOf(data, length);
        return new AudioStreamChunkPacket(sessionId, playbackId, sequence, Base64.getEncoder().encodeToString(exact),
                sampleRate, channels, bitsPerSample, false, "");
    }

    public static AudioStreamChunkPacket end(String sessionId, String playbackId, int sequence) {
        return new AudioStreamChunkPacket(sessionId, playbackId, sequence, "", true, "");
    }

    public static AudioStreamChunkPacket error(String sessionId, String playbackId, int sequence, String error) {
        return new AudioStreamChunkPacket(sessionId, playbackId, sequence, "", true, error);
    }

    public byte[] decodedData() {
        return data.isEmpty() ? new byte[0] : Base64.getDecoder().decode(data);
    }
}
