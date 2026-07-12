package org.evilproject.evilkaraoke.paper.messaging;

import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.PacketDebugFormatter;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.jetbrains.annotations.NotNull;

import org.evilproject.evilkaraoke.paper.playback.PlaybackCoordinator;

public final class EvilKaraokeMessageListener implements PluginMessageListener, Listener {
    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final JsonPacketCodec codec;
    private final PlaybackCoordinator coordinator;
    private final BooleanSupplier debugPackets;

    public EvilKaraokeMessageListener(Plugin plugin, ClientRegistry clientRegistry, JsonPacketCodec codec, PlaybackCoordinator coordinator, BooleanSupplier debugPackets) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.codec = codec;
        this.coordinator = coordinator;
        this.debugPackets = debugPackets;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!EvilKaraokeProtocol.HELLO_CHANNEL.equals(channel) && !EvilKaraokeProtocol.STATUS_CHANNEL.equals(channel)) {
            return;
        }

        try {
            ProtocolPacket packet = codec.decode(message);
            if (debugPackets.getAsBoolean()) {
                plugin.getLogger().info("Evilkaraoke debug packet IN " + channel + " from " + player.getName() + ": " + PacketDebugFormatter.describe(packet));
            }
            if (packet instanceof ClientHelloPacket hello) {
                clientRegistry.register(player.getUniqueId(), hello);
                plugin.getLogger().info("Registered Evilkaraoke client for " + player.getName() + " (" + hello.loader() + " " + hello.modVersion() + ")");
                // Delay sync by 1 tick so the client's incoming channel is fully
                // established before the PLAY packet arrives.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> coordinator.syncPlayer(player), 1L);
            } else if (packet instanceof ClientStatusPacket status) {
                coordinator.handleClientStatus(player.getUniqueId(), status);
            }
        } catch (PacketCodecException | IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING, "Ignoring invalid Evilkaraoke packet from " + player.getName(), ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clientRegistry.unregister(event.getPlayer().getUniqueId());
    }
}
