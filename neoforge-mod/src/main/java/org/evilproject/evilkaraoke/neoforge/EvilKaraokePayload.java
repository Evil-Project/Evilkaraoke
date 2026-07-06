package org.evilproject.evilkaraoke.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * NeoForge custom payload carrying Evilkaraoke's JSON protocol bytes for a single
 * channel. Kept deliberately dumb: the client never parses queue/command state,
 * only forwards bytes to the shared audio controller.
 */
public record EvilKaraokePayload(Type<EvilKaraokePayload> payloadType, byte[] data) implements CustomPacketPayload {
    public static Type<EvilKaraokePayload> type(String channel) {
        return new Type<>(Identifier.parse(channel));
    }

    public static StreamCodec<FriendlyByteBuf, EvilKaraokePayload> codec(Type<EvilKaraokePayload> type) {
        return StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data()),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new EvilKaraokePayload(type, bytes);
                });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return payloadType;
    }
}
