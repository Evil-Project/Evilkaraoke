package org.evilproject.evilkaraoke.paper;

import java.nio.file.Path;
import java.util.Objects;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeEndpoints;
import org.evilproject.evilkaraoke.paper.command.EvilkaraokeCommand;
import org.evilproject.evilkaraoke.paper.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.EvilkaraokeMessageListener;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.paper.stats.StatsService;

public final class EvilkaraokePlugin extends JavaPlugin {
    private final ClientRegistry clientRegistry = new ClientRegistry();
    private final JsonPacketCodec packetCodec = new JsonPacketCodec();
    private EvilkaraokeConfig config;
    private EvilkaraokeCommand command;
    private StatsService statsService;
    private PlaybackCoordinator coordinator;
    private NeurokaraokeClient neurokaraokeClient;
    private BukkitTask statsSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        config = EvilkaraokeConfig.from(getConfig());

        statsService = new StatsService(getLogger(), Path.of(getDataFolder().getPath(), "stats.json"));
        statsService.load();

        neurokaraokeClient = new NeurokaraokeClient(getLogger(), NeurokaraokeEndpoints.from(getConfig()));
        PlaybackMessenger messenger = new PlaybackMessenger(this, packetCodec);
        coordinator = new PlaybackCoordinator(this, clientRegistry, messenger, neurokaraokeClient, config);

        registerMessaging();
        registerCommands();
        scheduleStatsSave();
        getLogger().info("Evilkaraoke enabled with server-authoritative playback coordination.");
    }

    @Override
    public void onDisable() {
        if (statsSaveTask != null) {
            statsSaveTask.cancel();
        }
        if (statsService != null) {
            statsService.save();
        }
        getLogger().info("Evilkaraoke disabled.");
    }

    public void reloadEvilkaraokeConfig() {
        reloadConfig();
        config = EvilkaraokeConfig.from(getConfig());
        neurokaraokeClient = new NeurokaraokeClient(getLogger(), NeurokaraokeEndpoints.from(getConfig()));
        if (coordinator != null) {
            coordinator.update(neurokaraokeClient, config);
        }
        registerCommands();
    }

    private void registerMessaging() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, EvilkaraokeProtocol.AUDIO_CHANNEL);
        EvilkaraokeMessageListener listener = new EvilkaraokeMessageListener(this, clientRegistry, packetCodec);
        getServer().getMessenger().registerIncomingPluginChannel(this, EvilkaraokeProtocol.HELLO_CHANNEL, listener);
        getServer().getMessenger().registerIncomingPluginChannel(this, EvilkaraokeProtocol.STATUS_CHANNEL, listener);
    }

    private void registerCommands() {
        command = new EvilkaraokeCommand(this, clientRegistry, coordinator, neurokaraokeClient, statsService, config, this::reloadEvilkaraokeConfig);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("evilkaraoke"), "evilkaraoke command missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void scheduleStatsSave() {
        long intervalTicks = Math.max(20L, getConfig().getLong("stats.saveIntervalSeconds", 60L) * 20L);
        statsSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> statsService.save(), intervalTicks, intervalTicks);
    }
}
