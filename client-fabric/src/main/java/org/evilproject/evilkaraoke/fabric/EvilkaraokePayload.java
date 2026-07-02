package org.evilproject.evilkaraoke.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Thin wrapper that carries Evilkaraoke's JSON protocol bytes as a Minecraft
 * custom payload. One payload type is registered per channel id so the loader
 * networking layer can route audio/hello/status traffic without the client ever
 * interpreting queue or command state.
 */
public record EvilkaraokePayload(CustomPacketPayload.Type<EvilkaraokePayload> payloadType, byte[] data) implements CustomPacketPayload {
    public static CustomPacketPayload.Type<EvilkaraokePayload> type(String channel) {
        return new CustomPacketPayload.Type<>(Identifier.parse(channel));
    }

    public static StreamCodec<RegistryFriendlyByteBuf, EvilkaraokePayload> codec(CustomPacketPayload.Type<EvilkaraokePayload> type) {
        return StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data()),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new EvilkaraokePayload(type, bytes);
                });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return payloadType;
    }
}
