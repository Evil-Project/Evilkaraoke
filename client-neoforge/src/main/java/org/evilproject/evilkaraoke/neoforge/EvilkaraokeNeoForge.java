package org.evilproject.evilkaraoke.neoforge;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
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

@Mod("evilkaraoke")
public final class EvilkaraokeNeoForge {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");

    private final EvilkaraokePayload.Type<EvilkaraokePayload> audioType = EvilkaraokePayload.type(EvilkaraokeProtocol.AUDIO_CHANNEL);
    private final EvilkaraokePayload.Type<EvilkaraokePayload> helloType = EvilkaraokePayload.type(EvilkaraokeProtocol.HELLO_CHANNEL);
    private final EvilkaraokePayload.Type<EvilkaraokePayload> statusType = EvilkaraokePayload.type(EvilkaraokeProtocol.STATUS_CHANNEL);

    private EvilkaraokeServerCore core;
    private NeoForgePlatform platform;

    public EvilkaraokeNeoForge(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        if (FMLEnvironment.getDist().isClient()) {
            EvilkaraokeNeoForgeClient.init(modBus, container, audioType, helloType, statusType);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(audioType, EvilkaraokePayload.codec(audioType));
        registrar.playToServer(helloType, EvilkaraokePayload.codec(helloType), (payload, context) -> {
            if (core != null && context.player() instanceof ServerPlayer player) {
                core.handlePayload(EvilkaraokeProtocol.HELLO_CHANNEL, player(player), payload.data());
            }
        });
        registrar.playToServer(statusType, EvilkaraokePayload.codec(statusType), (payload, context) -> {
            if (core != null && context.player() instanceof ServerPlayer player) {
                core.handlePayload(EvilkaraokeProtocol.STATUS_CHANNEL, player(player), payload.data());
            }
        });
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        platform = new NeoForgePlatform(server, audioType);
        Path dataDirectory = server.getServerDirectory().resolve("config").resolve("evilkaraoke");
        core = new EvilkaraokeServerCore(LOGGER, dataDirectory, platform);
        core.enable();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (core != null) {
            core.disable();
            core = null;
            platform = null;
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (platform != null) {
            platform.tick();
        }
        if (core != null) {
            core.tick();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(command("ek"));
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (core != null && event.getEntity() instanceof ServerPlayer player) {
            core.unregisterClient(player(player));
        }
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
                if (!permission.startsWith("evilkaraoke.playback.") && !permission.startsWith("evilkaraoke.admin.")) {
                    return true;
                }
                if (player == null) {
                    return true;
                }
                if (source.permissions() instanceof LevelBasedPermissionSet levelBased) {
                    return levelBased.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
                }
                return false;
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

    private static final class NeoForgePlatform implements ServerPlaybackPlatform {
        private final MinecraftServer server;
        private final EvilkaraokePayload.Type<EvilkaraokePayload> audioType;
        private final JsonPacketCodec codec = new JsonPacketCodec();
        private final TickTaskScheduler scheduler = new TickTaskScheduler(LOGGER);

        private NeoForgePlatform(MinecraftServer server, EvilkaraokePayload.Type<EvilkaraokePayload> audioType) {
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
            return server.getPlayerList().getPlayers().stream().map(EvilkaraokeNeoForge::player).toList();
        }

        @Override
        public Optional<KaraokePlayer> player(UUID playerId) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(playerId)).map(EvilkaraokeNeoForge::player);
        }

        @Override
        public Optional<KaraokePlayer> player(String exactName) {
            return Optional.ofNullable(server.getPlayerList().getPlayerByName(exactName)).map(EvilkaraokeNeoForge::player);
        }

        @Override
        public void sendAudio(KaraokePlayer player, AudioCommandPacket packet) {
            ServerPlayer target = server.getPlayerList().getPlayer(player.id());
            if (target == null || !NetworkRegistry.hasChannel(target.connection, audioType.id())) {
                return;
            }
            PacketDistributor.sendToPlayer(target, new EvilkaraokePayload(audioType, codec.encode(packet)));
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
