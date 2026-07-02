package org.evilproject.evilkaraoke.paper.messaging;

import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.codec.PacketCodecException;
import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.jetbrains.annotations.NotNull;

public final class EvilkaraokeMessageListener implements PluginMessageListener {
    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final JsonPacketCodec codec;

    public EvilkaraokeMessageListener(Plugin plugin, ClientRegistry clientRegistry, JsonPacketCodec codec) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.codec = codec;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!EvilkaraokeProtocol.HELLO_CHANNEL.equals(channel) && !EvilkaraokeProtocol.STATUS_CHANNEL.equals(channel)) {
            return;
        }

        try {
            ProtocolPacket packet = codec.decode(message);
            if (packet instanceof ClientHelloPacket hello) {
                clientRegistry.register(player.getUniqueId(), hello);
                plugin.getLogger().info("Registered Evilkaraoke client for " + player.getName() + " (" + hello.loader() + " " + hello.modVersion() + ")");
            }
        } catch (PacketCodecException | IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING, "Ignoring invalid Evilkaraoke packet from " + player.getName(), ex);
        }
    }
}
