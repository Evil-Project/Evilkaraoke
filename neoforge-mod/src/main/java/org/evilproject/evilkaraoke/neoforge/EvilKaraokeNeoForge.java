package org.evilproject.evilkaraoke.neoforge;

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
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.server.command.CommandActor;
import org.evilproject.evilkaraoke.server.command.CommandMessage;
import org.evilproject.evilkaraoke.server.command.EvilKaraokeCommandService;
import org.evilproject.evilkaraoke.server.core.EvilKaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.platform.TickTaskScheduler;

@Mod("evilkaraoke")
public final class EvilKaraokeNeoForge {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");

    private final EvilKaraokePayload.Type<EvilKaraokePayload> audioType = EvilKaraokePayload.type(EvilKaraokeProtocol.AUDIO_CHANNEL);
    private final EvilKaraokePayload.Type<EvilKaraokePayload> helloType = EvilKaraokePayload.type(EvilKaraokeProtocol.HELLO_CHANNEL);
    private final EvilKaraokePayload.Type<EvilKaraokePayload> statusType = EvilKaraokePayload.type(EvilKaraokeProtocol.STATUS_CHANNEL);

    private EvilKaraokeServerCore core;
    private NeoForgePlatform platform;

    public EvilKaraokeNeoForge(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        if (FMLEnvironment.getDist().isClient()) {
            EvilKaraokeNeoForgeClient.init(modBus, container, audioType, helloType, statusType);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(audioType, EvilKaraokePayload.codec(audioType));
        registrar.playToServer(helloType, EvilKaraokePayload.codec(helloType), (payload, context) -> {
            if (core != null && context.player() instanceof ServerPlayer player) {
                core.handlePayload(EvilKaraokeProtocol.HELLO_CHANNEL, player(player), payload.data());
            }
        });
        registrar.playToServer(statusType, EvilKaraokePayload.codec(statusType), (payload, context) -> {
            if (core != null && context.player() instanceof ServerPlayer player) {
                core.handlePayload(EvilKaraokeProtocol.STATUS_CHANNEL, player(player), payload.data());
            }
        });
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        platform = new NeoForgePlatform(server, audioType);
        Path dataDirectory = server.getServerDirectory().resolve("config").resolve("evilkaraoke");
        core = new EvilKaraokeServerCore(LOGGER, dataDirectory, platform);
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
                if (!isOperatorPermission(permission)) {
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

    private static final class NeoForgePlatform implements ServerPlaybackPlatform {
        private final MinecraftServer server;
        private final EvilKaraokePayload.Type<EvilKaraokePayload> audioType;
        private final JsonPacketCodec codec = new JsonPacketCodec();
        private final TickTaskScheduler scheduler = new TickTaskScheduler(LOGGER);

        private NeoForgePlatform(MinecraftServer server, EvilKaraokePayload.Type<EvilKaraokePayload> audioType) {
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
            return server.getPlayerList().getPlayers().stream().map(EvilKaraokeNeoForge::player).toList();
        }

        @Override
        public Optional<KaraokePlayer> player(UUID playerId) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(playerId)).map(EvilKaraokeNeoForge::player);
        }

        @Override
        public Optional<KaraokePlayer> player(String exactName) {
            return Optional.ofNullable(server.getPlayerList().getPlayerByName(exactName)).map(EvilKaraokeNeoForge::player);
        }

        @Override
        public void sendAudio(KaraokePlayer player, ProtocolPacket packet) {
            ServerPlayer target = server.getPlayerList().getPlayer(player.id());
            if (target == null || !NetworkRegistry.hasChannel(target.connection, audioType.id())) {
                return;
            }
            PacketDistributor.sendToPlayer(target, new EvilKaraokePayload(audioType, codec.encode(packet)));
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
