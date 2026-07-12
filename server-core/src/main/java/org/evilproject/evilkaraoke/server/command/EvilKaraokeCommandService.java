package org.evilproject.evilkaraoke.server.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.UserAudioTracks;
import org.evilproject.evilkaraoke.common.protocol.LyricsDisplayAction;
import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;
import org.evilproject.evilkaraoke.common.util.DurationFormatter;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeApiUnavailableException;
import org.evilproject.evilkaraoke.server.core.EvilKaraokeServerCore;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeSetlist;
import org.evilproject.evilkaraoke.server.queue.KaraokeSession;
import org.evilproject.evilkaraoke.server.stats.SongStats;
import org.evilproject.evilkaraoke.server.stats.StatsService;
import org.evilproject.evilkaraoke.server.stats.UserStats;

public final class EvilKaraokeCommandService {
    private static final int CHAT_PAGE_SIZE = 5;
    private static final String QUEUE_REFRESH_TOKEN = "--refresh";
    private static final List<String> ROOT_SUBCOMMANDS = List.of(
            "help", "doctor", "listeners", "reload", "randomsong", "request", "search",
            "setlist", "playlist", "radio", "current", "queue",
            "audience", "stats", "issue", "lyrics"
    );

    private final EvilKaraokeServerCore core;

    public EvilKaraokeCommandService(EvilKaraokeServerCore core) {
        this.core = core;
    }

    public int execute(CommandActor actor, String label, String[] args) {
        String subcommand = args.length == 0 ? "help" : canonicalSubcommand(args[0]);
        return switch (subcommand) {
            case "help" -> help(actor, label);
            case "doctor" -> doctor(actor);
            case "listeners" -> listeners(actor);
            case "reload" -> reload(actor);
            case "randomsong" -> randomSong(actor, args, label);
            case "request" -> request(actor, args);
            case "search" -> search(actor, args, label);
            case "radio" -> radio(actor, args);
            case "current" -> current(actor);
            case "queue" -> queue(actor, args, label);
            case "audience" -> audience(actor, args);
            case "stats" -> stats(actor, args);
            case "issue" -> issue(actor);
            case "setlist" -> setlist(actor, args, label);
            case "playlist" -> playlist(actor, args, label);
            case "lyrics" -> lyrics(actor, args);
            default -> unknown(actor);
        };
    }

