package org.evilproject.evilkaraoke.common.codec;

public final class PacketEnvelope<T> {
    private final int version;
    private final String type;
    private final T payload;

    public PacketEnvelope(int version, String type, T payload) {
        this.version = version;
        this.type = type;
        this.payload = payload;
    }

    public int version() {
        return version;
    }

    public String type() {
        return type;
    }

    public T payload() {
        return payload;
    }
}
