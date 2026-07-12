package org.evilproject.evilkaraoke.client.net;

import java.util.function.Consumer;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.client.audio.AudioBackend;
import org.evilproject.evilkaraoke.client.audio.AudioBackendStatus;
import org.evilproject.evilkaraoke.client.audio.JavaSoundAudioBackend;
import org.evilproject.evilkaraoke.client.lyrics.LyricsPlaybackController;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.LyricsDisplayAction;
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
    private final LyricsPlaybackController lyricsController;
    private final String modVersion;
    private final String minecraftVersion;
    private final String loader;
    private volatile SoundCategory soundCategory = SoundCategory.MUSIC;
    private volatile String currentPlaybackId = null;
    private volatile AudioCommandPacket pendingServerStreamCommand = null;
    /**
     * Status override between a SERVER_STREAM play command and the backend
     * actually starting (first chunk), or after the stream fails before any
     * audio arrived. Without it the periodic status report attaches the
     * previous track's terminal state to the new playbackId, which the server
     * reads as "this track already finished" and instantly skips it — draining
     * the whole queue.
     */
    private volatile AudioBackendStatus preStreamStatus = null;
    private volatile PacketAudioInputStream currentServerStream = null;
    private final Object streamSequenceLock = new Object();
    private int expectedServerStreamSequence = 0;
    private long currentServerStreamMissingChunks = 0L;
    /**
     * A rejoin-sync stream starts mid-track, so the first chunk of a playback
     * defines the sequence baseline instead of being compared against 0 —
     * otherwise a late joiner would instantly fail with "lost N chunks".
     */
    private boolean serverStreamSequenceBaselined = false;
    /** Optional hook called on the game thread when a PLAY command is dispatched. */
    private Consumer<KaraokeTrack> onPlayCallback;
    /** Optional hook called on the game thread after a LYRICS command applies its state. */
    private volatile Consumer<Boolean> onLyricsToggledCallback;
    private volatile boolean lyricsEnabled = false;

    public ClientAudioController(Logger logger, String modVersion, String minecraftVersion, String loader) {
        this(logger, new JavaSoundAudioBackend(), modVersion, minecraftVersion, loader);
    }

    public ClientAudioController(Logger logger, AudioBackend backend, String modVersion, String minecraftVersion, String loader) {
        this(logger, backend, modVersion, minecraftVersion, loader, new LyricsPlaybackController(logger));
    }

    ClientAudioController(Logger logger, AudioBackend backend, String modVersion, String minecraftVersion, String loader,
                          LyricsPlaybackController lyricsController) {
        this.logger = logger;
        this.backend = backend;
        this.lyricsController = lyricsController;
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
    }

    /**
     * Sets a callback invoked (on the game thread) whenever a PLAY command is
     * dispatched. Loader modules use this to reset track-specific UI without
     * pulling Minecraft API into client-core. Pass {@code null} to clear.
     */
    public void setOnPlay(Consumer<KaraokeTrack> callback) {
        this.onPlayCallback = callback;
    }

    /** Sets the game-specific renderer used for each due lyric line. */
    public void setOnLyric(Consumer<String> callback) {
        lyricsController.setRenderer(callback);
    }

    /** Sets the game-specific callback used to clear the current lyric line. */
    public void setOnLyricClear(Runnable callback) {
        lyricsController.setClearer(callback);
    }

    /**
     * Sets a callback invoked with the resulting state whenever a server
     * LYRICS command is applied. Pass {@code null} to clear.
     */
    public void setOnLyricsToggled(Consumer<Boolean> callback) {
        this.onLyricsToggledCallback = callback;
    }

    /** Sets the locally persisted lyric preference without emitting a notification. */
    public void setLyricsEnabled(boolean lyricsEnabled) {
        boolean wasEnabled = this.lyricsEnabled;
        this.lyricsEnabled = lyricsEnabled;
        updateLyricsDisplayState(wasEnabled, lyricsEnabled);
    }

    /** @return whether lyric captions are currently enabled on this client. */
    public boolean lyricsEnabled() {
        return lyricsEnabled;
    }

    /**
     * Stops all active playback immediately. Call this when the client disconnects
     * from a server so the audio thread does not outlive the play session.
     */
    public void stopAll() {
        closeCurrentServerStream();
        backend.stop(null);
        lyricsController.stop();
        currentPlaybackId = null;
        pendingServerStreamCommand = null;
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
        } else if (packet instanceof AudioStreamChunkPacket chunk) {
            dispatch(chunk);
        }
    }

    /** Builds a status payload the loader can send back on the status channel. */
    public byte[] statusPayload() {
        AudioBackendStatus status = effectiveStatus();
        String playbackId = currentPlaybackId != null ? currentPlaybackId : "none";
        PacketAudioInputStream.StreamStats streamStats = streamStats();
        return codec.encode(new ClientStatusPacket(
                playbackId,
                status.state(),
                null,
                status.message(),
                streamStats.bytesReceived(),
                streamStats.bytesRead(),
                streamStats.queuedBytes(),
                streamMissingChunks()));
    }

    /** @deprecated Use {@link #statusPayload()} instead */
    @Deprecated
    public byte[] statusPayload(String playbackId) {
        AudioBackendStatus status = effectiveStatus();
        PacketAudioInputStream.StreamStats streamStats = streamStats();
        return codec.encode(new ClientStatusPacket(
                playbackId,
                status.state(),
                null,
                status.message(),
                streamStats.bytesReceived(),
                streamStats.bytesRead(),
                streamStats.queuedBytes(),
                streamMissingChunks()));
    }

    public AudioBackendStatus status() {
        return effectiveStatus();
    }

    private AudioBackendStatus effectiveStatus() {
        AudioBackendStatus preStream = preStreamStatus;
        return preStream != null ? preStream : backend.status();
    }

    /**
     * Returns true while a server playback session owns the client audio focus.
     * Paused playback still counts so vanilla music does not resume between pause
     * and resume commands.
     */
    public boolean isPlaybackSessionActive() {
        if (currentPlaybackId == null) {
            return false;
        }
        ClientPlaybackState state = effectiveStatus().state();
        return switch (state) {
            case BUFFERING, PLAYING, PAUSED -> true;
            case READY, STOPPED, ERROR -> false;
        };
    }

    /**
     * Applies the local Minecraft sound settings to JavaSound playback. Loader
     * modules call this from the client tick because JavaSound runs outside
     * Minecraft's own sound engine.
     */
    public void setGameVolume(float linearGain) {
        backend.setGameVolume(linearGain);
    }

    /** Advances timed lyrics from the actual decoded-audio position. */
    public void tickLyrics() {
        lyricsController.tick(lyricsEnabled, effectiveStatus().state(), backend.playbackPosition());
    }

    public SoundCategory soundCategory() {
        return soundCategory;
    }

    private void dispatch(AudioCommandPacket command) {
        AudioCommandType type = command.command();
        switch (type) {
            case PLAY -> {
                updateSoundCategory(command);
                if (command.playbackId().equals(currentPlaybackId) && isPlaybackSessionActive()) {
                    return;
                }
                if (command.deliveryMode() == AudioDeliveryMode.SERVER_STREAM) {
                    closeCurrentServerStream();
                    resetStreamSequenceStats();
                    preStreamStatus = new AudioBackendStatus(ClientPlaybackState.BUFFERING, "Waiting for server audio stream");
                    pendingServerStreamCommand = command;
                } else {
                    closeCurrentServerStream();
                    resetStreamSequenceStats();
                    backend.play(command);
                }
                currentPlaybackId = command.playbackId();
                lyricsController.handleCommand(command);
                if (onPlayCallback != null) {
                    onPlayCallback.accept(command.track());
                }
            }
            case PAUSE -> {
                if (isCurrentPlayback(command)) {
                    backend.pause(command);
                }
            }
            case RESUME -> {
                if (isCurrentPlayback(command)) {
                    backend.resume(command);
                }
            }
            case STOP -> {
                if (isCurrentPlayback(command)) {
                    closeCurrentServerStream();
                    resetStreamSequenceStats();
                    pendingServerStreamCommand = null;
                    backend.stop(command);
                    lyricsController.handleCommand(command);
                    currentPlaybackId = null;
                }
            }
            case VOLUME -> {
                if (isCurrentPlayback(command) && command.target() != null) {
                    updateSoundCategory(command);
                    backend.setVolume(command.target().volume());
                }
            }
            case SYNC -> {
                // Continuous local playback already tracks the stream; nothing to reseek.
            }
            case LYRICS -> LyricsDisplayAction.fromReason(command.reason()).ifPresent(action -> {
                boolean wasEnabled = lyricsEnabled;
                lyricsEnabled = action.apply(wasEnabled);
                updateLyricsDisplayState(wasEnabled, lyricsEnabled);
                Consumer<Boolean> callback = onLyricsToggledCallback;
                if (callback != null) {
                    callback.accept(lyricsEnabled);
                }
            });
        }
    }

    private void updateLyricsDisplayState(boolean wasEnabled, boolean enabled) {
        if (wasEnabled && !enabled) {
            lyricsController.clearDisplay();
        } else if (!wasEnabled && enabled) {
            lyricsController.resync();
        }
    }

    private void dispatch(AudioStreamChunkPacket chunk) {
        if (!chunk.playbackId().equals(currentPlaybackId)) {
            return;
        }
        StreamSequenceResult sequence = recordStreamSequence(chunk.sequence());
        if (sequence.duplicate()) {
            return;
        }
        PacketAudioInputStream stream = !chunk.end() && chunk.error().isBlank() ? ensureServerStreamStarted(chunk) : currentServerStream;
        if (sequence.missingChunks() > 0) {
            String message = "Server audio stream lost " + sequence.missingChunks() + " chunk(s)";
            if (stream != null) {
                stream.fail(message);
            }
            logger.warning("Evilkaraoke " + message + " for playback " + chunk.playbackId());
            return;
        }
        if (!chunk.error().isBlank()) {
            if (stream != null) {
                stream.fail(chunk.error());
            } else if (pendingServerStreamCommand != null) {
                // The stream failed before its first audio chunk; report the
                // error ourselves so the server can advance past this track.
                pendingServerStreamCommand = null;
                preStreamStatus = AudioBackendStatus.error(chunk.error());
            }
            return;
        }
        byte[] data;
        try {
            data = chunk.decodedData();
        } catch (IllegalArgumentException ex) {
            if (stream != null) {
                stream.fail("Server audio stream sent invalid chunk data");
            }
            logger.warning("Evilkaraoke server audio stream sent invalid chunk data: " + ex.getMessage());
            return;
        }
        if (stream == null) {
            if (chunk.end() && pendingServerStreamCommand != null) {
                pendingServerStreamCommand = null;
                preStreamStatus = new AudioBackendStatus(ClientPlaybackState.STOPPED, "Server stream ended");
            }
            return;
        }
        try {
            stream.append(data);
        } catch (Exception ex) {
            stream.fail(ex.getMessage());
            logger.warning("Evilkaraoke server audio stream failed: " + ex.getMessage());
            return;
        }
        if (chunk.end()) {
            stream.finish();
        }
    }

    private PacketAudioInputStream ensureServerStreamStarted(AudioStreamChunkPacket chunk) {
        PacketAudioInputStream stream = currentServerStream;
        if (stream != null) {
            return stream;
        }
        AudioCommandPacket command = pendingServerStreamCommand;
        if (command == null) {
            return null;
        }
        stream = new PacketAudioInputStream();
        currentServerStream = stream;
        pendingServerStreamCommand = null;
        backend.playPcmStream(command, stream, chunk.sampleRate(), chunk.channels(), chunk.bitsPerSample());
        preStreamStatus = null;
        return stream;
    }

    private PacketAudioInputStream.StreamStats streamStats() {
        PacketAudioInputStream stream = currentServerStream;
        return stream == null ? new PacketAudioInputStream.StreamStats(0L, 0L, 0) : stream.stats();
    }

    private long streamMissingChunks() {
        if (currentServerStream == null) {
            return 0L;
        }
        synchronized (streamSequenceLock) {
            return currentServerStreamMissingChunks;
        }
    }

    private StreamSequenceResult recordStreamSequence(int sequence) {
        synchronized (streamSequenceLock) {
            if (!serverStreamSequenceBaselined) {
                serverStreamSequenceBaselined = true;
                expectedServerStreamSequence = sequence + 1;
                return new StreamSequenceResult(false, 0L);
            }
            if (sequence < expectedServerStreamSequence) {
                return new StreamSequenceResult(true, 0L);
            }
            long missingChunks = 0L;
            if (sequence > expectedServerStreamSequence) {
                missingChunks = sequence - expectedServerStreamSequence;
                currentServerStreamMissingChunks += missingChunks;
            }
            expectedServerStreamSequence = sequence + 1;
            return new StreamSequenceResult(false, missingChunks);
        }
    }

    private void resetStreamSequenceStats() {
        synchronized (streamSequenceLock) {
            expectedServerStreamSequence = 0;
            currentServerStreamMissingChunks = 0L;
            serverStreamSequenceBaselined = false;
        }
    }

    private void updateSoundCategory(AudioCommandPacket command) {
        if (command.target() != null && command.target().category() != null) {
            soundCategory = command.target().category();
        }
    }

    private boolean isCurrentPlayback(AudioCommandPacket command) {
        String playbackId = currentPlaybackId;
        return playbackId != null && playbackId.equals(command.playbackId());
    }

    private void closeCurrentServerStream() {
        pendingServerStreamCommand = null;
        preStreamStatus = null;
        PacketAudioInputStream stream = currentServerStream;
        currentServerStream = null;
        if (stream != null) {
            stream.close();
        }
    }

    public void forEachChannelName(Consumer<String> consumer) {
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol.HELLO_CHANNEL);
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol.AUDIO_CHANNEL);
        consumer.accept(org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol.STATUS_CHANNEL);
    }

    private record StreamSequenceResult(boolean duplicate, long missingChunks) {
    }
}
