package org.evilproject.evilkaraoke.paper.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.util.DurationFormatter;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;
import org.evilproject.evilkaraoke.paper.stats.StatsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EvilkaraokeCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT_SUBCOMMANDS = List.of(
            "help", "commands", "doctor", "listeners", "reload", "randomsong", "request", "search",
            "playlist", "setlist", "radio", "current", "next", "queue", "pause", "resume", "skip", "stop", "audience", "stats", "issue"
    );

    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final PlaybackCoordinator coordinator;
    private final NeurokaraokeClient neurokaraokeClient;
    private final StatsService statsService;
    private final StatsCommand statsCommand;
    private final Runnable reloadCallback;
    private final EvilkaraokeConfig config;

    public EvilkaraokeCommand(Plugin plugin,
                              ClientRegistry clientRegistry,
                              PlaybackCoordinator coordinator,
                              NeurokaraokeClient neurokaraokeClient,
                              StatsService statsService,
                              EvilkaraokeConfig config,
                              Runnable reloadCallback) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.coordinator = coordinator;
        this.neurokaraokeClient = neurokaraokeClient;
        this.statsService = statsService;
        this.statsCommand = new StatsCommand(statsService);
        this.config = config;
        this.reloadCallback = reloadCallback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help", "commands" -> help(sender, label);
            case "doctor" -> doctor(sender);
            case "listeners" -> listeners(sender);
            case "reload" -> reload(sender);
            case "randomsong" -> randomSong(sender);
            case "request" -> request(sender, args);
            case "search" -> search(sender, args, label);
            case "radio" -> radio(sender, args);
            case "current" -> current(sender);
            case "next" -> next(sender);
            case "queue" -> queue(sender, args);
            case "pause" -> control(sender, "pause");
            case "resume" -> control(sender, "resume");
            case "skip" -> control(sender, "skip");
            case "stop" -> control(sender, "stop");
            case "audience" -> audience(sender, args);
            case "stats" -> statsCommand.handle(sender, args);
            case "issue" -> issue(sender);
            case "playlist", "setlist" -> collections(sender, subcommand);
            default -> unknown(sender);
        };
    }

    private boolean help(CommandSender sender, String label) {
        if (!sender.hasPermission("evilkaraoke.command.help")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Evilkaraoke commands", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " request <query> - request a song", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " request id <songId> - request by id", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " search <query> - search Neurokaraoke", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " randomsong - queue a random song", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue|current|next - inspect playback", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " pause|resume|skip|stop - controls (permission-gated)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " audience <@a|@s|player> - choose who hears playback (/playsound-style)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " radio <radio21|swarmfm> - start radio", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " stats <me|user|server|top> - stats", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " doctor - verify readiness", NamedTextColor.YELLOW));
        return true;
    }

    private boolean doctor(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.admin.doctor")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Evilkaraoke doctor", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Compatible clients online: " + clientRegistry.compatibleClientCount(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Default targets: " + config.defaultTargets(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Radio enabled: " + config.allowRadio(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Stats enabled: " + config.statsEnabled(), NamedTextColor.GRAY));
        if (sender instanceof Player player && clientRegistry.session(player.getUniqueId()).isEmpty()) {
            sender.sendMessage(Component.text("You have not completed the Evilkaraoke client handshake; install the client mod to hear audio.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean listeners(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.admin.doctor")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Modded Evilkaraoke listeners: " + clientRegistry.compatibleClientCount(), NamedTextColor.GOLD));
        clientRegistry.sessions().values().forEach(session ->
                sender.sendMessage(Component.text("- " + session.playerId() + " via " + session.hello().loader() + " " + session.hello().modVersion(), NamedTextColor.GRAY)));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.admin.reload")) {
            return deny(sender);
        }
        reloadCallback.run();
        sender.sendMessage(Component.text("Evilkaraoke configuration reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean randomSong(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (!sender.hasPermission("evilkaraoke.command.request")) {
            return deny(sender);
        }
        Player player = (Player) sender;
        maybeWarnClient(player);
        async(coordinator.requestRandom(player), track -> {
            statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
            player.sendMessage(Component.text("Queued random song: " + track.title() + " - " + track.artist(), NamedTextColor.GREEN));
        }, error -> player.sendMessage(Component.text("Could not fetch a random song: " + error.getMessage(), NamedTextColor.RED)));
        return true;
    }

    private boolean request(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (!sender.hasPermission("evilkaraoke.command.request")) {
            return deny(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek request <query> or /ek request id <songId>", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        maybeWarnClient(player);
        if ("id".equalsIgnoreCase(args[1]) && args.length >= 3) {
            String songId = args[2];
            async(neurokaraokeClient.song(songId).thenCompose(track -> coordinator.request(track, player).thenApply(ignored -> track)),
                    track -> {
                        statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                        player.sendMessage(Component.text("Queued: " + track.title() + " - " + track.artist(), NamedTextColor.GREEN));
                    },
                    error -> player.sendMessage(Component.text("Could not request that song id: " + error.getMessage(), NamedTextColor.RED)));
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        async(coordinator.requestSearch(query, player),
                track -> {
                    statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    player.sendMessage(Component.text("Queued: " + track.title() + " - " + track.artist(), NamedTextColor.GREEN));
                },
                error -> player.sendMessage(Component.text("No match for \"" + query + "\": " + error.getMessage(), NamedTextColor.RED)));
        return true;
    }

    private boolean search(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.search")) {
            return deny(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek search <query>", NamedTextColor.YELLOW));
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        async(neurokaraokeClient.search(query),
                results -> {
                    if (results.isEmpty()) {
                        sender.sendMessage(Component.text("No Neurokaraoke results for \"" + query + "\".", NamedTextColor.YELLOW));
                        return;
                    }
                    sender.sendMessage(Component.text("Results for \"" + query + "\":", NamedTextColor.GOLD));
                    int shown = 0;
                    for (KaraokeTrack track : results) {
                        if (shown++ >= 10) {
                            break;
                        }
                        Component line = Component.text("- " + track.title() + " - " + track.artist() + " ", NamedTextColor.GRAY)
                                .append(Component.text("[Request]", NamedTextColor.GREEN)
                                        .hoverEvent(HoverEvent.showText(Component.text("Request " + track.title())))
                                        .clickEvent(ClickEvent.runCommand("/" + label + " request id " + track.id())));
                        sender.sendMessage(line);
                    }
                },
                error -> sender.sendMessage(Component.text("Search failed: " + error.getMessage(), NamedTextColor.RED)));
        return true;
    }

    private boolean radio(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (!sender.hasPermission("evilkaraoke.command.radio")) {
            return deny(sender);
        }
        if (!config.allowRadio()) {
            sender.sendMessage(Component.text("Radio playback is disabled on this server.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek radio <radio21|swarmfm>", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        maybeWarnClient(player);
        async(coordinator.requestRadio(args[1], player),
                track -> player.sendMessage(Component.text("Queued radio: " + track.title(), NamedTextColor.GREEN)),
                error -> player.sendMessage(Component.text("Could not start radio: " + error.getMessage(), NamedTextColor.RED)));
        return true;
    }

    private boolean current(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.current")) {
            return deny(sender);
        }
        KaraokeSession.PlaybackSnapshot snapshot = coordinator.snapshot();
        if (snapshot.current() == null) {
            sender.sendMessage(Component.text("Nothing is playing right now.", NamedTextColor.YELLOW));
            return true;
        }
        KaraokeTrack track = snapshot.current().track();
        sender.sendMessage(Component.text("Now playing: " + track.title() + " - " + track.artist(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Requested by: " + snapshot.current().requesterName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Elapsed: " + DurationFormatter.mmss(snapshot.offset()), NamedTextColor.GRAY));
        return true;
    }

    private boolean next(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.current")) {
            return deny(sender);
        }
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(coordinator.queue());
        if (queue.isEmpty()) {
            sender.sendMessage(Component.text("No upcoming songs queued.", NamedTextColor.YELLOW));
            return true;
        }
        KaraokeSession.QueuedTrack upcoming = queue.getFirst();
        sender.sendMessage(Component.text("Up next: " + upcoming.track().title() + " - " + upcoming.track().artist(), NamedTextColor.GOLD));
        return true;
    }

    private boolean queue(CommandSender sender, String[] args) {
        if (!sender.hasPermission("evilkaraoke.command.queue")) {
            return deny(sender);
        }
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(coordinator.queue());
        int page = parsePage(args);
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil(queue.size() / (double) pageSize));
        page = Math.min(page, totalPages);
        sender.sendMessage(Component.text("Queue (page " + page + "/" + totalPages + ")", NamedTextColor.GOLD));
        int start = (page - 1) * pageSize;
        for (int i = start; i < Math.min(queue.size(), start + pageSize); i++) {
            KaraokeSession.QueuedTrack queued = queue.get(i);
            sender.sendMessage(Component.text((i + 1) + ". " + queued.track().title() + " - " + queued.track().artist() + " (by " + queued.requesterName() + ")", NamedTextColor.GRAY));
        }
        if (queue.isEmpty()) {
            sender.sendMessage(Component.text("The queue is empty.", NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean control(CommandSender sender, String action) {
        if (!sender.hasPermission("evilkaraoke.playback." + action)) {
            return deny(sender);
        }
        switch (action) {
            case "pause" -> coordinator.pause();
            case "resume" -> coordinator.resume();
            case "skip" -> coordinator.skip();
            case "stop" -> coordinator.stop();
            default -> {
                return unknown(sender);
            }
        }
        sender.sendMessage(Component.text("Playback " + action + " sent to listeners.", NamedTextColor.GREEN));
        return true;
    }

    private boolean audience(CommandSender sender, String[] args) {
        if (!sender.hasPermission("evilkaraoke.playback.audience")) {
            return deny(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek audience <@a|@s|player>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Current audience: " + coordinator.audienceLabel(), NamedTextColor.GRAY));
            return true;
        }
        String target = args[1];
        if ("@a".equalsIgnoreCase(target)) {
            coordinator.setAudienceAll();
            sender.sendMessage(Component.text("Playback audience set to all players (@a), mirroring /playsound @a.", NamedTextColor.GREEN));
            return true;
        }
        Player targetPlayer;
        if ("@s".equalsIgnoreCase(target)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("@s can only be used by a player.", NamedTextColor.RED));
                return true;
            }
            targetPlayer = player;
        } else {
            targetPlayer = Bukkit.getPlayerExact(target);
        }
        if (targetPlayer == null) {
            sender.sendMessage(Component.text("Player not found or not online: " + target, NamedTextColor.RED));
            return true;
        }
        coordinator.setAudiencePlayer(targetPlayer.getUniqueId(), targetPlayer.getName());
        sender.sendMessage(Component.text("Playback audience set to " + targetPlayer.getName() + " only, mirroring /playsound targeting one player.", NamedTextColor.GREEN));
        return true;
    }

    private boolean issue(CommandSender sender) {
        sender.sendMessage(Component.text("Evilkaraoke troubleshooting", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("1. Install the Evilkaraoke client mod (Fabric or NeoForge) to hear audio.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("2. Ensure your Minecraft sound and Music/Jukebox volume are above zero.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("3. Run /ek doctor to confirm the client handshake succeeded.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("4. If audio stalls, /ek skip to advance the queue.", NamedTextColor.GRAY));
        return true;
    }

    private boolean collections(CommandSender sender, String subcommand) {
        sender.sendMessage(Component.text("/ek " + subcommand + " browsing uses the same request flow: use /ek search <name> then click [Request].", NamedTextColor.YELLOW));
        return true;
    }

    private boolean unknown(CommandSender sender) {
        sender.sendMessage(Component.text("Unknown Evilkaraoke subcommand. Try /ek help.", NamedTextColor.RED));
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(Component.text("You do not have permission to use that Evilkaraoke command.", NamedTextColor.RED));
        return true;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        sender.sendMessage(Component.text("That command must be run by a player.", NamedTextColor.RED));
        return false;
    }

    private void maybeWarnClient(Player player) {
        if (config.requireClientMod() && clientRegistry.session(player.getUniqueId()).isEmpty()) {
            player.sendMessage(Component.text("Heads up: install the Evilkaraoke client mod to actually hear playback.", NamedTextColor.YELLOW));
        }
    }

    private <T> void async(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                onError.accept(error.getCause() == null ? error : error.getCause());
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    private int parsePage(String[] args) {
        if (args.length >= 2) {
            try {
                return Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                // default page
            }
        }
        return 1;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String subcommand : ROOT_SUBCOMMANDS) {
                if (subcommand.startsWith(prefix)) {
                    matches.add(subcommand);
                }
            }
            return matches;
        }
        if (args.length == 2 && "radio".equalsIgnoreCase(args[0])) {
            return List.of("radio21", "swarmfm");
        }
        if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
            return List.of("me", "user", "server", "top");
        }
        if (args.length == 2 && "audience".equalsIgnoreCase(args[0])) {
            return List.of("@a", "@s");
        }
        return List.of();
    }
}
