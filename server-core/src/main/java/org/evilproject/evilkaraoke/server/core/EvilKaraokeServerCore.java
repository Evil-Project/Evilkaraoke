package org.evilproject.evilkaraoke.server.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.PacketDebugFormatter;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.server.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.server.config.EvilKaraokeServerConfig;
import org.evilproject.evilkaraoke.server.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.server.stats.StatsService;

public final class EvilKaraokeServerCore {
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private final ServerPlaybackPlatform platform;
    private final JsonPacketCodec packetCodec = new JsonPacketCodec();
    private final ClientRegistry clientRegistry = new ClientRegistry();
    private final Map<UUID, List<KaraokeTrack>> randomSongSelections = new ConcurrentHashMap<>();
    private final Map<UUID, List<KaraokeTrack>> searchResultSelections = new ConcurrentHashMap<>();

    private EvilKaraokeServerConfig serverConfig;
    private NeurokaraokeClient neurokaraokeClient;
    private StatsService statsService;
    private PlaybackCoordinator coordinator;
    private int statsSaveTicks;

    public EvilKaraokeServerCore(Logger logger, Path dataDirectory, ServerPlaybackPlatform platform) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("evilkaraoke.json");
        this.platform = platform;
    }

    public void enable() {
        reload();
        statsService = new StatsService(logger, dataDirectory.resolve("stats.json"));
        statsService.load();
        coordinator = new PlaybackCoordinator(platform, clientRegistry, neurokaraokeClient, config());
        logger.info("Evilkaraoke server core enabled.");
    }

    public void disable() {
        if (coordinator != null) {
            coordinator.stop();
        }
        if (statsService != null) {
            statsService.save();
        }
        logger.info("Evilkaraoke server core disabled.");
    }

    public void reload() {
        serverConfig = EvilKaraokeServerConfig.loadOrCreate(configFile, logger);
        neurokaraokeClient = new NeurokaraokeClient(logger, serverConfig.api());
        if (coordinator != null) {
            coordinator.update(neurokaraokeClient, config());
        }
        statsSaveTicks = 0;
    }

    public void tick() {
        if (statsService == null || !config().statsEnabled()) {
            return;
        }
        statsSaveTicks++;
        int intervalTicks = Math.max(20, config().statsSaveIntervalSeconds() * 20);
        if (statsSaveTicks >= intervalTicks) {
            statsSaveTicks = 0;
            statsService.save();
        }
    }

    public void handlePayload(String channel, KaraokePlayer player, byte[] payload) {
        if (!EvilKaraokeProtocol.HELLO_CHANNEL.equals(channel) && !EvilKaraokeProtocol.STATUS_CHANNEL.equals(channel)) {
            return;
        }
        try {
            ProtocolPacket packet = packetCodec.decode(payload);
            if (config().debugPackets()) {
                logger.info("Evilkaraoke debug packet IN " + channel + " from " + player.name() + ": " + PacketDebugFormatter.describe(packet));
            }
            if (packet instanceof ClientHelloPacket hello) {
                clientRegistry.register(player.id(), hello);
                logger.info("Registered Evilkaraoke client for " + player.name() + " (" + hello.loader() + " " + hello.modVersion() + ")");
                platform.runLater(() -> coordinator.syncPlayer(player), 1L);
            } else if (packet instanceof ClientStatusPacket status) {
                coordinator.handleClientStatus(player.id(), status);
            }
        } catch (PacketCodecException | IllegalArgumentException ex) {
            logger.log(Level.WARNING, "Ignoring invalid Evilkaraoke packet from " + player.name(), ex);
        }
    }

    public void unregisterClient(KaraokePlayer player) {
        clientRegistry.unregister(player.id());
    }

    public byte[] encodeAudio(ProtocolPacket packet) {
        return packetCodec.encode(packet);
    }

    public EvilKaraokeConfig config() {
        return serverConfig == null ? EvilKaraokeConfig.defaults() : serverConfig.playback();
    }

    public ServerPlaybackPlatform platform() {
        return platform;
    }

    public ClientRegistry clientRegistry() {
        return clientRegistry;
    }

    public NeurokaraokeClient neurokaraokeClient() {
        return neurokaraokeClient;
    }

    public StatsService statsService() {
        return statsService;
    }

    public PlaybackCoordinator coordinator() {
        return coordinator;
    }

    public void rememberRandomSongSelection(UUID playerId, List<KaraokeTrack> tracks) {
        randomSongSelections.put(playerId, List.copyOf(tracks));
    }

    public Optional<List<KaraokeTrack>> randomSongSelection(UUID playerId) {
        return Optional.ofNullable(randomSongSelections.get(playerId));
    }

    public void rememberSearchResultSelection(UUID playerId, List<KaraokeTrack> tracks) {
        searchResultSelections.put(playerId, List.copyOf(tracks));
    }

    public Optional<List<KaraokeTrack>> searchResultSelection(UUID playerId) {
        return Optional.ofNullable(searchResultSelections.get(playerId));
    }
}
