package org.evilproject.evilkaraoke.server.core;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.server.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.server.config.EvilkaraokeServerConfig;
import org.evilproject.evilkaraoke.server.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.server.stats.StatsService;

public final class EvilkaraokeServerCore {
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private final ServerPlaybackPlatform platform;
    private final JsonPacketCodec packetCodec = new JsonPacketCodec();
    private final ClientRegistry clientRegistry = new ClientRegistry();

    private EvilkaraokeServerConfig serverConfig;
    private NeurokaraokeClient neurokaraokeClient;
    private StatsService statsService;
    private PlaybackCoordinator coordinator;
    private int statsSaveTicks;

    public EvilkaraokeServerCore(Logger logger, Path dataDirectory, ServerPlaybackPlatform platform) {
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
        if (statsService != null) {
            statsService.save();
        }
        logger.info("Evilkaraoke server core disabled.");
    }

    public void reload() {
        serverConfig = EvilkaraokeServerConfig.loadOrCreate(configFile, logger);
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
        if (!EvilkaraokeProtocol.HELLO_CHANNEL.equals(channel) && !EvilkaraokeProtocol.STATUS_CHANNEL.equals(channel)) {
            return;
        }
        try {
            ProtocolPacket packet = packetCodec.decode(payload);
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

    public byte[] encodeAudio(org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket packet) {
        return packetCodec.encode(packet);
    }

    public EvilkaraokeConfig config() {
        return serverConfig == null ? EvilkaraokeConfig.defaults() : serverConfig.playback();
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
}