    public static List<String> suggest(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ROOT_SUBCOMMANDS.stream().filter(command -> command.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "radio".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("radio21", "swarmfm"), args[1]);
        }
        if (args.length == 2 && "request".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("id", "url"), args[1]);
        }
        if (args.length == 2 && "lyrics".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("enable", "disable"), args[1]);
        }
        if (args.length == 2 && "randomsong".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("queue", "1"), args[1]);
        }
        if (args.length == 3 && "randomsong".equalsIgnoreCase(args[0]) && "queue".equalsIgnoreCase(args[1])) {
            return filterPrefix(randomSongQueueArguments(CHAT_PAGE_SIZE), args[2]);
        }
        if (args.length == 2 && "queue".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("move", "cancel", "random", "loop", "previous", "next", "pause", "resume", "stop", "1"), args[1]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "cancel".equalsIgnoreCase(args[1])) {
            return filterPrefix(List.of("all"), args[2]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "loop".equalsIgnoreCase(args[1])) {
            return filterPrefix(numberRange(1, CHAT_PAGE_SIZE), args[2]);
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
            return filterPrefix(List.of("@a", "@s"), args[1]);
        }
        if (isCollectionAddSuggestion(args)) {
            return filterPrefix(numberRange(1, CHAT_PAGE_SIZE), args[args.length - 1]);
        }
        return List.of();
    }

    public List<String> suggest(CommandActor actor, String[] args) {
        if (args.length == 2 && "audience".equalsIgnoreCase(args[0])) {
            List<String> targets = new ArrayList<>(List.of("@a", "@s"));
            targets.addAll(onlinePlayerNames());
            return filterPrefix(targets, args[1]);
        }
        if (args.length == 3 && "stats".equalsIgnoreCase(args[0]) && "user".equalsIgnoreCase(args[1])) {
            return filterPrefix(onlinePlayerNames(), args[2]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "cancel".equalsIgnoreCase(args[1])) {
            return filterPrefix(cancelArgumentSuggestions(actor), args[2]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "move".equalsIgnoreCase(args[1])) {
            return filterPrefix(moveFromPositionSuggestions(actor), args[2]);
        }
        if (args.length == 4 && "queue".equalsIgnoreCase(args[0]) && "move".equalsIgnoreCase(args[1])) {
            return filterPrefix(moveToPositionSuggestions(actor, args[2]), args[3]);
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0]) && "loop".equalsIgnoreCase(args[1])) {
            return filterPrefix(queuePositionSuggestions(actor), args[2]);
        }
        if (args.length == 3 && "randomsong".equalsIgnoreCase(args[0]) && "queue".equalsIgnoreCase(args[1])) {
            return filterPrefix(randomSongSelectionSuggestions(actor), args[2]);
        }
        return suggest(args);
    }

    public static String[] splitArgsForSuggestions(String input) {
        if (input == null || input.isEmpty()) {
            return new String[] {""};
        }
        String value = input.stripLeading();
        if (value.isEmpty()) {
            return new String[] {""};
        }
        String[] args = value.split("\\s+");
        if (Character.isWhitespace(value.charAt(value.length() - 1))) {
            args = Arrays.copyOf(args, args.length + 1);
            args[args.length - 1] = "";
        }
        return args;
    }

    public static int suggestionTokenStart(String input) {
        if (input == null || input.isEmpty()) {
            return 0;
        }
        for (int i = input.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(input.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    public static String[] splitArgs(String input) {
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        return input.trim().split("\\s+");
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)).toList();
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

    private List<String> onlinePlayerNames() {
        return core.platform().onlinePlayers().stream()
                .map(KaraokePlayer::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> cancelArgumentSuggestions(CommandActor actor) {
        if (!actor.isPlayer() || !actor.hasPermission("evilkaraoke.command.queue.cancel")) {
            return List.of();
        }
        boolean isAdmin = actor.hasPermission("evilkaraoke.admin.queue.cancel");
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(core.coordinator().queue());
        List<String> positions = new ArrayList<>(List.of("all"));
        for (int i = 0; i < queue.size(); i++) {
            KaraokeSession.QueuedTrack queued = queue.get(i);
            boolean isOwnSong = queued.requester() != null && queued.requester().equals(actor.playerId());
            if (isAdmin || isOwnSong) {
                positions.add(Integer.toString(i + 1));
            }
        }
        return positions;
    }

    private List<String> moveFromPositionSuggestions(CommandActor actor) {
        if (!actor.isPlayer() || !actor.hasPermission("evilkaraoke.command.queue.move")) {
            return List.of();
        }
        List<KaraokeSession.QueuedTrack> requests = core.coordinator().snapshot().requests();
        List<String> positions = new ArrayList<>();
        for (int from = 0; from < requests.size(); from++) {
            for (int to = 0; to < requests.size(); to++) {
                if (from != to && canMoveRequestedRange(actor, requests, from, to)) {
                    positions.add(Integer.toString(from + 1));
                    break;
                }
            }
        }
        return positions;
    }

    private List<String> moveToPositionSuggestions(CommandActor actor, String fromValue) {
        if (!actor.isPlayer() || !actor.hasPermission("evilkaraoke.command.queue.move")) {
            return List.of();
        }
        int from = parseRawInt(fromValue, -1) - 1;
        List<KaraokeSession.QueuedTrack> requests = core.coordinator().snapshot().requests();
        if (from < 0 || from >= requests.size()) {
            return List.of();
        }
        List<String> positions = new ArrayList<>();
        for (int to = 0; to < requests.size(); to++) {
            if (from != to && canMoveRequestedRange(actor, requests, from, to)) {
                positions.add(Integer.toString(to + 1));
            }
        }
        return positions;
    }

    private List<String> queuePositionSuggestions(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.queue.loop")) {
            return List.of();
        }
        int size = core.coordinator().queue().size();
        return size == 0 ? List.of() : numberRange(1, size);
    }

    private List<String> randomSongSelectionSuggestions(CommandActor actor) {
        if (!actor.isPlayer() || !actor.hasPermission("evilkaraoke.command.randomsong")) {
            return List.of();
        }
        int size = core.randomSongSelection(actor.playerId()).map(List::size).orElse(CHAT_PAGE_SIZE);
        return randomSongQueueArguments(size);
    }

    private static String canonicalSubcommand(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean hasQueueRefreshToken(String[] args) {
        return Arrays.stream(args).anyMatch(EvilKaraokeCommandService::isQueueRefreshToken);
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

    private int help(CommandActor actor, String label) {
        if (!actor.hasPermission("evilkaraoke.command.help")) {
            return deny(actor);
        }
        actor.sendMessage("Evilkaraoke commands");
        actor.sendMessage("/" + label + " request <query> - search and request a song");
        actor.sendMessage("/" + label + " request id <songId> - request by song id");
        actor.sendMessage("/" + label + " request url <https://...> [title] - request a direct audio URL");
        actor.sendMessage("/" + label + " search <query> - search Neurokaraoke");
        actor.sendMessage("/" + label + " search queue-all - queue the latest shown search page");
        actor.sendMessage("/" + label + " setlist [page] - list Neurokaraoke setlists");
        actor.sendMessage("/" + label + " playlist [page] - list public Neurokaraoke playlists");
        actor.sendMessage("/" + label + " randomsong [page] - show random songs to queue");
        actor.sendMessage("/" + label + " randomsong queue <all|row> - queue all latest random songs or one row");
        actor.sendMessage("/" + label + " queue|current - inspect playback");
        actor.sendMessage("/" + label + " queue cancel <position|all> - remove queued song(s)");
        actor.sendMessage("/" + label + " queue move <from> <to> - reorder your queued songs");
        actor.sendMessage("/" + label + " queue pause|resume|stop - playback controls");
        actor.sendMessage("/" + label + " queue previous|next - navigate tracks");
        actor.sendMessage("/" + label + " audience <@a|@s|player> - choose who hears playback");
        actor.sendMessage("/" + label + " radio <radio21|swarmfm> - start radio");
        actor.sendMessage("/" + label + " lyrics [enable|disable] - toggle or set lyric captions on your client");
        actor.sendMessage("/" + label + " stats <me|user|server|top> - stats");
        actor.sendMessage("/" + label + " doctor - verify readiness");
        return 1;
    }

    private int lyrics(CommandActor actor, String[] args) {
        if (args.length > 2) {
            actor.sendMessage("Usage: /ek lyrics [enable|disable]");
            return 0;
        }
        var action = LyricsDisplayAction.parseCommandArgument(args.length == 2 ? args[1] : null);
        if (action.isEmpty()) {
            actor.sendMessage("Usage: /ek lyrics [enable|disable]");
            return 0;
        }
        if (!actor.isPlayer()) {
            actor.sendMessage("Only players can change lyric captions.");
            return 0;
        }
        if (!core.coordinator().setLyrics(actor.playerId(), action.get())) {
            actor.sendMessage("You need an updated Evilkaraoke client mod to see lyric captions.");
            return 0;
        }
        return 1;
    }

    private int doctor(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.doctor")) {
            return deny(actor);
        }
        actor.sendMessage("Evilkaraoke doctor");
        actor.sendMessage("Compatible clients online: " + core.clientRegistry().compatibleClientCount());
        actor.sendMessage("Default targets: " + core.config().defaultTargets());
        actor.sendMessage("Radio enabled: " + core.config().allowRadio());
        actor.sendMessage("Stats enabled: " + core.config().statsEnabled());
        if (actor.isPlayer() && !core.clientRegistry().isCompatible(actor.playerId())) {
            actor.sendMessage("You have not completed a compatible Evilkaraoke client handshake; install or update the client mod to hear audio.");
        }
        return 1;
    }

    private int listeners(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.listeners")) {
            return deny(actor);
        }
        actor.sendMessage("Modded Evilkaraoke listeners: " + core.clientRegistry().compatibleClientCount());
        core.clientRegistry().sessions().values().forEach(session ->
                actor.sendMessage("- " + session.playerId() + " via " + session.hello().loader() + " " + session.hello().modVersion()));
        return 1;
    }

    private int reload(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.reload")) {
            return deny(actor);
        }
        core.reload();
        actor.sendMessage("Evilkaraoke configuration reloaded.");
        return 1;
    }

    private int randomSong(CommandActor actor, String[] args, String label) {
        if (!actor.hasPermission("evilkaraoke.command.randomsong")) {
            return deny(actor);
        }
        int page = 1;
        if (args.length >= 2) {
            if ("queue".equalsIgnoreCase(args[1])) {
                return queueRandomSongSelection(actor, args);
            }
            page = parseRawInt(args[1], -1);
            if (page < 1) {
                actor.sendMessage("Usage: /ek randomsong [page] | /ek randomsong queue <all|row>");
                return 1;
            }
        }
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        int requestedPage = page;
        if (args.length >= 2) {
            List<KaraokeTrack> cached = core.randomSongSelection(actor.playerId()).orElse(List.of());
            if (!cached.isEmpty()) {
                randomSongMessages(cached, requestedPage, label).forEach(actor::sendMessage);
                return 1;
            }
        }
        async(core.neurokaraokeClient().randomSongs().thenApply(this::randomPlaylist),
                tracks -> {
                    core.rememberRandomSongSelection(actor.playerId(), tracks);
                    randomSongMessages(tracks, requestedPage, label).forEach(actor::sendMessage);
                },
                error -> actor.sendMessage(error("Could not fetch random songs", error)));
        return 1;
    }

    private int queueRandomSongSelection(CommandActor actor, String[] args) {
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        if (args.length < 3) {
            actor.sendMessage("Usage: /ek randomsong queue <all|row>");
            return 1;
        }
        List<KaraokeTrack> tracks = core.randomSongSelection(actor.playerId()).orElse(List.of());
        if (tracks.isEmpty()) {
            actor.sendMessage("No random song playlist is ready. Use /ek randomsong first.");
            return 1;
        }
        KaraokePlayer player = player(actor);
        maybeWarnClient(actor);
        if ("all".equalsIgnoreCase(args[2])) {
            async(core.coordinator().requestAll(tracks, player),
                    count -> {
                        for (KaraokeTrack track : tracks) {
                            core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                        }
                        actor.sendMessage("Queued " + count + " random song(s).");
                    },
                    error -> actor.sendMessage(error("Could not queue random songs", error)));
            return 1;
        }
        int row = parseRawInt(args[2], -1);
        if (row < 1 || row > tracks.size()) {
            actor.sendMessage("Random song row must be between 1 and " + tracks.size() + ".");
            return 1;
        }
        KaraokeTrack track = tracks.get(row - 1);
        async(core.coordinator().request(track, player).thenApply(ignored -> track),
                queued -> {
                    core.statsService().recordRequest(player.id(), player.name(), queued.id(), queued.title());
                    actor.sendMessage("Queued random song: " + songLine(queued));
                },
                error -> actor.sendMessage(error("Could not queue that random song", error)));
        return 1;
    }

    private List<KaraokeTrack> randomPlaylist(List<KaraokeTrack> tracks) {
        if (tracks.isEmpty()) {
            throw new IllegalStateException("No random Neurokaraoke songs returned");
        }
        return List.copyOf(tracks);
    }

    private static List<CommandMessage> randomSongMessages(List<KaraokeTrack> tracks, int requestedPage, String label) {
        List<CommandMessage> messages = new ArrayList<>();
        int totalPages = Math.max(1, (int) Math.ceil(tracks.size() / (double) CHAT_PAGE_SIZE));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        messages.add(CommandMessage.builder()
                .append("Random songs (page " + page + "/" + totalPages + "): ")
                .action("[Queue All]", "/" + label + " randomsong queue all", "Queue all random songs")
                .build());
        int start = (page - 1) * CHAT_PAGE_SIZE;
        for (int i = start; i < Math.min(tracks.size(), start + CHAT_PAGE_SIZE); i++) {
            KaraokeTrack track = tracks.get(i);
            messages.add(CommandMessage.builder()
                    .append((i + 1) + ". " + songLine(track) + " ")
                    .action("[Queue]", "/" + label + " randomsong queue " + (i + 1), "Queue " + track.title())
                    .build());
        }
        if (totalPages > 1) {
            messages.add(pageNavigation("/" + label + " randomsong", page, page > 1, page < totalPages));
        }
        return messages;
    }

    private static List<String> randomSongQueueArguments(int size) {
        List<String> arguments = new ArrayList<>(List.of("all"));
        int count = Math.max(1, size);
        for (int i = 1; i <= count; i++) {
            arguments.add(Integer.toString(i));
        }
        return arguments;
    }

    private int request(CommandActor actor, String[] args) {
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        if (args.length < 2) {
            actor.sendMessage("Usage: /ek request <query> | /ek request id <songId> | /ek request url <https://...> [title]");
            return 1;
        }
        KaraokePlayer player = player(actor);
        maybeWarnClient(actor);
        if (isSongIdMode(args[1])) {
            if (args.length < 3) {
                actor.sendMessage("Usage: /ek request id <songId>");
                return 1;
            }
            String songId = args[2];
            async(core.neurokaraokeClient().song(songId).thenCompose(track -> core.coordinator().request(track, player).thenApply(ignored -> track)),
                    track -> {
                        core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                        actor.sendMessage("Queued: " + songLine(track));
                    },
                    error -> actor.sendMessage(error("Could not request that song id", error)));
            return 1;
        }
        if ("url".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                actor.sendMessage("Usage: /ek request url <https://...> [title]");
                return 1;
            }
            return requestUrl(actor, player, args[2], joinTail(args, 3));
        }
        if (AudioUrlValidator.hasHttpScheme(args[1])) {
            return requestUrl(actor, player, args[1], joinTail(args, 2));
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        async(core.coordinator().requestSearch(query, player),
                track -> {
                    core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                    actor.sendMessage("Queued: " + songLine(track));
                },
                error -> actor.sendMessage(error("No match for \"" + query + "\"", error)));
        return 1;
    }

    private int requestUrl(CommandActor actor, KaraokePlayer player, String rawUrl, String title) {
        CompletableFuture<KaraokeTrack> future = CompletableFuture.supplyAsync(() -> UserAudioTracks.fromUrl(rawUrl, title))
                .thenCompose(track -> core.coordinator().request(track, player).thenApply(ignored -> track));
        async(future,
                track -> {
                    core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                    actor.sendMessage("Queued URL: " + songLine(track));
                },
                error -> actor.sendMessage(error("Could not request that URL", error)));
        return 1;
    }

    private boolean canRequest(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.request")) {
            deny(actor);
            return false;
        }
        return true;
    }

    private int search(CommandActor actor, String[] args, String label) {
        if (!actor.hasPermission("evilkaraoke.command.search")) {
            return deny(actor);
        }
        if (args.length >= 2 && "queue-all".equalsIgnoreCase(args[1])) {
            return queueSearchSelection(actor);
        }
        if (args.length < 2) {
            actor.sendMessage("Usage: /ek search <query> [page] | /ek search queue-all");
            return 1;
        }
        SearchRequest searchRequest = parseSearchRequest(args);
        async(searchPage(searchRequest),
                page -> {
                    if (page.results().isEmpty()) {
                        actor.sendMessage("No Neurokaraoke results for \"" + searchRequest.query() + "\".");
                        return;
                    }
                    if (actor.isPlayer()) {
                        core.rememberSearchResultSelection(actor.playerId(), page.results());
                    }
                    actor.sendMessage(searchHeaderMessage(searchRequest.query(), searchRequest.page(), label));
                    for (int i = 0; i < page.results().size(); i++) {
                        actor.sendMessage(searchResultMessage(page.results().get(i), searchRequest.page(), i, label));
                    }
                    if (searchRequest.page() > 1 || page.hasNextPage()) {
                        actor.sendMessage(pageNavigation("/" + label + " search " + searchRequest.query(), searchRequest.page(), searchRequest.page() > 1, page.hasNextPage()));
                    }
                },
                error -> actor.sendMessage(error("Search failed", error)));
        return 1;
    }

    private int queueSearchSelection(CommandActor actor) {
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        List<KaraokeTrack> tracks = core.searchResultSelection(actor.playerId()).orElse(List.of());
        if (tracks.isEmpty()) {
            actor.sendMessage("No search results are ready. Use /ek search <query> first.");
            return 1;
        }
        KaraokePlayer player = player(actor);
        maybeWarnClient(actor);
        async(core.coordinator().requestAll(tracks, player),
                count -> {
                    for (KaraokeTrack track : tracks) {
                        core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                    }
                    actor.sendMessage("Queued " + count + " search result song(s).");
                },
                error -> actor.sendMessage(error("Could not queue search results", error)));
        return 1;
    }

    private CompletableFuture<SearchPage> searchPage(SearchRequest request) {
        return core.neurokaraokeClient().search(request.query(), request.page() - 1, CHAT_PAGE_SIZE)
                .thenCompose(results -> {
                    if (results.size() < CHAT_PAGE_SIZE) {
                        return CompletableFuture.completedFuture(new SearchPage(results, false));
                    }
                    return core.neurokaraokeClient().search(request.query(), request.page(), CHAT_PAGE_SIZE)
                            .thenApply(nextResults -> new SearchPage(results, !nextResults.isEmpty()));
                });
    }

    private int radio(CommandActor actor, String[] args) {
        if (!requirePlayer(actor)) {
            return 1;
        }
        if (!actor.hasPermission("evilkaraoke.command.radio")) {
            return deny(actor);
        }
        if (!core.config().allowRadio()) {
            actor.sendMessage("Radio playback is disabled on this server.");
            return 1;
        }
        if (args.length < 2) {
            actor.sendMessage("Usage: /ek radio <radio21|swarmfm>");
            return 1;
        }
        KaraokePlayer player = player(actor);
        maybeWarnClient(actor);
        async(core.coordinator().requestRadio(args[1], player),
                track -> actor.sendMessage("Queued radio: " + track.title()),
                error -> actor.sendMessage(error("Could not start radio", error)));
        return 1;
    }

    private int current(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.current")) {
            return deny(actor);
        }
        KaraokeSession.PlaybackSnapshot snapshot = core.coordinator().snapshot();
        if (snapshot.current() == null) {
            actor.sendMessage("Nothing is playing right now.");
            return 1;
        }
        KaraokeTrack track = snapshot.current().track();
        actor.sendMessage("Now playing: " + songLine(track) + currentTrackDetails(snapshot));
        return 1;
    }

    private int queue(CommandActor actor, String[] args, String label) {
        if (!actor.hasPermission("evilkaraoke.command.queue")) {
            return deny(actor);
        }
        boolean refresh = hasQueueRefreshToken(args);
        int refreshPage = queueRefreshPage(args);
        String[] queueArgs = stripQueueRefreshTokens(args);
        if (queueArgs.length >= 2 && isQueuePlaybackControl(queueArgs[1])) {
            return control(actor, queueArgs[1].toLowerCase(Locale.ROOT), refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "cancel".equalsIgnoreCase(queueArgs[1])) {
            return cancel(actor, queueArgs, 2, "/" + label + " queue cancel <position|all>", refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "move".equalsIgnoreCase(queueArgs[1])) {
            return moveQueue(actor, queueArgs, refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "random".equalsIgnoreCase(queueArgs[1])) {
            return toggleRandomQueue(actor, refresh, label, refreshPage);
        }
        if (queueArgs.length >= 2 && "loop".equalsIgnoreCase(queueArgs[1])) {
            return queueArgs.length >= 3
                    ? toggleSingleLoop(actor, queueArgs, refresh, label, refreshPage)
                    : toggleQueueLoop(actor, refresh, label, refreshPage);
        }
        sendQueue(actor, queueArgs, label);
        return 1;
    }

    private int sendQueue(CommandActor actor, String[] args, String label) {
        KaraokeSession.PlaybackSnapshot snapshot = core.coordinator().snapshot();
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(snapshot.requests());
        queue.addAll(snapshot.randomTracks());
        List<KaraokeSession.QueuedTrack> requests = snapshot.requests();
        int page = Math.max(1, parsePage(args));
        int totalPages = Math.max(1, (int) Math.ceil(queue.size() / (double) CHAT_PAGE_SIZE));
        page = Math.min(page, totalPages);
        if (snapshot.current() != null) {
            KaraokeTrack currentTrack = snapshot.current().track();
            actor.sendMessage(currentTrackMessage(actor, snapshot, songLine(currentTrack), label, page));
        }
        actor.sendMessage("Queue (page " + page + "/" + totalPages + ")");
        int start = (page - 1) * CHAT_PAGE_SIZE;
        for (int i = start; i < Math.min(queue.size(), start + CHAT_PAGE_SIZE); i++) {
            KaraokeSession.QueuedTrack queued = queue.get(i);
            actor.sendMessage(queueItemMessage(actor, queued, requests, snapshot.singleLoopTrack(), i + 1, label));
        }
        if (queue.isEmpty()) {
            actor.sendMessage(snapshot.current() == null ? "The queue is empty." : "No upcoming songs queued.");
        }
        if (totalPages > 1) {
            actor.sendMessage(pageNavigation("/" + label + " queue", page, page > 1, page < totalPages));
        }
        actor.sendMessage(queueControls(actor, snapshot, label, page));
        return 1;
    }

    private int toggleRandomQueue(CommandActor actor, boolean refresh, String label, int page) {
        if (!actor.hasPermission("evilkaraoke.command.queue.random")) {
            return deny(actor);
        }
        boolean enabled = core.coordinator().toggleRandomQueue();
        if (refresh) {
            return refreshQueue(actor, label, page);
        }
        actor.sendMessage(enabled ? "Random queue order shuffled." : "Random queue playback disabled.");
        return 1;
    }

    private int toggleQueueLoop(CommandActor actor, boolean refresh, String label, int page) {
        if (!actor.hasPermission("evilkaraoke.command.queue.loop")) {
            return deny(actor);
        }
        boolean enabled = core.coordinator().toggleQueueLoop();
        if (refresh) {
            return refreshQueue(actor, label, page);
        }
        actor.sendMessage("Queue loop " + (enabled ? "enabled." : "disabled."));
        return 1;
    }

    private int toggleSingleLoop(CommandActor actor, String[] args, boolean refresh, String label, int page) {
        if (!actor.hasPermission("evilkaraoke.command.queue.loop")) {
            return deny(actor);
        }
        int position = parseRawInt(args[2], -1);
        if (position < 1) {
            actor.sendMessage("Queue position must be a positive number.");
            return 1;
        }
        core.coordinator().toggleSingleLoop(position).ifPresentOrElse(
                change -> {
                    if (refresh) {
                        refreshQueue(actor, label, page);
                    } else {
                        actor.sendMessage((change.enabled() ? "Single-song loop enabled: " : "Single-song loop disabled: ")
                                + songLine(change.track().track()));
                    }
                },
                () -> actor.sendMessage("Position " + position + " is out of range. Queue has " + core.coordinator().queue().size() + " song(s)."));
        return 1;
    }

    private int moveQueue(CommandActor actor, String[] args, boolean refresh, String label, int page) {
        if (!requirePlayer(actor)) {
            return 1;
        }
        if (!actor.hasPermission("evilkaraoke.command.queue.move")) {
            return deny(actor);
        }
        if (args.length < 4) {
            actor.sendMessage("Usage: /ek queue move <from> <to>");
            return 1;
        }
        int from = parseRawInt(args[2], -1);
        int to = parseRawInt(args[3], -1);
        if (from < 1 || to < 1) {
            actor.sendMessage("Queue positions must be positive numbers.");
            return 1;
        }
        if (from == to) {
            actor.sendMessage("That song is already at position " + to + ".");
            return 1;
        }
        List<KaraokeSession.QueuedTrack> requests = core.coordinator().snapshot().requests();
        if (from > requests.size() || to > requests.size()) {
            actor.sendMessage("Only requested songs can be reordered. Requested queue has " + requests.size() + " song(s).");
            return 1;
        }
        if (!canMoveRequestedRange(actor, requests, from - 1, to - 1)) {
            actor.sendMessage("You can only reorder your own queued songs.");
            return 1;
        }
        core.coordinator().moveRequest(from, to).ifPresentOrElse(
                moved -> {
                    if (refresh) {
                        refreshQueue(actor, label, page);
                    } else {
                        actor.sendMessage("Moved: " + moved.track().title() + " from " + from + " to " + to + ".");
                    }
                },
                () -> actor.sendMessage("Could not move that queued song."));
        return 1;
    }

    private int cancel(CommandActor actor, String[] args, int argumentIndex, String usage) {
        return cancel(actor, args, argumentIndex, usage, false, "ek", 1);
    }

    private int cancel(CommandActor actor, String[] args, int argumentIndex, String usage, boolean refresh, String label, int page) {
        if (!requirePlayer(actor)) {
            return 1;
        }
        if (!actor.hasPermission("evilkaraoke.command.queue.cancel")) {
            return deny(actor);
        }
        if (args.length <= argumentIndex) {
            actor.sendMessage("Usage: " + usage);
            actor.sendMessage("Use /ek queue to see song positions.");
            return 1;
        }
        String target = args[argumentIndex];
        if ("all".equalsIgnoreCase(target)) {
            return cancelAll(actor, refresh, label, page);
        }
        int position;
        try {
            position = Integer.parseInt(target);
        } catch (NumberFormatException e) {
            actor.sendMessage("Invalid position. Must be a number.");
            return 1;
        }
        List<KaraokeSession.QueuedTrack> queue = new ArrayList<>(core.coordinator().queue());
        if (position < 1 || position > queue.size()) {
            actor.sendMessage("Position " + position + " is out of range. Queue has " + queue.size() + " song(s).");
            return 1;
        }
        KaraokeSession.QueuedTrack trackToRemove = queue.get(position - 1);
        boolean isAdmin = actor.hasPermission("evilkaraoke.admin.queue.cancel");
        boolean isOwnSong = trackToRemove.requester() != null && trackToRemove.requester().equals(actor.playerId());
        if (!isAdmin && !isOwnSong) {
            actor.sendMessage("You can only cancel your own songs. That song was queued by " + trackToRemove.requesterName() + ".");
            return 1;
        }
        core.coordinator().cancelAt(position).ifPresentOrElse(
                removed -> {
                    if (refresh) {
                        refreshQueue(actor, label, page);
                    } else {
                        actor.sendMessage("Removed from queue: " + songLine(removed.track()));
                    }
                },
                () -> actor.sendMessage("Could not remove song at position " + position + "."));
        return 1;
    }

    private int cancelAll(CommandActor actor) {
        return cancelAll(actor, false, "ek", 1);
    }

    private int cancelAll(CommandActor actor, boolean refresh, String label, int page) {
        boolean isAdmin = actor.hasPermission("evilkaraoke.admin.queue.cancel");
        List<KaraokeSession.QueuedTrack> removed = isAdmin
                ? core.coordinator().cancelAll()
                : core.coordinator().cancelAllByRequester(actor.playerId());
        if (removed.isEmpty()) {
            actor.sendMessage(isAdmin ? "The queue is already empty." : "You have no queued songs to cancel.");
            return 1;
        }
        if (refresh) {
            return refreshQueue(actor, label, page);
        }
        actor.sendMessage("Removed " + removed.size() + " queued song(s).");
        if (!isAdmin) {
            actor.sendMessage("Only your queued songs were removed.");
        }
        return 1;
    }

    private int refreshQueue(CommandActor actor, String label, int page) {
        return sendQueue(actor, new String[] {"queue", Integer.toString(Math.max(1, page))}, label);
    }

    private int control(CommandActor actor, String action, boolean refresh, String label, int page) {
        if (!isQueuePlaybackControl(action)) {
            return unknown(actor);
        }
        if (!canUsePlaybackControl(actor, action)) {
            return deny(actor);
        }
        boolean applied = switch (action) {
            case "pause" -> core.coordinator().pause();
            case "resume" -> core.coordinator().resume();
            case "next" -> core.coordinator().skip();
            case "previous" -> core.coordinator().previous();
            case "stop" -> core.coordinator().stop();
            default -> throw new IllegalStateException("Unexpected playback control: " + action);
        };
        if (!applied) {
            actor.sendMessage(playbackControlUnavailableMessage(action));
            return refresh ? refreshQueue(actor, label, page) : 1;
        }
        if (refresh) {
            return refreshQueue(actor, label, page);
        }
        actor.sendMessage(switch (action) {
            case "previous" -> "Going back to previous track.";
            case "next" -> "Skipping to next track.";
            case "stop" -> "Playback stopped.";
            default -> "Playback " + action + " sent to listeners.";
        });
        return 1;
    }

    private static String playbackControlUnavailableMessage(String action) {
        return switch (action) {
            case "previous" -> "No previous track is available.";
            case "next" -> "Nothing is playing and the queue is empty.";
            case "resume" -> "Playback is not paused.";
            default -> "Nothing is currently playing.";
        };
    }

    private int audience(CommandActor actor, String[] args) {
        if (!actor.hasPermission("evilkaraoke.command.audience")) {
            return deny(actor);
        }
        if (args.length < 2) {
            actor.sendMessage("Usage: /ek audience <@a|@s|player>");
            actor.sendMessage("Current audience: " + core.coordinator().audienceLabel());
            return 1;
        }
        String target = args[1];
        if ("@a".equalsIgnoreCase(target)) {
            core.coordinator().setAudienceAll();
            actor.sendMessage("Playback audience set to all players (@a).");
            return 1;
        }
        KaraokePlayer targetPlayer;
        if ("@s".equalsIgnoreCase(target)) {
            if (!actor.isPlayer()) {
                actor.sendMessage("@s can only be used by a player.");
                return 1;
            }
            targetPlayer = player(actor);
        } else {
            targetPlayer = core.platform().player(target).orElse(null);
        }
        if (targetPlayer == null) {
            actor.sendMessage("Player not found or not online: " + target);
            return 1;
        }
        core.coordinator().setAudiencePlayer(targetPlayer.id(), targetPlayer.name());
        actor.sendMessage("Playback audience set to " + targetPlayer.name() + " only.");
        return 1;
    }

    private int issue(CommandActor actor) {
        if (!actor.hasPermission("evilkaraoke.command.issue")) {
            return deny(actor);
        }
        actor.sendMessage("Evilkaraoke troubleshooting");
        actor.sendMessage("1. Install the Evilkaraoke client mod (Fabric or NeoForge) to hear audio.");
        actor.sendMessage("2. Ensure your Minecraft sound and Music/Jukebox volume are above zero.");
        actor.sendMessage("3. Run /ek doctor to confirm the client handshake succeeded.");
        actor.sendMessage("4. If audio stalls, /ek queue next to advance the queue.");
        return 1;
    }

    private int setlist(CommandActor actor, String[] args, String label) {
        if (!actor.hasPermission("evilkaraoke.command.setlist")) {
            return deny(actor);
        }
        if (args.length >= 2 && "add".equalsIgnoreCase(args[1])) {
            return addSetlist(actor, args);
        }
        int page = parsePage(args);
        async(core.neurokaraokeClient().setlists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                setlists -> {
                    if (setlists.isEmpty()) {
                        actor.sendMessage("No Neurokaraoke setlists found for page " + page + ".");
                        return;
                    }
                    actor.sendMessage("Setlists (page " + page + "):");
                    for (int i = 0; i < Math.min(setlists.size(), CHAT_PAGE_SIZE); i++) {
                        NeurokaraokeSetlist setlist = setlists.get(i);
                        actor.sendMessage(collectionMessage(setlist, page, i, label, "setlist"));
                    }
                    if (page > 1 || setlists.size() >= CHAT_PAGE_SIZE) {
                        actor.sendMessage(pageNavigation("/" + label + " setlist", page, page > 1, setlists.size() >= CHAT_PAGE_SIZE));
                    }
                },
                error -> actor.sendMessage(error("Setlist lookup failed", error)));
        return 1;
    }

    private int addSetlist(CommandActor actor, String[] args) {
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        if (args.length < 4) {
            actor.sendMessage("Usage: /ek setlist add <page> <row>");
            return 1;
        }
        KaraokePlayer player = player(actor);
        int page = parsePositiveInt(args[2], 1);
        int row = parsePositiveInt(args[3], 1);
        if (row > CHAT_PAGE_SIZE) {
            actor.sendMessage("Setlist row must be between 1 and " + CHAT_PAGE_SIZE + ".");
            return 1;
        }
        async(core.neurokaraokeClient().setlists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                setlists -> {
                    int index = row - 1;
                    if (index >= setlists.size()) {
                        actor.sendMessage("That setlist is no longer available on page " + page + ".");
                        return;
                    }
                    NeurokaraokeSetlist setlist = setlists.get(index);
                    if (setlist.songs().isEmpty()) {
                        actor.sendMessage("That setlist has no songs to queue.");
                        return;
                    }
                    core.coordinator().requestAll(setlist.songs(), player);
                    for (KaraokeTrack track : setlist.songs()) {
                        core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                    }
                    actor.sendMessage("Queued " + setlist.songs().size() + " song(s) from " + setlist.name() + ".");
                },
                error -> actor.sendMessage(error("Could not queue that setlist", error)));
        return 1;
    }

    private int playlist(CommandActor actor, String[] args, String label) {
        if (!actor.hasPermission("evilkaraoke.command.playlist")) {
            return deny(actor);
        }
        if (args.length >= 2 && "add".equalsIgnoreCase(args[1])) {
            return addPlaylist(actor, args);
        }
        int page = parsePage(args);
        async(core.neurokaraokeClient().publicPlaylists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE),
                playlists -> {
                    if (playlists.isEmpty()) {
                        actor.sendMessage("No public Neurokaraoke playlists found for page " + page + ".");
                        return;
                    }
                    actor.sendMessage("Public playlists (page " + page + "):");
                    for (int i = 0; i < Math.min(playlists.size(), CHAT_PAGE_SIZE); i++) {
                        NeurokaraokeSetlist playlist = playlists.get(i);
                        actor.sendMessage(collectionMessage(playlist, page, i, label, "playlist"));
                    }
                    if (page > 1 || playlists.size() >= CHAT_PAGE_SIZE) {
                        actor.sendMessage(pageNavigation("/" + label + " playlist", page, page > 1, playlists.size() >= CHAT_PAGE_SIZE));
                    }
                },
                error -> actor.sendMessage(error("Playlist lookup failed", error)));
        return 1;
    }

    private int addPlaylist(CommandActor actor, String[] args) {
        if (!requirePlayer(actor) || !canRequest(actor)) {
            return 1;
        }
        if (args.length < 4) {
            actor.sendMessage("Usage: /ek playlist add <page> <row>");
            return 1;
        }
        KaraokePlayer player = player(actor);
        int page = parsePositiveInt(args[2], 1);
        int row = parsePositiveInt(args[3], 1);
        if (row > CHAT_PAGE_SIZE) {
            actor.sendMessage("Playlist row must be between 1 and " + CHAT_PAGE_SIZE + ".");
            return 1;
        }
        async(core.neurokaraokeClient().publicPlaylists((page - 1) * CHAT_PAGE_SIZE, CHAT_PAGE_SIZE)
                        .thenCompose(playlists -> {
                            int index = row - 1;
                            if (index >= playlists.size()) {
                                return CompletableFuture.failedFuture(new IllegalArgumentException("That playlist is no longer available on page " + page + "."));
                            }
                            NeurokaraokeSetlist playlist = playlists.get(index);
                            if (playlist.songCount() == 0) {
                                return CompletableFuture.completedFuture(playlist);
                            }
                            return core.neurokaraokeClient().publicPlaylist(playlist.id());
                        }),
                playlist -> {
                    if (playlist.songs().isEmpty()) {
                        actor.sendMessage("That playlist has no songs to queue.");
                        return;
                    }
                    core.coordinator().requestAll(playlist.songs(), player);
                    for (KaraokeTrack track : playlist.songs()) {
                        core.statsService().recordRequest(player.id(), player.name(), track.id(), track.title());
                    }
                    actor.sendMessage("Queued " + playlist.songs().size() + " song(s) from playlist " + playlist.name() + ".");
                },
                error -> actor.sendMessage(error("Could not queue that playlist", error)));
        return 1;
    }

    private int stats(CommandActor actor, String[] args) {
        if (!actor.hasPermission("evilkaraoke.command.stats")) {
            actor.sendMessage("You do not have permission to view Evilkaraoke stats.");
            return 1;
        }
        String scope = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "me";
        return switch (scope) {
            case "me" -> statsMe(actor);
            case "user" -> statsUser(actor, args);
            case "server" -> statsServer(actor);
            case "top" -> statsTop(actor, args);
            default -> {
                actor.sendMessage("Usage: /ek stats <me|user|server|top>");
                yield 1;
            }
        };
    }

    private int statsMe(CommandActor actor) {
        if (!actor.isPlayer()) {
            actor.sendMessage("Only players have personal Evilkaraoke stats.");
            return 1;
        }
        UserStats stats = core.statsService().user(actor.playerId(), actor.name());
        actor.sendMessage("Your Evilkaraoke stats");
        actor.sendMessage("Listen time: " + stats.listenSeconds() + "s");
        actor.sendMessage("Songs listened: " + stats.songsListened());
        actor.sendMessage("Songs requested: " + stats.songsRequested());
        actor.sendMessage("Permission group: " + actor.group());
        return 1;
    }

    private int statsUser(CommandActor actor, String[] args) {
        if (args.length < 3) {
            actor.sendMessage("Usage: /ek stats user <player>");
            return 1;
        }
        KaraokePlayer target = core.platform().player(args[2]).orElse(null);
        if (target == null) {
            UserStats stats = core.statsService().findUserByName(args[2]).orElse(null);
            if (stats == null) {
                actor.sendMessage("Unknown player: " + args[2]);
                return 1;
            }
            actor.sendMessage("Evilkaraoke stats for " + stats.playerName());
            actor.sendMessage("Listen time: " + stats.listenSeconds() + "s");
            actor.sendMessage("Songs listened: " + stats.songsListened());
            actor.sendMessage("Songs requested: " + stats.songsRequested());
            return 1;
        }
        UserStats stats = core.statsService().user(target.id(), target.name());
        actor.sendMessage("Evilkaraoke stats for " + target.name());
        actor.sendMessage("Listen time: " + stats.listenSeconds() + "s");
        actor.sendMessage("Songs listened: " + stats.songsListened());
        actor.sendMessage("Songs requested: " + stats.songsRequested());
        return 1;
    }

    private int statsServer(CommandActor actor) {
        StatsService.ServerStats stats = core.statsService().serverStats();
        actor.sendMessage("Evilkaraoke server stats");
        actor.sendMessage("Total listen time: " + stats.totalListenSeconds() + "s");
        actor.sendMessage("Songs played: " + stats.totalSongsPlayed());
        actor.sendMessage("Songs requested: " + stats.totalSongsRequested());
        return 1;
    }

    private int statsTop(CommandActor actor, String[] args) {
        String category = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "users";
        int limit = parseLimit(args, 4, 5);
        if ("songs".equals(category)) {
            String by = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "played";
            List<SongStats> top = by.equals("requested") ? core.statsService().topSongsByRequests(limit) : core.statsService().topSongsByPlays(limit);
            actor.sendMessage("Top songs by " + by);
            int rank = 1;
            for (SongStats song : top) {
                long value = by.equals("requested") ? song.timesRequested() : song.timesPlayed();
                actor.sendMessage(rank++ + ". " + song.title() + " (" + value + ")");
            }
            return 1;
        }
        String by = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "time";
        List<UserStats> top = switch (by) {
            case "song-count" -> core.statsService().topUsersBySongs(limit);
            case "request-count" -> core.statsService().topUsersByRequests(limit);
            default -> core.statsService().topUsersByTime(limit);
        };
        actor.sendMessage("Top users by " + by);
        int rank = 1;
        for (UserStats user : top) {
            long value = switch (by) {
                case "song-count" -> user.songsListened();
                case "request-count" -> user.songsRequested();
                default -> user.listenSeconds();
            };
            actor.sendMessage(rank++ + ". " + user.playerName() + " (" + value + ")");
        }
        return 1;
    }

    private int unknown(CommandActor actor) {
        actor.sendMessage("Unknown Evilkaraoke subcommand. Try /ek help.");
        return 0;
    }

    private int deny(CommandActor actor) {
        actor.sendMessage("You do not have permission to use that Evilkaraoke command.");
        return 0;
    }

    private boolean requirePlayer(CommandActor actor) {
        if (actor.isPlayer()) {
            return true;
        }
        actor.sendMessage("That command must be run by a player.");
        return false;
    }

    private void maybeWarnClient(CommandActor actor) {
        if (actor.isPlayer() && core.config().requireClientMod() && !core.clientRegistry().isCompatible(actor.playerId())) {
            actor.sendMessage("Heads up: install or update the Evilkaraoke client mod to actually hear playback.");
        }
    }

    private <T> void async(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> core.platform().runNow(() -> {
            if (error != null) {
                Throwable cause = unwrapCompletion(error);
                logCommandFailure(cause);
                onError.accept(cause);
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    private KaraokePlayer player(CommandActor actor) {
        return new KaraokePlayer(actor.playerId(), actor.name());
    }

    private static String error(String prefix, Throwable error) {
        Throwable cause = unwrapCompletion(error);
        if (cause instanceof NeurokaraokeApiUnavailableException) {
            return "Neurokaraoke API unavailable: " + safeErrorDetail(cause);
        }
        return prefix + ": " + safeErrorDetail(cause);
    }

    private void logCommandFailure(Throwable cause) {
        if (cause instanceof NeurokaraokeApiUnavailableException) {
            core.platform().log(Level.WARNING, "Evilkaraoke command failed: " + cause.getMessage(), null);
            return;
        }
        core.platform().log(Level.WARNING, "Evilkaraoke command failed", cause);
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String safeErrorDetail(Throwable error) {
        String detail = error == null ? "" : error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        return detail.length() <= 240 ? detail : detail.substring(0, 209) + "... (truncated; see server log)";
    }

    private static boolean isSongIdMode(String value) {
        return "id".equalsIgnoreCase(value);
    }

    private static String joinTail(String[] args, int startInclusive) {
        if (args.length <= startInclusive) {
            return null;
        }
        return String.join(" ", Arrays.copyOfRange(args, startInclusive, args.length));
    }

    private static CommandMessage searchHeaderMessage(String query, int page, String label) {
        return CommandMessage.builder()
                .append("Results for \"" + query + "\" (page " + page + "): ")
                .action("[Queue All]", "/" + label + " search queue-all", "Queue all results on this page")
                .build();
    }

    private static CommandMessage searchResultMessage(KaraokeTrack track, int page, int rowIndex, String label) {
        int number = ((Math.max(1, page) - 1) * CHAT_PAGE_SIZE) + rowIndex + 1;
        CommandMessage.Builder message = CommandMessage.builder()
                .append(number + ". " + songLine(track) + " ");
        if (safeCommandToken(track.id())) {
            message.action("[Request]", "/" + label + " request id " + track.id(), "Request " + track.title());
        } else {
            message.append("(request with /" + label + " request id " + track.id() + ")");
        }
        return message.build();
    }

    private static CommandMessage collectionMessage(NeurokaraokeSetlist setlist, int page, int rowIndex, String label, String command) {
        CommandMessage.Builder message = CommandMessage.builder()
                .append((rowIndex + 1) + ". " + setlist.name() + " - " + setlist.songCount() + " songs ");
        if (setlist.songCount() <= 0) {
            message.append("[empty]");
        } else {
            message.action("[Queue All]", "/" + label + " " + command + " add " + page + " " + (rowIndex + 1), "Queue all songs from " + setlist.name());
        }
        return message.build();
    }

    private static CommandMessage queueControls(CommandActor actor, KaraokeSession.PlaybackSnapshot snapshot, String label, int page) {
        CommandMessage.Builder message = CommandMessage.builder()
                .append("Controls: ");
        message.action("[Previous]", "/" + label + " queue previous " + queueRefreshToken(page), "Go back to previous track");
        message.append(" ");
        message.action("[Next]", "/" + label + " queue next " + queueRefreshToken(page), "Skip to next track");
        message.append(" ");
        return message.action(snapshot.randomEnabled() ? "[Random: On]" : "[Random: Off]",
                        "/" + label + " queue random " + queueRefreshToken(page),
                        "Toggle random queue order")
                .append(" ")
                .action(snapshot.loopQueueEnabled() ? "[Loop: On]" : "[Loop: Off]",
                        "/" + label + " queue loop " + queueRefreshToken(page),
                        "Toggle whole queue loop")
                .build();
    }

    private static CommandMessage currentTrackMessage(CommandActor actor, KaraokeSession.PlaybackSnapshot snapshot, String trackLine, String label, int page) {
        CommandMessage.Builder message = CommandMessage.builder()
                .append("Now playing: " + trackLine)
                .append(currentTrackDetails(snapshot));
        if (snapshot.state() == PlaybackState.PLAYING && canUsePlaybackControl(actor, "pause")) {
            message.append(" ");
            message.action("[Pause]", "/" + label + " queue pause " + queueRefreshToken(page), "Pause current playback");
        } else if (snapshot.state() == PlaybackState.PAUSED && canUsePlaybackControl(actor, "resume")) {
            message.append(" ");
            message.action("[Resume]", "/" + label + " queue resume " + queueRefreshToken(page), "Resume current playback");
        }
        if (canUsePlaybackControl(actor, "stop")) {
            message.append(" ");
            message.action("[Stop]", "/" + label + " queue stop " + queueRefreshToken(page), "Stop current playback");
        }
        return message.build();
    }

    private static String currentTrackDetails(KaraokeSession.PlaybackSnapshot snapshot) {
        if (snapshot.current() == null) {
            return "";
        }
        return " (by " + snapshot.current().requesterName() + ")"
                + " | Elapsed: " + DurationFormatter.mmss(snapshot.offset());
    }

    private static boolean canUsePlaybackControl(CommandActor actor, String action) {
        return isPublicSwitchControl(action) || actor.hasPermission("evilkaraoke.command.queue." + action);
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

    private CommandMessage queueItemMessage(CommandActor actor,
                                            KaraokeSession.QueuedTrack queued,
                                            List<KaraokeSession.QueuedTrack> requests,
                                            KaraokeSession.QueuedTrack singleLoopTrack,
                                            int position,
                                            String label) {
        CommandMessage.Builder message = CommandMessage.builder()
                .append(position + ". " + songLine(queued.track()) + " (by " + queued.requesterName() + ") ");
        int requestIndex = position - 1;
        if (requestIndex >= 0 && requestIndex < requests.size()) {
            if (requestIndex > 0 && canMoveRequestedRange(actor, requests, requestIndex, requestIndex - 1)) {
                message.action("[Up]", "/" + label + " queue move " + position + " " + (position - 1) + " " + queueRefreshToken(queuePage(position)), "Move earlier");
                message.append(" ");
            }
            if (requestIndex + 1 < requests.size() && canMoveRequestedRange(actor, requests, requestIndex, requestIndex + 1)) {
                message.action("[Down]", "/" + label + " queue move " + position + " " + (position + 1) + " " + queueRefreshToken(queuePage(position)), "Move later");
                message.append(" ");
            }
        }
        boolean singleLoop = sameQueuedTrack(queued, singleLoopTrack);
        message.action(singleLoop ? "[Loop 1: On]" : "[Loop 1]",
                "/" + label + " queue loop " + position + " " + queueRefreshToken(queuePage(position)),
                singleLoop ? "Disable single-song loop" : "Loop this song");
        message.append(" ");
        message.action("[Cancel]", "/" + label + " queue cancel " + position + " " + queueRefreshToken(queuePage(position)), "Remove this song from the queue");
        return message.build();
    }

    private static int queuePage(int position) {
        return Math.max(1, ((position - 1) / CHAT_PAGE_SIZE) + 1);
    }

    private static String songLine(KaraokeTrack track) {
        return track.title() + " - " + track.artist() + coveredBySuffix(track);
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

    private static CommandMessage pageNavigation(String commandPrefix, int page, boolean hasPreviousPage, boolean hasNextPage) {
        CommandMessage.Builder message = CommandMessage.builder();
        if (hasPreviousPage) {
            message.action("[Prev]", commandPrefix + " " + (page - 1), "Previous page (" + (page - 1) + ")");
        }
        if (hasPreviousPage && hasNextPage) {
            message.append(" ");
        }
        if (hasNextPage) {
            message.action("[Next]", commandPrefix + " " + (page + 1), "Next page (" + (page + 1) + ")");
        }
        return message.build();
    }

    private static boolean canMoveRequestedRange(CommandActor actor, List<KaraokeSession.QueuedTrack> requests, int fromIndex, int toIndex) {
        if (actor.hasPermission("evilkaraoke.admin.queue.move")) {
            return true;
        }
        int start = Math.min(fromIndex, toIndex);
        int end = Math.max(fromIndex, toIndex);
        for (int i = start; i <= end; i++) {
            if (i < 0 || i >= requests.size()) {
                return false;
            }
            KaraokeSession.QueuedTrack queued = requests.get(i);
            if (queued.requester() == null || !queued.requester().equals(actor.playerId())) {
                return false;
            }
        }
        return true;
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

    private SearchRequest parseSearchRequest(String[] args) {
        int page = 1;
        int queryEndExclusive = args.length;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[args.length - 1]));
                queryEndExclusive = args.length - 1;
            } catch (NumberFormatException ignored) {
                // Last token is part of the query.
            }
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, queryEndExclusive));
        return new SearchRequest(query, page);
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

    private int parseLimit(String[] args, int... indices) {
        for (int index : indices) {
            if (args.length > index) {
                try {
                    return Math.min(20, Math.max(1, Integer.parseInt(args[index])));
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return 5;
    }

    private record SearchRequest(String query, int page) {
    }

    private record SearchPage(List<KaraokeTrack> results, boolean hasNextPage) {
    }
}
