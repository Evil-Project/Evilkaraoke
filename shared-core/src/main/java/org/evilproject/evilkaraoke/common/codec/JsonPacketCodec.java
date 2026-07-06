package org.evilproject.evilkaraoke.common.codec;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;

public final class JsonPacketCodec {
    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_AUDIO_COMMAND = "audio_command";
    public static final String TYPE_AUDIO_STREAM_CHUNK = "audio_stream_chunk";
    public static final String TYPE_STATUS = "status";

    private static final Type ENVELOPE_TYPE = new TypeToken<PacketEnvelope<JsonObject>>() {
    }.getType();

    private final Gson gson;

    public JsonPacketCodec() {
        gson = new GsonBuilder()
                .registerTypeAdapter(Duration.class, (JsonSerializer<Duration>) (src, typeOfSrc, context) -> context.serialize(src.toMillis()))
                .registerTypeAdapter(Duration.class, (JsonDeserializer<Duration>) (json, typeOfT, context) -> Duration.ofMillis(json.getAsLong()))
                .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, typeOfSrc, context) -> context.serialize(src.toEpochMilli()))
                .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, typeOfT, context) -> Instant.ofEpochMilli(json.getAsLong()))
                .create();
    }

    public byte[] encode(ProtocolPacket packet) {
        String type = switch (packet) {
            case ClientHelloPacket ignored -> TYPE_HELLO;
            case AudioCommandPacket ignored -> TYPE_AUDIO_COMMAND;
            case AudioStreamChunkPacket ignored -> TYPE_AUDIO_STREAM_CHUNK;
            case ClientStatusPacket ignored -> TYPE_STATUS;
        };
        return gson.toJson(new PacketEnvelope<>(EvilKaraokeProtocol.VERSION, type, packet)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public ProtocolPacket decode(byte[] payload) {
        if (payload == null) {
            throw new PacketCodecException("Packet payload is required");
        }
        try {
            String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            PacketEnvelope<JsonObject> envelope = gson.fromJson(object, ENVELOPE_TYPE);
            if (envelope.version() != EvilKaraokeProtocol.VERSION) {
                throw new PacketCodecException("Unsupported Evilkaraoke protocol version: " + envelope.version());
            }
            return switch (envelope.type()) {
                case TYPE_HELLO -> gson.fromJson(envelope.payload(), ClientHelloPacket.class);
                case TYPE_AUDIO_COMMAND -> gson.fromJson(envelope.payload(), AudioCommandPacket.class);
                case TYPE_AUDIO_STREAM_CHUNK -> gson.fromJson(envelope.payload(), AudioStreamChunkPacket.class);
                case TYPE_STATUS -> gson.fromJson(envelope.payload(), ClientStatusPacket.class);
                default -> throw new PacketCodecException("Unknown packet type: " + envelope.type());
            };
        } catch (PacketCodecException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PacketCodecException("Invalid Evilkaraoke packet payload", ex);
        }
    }
}
