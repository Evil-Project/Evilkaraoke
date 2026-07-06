package org.evilproject.evilkaraoke.server.platform;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;

public interface ServerPlaybackPlatform {
    void runNow(Runnable task);

    int runLater(Runnable task, long delayTicks);

    void cancelTask(int taskId);

    Collection<KaraokePlayer> onlinePlayers();

    Optional<KaraokePlayer> player(UUID playerId);

    Optional<KaraokePlayer> player(String exactName);

    void sendAudio(KaraokePlayer player, ProtocolPacket packet);

    void log(Level level, String message, Throwable error);

    default int pingMillis(KaraokePlayer player) {
        return -1;
    }

    default void info(String message) {
        log(Level.INFO, message, null);
    }

    default void fine(String message) {
        log(Level.FINE, message, null);
    }

    default void warning(String message, Throwable error) {
        log(Level.WARNING, message, error);
    }
}
