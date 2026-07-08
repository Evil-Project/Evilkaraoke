package org.evilproject.evilkaraoke.common.protocol;

import java.time.Duration;
import java.util.Objects;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;

public final class PacketDebugFormatter {
    private static final int TEXT_LIMIT = 80;

    private PacketDebugFormatter() {
    }

    public static String describe(ProtocolPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet instanceof ClientHelloPacket hello) {
            return "ClientHello protocol=" + hello.protocolVersion()
                    + " loader=" + safe(hello.loader())
                    + " mod=" + safe(hello.modVersion())
                    + " minecraft=" + safe(hello.minecraftVersion())
                    + " codecs=" + hello.supportedCodecs();
        }
        if (packet instanceof ClientStatusPacket status) {
            return "ClientStatus playbackId=" + safe(status.playbackId())
                    + " state=" + status.state()
                    + " offset=" + millis(status.offset()) + "ms"
                    + " streamReceived=" + status.streamBytesReceived()
                    + " streamRead=" + status.streamBytesRead()
                    + " streamQueued=" + status.streamQueuedBytes()
                    + " streamMissing=" + status.streamMissingChunks()
                    + optional(" message=", status.message());
        }
        if (packet instanceof AudioCommandPacket command) {
            return "AudioCommand command=" + command.command()
                    + " playbackId=" + safe(command.playbackId())
                    + " sessionId=" + safe(command.sessionId())
                    + " delivery=" + command.deliveryMode()
                    + " offset=" + millis(command.offset()) + "ms"
                    + " target=" + target(command.target())
                    + " track=" + track(command.track())
                    + optional(" reason=", command.reason());
        }
        if (packet instanceof AudioStreamChunkPacket chunk) {
            return "AudioStreamChunk playbackId=" + safe(chunk.playbackId())
                    + " sessionId=" + safe(chunk.sessionId())
                    + " sequence=" + chunk.sequence()
                    + " dataChars=" + chunk.data().length()
                    + " format=" + chunk.sampleRate() + "Hz/" + chunk.channels() + "ch/" + chunk.bitsPerSample() + "bit"
                    + " end=" + chunk.end()
                    + optional(" error=", chunk.error());
        }
        return packet.getClass().getSimpleName();
    }

    private static String target(PlaybackTarget target) {
        if (target == null) {
            return "none";
        }
        return target.mode() + ":" + safe(target.selector());
    }

    private static String track(KaraokeTrack track) {
        if (track == null) {
            return "none";
        }
        return safe(track.id()) + "/" + safe(track.title());
    }

    private static long millis(Duration duration) {
        return duration == null ? 0L : duration.toMillis();
    }

    private static String optional(String prefix, String value) {
        String safe = safe(value);
        return safe.isBlank() ? "" : prefix + safe;
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (cleaned.length() <= TEXT_LIMIT) {
            return cleaned;
        }
        return cleaned.substring(0, TEXT_LIMIT - 3) + "...";
    }
}
