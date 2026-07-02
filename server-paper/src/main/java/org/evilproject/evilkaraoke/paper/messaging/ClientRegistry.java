package org.evilproject.evilkaraoke.paper.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;

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

    public int compatibleClientCount() {
        return sessions.size();
    }

    public Map<UUID, ClientSession> sessions() {
        return Map.copyOf(sessions);
    }
}
