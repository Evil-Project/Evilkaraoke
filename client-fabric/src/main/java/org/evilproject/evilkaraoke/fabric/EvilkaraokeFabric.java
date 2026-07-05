package org.evilproject.evilkaraoke.fabric;

import java.nio.file.Path;
import java.util.Collection;
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
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;
import org.evilproject.evilkaraoke.server.command.CommandActor;
import org.evilproject.evilkaraoke.server.command.CommandMessage;
import org.evilproject.evilkaraoke.server.command.EvilkaraokeCommandService;
import org.evilproject.evilkaraoke.server.core.EvilkaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.platform.TickTaskScheduler;

public final class EvilkaraokeFabric implements ModInitializer {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");

    private final CustomPacketPayload.Type<EvilkaraokePayload> helloType = EvilkaraokePayload.type(EvilkaraokeProtocol.HELLO_CHANNEL);
    private final CustomPacketPayload.Type<EvilkaraokePayload> audioType = EvilkaraokePayload.type(EvilkaraokeProtocol.AUDIO_CHANNEL);
    private final CustomPacketPayload.Type<EvilkaraokePayload> statusType = EvilkaraokePayload.type(EvilkaraokeProtocol.STATUS_CHANNEL);

    private EvilkaraokeServerCore core;
    private FabricPlatform platform;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(audioType, EvilkaraokePayload.codec(audioType));
        PayloadTypeRegistry.serverboundPlay().register(helloType, EvilkaraokePayload.codec(helloType));
        PayloadTypeRegistry.serverboundPlay().register(statusType, EvilkaraokePayload.codec(statusType));

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
        core = new EvilkaraokeServerCore(LOGGER, dataDirectory, platform);
        core.enable();
    }

    private void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(helloType, (payload, context) -> {
            if (core != null) {
                core.handlePayload(EvilkaraokeProtocol.HELLO_CHANNEL, player(context.player()), payload.data());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(statusType, (payload, context) -> {
            if (core != null) {
                core.handlePayload(EvilkaraokeProtocol.STATUS_CHANNEL, player(context.player()), payload.data());
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
                        .executes(context -> execute(context.getSource(), label, EvilkaraokeCommandService.splitArgs(StringArgumentType.getString(context, "args")))));
    }

    private java.util.concurrent.CompletableFuture<Suggestions> suggest(CommandSourceStack source, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        SuggestionsBuilder tokenBuilder = builder.createOffset(builder.getStart() + EvilkaraokeCommandService.suggestionTokenStart(remaining));
        String[] args = EvilkaraokeCommandService.splitArgsForSuggestions(remaining);
        if (core == null) {
            return SharedSuggestionProvider.suggest(EvilkaraokeCommandService.suggest(args), tokenBuilder);
        }
        return SharedSuggestionProvider.suggest(new EvilkaraokeCommandService(core).suggest(actor(source), args), tokenBuilder);
    }

    private int execute(CommandSourceStack source, String label, String[] args) {
        if (core == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Evilkaraoke server core is not ready yet."));
            return 0;
        }
        return new EvilkaraokeCommandService(core).execute(actor(source), label, args);
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
                if (permission.startsWith("evilkaraoke.playback.") || permission.startsWith("evilkaraoke.admin.")) {
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
                source.sendSystemMessage(Component.literal(message));
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
            MutableComponent child = Component.literal(part.text());
            if (part.clickable()) {
                child.withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand(part.command()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText(part)))));
            }
            root.append(child);
        }
        return root;
    }

    private static String hoverText(CommandMessage.Part part) {
        return part.hoverText() == null || part.hoverText().isBlank() ? part.command() : part.hoverText();
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
        private final CustomPacketPayload.Type<EvilkaraokePayload> audioType;
        private final JsonPacketCodec codec = new JsonPacketCodec();
        private final TickTaskScheduler scheduler = new TickTaskScheduler(LOGGER);

        private FabricPlatform(MinecraftServer server, CustomPacketPayload.Type<EvilkaraokePayload> audioType) {
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
            return server.getPlayerList().getPlayers().stream().map(EvilkaraokeFabric::player).toList();
        }

        @Override
        public Optional<KaraokePlayer> player(UUID playerId) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(playerId)).map(EvilkaraokeFabric::player);
        }

        @Override
        public Optional<KaraokePlayer> player(String exactName) {
            return Optional.ofNullable(server.getPlayerList().getPlayerByName(exactName)).map(EvilkaraokeFabric::player);
        }

        @Override
        public void sendAudio(KaraokePlayer player, AudioCommandPacket packet) {
            ServerPlayer target = server.getPlayerList().getPlayer(player.id());
            if (target == null || !ServerPlayNetworking.canSend(target, audioType)) {
                return;
            }
            ServerPlayNetworking.send(target, new EvilkaraokePayload(audioType, codec.encode(packet)));
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
