package org.evilproject.evilkaraoke.fabric;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import me.lucko.fabric.api.permissions.v0.Permissions;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.command.CommandActor;
import org.evilproject.evilkaraoke.server.command.CommandMessage;
import org.evilproject.evilkaraoke.server.command.EvilKaraokeCommandService;
import org.evilproject.evilkaraoke.server.core.EvilKaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.platform.TickTaskScheduler;

public final class EvilKaraokeFabric implements ModInitializer {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");

    private final CustomPacketPayload.Type<EvilKaraokePayload> helloType = EvilKaraokePayload.type(EvilKaraokeProtocol.HELLO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> audioType = EvilKaraokePayload.type(EvilKaraokeProtocol.AUDIO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> statusType = EvilKaraokePayload.type(EvilKaraokeProtocol.STATUS_CHANNEL);

    private EvilKaraokeServerCore core;
    private FabricPlatform platform;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(audioType, EvilKaraokePayload.codec(audioType));
        PayloadTypeRegistry.serverboundPlay().register(helloType, EvilKaraokePayload.codec(helloType));
        PayloadTypeRegistry.serverboundPlay().register(statusType, EvilKaraokePayload.codec(statusType));

        registerNetworking();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            dispatcher.register(command("ek"));
        });
        ServerLifecycleEvents.SERVER_STARTING.register(this::enableServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (core != null) {
                core.disable();
                core = null;
                platform = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (platform != null) {
                platform.tick();
            }
            if (core != null) {
                core.tick();
            }
        });
    }

    private void enableServer(MinecraftServer server) {
        platform = new FabricPlatform(server, audioType);
        Path dataDirectory = server.getServerDirectory().resolve("config").resolve("evilkaraoke");
        core = new EvilKaraokeServerCore(LOGGER, dataDirectory, platform);
        core.enable();
    }

    private void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(helloType, (payload, context) -> {
            if (core != null) {
                core.handlePayload(EvilKaraokeProtocol.HELLO_CHANNEL, player(context.player()), payload.data());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(statusType, (payload, context) -> {
            if (core != null) {
                core.handlePayload(EvilKaraokeProtocol.STATUS_CHANNEL, player(context.player()), payload.data());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            if (core != null) {
                core.unregisterClient(player(listener.player));
            }
        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> command(String label) {
        return Commands.literal(label)
                .executes(context -> execute(context.getSource(), label, new String[0]))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggest(context.getSource(), builder))
                        .executes(context -> execute(context.getSource(), label, EvilKaraokeCommandService.splitArgs(StringArgumentType.getString(context, "args")))));
    }

    private java.util.concurrent.CompletableFuture<Suggestions> suggest(CommandSourceStack source, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        SuggestionsBuilder tokenBuilder = builder.createOffset(builder.getStart() + EvilKaraokeCommandService.suggestionTokenStart(remaining));
        String[] args = EvilKaraokeCommandService.splitArgsForSuggestions(remaining);
        if (core == null) {
            return SharedSuggestionProvider.suggest(EvilKaraokeCommandService.suggest(args), tokenBuilder);
        }
        return SharedSuggestionProvider.suggest(new EvilKaraokeCommandService(core).suggest(actor(source), args), tokenBuilder);
    }

    private int execute(CommandSourceStack source, String label, String[] args) {
        if (core == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Evilkaraoke server core is not ready yet."));
            return 0;
        }
        return new EvilKaraokeCommandService(core).execute(actor(source), label, args);
    }

    private CommandActor actor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return new CommandActor() {
            @Override
            public boolean isPlayer() {
                return player != null;
            }

            @Override
            public UUID playerId() {
                return player == null ? new UUID(0L, 0L) : player.getUUID();
            }

            @Override
            public String name() {
                return player == null ? source.getTextName() : player.getGameProfile().name();
            }

            @Override
            public boolean hasPermission(String permission) {
                if (player == null) {
                    return true;
                }
                if (isOperatorPermission(permission)) {
                    return Permissions.check(source, permission, PermissionLevel.GAMEMASTERS);
                }
                return Permissions.check(source, permission, true);
            }

            @Override
            public String group() {
                if (player == null) {
                    return "default";
                }
                try {
                    return FabricLuckPermsHook.group(player);
                } catch (NoClassDefFoundError ignored) {
                    return "default";
                }
            }

            @Override
            public void sendMessage(String message) {
                source.sendSystemMessage(chatLine(message));
            }

            @Override
            public void sendMessage(CommandMessage message) {
                source.sendSystemMessage(chatComponent(message));
            }
        };
    }

    private static Component chatComponent(CommandMessage message) {
        MutableComponent root = Component.empty();
        for (CommandMessage.Part part : message.parts()) {
            MutableComponent child = Component.literal(part.text())
                    .withStyle(style -> style.withColor(chatColor(part.text())));
            if (part.clickable()) {
                child.withStyle(style -> style
                        .withColor(actionColor(part.text()))
                        .withClickEvent(new ClickEvent.RunCommand(part.command()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText(part)))));
            }
            root.append(child);
        }
        return root;
    }

    private static Component chatLine(String message) {
        return Component.literal(message).withStyle(style -> style.withColor(chatColor(message)));
    }

    private static ChatFormatting actionColor(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("cancel") || lower.contains("stop")) {
            return ChatFormatting.RED;
        }
        if (lower.contains("pause")) {
            return ChatFormatting.YELLOW;
        }
        if (lower.contains("loop") || lower.contains("random")) {
            return lower.contains("on") ? ChatFormatting.AQUA : ChatFormatting.BLUE;
        }
        if (lower.contains("prev") || lower.contains("next")) {
            return ChatFormatting.AQUA;
        }
        return ChatFormatting.GREEN;
    }

    private static ChatFormatting chatColor(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return ChatFormatting.GRAY;
        }
        if (lower.startsWith("evilkaraoke ")
                || lower.startsWith("queue ")
                || lower.startsWith("results for")
                || lower.startsWith("setlists")
                || lower.startsWith("public playlists")
                || lower.startsWith("now playing")
                || lower.startsWith("top ")
                || lower.startsWith("your evilkaraoke stats")) {
            return ChatFormatting.GOLD;
        }
        if (lower.startsWith("queued")
                || lower.startsWith("removed")
                || lower.startsWith("moved")
                || lower.startsWith("playback ")
                || lower.startsWith("random queue playback")
                || lower.startsWith("random queue order")
                || lower.startsWith("queue loop")
                || lower.startsWith("single-song loop")
                || lower.startsWith("going back")
                || lower.startsWith("skipping")
                || lower.startsWith("evilkaraoke configuration reloaded")) {
            return ChatFormatting.GREEN;
        }
        if (lower.startsWith("usage:")
                || lower.startsWith("heads up")
                || lower.startsWith("nothing ")
                || lower.startsWith("no ")
                || lower.startsWith("radio playback is disabled")
                || lower.startsWith("that song is already")
                || lower.startsWith("current audience")) {
            return ChatFormatting.YELLOW;
        }
        if (lower.startsWith("could not")
                || lower.startsWith("unknown ")
                || lower.startsWith("you do not have permission")
                || lower.startsWith("that command must")
                || lower.startsWith("invalid ")
                || lower.startsWith("position ")
                || lower.startsWith("queue position")
                || lower.startsWith("queue positions")
                || lower.startsWith("only ")
                || lower.startsWith("player not found")
                || lower.startsWith("neurokaraoke api unavailable")
                || lower.contains(" failed")) {
            return ChatFormatting.RED;
        }
        if (lower.startsWith("- ") || lower.matches("\\d+\\..*") || lower.startsWith("controls:")) {
            return ChatFormatting.GRAY;
        }
        if (lower.contains(":")) {
            return ChatFormatting.AQUA;
        }
        return ChatFormatting.GRAY;
    }

    private static String hoverText(CommandMessage.Part part) {
        return part.hoverText() == null || part.hoverText().isBlank() ? part.command() : part.hoverText();
    }

    private static boolean isOperatorPermission(String permission) {
        return permission.startsWith("evilkaraoke.admin.")
                || "evilkaraoke.command.reload".equals(permission)
                || "evilkaraoke.command.doctor".equals(permission)
                || "evilkaraoke.command.listeners".equals(permission)
                || "evilkaraoke.command.audience".equals(permission)
                || "evilkaraoke.command.queue.pause".equals(permission)
                || "evilkaraoke.command.queue.resume".equals(permission)
                || "evilkaraoke.command.queue.stop".equals(permission);
    }

    private static KaraokePlayer player(ServerPlayer player) {
        return new KaraokePlayer(player.getUUID(), player.getGameProfile().name());
    }

    private static final class FabricLuckPermsHook {
        private FabricLuckPermsHook() {
        }

        private static String group(ServerPlayer player) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getPlayerAdapter(ServerPlayer.class)
                        .getUser(player);
                if (user == null || user.getPrimaryGroup() == null || user.getPrimaryGroup().isBlank()) {
                    return "default";
                }
                return user.getPrimaryGroup();
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                return "default";
            }
        }
    }

    private static final class FabricPlatform implements ServerPlaybackPlatform {
        private final MinecraftServer server;
        private final CustomPacketPayload.Type<EvilKaraokePayload> audioType;
        private final JsonPacketCodec codec = new JsonPacketCodec();
        private final TickTaskScheduler scheduler = new TickTaskScheduler(LOGGER);

        private FabricPlatform(MinecraftServer server, CustomPacketPayload.Type<EvilKaraokePayload> audioType) {
            this.server = server;
            this.audioType = audioType;
        }

        private void tick() {
            scheduler.tick();
        }

        @Override
        public void runNow(Runnable task) {
            server.execute(task);
        }

        @Override
        public int runLater(Runnable task, long delayTicks) {
            return scheduler.schedule(task, delayTicks);
        }

        @Override
        public void cancelTask(int taskId) {
            scheduler.cancel(taskId);
        }

        @Override
        public Collection<KaraokePlayer> onlinePlayers() {
            return server.getPlayerList().getPlayers().stream().map(EvilKaraokeFabric::player).toList();
        }

        @Override
        public Optional<KaraokePlayer> player(UUID playerId) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(playerId)).map(EvilKaraokeFabric::player);
        }

        @Override
        public Optional<KaraokePlayer> player(String exactName) {
            return Optional.ofNullable(server.getPlayerList().getPlayerByName(exactName)).map(EvilKaraokeFabric::player);
        }

        @Override
        public void sendAudio(KaraokePlayer player, ProtocolPacket packet) {
            ServerPlayer target = server.getPlayerList().getPlayer(player.id());
            if (target == null || !ServerPlayNetworking.canSend(target, audioType)) {
                return;
            }
            ServerPlayNetworking.send(target, new EvilKaraokePayload(audioType, codec.encode(packet)));
        }

        @Override
        public int pingMillis(KaraokePlayer player) {
            ServerPlayer target = server.getPlayerList().getPlayer(player.id());
            return target == null ? -1 : target.connection.latency();
        }

        @Override
        public void log(Level level, String message, Throwable error) {
            if (error == null) {
                LOGGER.log(level, message);
            } else {
                LOGGER.log(level, message, error);
            }
        }
    }
}
