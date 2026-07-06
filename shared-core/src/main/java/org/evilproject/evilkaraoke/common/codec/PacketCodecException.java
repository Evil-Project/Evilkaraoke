package org.evilproject.evilkaraoke.common.codec;

public final class PacketCodecException extends RuntimeException {
    public PacketCodecException(String message) {
        super(message);
    }

    public PacketCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
