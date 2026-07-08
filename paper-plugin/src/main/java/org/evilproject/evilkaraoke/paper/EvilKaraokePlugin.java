package org.evilproject.evilkaraoke.paper;

import java.nio.file.Path;
import java.util.Objects;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeEndpoints;
import org.evilproject.evilkaraoke.paper.command.EvilKaraokeCommand;
import org.evilproject.evilkaraoke.paper.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.EvilKaraokeMessageListener;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.paper.permission.PermissionService;
import org.evilproject.evilkaraoke.paper.stats.StatsService;

public final class EvilKaraokePlugin extends JavaPlugin {
    private final ClientRegistry clientRegistry = new ClientRegistry();
    private final JsonPacketCodec packetCodec = new JsonPacketCodec();
    private EvilKaraokeConfig config;
    private EvilKaraokeCommand command;
    private PermissionService permissionService;
    private StatsService statsService;
    private PlaybackCoordinator coordinator;
    private NeurokaraokeClient neurokaraokeClient;
    private BukkitTask statsSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        permissionService = new PermissionService(getLogger());
        saveResource("messages.yml", false);
        config = EvilKaraokeConfig.from(getConfig());

        statsService = new StatsService(getLogger(), Path.of(getDataFolder().getPath(), "stats.json"));
        statsService.load();

        neurokaraokeClient = new NeurokaraokeClient(getLogger(), NeurokaraokeEndpoints.from(getConfig()));
        PlaybackMessenger messenger = new PlaybackMessenger(this, packetCodec, this::isPacketDebugLoggingEnabled);
        coordinator = new PlaybackCoordinator(this, clientRegistry, messenger, neurokaraokeClient, config);

        registerMessaging();
        registerCommands(permissionService);
        scheduleStatsSave();
        getLogger().info("Evilkaraoke enabled with server-authoritative playback coordination.");
    }

    @Override
    public void onDisable() {
        if (statsSaveTask != null) {
            statsSaveTask.cancel();
        }
        if (coordinator != null) {
            coordinator.stop();
        }
        if (statsService != null) {
            statsService.save();
        }
        getLogger().info("Evilkaraoke disabled.");
    }

    public void reloadEvilKaraokeConfig() {
        reloadConfig();
        config = EvilKaraokeConfig.from(getConfig());
        neurokaraokeClient = new NeurokaraokeClient(getLogger(), NeurokaraokeEndpoints.from(getConfig()));
        if (coordinator != null) {
            coordinator.update(neurokaraokeClient, config);
        }
        registerCommands(permissionService);
    }

    private void registerMessaging() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, EvilKaraokeProtocol.AUDIO_CHANNEL);
        EvilKaraokeMessageListener listener = new EvilKaraokeMessageListener(this, clientRegistry, packetCodec, coordinator, this::isPacketDebugLoggingEnabled);
        getServer().getMessenger().registerIncomingPluginChannel(this, EvilKaraokeProtocol.HELLO_CHANNEL, listener);
        getServer().getMessenger().registerIncomingPluginChannel(this, EvilKaraokeProtocol.STATUS_CHANNEL, listener);
    }

    private boolean isPacketDebugLoggingEnabled() {
        return config != null && config.debugPackets();
    }

    private void registerCommands(PermissionService permissionService) {
        command = new EvilKaraokeCommand(this, clientRegistry, coordinator, neurokaraokeClient, statsService, config, permissionService, this::reloadEvilKaraokeConfig);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("ek"), "ek command missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void scheduleStatsSave() {
        long intervalTicks = Math.max(20L, getConfig().getLong("stats.saveIntervalSeconds", 60L) * 20L);
        statsSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> statsService.save(), intervalTicks, intervalTicks);
    }
}
