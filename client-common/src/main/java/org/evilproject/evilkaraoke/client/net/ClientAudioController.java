package org.evilproject.evilkaraoke.client.net;

import java.util.function.Consumer;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.client.audio.AudioBackend;
import org.evilproject.evilkaraoke.client.audio.AudioBackendStatus;
import org.evilproject.evilkaraoke.client.audio.JavaSoundAudioBackend;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;

/**
 * Loader-neutral glue between the network payloads and the audio backend. Fabric
 * and NeoForge entrypoints construct one of these and forward raw channel bytes
 * to {@link #handleAudioPayload(byte[])}; everything else (decode, dispatch,
 * playback) is shared here so the loader modules stay tiny and audio-only.
 */
public final class ClientAudioController {
    private final Logger logger;
    private final JsonPacketCodec codec = new JsonPacketCodec();
    private final AudioBackend backend;
    private final String modVersion;
    private final String minecraftVersion;
    private final String loader;
    /** Optional hook called on the game thread when a PLAY command is dispatched. */
    private Consumer<KaraokeTrack> onPlayCallback;

    public ClientAudioController(Logger logger, String modVersion, String minecraftVersion, String loader) {
        this(logger, new JavaSoundAudioBackend(), modVersion, minecraftVersion, loader);
    }

    public ClientAudioController(Logger logger, AudioBackend backend, String modVersion, String minecraftVersion, String loader) {
        this.logger = logger;
        this.backend = backend;
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
    }

    /**
     * Sets a callback invoked (on the game thread) whenever a PLAY command is
     * dispatched. Loader modules use this to show a music toast without pulling
     * Minecraft API into client-common. Pass {@code null} to clear.
     */
    public void setOnPlay(Consumer<KaraokeTrack> callback) {
        this.onPlayCallback = callback;
    }

    /**
     * Stops all active playback immediately. Call this when the client disconnects
     * from a server so the audio thread does not outlive the play session.
     */
    public void stopAll() {
        backend.stop(null);
    }

    /** @return the handshake bytes to send on the hello channel when joining a server. */
    public byte[] helloPayload() {
        return codec.encode(ClientHandshakeFactory.create(modVersion, minecraftVersion, loader));
    }

    /** Decodes and applies a server audio-channel payload. Safe to call from a network thread. */
    public void handleAudioPayload(byte[] payload) {
        ProtocolPacket packet;
        try {
            packet = codec.decode(payload);
        } catch (PacketCodecException ex) {
            logger.warning("Evilkaraoke received an undecodable audio payload: " + ex.getMessage());
            return;
        }
        if (packet instanceof AudioCommandPacket command) {
            dispatch(command);
        }
    }

    /** Builds a status payload the loader can send back on the status channel. */
    public byte[] statusPayload(String playbackId) {
        AudioBackendStatus status = backend.status();
        return codec.encode(new ClientStatusPacket(playbackId, status.state(), null, status.message()));
    }

    public AudioBackendStatus status() {
        return backend.status();
    }

    private void dispatch(AudioCommandPacket command) {
        AudioCommandType type = command.command();
        switch (type) {
            case PLAY -> {
                backend.play(command);
                if (onPlayCallback != null && command.track() != null) {
                    onPlayCallback.accept(command.track());
                }
            }
            case PAUSE -> backend.pause(command);
            case RESUME -> backend.resume(command);
            case STOP -> backend.stop(command);
            case VOLUME -> {
                if (command.target() != null) {
                    backend.setVolume(command.target().volume());
                }
            }
            case SYNC -> {
                // Continuous local playback already tracks the stream; nothing to reseek.
            }
        }
    }

    public void forEachChannelName(Consumer<String> consumer) {
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol.HELLO_CHANNEL);
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol.AUDIO_CHANNEL);
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol.STATUS_CHANNEL);
    }
}
