package org.evilproject.evilkaraoke.server.messaging;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;

public final class ClientRegistry {
    private final Map<UUID, ClientSession> sessions = new ConcurrentHashMap<>();

    public void register(UUID playerId, ClientHelloPacket hello) {
        sessions.put(playerId, new ClientSession(playerId, hello, Instant.now(), "READY", ""));
    }

    public void unregister(UUID playerId) {
        sessions.remove(playerId);
    }

    public Optional<ClientSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public boolean isCompatible(UUID playerId) {
        return session(playerId).map(ClientRegistry::isCompatible).orElse(false);
    }

    public boolean supportsServerStream(UUID playerId) {
        return session(playerId).map(ClientRegistry::supportsServerStream).orElse(false);
    }

    public boolean supportsLyrics(UUID playerId) {
        return session(playerId).map(ClientRegistry::supportsLyrics).orElse(false);
    }

    public int compatibleClientCount() {
        return (int) sessions.values().stream().filter(ClientRegistry::isCompatible).count();
    }

    public Map<UUID, ClientSession> sessions() {
        return Map.copyOf(sessions);
    }

    private static boolean isCompatible(ClientSession session) {
        return session.hello().protocolVersion() == EvilKaraokeProtocol.VERSION;
    }

    private static boolean supportsServerStream(ClientSession session) {
        return isCompatible(session) && session.hello().supportedCodecs().stream()
                .map(codec -> codec.toLowerCase(Locale.ROOT))
                .anyMatch("stream"::equals);
    }

    private static boolean supportsLyrics(ClientSession session) {
        return isCompatible(session) && session.hello().supportsLyrics();
    }
}
