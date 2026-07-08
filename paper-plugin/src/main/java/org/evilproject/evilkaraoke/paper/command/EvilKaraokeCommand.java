package org.evilproject.evilkaraoke.paper.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

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
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.UserAudioTracks;
import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;
import org.evilproject.evilkaraoke.common.util.DurationFormatter;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeApiUnavailableException;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeSetlist;
import org.evilproject.evilkaraoke.paper.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.playback.PlaybackCoordinator;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;
import org.evilproject.evilkaraoke.paper.permission.PermissionService;
import org.evilproject.evilkaraoke.paper.stats.StatsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EvilKaraokeCommand implements CommandExecutor, TabCompleter {
    private static final int CHAT_PAGE_SIZE = 5;
    private static final String QUEUE_REFRESH_TOKEN = "--refresh";

    private static final List<String> CANONICAL_ROOT_SUBCOMMANDS = List.of(
            "help", "doctor", "listeners", "reload", "randomsong", "request", "search",
            "setlist", "playlist", "radio", "current", "queue",
            "audience", "stats", "issue"
    );

    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final PlaybackCoordinator coordinator;
    private final NeurokaraokeClient neurokaraokeClient;
    private final StatsService statsService;
    private final StatsCommand statsCommand;
    private final Runnable reloadCallback;
    private final EvilKaraokeConfig config;
    private final PermissionService permissionService;
    private final Map<UUID, List<KaraokeTrack>> randomSongSelections = new ConcurrentHashMap<>();
    private final Map<UUID, List<KaraokeTrack>> searchResultSelections = new ConcurrentHashMap<>();

    public EvilKaraokeCommand(Plugin plugin,
                              ClientRegistry clientRegistry,
                              PlaybackCoordinator coordinator,
                              NeurokaraokeClient neurokaraokeClient,
                              StatsService statsService,
                              EvilKaraokeConfig config,
                              PermissionService permissionService,
                              Runnable reloadCallback) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.coordinator = coordinator;
        this.neurokaraokeClient = neurokaraokeClient;
        this.statsService = statsService;
        this.statsCommand = new StatsCommand(statsService, permissionService);
        this.config = config;
        this.permissionService = permissionService;
        this.reloadCallback = reloadCallback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String subcommand = args.length == 0 ? "help" : canonicalSubcommand(args[0]);
        return switch (subcommand) {
            case "help" -> help(sender, label);
            case "doctor" -> doctor(sender);
            case "listeners" -> listeners(sender);
            case "reload" -> reload(sender);
            case "randomsong" -> randomSong(sender, args, label);
            case "request" -> request(sender, args);
            case "search" -> search(sender, args, label);
            case "radio" -> radio(sender, args);
            case "current" -> current(sender);
            case "queue" -> queue(sender, args, label);
            case "audience" -> audience(sender, args);
            case "stats" -> statsCommand.handle(sender, args);
            case "issue" -> issue(sender);
            case "setlist" -> setlist(sender, args, label);
            case "playlist" -> playlist(sender, args, label);
            default -> unknown(sender);
        };
    }

    private static String canonicalSubcommand(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean hasQueueRefreshToken(String[] args) {
        return Arrays.stream(args).anyMatch(EvilKaraokeCommand::isQueueRefreshToken);
    }

    private static String[] stripQueueRefreshTokens(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !isQueueRefreshToken(arg))
                .toArray(String[]::new);
    }

    private static boolean isQueueRefreshToken(String arg) {
        return QUEUE_REFRESH_TOKEN.equalsIgnoreCase(arg)
                || arg.toLowerCase(Locale.ROOT).startsWith(QUEUE_REFRESH_TOKEN + "=");
    }

    private static int queueRefreshPage(String[] args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith(QUEUE_REFRESH_TOKEN + "=")) {
                return parseRawInt(arg.substring((QUEUE_REFRESH_TOKEN + "=").length()), 1);
            }
        }
        return 1;
    }

    private static String queueRefreshToken(int page) {
        return QUEUE_REFRESH_TOKEN + "=" + Math.max(1, page);
    }

    private boolean help(CommandSender sender, String label) {
        if (!sender.hasPermission("evilkaraoke.command.help")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Evilkaraoke commands", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " request <query> - search and request a song", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " request id <songId> - request by song id", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " request url <https://...> [title] - request a direct audio URL", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " search <query> - search Neurokaraoke", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " search queue-all - queue the latest shown search page", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " setlist [page] - list Neurokaraoke setlists", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " playlist [page] - list public Neurokaraoke playlists", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " randomsong [page] - show random songs to queue", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " randomsong queue <all|row> - queue all latest random songs or one row", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue|current - inspect playback", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue cancel <position|all> - remove queued song(s)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue move <from> <to> - reorder your queued songs", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue pause|resume|stop - playback controls", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " queue previous|next - navigate tracks", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " audience <@a|@s|player> - choose who hears playback (/playsound-style)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " radio <radio21|swarmfm> - start radio", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " stats <me|user|server|top> - stats", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " doctor - verify readiness", NamedTextColor.YELLOW));
        return true;
    }

    private boolean doctor(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.doctor")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Evilkaraoke doctor", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Compatible clients online: " + clientRegistry.compatibleClientCount(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Default targets: " + config.defaultTargets(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Radio enabled: " + config.allowRadio(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Stats enabled: " + config.statsEnabled(), NamedTextColor.GRAY));
        if (sender instanceof Player player && !clientRegistry.isCompatible(player.getUniqueId())) {
            sender.sendMessage(Component.text("You have not completed a compatible Evilkaraoke client handshake; install or update the client mod to hear audio.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean listeners(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.listeners")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Modded Evilkaraoke listeners: " + clientRegistry.compatibleClientCount(), NamedTextColor.GOLD));
        clientRegistry.sessions().values().forEach(session ->
                sender.sendMessage(Component.text("- " + session.playerId() + " via " + session.hello().loader() + " " + session.hello().modVersion(), NamedTextColor.GRAY)));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.reload")) {
            return deny(sender);
        }
        reloadCallback.run();
        sender.sendMessage(Component.text("Evilkaraoke configuration reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean randomSong(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.randomsong")) {
            return deny(sender);
        }
        int page = 1;
        if (args.length >= 2) {
            if ("queue".equalsIgnoreCase(args[1])) {
                return queueRandomSongSelection(sender, args);
            }
            page = parseRawInt(args[1], -1);
            if (page < 1) {
                sender.sendMessage(Component.text("Usage: /ek randomsong [page] | /ek randomsong queue <all|row>", NamedTextColor.YELLOW));
                return true;
            }
        }
        if (!requirePlayer(sender)) {
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        int requestedPage = page;
        if (args.length >= 2) {
            List<KaraokeTrack> cached = randomSongSelections.getOrDefault(player.getUniqueId(), List.of());
            if (!cached.isEmpty()) {
                randomSongMessages(cached, requestedPage, label).forEach(player::sendMessage);
                return true;
            }
        }
        async(neurokaraokeClient.randomSongs().thenApply(this::randomPlaylist), tracks -> {
            randomSongSelections.put(player.getUniqueId(), List.copyOf(tracks));
            randomSongMessages(tracks, requestedPage, label).forEach(player::sendMessage);
        }, error -> player.sendMessage(ChatMessages.error("Could not fetch random songs", error)));
        return true;
    }

    private boolean queueRandomSongSelection(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /ek randomsong queue <all|row>", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        List<KaraokeTrack> tracks = randomSongSelections.getOrDefault(player.getUniqueId(), List.of());
        if (tracks.isEmpty()) {
            sender.sendMessage(Component.text("No random song playlist is ready. Use /ek randomsong first.", NamedTextColor.YELLOW));
            return true;
        }
        maybeWarnClient(player);
        if ("all".equalsIgnoreCase(args[2])) {
            async(coordinator.requestAll(tracks, player),
                    count -> {
                        for (KaraokeTrack track : tracks) {
                            statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                        }
                        player.sendMessage(Component.text("Queued " + count + " random song(s).", NamedTextColor.GREEN));
                    },
                    error -> player.sendMessage(ChatMessages.error("Could not queue random songs", error)));
            return true;
        }
        int row = parseRawInt(args[2], -1);
        if (row < 1 || row > tracks.size()) {
            sender.sendMessage(Component.text("Random song row must be between 1 and " + tracks.size() + ".", NamedTextColor.RED));
            return true;
        }
        KaraokeTrack track = tracks.get(row - 1);
        async(coordinator.request(track, player).thenApply(ignored -> track),
                queued -> {
                    statsService.recordRequest(player.getUniqueId(), player.getName(), queued.id(), queued.title());
                    player.sendMessage(Component.text("Queued random song: ", NamedTextColor.GREEN)
                            .append(songLineComponent(queued)));
                },
                error -> player.sendMessage(ChatMessages.error("Could not queue that random song", error)));
        return true;
    }

    private List<KaraokeTrack> randomPlaylist(List<KaraokeTrack> tracks) {
        if (tracks.isEmpty()) {
            throw new IllegalStateException("No random Neurokaraoke songs returned");
        }
        return List.copyOf(tracks);
    }

    private boolean request(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek request <query> | /ek request id <songId> | /ek request url <https://...> [title]", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        maybeWarnClient(player);
        if (isSongIdMode(args[1])) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /ek request id <songId>", NamedTextColor.YELLOW));
                return true;
            }
            String songId = args[2];
            async(neurokaraokeClient.song(songId).thenCompose(track -> coordinator.request(track, player).thenApply(ignored -> track)),
                    track -> {
                        statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                        player.sendMessage(Component.text("Queued: ", NamedTextColor.GREEN)
                                .append(songLineComponent(track)));
                    },
                    error -> player.sendMessage(ChatMessages.error("Could not request that song id", error)));
            return true;
        }
        if ("url".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /ek request url <https://...> [title]", NamedTextColor.YELLOW));
                return true;
            }
            return requestUrl(player, args[2], joinTail(args, 3));
        }
        if (AudioUrlValidator.hasHttpScheme(args[1])) {
            return requestUrl(player, args[1], joinTail(args, 2));
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        async(coordinator.requestSearch(query, player),
                track -> {
                    statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    player.sendMessage(Component.text("Queued: ", NamedTextColor.GREEN)
                            .append(songLineComponent(track)));
                },
                error -> player.sendMessage(ChatMessages.error("No match for \"" + query + "\"", error)));
        return true;
    }

    private boolean requestUrl(Player player, String rawUrl, String title) {
        CompletableFuture<KaraokeTrack> future = CompletableFuture.supplyAsync(() -> UserAudioTracks.fromUrl(rawUrl, title))
                .thenCompose(track -> coordinator.request(track, player).thenApply(ignored -> track));
        async(future,
                track -> {
                    statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    player.sendMessage(Component.text("Queued URL: ", NamedTextColor.GREEN)
                            .append(songLineComponent(track)));
                },
                error -> player.sendMessage(ChatMessages.error("Could not request that URL", error)));
        return true;
    }

    private boolean canRequest(Player player) {
        if (!player.hasPermission("evilkaraoke.command.request")) {
            deny(player);
            return false;
        }
        return true;
    }

    private boolean search(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.search")) {
            return deny(sender);
        }
        if (args.length >= 2 && "queue-all".equalsIgnoreCase(args[1])) {
            return queueSearchSelection(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ek search <query> [page] | /ek search queue-all", NamedTextColor.YELLOW));
            return true;
        }
        SearchRequest searchRequest = parseSearchRequest(args);
        async(searchPage(searchRequest),
                page -> {
                    if (page.results().isEmpty()) {
                        sender.sendMessage(Component.text("No Neurokaraoke results for \"" + searchRequest.query() + "\".", NamedTextColor.YELLOW));
                        return;
                    }
                    if (sender instanceof Player player) {
                        searchResultSelections.put(player.getUniqueId(), List.copyOf(page.results()));
                    }
                    searchMessages(searchRequest.query(), searchRequest.page(), page.results(), label, page.hasNextPage()).forEach(sender::sendMessage);
                },
                error -> sender.sendMessage(ChatMessages.error("Search failed", error)));
        return true;
    }

    private boolean queueSearchSelection(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        List<KaraokeTrack> tracks = searchResultSelections.getOrDefault(player.getUniqueId(), List.of());
        if (tracks.isEmpty()) {
            sender.sendMessage(Component.text("No search results are ready. Use /ek search <query> first.", NamedTextColor.YELLOW));
            return true;
        }
        maybeWarnClient(player);
        async(coordinator.requestAll(tracks, player),
                count -> {
                    for (KaraokeTrack track : tracks) {
                        statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    }
                    player.sendMessage(Component.text("Queued " + count + " search result song(s).", NamedTextColor.GREEN));
                },
                error -> player.sendMessage(ChatMessages.error("Could not queue search results", error)));
        return true;
    }

    private CompletableFuture<SearchPage> searchPage(SearchRequest request) {
        return neurokaraokeClient.search(request.query(), request.page() - 1, CHAT_PAGE_SIZE)
                .thenCompose(results -> {
                    if (results.size() < CHAT_PAGE_SIZE) {
                        return CompletableFuture.completedFuture(new SearchPage(results, false));
                    }
                    return neurokaraokeClient.search(request.query(), request.page(), CHAT_PAGE_SIZE)
                            .thenApply(nextResults -> new SearchPage(results, !nextResults.isEmpty()));
                });
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
                error -> player.sendMessage(ChatMessages.error("Could not start radio", error)));
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
        sender.sendMessage(Component.text("Now playing: ", NamedTextColor.GOLD)
                .append(songLineComponent(track))
                .append(currentTrackDetails(snapshot)));
        return true;
    }

    private boolean queue(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.queue")) {
            return deny(sender);
        }
        boolean refresh = hasQueueRefreshToken(args);
        int refreshPage = queueRefreshPage(args);
        String[] queueArgs = stripQueueRefreshTokens(args);
        if (queueArgs.length >= 2 && isQueuePlaybackControl(queueArgs[1])) {
            return control(sender, queueArgs[1].toLowerCase(Locale.ROOT), refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "cancel".equalsIgnoreCase(queueArgs[1])) {
            return cancel(sender, queueArgs, 2, "/" + label + " queue cancel <position|all>", refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "move".equalsIgnoreCase(queueArgs[1])) {
            return moveQueue(sender, queueArgs, refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "random".equalsIgnoreCase(queueArgs[1])) {
            return toggleRandomQueue(sender, refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "loop".equalsIgnoreCase(queueArgs[1])) {
            return queueArgs.length >= 3
                    ? toggleSingleLoop(sender, queueArgs, refresh, label, refreshPage)
                    : toggleQueueLoop(sender, refresh, label, refreshPage);
        }
        KaraokeSession.PlaybackSnapshot snapshot = coordinator.snapshot();
        queueMessages(sender, snapshot, parsePage(queueArgs), "Queue", label).forEach(sender::sendMessage);
        return true;
    }

    private boolean toggleRandomQueue(CommandSender sender, boolean refresh, String label, int page) {
        if (!sender.hasPermission("evilkaraoke.command.queue.random")) {
            return deny(sender);
        }
        boolean enabled = coordinator.toggleRandomQueue();
        if (refresh) {
            return refreshQueue(sender, label, page);
        }
        sender.sendMessage(Component.text(enabled ? "Random queue order shuffled." : "Random queue playback disabled.", NamedTextColor.GREEN));
        return true;
    }

    private boolean toggleQueueLoop(CommandSender sender, boolean refresh, String label, int page) {
        if (!sender.hasPermission("evilkaraoke.command.queue.loop")) {
            return deny(sender);
        }
        boolean enabled = coordinator.toggleQueueLoop();
        if (refresh) {
            return refreshQueue(sender, label, page);
        }
        sender.sendMessage(Component.text("Queue loop " + (enabled ? "enabled." : "disabled."), NamedTextColor.GREEN));
        return true;
    }

    private boolean toggleSingleLoop(CommandSender sender, String[] args, boolean refresh, String label, int page) {
        if (!sender.hasPermission("evilkaraoke.command.queue.loop")) {
            return deny(sender);
        }
        int position = parseRawInt(args[2], -1);
        if (position < 1) {
            sender.sendMessage(Component.text("Queue position must be a positive number.", NamedTextColor.RED));
            return true;
        }
        coordinator.toggleSingleLoop(position).ifPresentOrElse(
                change -> {
                    if (refresh) {
                        refreshQueue(sender, label, page);
                    } else {
                        sender.sendMessage(Component.text((change.enabled() ? "Single-song loop enabled: " : "Single-song loop disabled: ")
                                + songLine(change.track().track()), NamedTextColor.GREEN));
                    }
                },
                () -> sender.sendMessage(Component.text("Position " + position + " is out of range. Queue has " + coordinator.queue().size() + " song(s).", NamedTextColor.RED)));
        return true;
    }

    private boolean moveQueue(CommandSender sender, String[] args, boolean refresh, String label, int page) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (!sender.hasPermission("evilkaraoke.command.queue.move")) {
            return deny(sender);
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /ek queue move <from> <to>", NamedTextColor.YELLOW));
            return true;
        }
        int from = parseRawInt(args[2], -1);
        int to = parseRawInt(args[3], -1);
        if (from < 1 || to < 1) {
            sender.sendMessage(Component.text("Queue positions must be positive numbers.", NamedTextColor.RED));
            return true;
        }
        if (from == to) {
            sender.sendMessage(Component.text("That song is already at position " + to + ".", NamedTextColor.YELLOW));
            return true;
        }
        List<KaraokeSession.QueuedTrack> requests = coordinator.snapshot().requests();
        if (from > requests.size() || to > requests.size()) {
            sender.sendMessage(Component.text("Only requested songs can be reordered. Requested queue has " + requests.size() + " song(s).", NamedTextColor.RED));
            return true;
        }
        if (!canMoveRequestedRange(sender, requests, from - 1, to - 1)) {
            sender.sendMessage(Component.text("You can only reorder your own queued songs.", NamedTextColor.RED));
            return true;
        }
        coordinator.moveRequest(from, to).ifPresentOrElse(
                moved -> {
                    if (refresh) {
                        refreshQueue(sender, label, page);
                    } else {
                        sender.sendMessage(Component.text("Moved: " + moved.track().title() + " from " + from + " to " + to + ".", NamedTextColor.GREEN));
                    }
                },
                () -> sender.sendMessage(Component.text("Could not move that queued song.", NamedTextColor.RED)));
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args, int argumentIndex, String usage) {
        return cancel(sender, args, argumentIndex, usage, false, "ek", 1);
    }

    private boolean cancel(CommandSender sender, String[] args, int argumentIndex, String usage, boolean refresh, String label, int page) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (!sender.hasPermission("evilkaraoke.command.queue.cancel")) {
            return deny(sender);
        }
        if (args.length <= argumentIndex) {
            sender.sendMessage(Component.text("Usage: " + usage, NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Use /ek queue to see song positions.", NamedTextColor.GRAY));
            return true;
        }

        Player player = (Player) sender;
        String target = args[argumentIndex];
        if ("all".equalsIgnoreCase(target)) {
            return cancelAll(sender, player, refresh, label, page);
        }

        int position;
        try {
            position = Integer.parseInt(target);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid position. Must be a number.", NamedTextColor.RED));
            return true;
        }

        if (position < 1) {
            sender.sendMessage(Component.text("Position must be 1 or greater.", NamedTextColor.RED));
            return true;
        }

        // Get the track before removing it to check permissions and show info
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(coordinator.queue());
        if (position > queue.size()) {
            sender.sendMessage(Component.text("Position " + position + " is out of range. Queue has " + queue.size() + " song(s).", NamedTextColor.RED));
            return true;
        }

        KaraokeSession.QueuedTrack trackToRemove = queue.get(position - 1);

        // Check permission: users can only cancel their own songs unless they have admin permission
        boolean isAdmin = sender.hasPermission("evilkaraoke.admin.queue.cancel");
        boolean isOwnSong = trackToRemove.requester() != null && trackToRemove.requester().equals(player.getUniqueId());

        if (!isAdmin && !isOwnSong) {
            sender.sendMessage(Component.text("You can only cancel your own songs. That song was queued by " + trackToRemove.requesterName() + ".", NamedTextColor.RED));
            return true;
        }

        // Remove the track
        coordinator.cancelAt(position).ifPresentOrElse(
            removed -> {
                if (refresh) {
                    refreshQueue(sender, label, page);
                } else {
                    sender.sendMessage(Component.text("Removed from queue: " + songLine(removed.track()), NamedTextColor.GREEN));
                }
                if (!refresh && isAdmin && !isOwnSong) {
                    sender.sendMessage(Component.text("(was queued by " + removed.requesterName() + ")", NamedTextColor.GRAY));
                }
            },
            () -> sender.sendMessage(Component.text("Could not remove song at position " + position + ".", NamedTextColor.RED))
        );

        return true;
    }

    private boolean cancelAll(CommandSender sender, Player player) {
        return cancelAll(sender, player, false, "ek", 1);
    }

    private boolean cancelAll(CommandSender sender, Player player, boolean refresh, String label, int page) {
        boolean isAdmin = sender.hasPermission("evilkaraoke.admin.queue.cancel");
        List<KaraokeSession.QueuedTrack> removed = isAdmin
                ? coordinator.cancelAll()
                : coordinator.cancelAllByRequester(player.getUniqueId());
        if (removed.isEmpty()) {
            sender.sendMessage(Component.text(isAdmin ? "The queue is already empty." : "You have no queued songs to cancel.", NamedTextColor.YELLOW));
            return true;
        }
        if (refresh) {
            return refreshQueue(sender, label, page);
        }
        sender.sendMessage(Component.text("Removed " + removed.size() + " queued song(s).", NamedTextColor.GREEN));
        if (!isAdmin) {
            sender.sendMessage(Component.text("Only your queued songs were removed.", NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean refreshQueue(CommandSender sender, String label, int page) {
        queueMessages(sender, coordinator.snapshot(), page, "Queue", label).forEach(sender::sendMessage);
        return true;
    }

    static List<Component> queueMessages(KaraokeSession.PlaybackSnapshot snapshot, int requestedPage) {
        return queueMessages(snapshot, requestedPage, "Queue");
    }

    static List<Component> queueMessages(KaraokeSession.PlaybackSnapshot snapshot, int requestedPage, String title) {
        return queueMessages(snapshot, requestedPage, title, "ek");
    }

    static List<Component> queueMessages(KaraokeSession.PlaybackSnapshot snapshot, int requestedPage, String title, String label) {
        return queueMessages(null, snapshot, requestedPage, title, label);
    }

    private static List<Component> queueMessages(CommandSender sender, KaraokeSession.PlaybackSnapshot snapshot, int requestedPage, String title, String label) {
        List<Component> messages = new ArrayList<>();
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(snapshot.requests());
        queue.addAll(snapshot.randomTracks());
        List<KaraokeSession.QueuedTrack> requests = snapshot.requests();

        int page = Math.max(1, requestedPage);
        int totalPages = Math.max(1, (int) Math.ceil(queue.size() / (double) CHAT_PAGE_SIZE));
        page = Math.min(page, totalPages);

        if (snapshot.current() != null) {
            KaraokeTrack currentTrack = snapshot.current().track();
            messages.add(currentTrackMessage(sender, snapshot, songLineComponent(currentTrack), label, page));
        }

        messages.add(Component.text(title + " (page " + page + "/" + totalPages + ")", NamedTextColor.GOLD));
        int start = (page - 1) * CHAT_PAGE_SIZE;
        for (int i = start; i < Math.min(queue.size(), start + CHAT_PAGE_SIZE); i++) {
            KaraokeSession.QueuedTrack queued = queue.get(i);
            int position = i + 1;
            Component line = Component.text(position + ". ", NamedTextColor.GOLD)
                    .append(songLineComponent(queued.track()))
                    .append(Component.text(" (by ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(queued.requesterName(), NamedTextColor.WHITE))
                    .append(Component.text(") ", NamedTextColor.DARK_GRAY));
            int requestIndex = position - 1;
            if (sender != null && requestIndex >= 0 && requestIndex < requests.size()) {
                if (requestIndex > 0 && canMoveRequestedRange(sender, requests, requestIndex, requestIndex - 1)) {
                    line = line.append(Component.text("[Up]", NamedTextColor.GREEN)
                            .hoverEvent(HoverEvent.showText(Component.text("Move earlier")))
                            .clickEvent(ClickEvent.runCommand("/" + label + " queue move " + position + " " + (position - 1) + " " + queueRefreshToken(page))))
                            .append(Component.text(" ", NamedTextColor.GRAY));
                }
                if (requestIndex + 1 < requests.size() && canMoveRequestedRange(sender, requests, requestIndex, requestIndex + 1)) {
                    line = line.append(Component.text("[Down]", NamedTextColor.GREEN)
                            .hoverEvent(HoverEvent.showText(Component.text("Move later")))
                            .clickEvent(ClickEvent.runCommand("/" + label + " queue move " + position + " " + (position + 1) + " " + queueRefreshToken(page))))
                            .append(Component.text(" ", NamedTextColor.GRAY));
                }
            }
            boolean singleLoop = sameQueuedTrack(queued, snapshot.singleLoopTrack());
            line = line.append(Component.text(singleLoop ? "[Loop 1: On]" : "[Loop 1]", singleLoop ? NamedTextColor.AQUA : NamedTextColor.BLUE)
                    .hoverEvent(HoverEvent.showText(Component.text(singleLoop ? "Disable single-song loop" : "Loop this song")))
                    .clickEvent(ClickEvent.runCommand("/" + label + " queue loop " + position + " " + queueRefreshToken(page))))
                    .append(Component.text(" ", NamedTextColor.GRAY));
            line = line.append(Component.text("[Cancel]", NamedTextColor.RED)
                    .hoverEvent(HoverEvent.showText(Component.text("Remove this song from the queue")))
                    .clickEvent(ClickEvent.runCommand("/" + label + " queue cancel " + position + " " + queueRefreshToken(page))));
            messages.add(line);
        }
        if (queue.isEmpty()) {
            messages.add(Component.text(snapshot.current() == null ? "The queue is empty." : "No upcoming songs queued.", NamedTextColor.GRAY));
        }
        if (totalPages > 1) {
            messages.add(pageNavigation("queue", "/" + label + " queue", page, page > 1, page < totalPages));
        }
        messages.add(queueControls(sender, snapshot, label, page));
        return messages;
    }

    private static Component currentTrackMessage(CommandSender sender, KaraokeSession.PlaybackSnapshot snapshot, Component trackLine, String label, int page) {
        Component line = Component.text("Now playing: ", NamedTextColor.GOLD)
                .append(trackLine)
                .append(currentTrackDetails(snapshot));
        if (snapshot.state() == PlaybackState.PLAYING && canUsePlaybackControl(sender, "pause")) {
            line = line.append(Component.text(" ", NamedTextColor.GRAY))
                    .append(playbackControlButton("Pause", "/" + label + " queue pause " + queueRefreshToken(page), "Pause current playback", NamedTextColor.YELLOW));
        } else if (snapshot.state() == PlaybackState.PAUSED && canUsePlaybackControl(sender, "resume")) {
            line = line.append(Component.text(" ", NamedTextColor.GRAY))
                    .append(playbackControlButton("Resume", "/" + label + " queue resume " + queueRefreshToken(page), "Resume current playback", NamedTextColor.GREEN));
        }
        if (canUsePlaybackControl(sender, "stop")) {
            line = line.append(Component.text(" ", NamedTextColor.GRAY))
                    .append(playbackControlButton("Stop", "/" + label + " queue stop " + queueRefreshToken(page), "Stop current playback", NamedTextColor.RED));
        }
        return line;
    }

    private static Component currentTrackDetails(KaraokeSession.PlaybackSnapshot snapshot) {
        if (snapshot.current() == null) {
            return Component.empty();
        }
        return Component.text(" (by ", NamedTextColor.DARK_GRAY)
                .append(Component.text(snapshot.current().requesterName(), NamedTextColor.WHITE))
                .append(Component.text(") | Elapsed: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(DurationFormatter.mmss(snapshot.offset()), NamedTextColor.AQUA));
    }

    private static Component playbackControlButton(String text, String command, String hoverText, NamedTextColor color) {
        return Component.text("[" + text + "]", color)
                .hoverEvent(HoverEvent.showText(Component.text(hoverText)))
                .clickEvent(ClickEvent.runCommand(command));
    }

    private static boolean canUsePlaybackControl(CommandSender sender, String action) {
        return isPublicSwitchControl(action) || sender == null || sender.hasPermission("evilkaraoke.command.queue." + action);
    }

    private static boolean isQueuePlaybackControl(String value) {
        return "pause".equalsIgnoreCase(value)
                || "resume".equalsIgnoreCase(value)
                || "previous".equalsIgnoreCase(value)
                || "next".equalsIgnoreCase(value)
                || "stop".equalsIgnoreCase(value);
    }

    private static boolean isPublicSwitchControl(String action) {
        return "previous".equalsIgnoreCase(action) || "next".equalsIgnoreCase(action);
    }

    private static Component queueControls(CommandSender sender, KaraokeSession.PlaybackSnapshot snapshot, String label, int page) {
        Component controls = Component.text("Controls: ", NamedTextColor.GRAY);
        controls = controls.append(Component.text("[Previous]", NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(Component.text("Go back to previous track")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " queue previous " + queueRefreshToken(page))))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("[Next]", NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(Component.text("Skip to next track")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " queue next " + queueRefreshToken(page))))
                .append(Component.text(" ", NamedTextColor.GRAY));
        return controls.append(Component.text(snapshot.randomEnabled() ? "[Random: On]" : "[Random: Off]",
                        snapshot.randomEnabled() ? NamedTextColor.AQUA : NamedTextColor.BLUE)
                        .hoverEvent(HoverEvent.showText(Component.text("Toggle random queue order")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " queue random " + queueRefreshToken(page))))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(snapshot.loopQueueEnabled() ? "[Loop: On]" : "[Loop: Off]",
                                snapshot.loopQueueEnabled() ? NamedTextColor.AQUA : NamedTextColor.BLUE)
                        .hoverEvent(HoverEvent.showText(Component.text("Toggle whole queue loop")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " queue loop " + queueRefreshToken(page))));
    }

    private boolean control(CommandSender sender, String action, boolean refresh, String label, int page) {
        if (!canUsePlaybackControl(sender, action)) {
            return deny(sender);
        }
        switch (action) {
            case "pause" -> coordinator.pause();
            case "resume" -> coordinator.resume();
            case "next" -> coordinator.skip();
            case "previous" -> coordinator.previous();
            case "stop" -> coordinator.stop();
            default -> {
                return unknown(sender);
            }
        }
        if (refresh) {
            return refreshQueue(sender, label, page);
        }
        sender.sendMessage(Component.text(playbackControlMessage(action), NamedTextColor.GREEN));
        return true;
    }

    private static String playbackControlMessage(String action) {
        return switch (action) {
            case "previous" -> "Going back to previous track.";
            case "next" -> "Skipping to next track.";
            case "stop" -> "Playback stopped.";
            default -> "Playback " + action + " sent to listeners.";
        };
    }

    private boolean audience(CommandSender sender, String[] args) {
        if (!sender.hasPermission("evilkaraoke.command.audience")) {
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
        if (!sender.hasPermission("evilkaraoke.command.issue")) {
            return deny(sender);
        }
        sender.sendMessage(Component.text("Evilkaraoke troubleshooting", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("1. Install the Evilkaraoke client mod (Fabric or NeoForge) to hear audio.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("2. Ensure your Minecraft sound and Music/Jukebox volume are above zero.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("3. Run /ek doctor to confirm the client handshake succeeded.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("4. If audio stalls, /ek queue next to advance the queue.", NamedTextColor.GRAY));
        return true;
    }

    private boolean setlist(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.setlist")) {
            return deny(sender);
        }
        if (args.length >= 2 && "add".equalsIgnoreCase(args[1])) {
            return addSetlist(sender, args);
        }
        int page = parsePage(args);
        async(neurokaraokeClient.setlists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                setlists -> {
                    if (setlists.isEmpty()) {
                        sender.sendMessage(Component.text("No Neurokaraoke setlists found for page " + page + ".", NamedTextColor.YELLOW));
                        return;
                    }
                    setlistMessages(page, setlists, label).forEach(sender::sendMessage);
                },
                error -> sender.sendMessage(ChatMessages.error("Setlist lookup failed", error)));
        return true;
    }

    private boolean addSetlist(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /ek setlist add <page> <row>", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        maybeWarnClient(player);
        int page = parsePositiveInt(args[2], 1);
        int row = parsePositiveInt(args[3], 1);
        if (row > CHAT_PAGE_SIZE) {
            sender.sendMessage(Component.text("Setlist row must be between 1 and " + CHAT_PAGE_SIZE + ".", NamedTextColor.RED));
            return true;
        }
        async(neurokaraokeClient.setlists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                setlists -> {
                    int index = row - 1;
                    if (index >= setlists.size()) {
                        sender.sendMessage(Component.text("That setlist is no longer available on page " + page + ".", NamedTextColor.YELLOW));
                        return;
                    }
                    NeurokaraokeSetlist setlist = setlists.get(index);
                    if (setlist.songs().isEmpty()) {
                        sender.sendMessage(Component.text("That setlist has no songs to queue.", NamedTextColor.YELLOW));
                        return;
                    }
                    coordinator.requestAll(setlist.songs(), player);
                    for (KaraokeTrack track : setlist.songs()) {
                        statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    }
                    sender.sendMessage(Component.text("Queued " + setlist.songs().size() + " song(s) from " + setlist.name() + ".", NamedTextColor.GREEN));
                },
                error -> sender.sendMessage(ChatMessages.error("Could not queue that setlist", error)));
        return true;
    }

    private boolean playlist(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("evilkaraoke.command.playlist")) {
            return deny(sender);
        }
        if (args.length >= 2 && "add".equalsIgnoreCase(args[1])) {
            return addPlaylist(sender, args);
        }
        int page = parsePage(args);
        async(neurokaraokeClient.publicPlaylists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                playlists -> {
                    if (playlists.isEmpty()) {
                        sender.sendMessage(Component.text("No public Neurokaraoke playlists found for page " + page + ".", NamedTextColor.YELLOW));
                        return;
                    }
                    playlistMessages(page, playlists, label).forEach(sender::sendMessage);
                },
                error -> sender.sendMessage(ChatMessages.error("Playlist lookup failed", error)));
        return true;
    }

    private boolean addPlaylist(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /ek playlist add <page> <row>", NamedTextColor.YELLOW));
            return true;
        }
        Player player = (Player) sender;
        if (!canRequest(player)) {
            return true;
        }
        maybeWarnClient(player);
        int page = parsePositiveInt(args[2], 1);
        int row = parsePositiveInt(args[3], 1);
        if (row > CHAT_PAGE_SIZE) {
            sender.sendMessage(Component.text("Playlist row must be between 1 and " + CHAT_PAGE_SIZE + ".", NamedTextColor.RED));
            return true;
        }
        async(neurokaraokeClient.publicPlaylists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE)
                        .thenCompose(playlists -> {
                            int index = row - 1;
                            if (index >= playlists.size()) {
                                return CompletableFuture.failedFuture(new IllegalArgumentException("That playlist is no longer available on page " + page + "."));
                            }
                            NeurokaraokeSetlist playlist = playlists.get(index);
                            if (playlist.songCount() == 0) {
                                return CompletableFuture.completedFuture(playlist);
                            }
                            return neurokaraokeClient.publicPlaylist(playlist.id());
                        }),
                playlist -> {
                    if (playlist.songs().isEmpty()) {
                        sender.sendMessage(Component.text("That playlist has no songs to queue.", NamedTextColor.YELLOW));
                        return;
                    }
                    coordinator.requestAll(playlist.songs(), player);
                    for (KaraokeTrack track : playlist.songs()) {
                        statsService.recordRequest(player.getUniqueId(), player.getName(), track.id(), track.title());
                    }
                    sender.sendMessage(Component.text("Queued " + playlist.songs().size() + " song(s) from playlist " + playlist.name() + ".", NamedTextColor.GREEN));
                },
                error -> sender.sendMessage(ChatMessages.error("Could not queue that playlist", error)));
        return true;
    }

    static List<Component> setlistMessages(int requestedPage, List<NeurokaraokeSetlist> setlists) {
        return setlistMessages(requestedPage, setlists, "ek");
    }

    static List<Component> setlistMessages(int requestedPage, List<NeurokaraokeSetlist> setlists, String label) {
        List<Component> messages = new ArrayList<>();
        int page = Math.max(1, requestedPage);
        messages.add(Component.text("Setlists (page " + page + "):", NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(setlists.size(), CHAT_PAGE_SIZE); i++) {
            NeurokaraokeSetlist setlist = setlists.get(i);
            int number = ((page - 1) * CHAT_PAGE_SIZE) + i + 1;
            String duration = setlist.totalDuration() == null ? "" : " | " + DurationFormatter.mmss(setlist.totalDuration());
            Component line = collectionLine(number, setlist.name(), setlist.songCount(), duration);
            if (setlist.songCount() == 0) {
                line = line.append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This setlist has no songs"))));
            } else {
                line = line.append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all songs from " + setlist.name())))
                        .clickEvent(ClickEvent.runCommand("/" + label + " setlist add " + page + " " + (i + 1))));
            }
            messages.add(line);
        }
        boolean hasNextPage = setlists.size() >= CHAT_PAGE_SIZE;
        if (page > 1 || hasNextPage) {
            messages.add(pageNavigation("setlist", "/" + label + " setlist", page, page > 1, hasNextPage));
        }
        return messages;
    }

    static List<Component> playlistMessages(int requestedPage, List<NeurokaraokeSetlist> playlists, String label) {
        List<Component> messages = new ArrayList<>();
        int page = Math.max(1, requestedPage);
        messages.add(Component.text("Public playlists (page " + page + "):", NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(playlists.size(), CHAT_PAGE_SIZE); i++) {
            NeurokaraokeSetlist playlist = playlists.get(i);
            int number = ((page - 1) * CHAT_PAGE_SIZE) + i + 1;
            String duration = playlist.totalDuration() == null ? "" : " | " + DurationFormatter.mmss(playlist.totalDuration());
            Component line = collectionLine(number, playlist.name(), playlist.songCount(), duration);
            if (playlist.songCount() == 0) {
                line = line.append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This playlist has no songs"))));
            } else {
                line = line.append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all songs from " + playlist.name())))
                        .clickEvent(ClickEvent.runCommand("/" + label + " playlist add " + page + " " + (i + 1))));
            }
            messages.add(line);
        }
        boolean hasNextPage = playlists.size() >= CHAT_PAGE_SIZE;
        if (page > 1 || hasNextPage) {
            messages.add(pageNavigation("playlist", "/" + label + " playlist", page, page > 1, hasNextPage));
        }
        return messages;
    }

    static List<Component> randomSongMessages(List<KaraokeTrack> tracks, String label) {
        return randomSongMessages(tracks, 1, label);
    }

    static List<Component> randomSongMessages(List<KaraokeTrack> tracks, int requestedPage, String label) {
        List<Component> messages = new ArrayList<>();
        int totalPages = Math.max(1, (int) Math.ceil(tracks.size() / (double) CHAT_PAGE_SIZE));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        messages.add(Component.text("Random songs (page " + page + "/" + totalPages + "): ", NamedTextColor.GOLD)
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all random songs")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " randomsong queue all"))));
        int start = (page - 1) * CHAT_PAGE_SIZE;
        for (int i = start; i < Math.min(tracks.size(), start + CHAT_PAGE_SIZE); i++) {
            KaraokeTrack track = tracks.get(i);
            messages.add(Component.text((i + 1) + ". ", NamedTextColor.GOLD)
                    .append(songLineComponent(track))
                    .append(Component.text(" ", NamedTextColor.GRAY))
                    .append(Component.text("[Queue]", NamedTextColor.GREEN)
                            .hoverEvent(HoverEvent.showText(Component.text("Queue " + track.title())))
                            .clickEvent(ClickEvent.runCommand("/" + label + " randomsong queue " + (i + 1)))));
        }
        if (totalPages > 1) {
            messages.add(pageNavigation("randomsong", "/" + label + " randomsong", page, page > 1, page < totalPages));
        }
        return messages;
    }

    static List<Component> searchMessages(String query, int requestedPage, List<KaraokeTrack> results, String label) {
        return searchMessages(query, requestedPage, results, label, results.size() > CHAT_PAGE_SIZE);
    }

    static List<Component> searchMessages(String query, int requestedPage, List<KaraokeTrack> results, String label, boolean hasNextPage) {
        List<Component> messages = new ArrayList<>();
        int page = Math.max(1, requestedPage);
        messages.add(Component.text("Results for \"" + query + "\" (page " + page + "): ", NamedTextColor.GOLD)
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all results on this page")))
                        .clickEvent(ClickEvent.runCommand("/" + label + " search queue-all"))));
        for (int i = 0; i < Math.min(results.size(), CHAT_PAGE_SIZE); i++) {
            KaraokeTrack track = results.get(i);
            Component line = Component.text("- ", NamedTextColor.DARK_GRAY)
                    .append(songLineComponent(track))
                    .append(Component.text(" ", NamedTextColor.GRAY));
            if (safeCommandToken(track.id())) {
                line = line.append(Component.text("[Request]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Request " + track.title())))
                        .clickEvent(ClickEvent.runCommand("/" + label + " request id " + track.id())));
            } else {
                line = line.append(Component.text("(request with /" + label + " request id " + track.id() + ")", NamedTextColor.YELLOW));
            }
            messages.add(line);
        }
        if (page > 1 || hasNextPage) {
            messages.add(pageNavigation("search", "/" + label + " search " + query, page, page > 1, hasNextPage));
        }
        return messages;
    }

    private static String songLine(KaraokeTrack track) {
        return track.title() + " - " + track.artist() + coveredBySuffix(track);
    }

    private static Component songLineComponent(KaraokeTrack track) {
        Component line = Component.text(track.title(), NamedTextColor.AQUA)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(track.artist(), NamedTextColor.LIGHT_PURPLE));
        return track.coveredBy()
                .filter(coverArtists -> !coverArtists.equalsIgnoreCase(track.artist()))
                .map(coverArtists -> line.append(Component.text(" (covered by ", NamedTextColor.GRAY))
                        .append(Component.text(coverArtists, NamedTextColor.YELLOW))
                        .append(Component.text(")", NamedTextColor.GRAY)))
                .orElse(line);
    }

    private static Component collectionLine(int number, String name, int songCount, String duration) {
        return Component.text(number + ". ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.AQUA))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(songCount, NamedTextColor.YELLOW))
                .append(Component.text(" songs", NamedTextColor.GRAY))
                .append(Component.text(duration + " ", NamedTextColor.GRAY));
    }

    private static String coveredBySuffix(KaraokeTrack track) {
        return track.coveredBy()
                .filter(coverArtists -> !coverArtists.equalsIgnoreCase(track.artist()))
                .map(coverArtists -> " (covered by " + coverArtists + ")")
                .orElse("");
    }

    private static boolean sameQueuedTrack(KaraokeSession.QueuedTrack first, KaraokeSession.QueuedTrack second) {
        return first != null && first.equals(second);
    }

    private static Component pageNavigation(String surface, String commandPrefix, int page, boolean hasPreviousPage, boolean hasNextPage) {
        Component navigation = Component.empty();
        if (hasPreviousPage) {
            navigation = navigation.append(Component.text("⬆️ ", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text("Previous page (" + (page - 1) + ")")))
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page - 1))));
        }
        if (hasPreviousPage && hasNextPage) {
            navigation = navigation.append(Component.text(" ", NamedTextColor.GRAY));
        }
        if (hasNextPage) {
            navigation = navigation.append(Component.text("⬇️", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text("Next page (" + (page + 1) + ")")))
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page + 1))));
        }
        return navigation;
    }

    private SearchRequest parseSearchRequest(String[] args) {
        int page = 1;
        int queryEndExclusive = args.length;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[args.length - 1]));
                queryEndExclusive = args.length - 1;
            } catch (NumberFormatException ignored) {
                // Last token is part of the search query.
            }
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, queryEndExclusive));
        return new SearchRequest(query, page);
    }

    private record SearchRequest(String query, int page) {
    }

    private record SearchPage(List<KaraokeTrack> results, boolean hasNextPage) {
    }

    private static boolean isSongIdMode(String value) {
        return "id".equalsIgnoreCase(value);
    }

    private static String joinTail(String[] args, int startInclusive) {
        if (args.length <= startInclusive) {
            return null;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, startInclusive, args.length));
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
        if (config.requireClientMod() && !clientRegistry.isCompatible(player.getUniqueId())) {
            player.sendMessage(Component.text("Heads up: install or update the Evilkaraoke client mod to actually hear playback.", NamedTextColor.YELLOW));
        }
    }

    private <T> void async(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = ErrorDetails.unwrap(error);
                logCommandFailure(cause);
                onError.accept(cause);
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    private void logCommandFailure(Throwable cause) {
        if (cause instanceof NeurokaraokeApiUnavailableException) {
            plugin.getLogger().warning("Evilkaraoke command failed: " + cause.getMessage());
            return;
        }
        plugin.getLogger().log(Level.WARNING, "Evilkaraoke command failed", cause);
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

    private int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseRawInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean canMoveRequestedRange(CommandSender sender, List<KaraokeSession.QueuedTrack> requests, int fromIndex, int toIndex) {
        if (sender.hasPermission("evilkaraoke.admin.queue.move")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }
        int start = Math.min(fromIndex, toIndex);
        int end = Math.max(fromIndex, toIndex);
        for (int i = start; i <= end; i++) {
            if (i < 0 || i >= requests.size()) {
                return false;
            }
            KaraokeSession.QueuedTrack queued = requests.get(i);
            if (queued.requester() == null || !queued.requester().equals(player.getUniqueId())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String subcommand : CANONICAL_ROOT_SUBCOMMANDS) {
                if (subcommand.startsWith(prefix)) {
                    matches.add(subcommand);
                }
            }
            return matches;
        }
        if (args.length == 2 && "radio".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("radio21", "swarmfm"), args[1]);
        }
        if (args.length == 2 && "request".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("id", "url"), args[1]);
        }
        if (args.length == 2 && "randomsong".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("queue", "1"), args[1]);
        }
        if (args.length == 3 && "randomsong".equalsIgnoreCase(args[0]) && "queue".equalsIgnoreCase(args[1])) {
            return filterPrefix(randomSongSelectionSuggestions(sender), args[2]);
        }
        if (args.length == 2 && "queue".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("move", "cancel", "random", "loop", "previous", "next", "pause", "resume", "stop", "1"), args[1]);
        }
        if (args.length == 2 && "setlist".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("add", "1"), args[1]);
        }
        if (args.length == 2 && "playlist".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("add", "1"), args[1]);
        }
        if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("me", "user", "server", "top"), args[1]);
        }
        if (args.length == 3 && "stats".equalsIgnoreCase(args[0]) && "user".equalsIgnoreCase(args[1])) {
            return filterPrefix(onlinePlayerNames(), args[2]);
        }
        if (args.length == 3 && "stats".equalsIgnoreCase(args[0]) && "top".equalsIgnoreCase(args[1])) {
            return filterPrefix(List.of("users", "songs"), args[2]);
        }
        if (args.length == 4 && "stats".equalsIgnoreCase(args[0]) && "top".equalsIgnoreCase(args[1])) {
            if ("songs".equalsIgnoreCase(args[2])) {
                return filterPrefix(List.of("played", "requested"), args[3]);
            }
            if ("users".equalsIgnoreCase(args[2])) {
                return filterPrefix(List.of("time", "song-count", "request-count"), args[3]);
            }
        }
        if (args.length == 2 && "audience".equalsIgnoreCase(args[0])) {
            List<String> targets = new ArrayList<>(List.of("@a", "@s"));
            targets.addAll(onlinePlayerNames());
            return filterPrefix(targets, args[1]);
        }
        if (isCollectionAddSuggestion(args)) {
            return filterPrefix(numberRange(1, CHAT_PAGE_SIZE), args[args.length - 1]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "move".equalsIgnoreCase(args[1])) {
            return filterPrefix(moveFromPositionSuggestions(sender), args[2]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "cancel".equalsIgnoreCase(args[1])) {
            return filterPrefix(cancelArgumentSuggestions(sender), args[2]);
        }
        if (args.length == 4 && "queue".equalsIgnoreCase(args[0]) && "move".equalsIgnoreCase(args[1])) {
            return filterPrefix(moveToPositionSuggestions(sender, args[2]), args[3]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "loop".equalsIgnoreCase(args[1])) {
            return filterPrefix(queuePositionSuggestions(sender), args[2]);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> cancelArgumentSuggestions(CommandSender sender) {
        if (!(sender instanceof Player player) || !sender.hasPermission("evilkaraoke.command.queue.cancel")) {
            return List.of();
        }
        boolean isAdmin = sender.hasPermission("evilkaraoke.admin.queue.cancel");
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(coordinator.queue());
        List<String> suggestions = new ArrayList<>(List.of("all"));
        for (int i = 0; i < queue.size(); i++) {
            KaraokeSession.QueuedTrack queued = queue.get(i);
            boolean isOwnSong = queued.requester() != null && queued.requester().equals(player.getUniqueId());
            if (isAdmin || isOwnSong) {
                suggestions.add(Integer.toString(i + 1));
            }
        }
        return suggestions;
    }

    private List<String> moveFromPositionSuggestions(CommandSender sender) {
        if (!(sender instanceof Player) || !sender.hasPermission("evilkaraoke.command.queue.move")) {
            return List.of();
        }
        List<KaraokeSession.QueuedTrack> requests = coordinator.snapshot().requests();
        List<String> positions = new ArrayList<>();
        for (int from = 0; from < requests.size(); from++) {
            for (int to = 0; to < requests.size(); to++) {
                if (from != to && canMoveRequestedRange(sender, requests, from, to)) {
                    positions.add(Integer.toString(from + 1));
                    break;
                }
            }
        }
        return positions;
    }

    private List<String> moveToPositionSuggestions(CommandSender sender, String fromValue) {
        if (!(sender instanceof Player) || !sender.hasPermission("evilkaraoke.command.queue.move")) {
            return List.of();
        }
        int from = parseRawInt(fromValue, -1) - 1;
        List<KaraokeSession.QueuedTrack> requests = coordinator.snapshot().requests();
        if (from < 0 || from >= requests.size()) {
            return List.of();
        }
        List<String> positions = new ArrayList<>();
        for (int to = 0; to < requests.size(); to++) {
            if (from != to && canMoveRequestedRange(sender, requests, from, to)) {
                positions.add(Integer.toString(to + 1));
            }
        }
        return positions;
    }

    private List<String> queuePositionSuggestions(CommandSender sender) {
        if (!sender.hasPermission("evilkaraoke.command.queue.loop")) {
            return List.of();
        }
        int size = coordinator.queue().size();
        return size == 0 ? List.of() : numberRange(1, size);
    }

    private List<String> randomSongSelectionSuggestions(CommandSender sender) {
        if (!(sender instanceof Player player) || !sender.hasPermission("evilkaraoke.command.randomsong")) {
            return List.of();
        }
        int size = randomSongSelections.getOrDefault(player.getUniqueId(), List.of()).size();
        return randomSongQueueArguments(size == 0 ? CHAT_PAGE_SIZE : size);
    }

    private static boolean isCollectionAddSuggestion(String[] args) {
        if (args.length != 3 && args.length != 4) {
            return false;
        }
        boolean collectionCommand = "setlist".equalsIgnoreCase(args[0]) || "playlist".equalsIgnoreCase(args[0]);
        return collectionCommand && "add".equalsIgnoreCase(args[1]);
    }

    private static List<String> numberRange(int startInclusive, int endInclusive) {
        List<String> values = new ArrayList<>();
        for (int value = startInclusive; value <= endInclusive; value++) {
            values.add(Integer.toString(value));
        }
        return values;
    }

    private static List<String> randomSongQueueArguments(int size) {
        List<String> arguments = new ArrayList<>(List.of("all"));
        int count = Math.max(1, size);
        for (int i = 1; i <= count; i++) {
            arguments.add(Integer.toString(i));
        }
        return arguments;
    }

    private static boolean safeCommandToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)).toList();
    }
}
