package org.evilproject.evilkaraoke.paper.playback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.audio.AudioStreamRelay;
import org.evilproject.evilkaraoke.common.audio.ServerAudioStreamRelay;
import org.evilproject.evilkaraoke.common.audio.ServerStreamQualitySelector;
import org.evilproject.evilkaraoke.common.audio.ServerStreamQualitySelector.ClientHealth;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;

public final class PlaybackCoordinator {
    private static final long BUFFER_TIME_SECONDS = 10L; // Extra time to account for client buffering
    private static final long CLIENT_START_TIMEOUT_SECONDS = 10L;
    private static final long CLIENT_STATUS_STALE_SECONDS = 15L;
    private static final long CLIENT_WATCHDOG_POLL_SECONDS = 5L;
    private static final Duration SERVER_STREAM_START_DELAY = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final PlaybackMessenger messenger;
    private final AudioStreamRelay audioStreamRelay = new ServerAudioStreamRelay();
    private final KaraokeSession session = new KaraokeSession();
    private NeurokaraokeClient neurokaraokeClient;
    private EvilKaraokeConfig config;
    private int autoAdvanceTask = -1;

    private TargetMode audienceMode = TargetMode.ALL;
    private UUID audiencePlayer;
    private String audienceLabel = "@a";

    /** Tracks the current playback ID and which clients have started playing */
    private volatile String currentPlaybackId = null;
    private final Map<UUID, ClientPlaybackReport> clientStates = new HashMap<>();
    private final Set<UUID> playbackClientIds = new HashSet<>();
    private boolean timerStarted = false;
    private boolean clientDrivenPlayback = false;

    public PlaybackCoordinator(Plugin plugin, ClientRegistry clientRegistry, PlaybackMessenger messenger, NeurokaraokeClient neurokaraokeClient, EvilKaraokeConfig config) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.messenger = messenger;
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
        this.audienceLabel = config.defaultTargets();
        this.audienceMode = "@a".equals(config.defaultTargets()) ? TargetMode.ALL : TargetMode.SELECTOR;
    }

    public void update(NeurokaraokeClient neurokaraokeClient, EvilKaraokeConfig config) {
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
    }

    public CompletableFuture<KaraokeTrack> requestSearch(String query, Player requester) {
        return neurokaraokeClient.search(query).thenCompose(results -> {
            if (results.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("No Neurokaraoke results for: " + query));
            }
            KaraokeTrack track = results.getFirst();
            return request(track, requester).thenApply(ignored -> track);
        });
    }

    public CompletableFuture<KaraokeTrack> requestRandom(Player requester) {
        return neurokaraokeClient.randomSong().thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<KaraokeTrack> requestRadio(String station, Player requester) {
        return neurokaraokeClient.radio(station).thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<Void> request(KaraokeTrack track, Player requester) {
        session.request(track, requester.getUniqueId(), requester.getName());
        if (session.current().isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, this::playNext);
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Integer> requestAll(List<KaraokeTrack> tracks, Player requester) {
        if (tracks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        boolean shouldStart = session.current().isEmpty();
        for (KaraokeTrack track : tracks) {
            session.request(track, requester.getUniqueId(), requester.getName());
        }
        if (shouldStart) {
            Bukkit.getScheduler().runTask(plugin, this::playNext);
        }
        return CompletableFuture.completedFuture(tracks.size());
    }

    public void playNext() {
        session.next().ifPresentOrElse(
                queued -> beginPlayback(queued, ""),
                () -> {
                    clearPlaybackTracking();
                    plugin.getLogger().fine("Evilkaraoke queue is empty.");
                });
    }

    private void beginPlayback(KaraokeSession.QueuedTrack queued, String reason) {
        List<Player> streamRecipients = serverStreamRecipients();
        List<Player> urlRecipients = urlRecipients();
        ServerStreamQualitySelector.Selection streamSelection = selectStreamTrack(queued.track(), streamRecipients);
        KaraokeTrack streamTrack = streamSelection.track();
        currentPlaybackId = UUID.randomUUID().toString();
        clientStates.clear();
        playbackClientIds.clear();
        timerStarted = false;
        clientDrivenPlayback = false;
        Instant scheduledStart = Instant.now().plus(SERVER_STREAM_START_DELAY);
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId,
                streamTrack,
                audienceTarget(),
                Duration.ZERO,
                scheduledStart,
                reason,
                Duration.ZERO,
                AudioDeliveryMode.SERVER_STREAM);
        broadcastTo(streamRecipients, packet);
        playbackClientIds.addAll(streamRecipients.stream().map(Player::getUniqueId).toList());
        if (!urlRecipients.isEmpty()) {
            AudioCommandPacket urlPacket = new AudioCommandPacket(
                    AudioCommandType.PLAY,
                    KaraokeSession.GLOBAL_SESSION_ID,
                    currentPlaybackId,
                    queued.track(),
                    audienceTarget(),
                    Duration.ZERO,
                    scheduledStart,
                    reason,
                    Duration.ZERO,
                    AudioDeliveryMode.URL);
            broadcastTo(urlRecipients, urlPacket);
            playbackClientIds.addAll(urlRecipients.stream().map(Player::getUniqueId).toList());
        }
        startAudioRelay(streamTrack, packet, streamRecipients);
        schedulePlaybackStartFallback(streamTrack, currentPlaybackId);
    }

    private void clearPlaybackTracking() {
        cancelAutoAdvance();
        currentPlaybackId = null;
        clientStates.clear();
        playbackClientIds.clear();
        timerStarted = false;
        clientDrivenPlayback = false;
    }

    public void pause() {
        session.pause();
        cancelAutoAdvance();
        broadcastControl(AudioCommandType.PAUSE, "pause");
    }

    public void resume() {
        session.resume();
        if (currentPlaybackId != null) {
            session.current().ifPresent(current -> {
                if (timerStarted) {
                    if (clientDrivenPlayback) {
                        scheduleClientDrivenWatchdog(current.track(), currentPlaybackId);
                    } else {
                        scheduleAutoAdvance(current.track(), currentPlaybackId);
                    }
                } else {
                    schedulePlaybackStartFallback(current.track(), currentPlaybackId);
                }
            });
        }
        broadcastControl(AudioCommandType.RESUME, "resume");
    }

    public void skip() {
        broadcastControl(AudioCommandType.STOP, "skip");
        playNext();
    }

    public void previous() {
        session.previous().ifPresentOrElse(
                queued -> beginPlayback(queued, "previous"),
                () -> plugin.getLogger().fine("No previous track in history."));
    }

    public void stop() {
        broadcastControl(AudioCommandType.STOP, "stop");
        session.stopCurrent();
        clearPlaybackTracking();
    }

    /**
     * Handles a status update from a client. When the first client transitions to
     * PLAYING state, starts the session timer so auto-advance happens relative to
     * actual playback rather than the buffering phase.
     */
    public void handleClientStatus(UUID playerId, ClientStatusPacket status) {
        if (currentPlaybackId == null || !currentPlaybackId.equals(status.playbackId())) {
            return;
        }

        ClientPlaybackReport previous = clientStates.get(playerId);
        boolean started = (previous != null && previous.started())
                || status.state() == ClientPlaybackState.PLAYING
                || isTerminal(status.state());
        clientStates.put(playerId, new ClientPlaybackReport(
                status.state(),
                Instant.now(),
                started,
                status.streamBytesReceived(),
                status.streamBytesRead(),
                status.streamQueuedBytes(),
                status.streamMissingChunks()));

        if (isTerminal(status.state()) && session.snapshot().state() == PlaybackState.PLAYING) {
            maybeAdvanceAfterClientReports();
            return;
        }

        // Start the session timer when the first client actually begins playing.
        if (!timerStarted && status.state() == ClientPlaybackState.PLAYING) {
            session.current().ifPresent(current ->
                    startPlaybackTimer(current.track(), status.playbackId(), "client " + playerId + " began playing", true));
        }
    }

    private void startPlaybackTimer(KaraokeTrack track, String playbackId, String reason, boolean clientConfirmed) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        if (timerStarted
                || currentPlaybackId == null
                || !currentPlaybackId.equals(playbackId)
                || snapshot.current() == null
                || snapshot.state() != PlaybackState.PLAYING) {
            return;
        }
        timerStarted = true;
        clientDrivenPlayback = clientConfirmed;
        session.startTimer();
        cancelAutoAdvance();
        if (clientDrivenPlayback) {
            scheduleClientDrivenWatchdog(track, playbackId);
        } else {
            scheduleAutoAdvance(track, playbackId);
        }
        plugin.getLogger().fine("Started playback timer after " + reason);
    }

    /**
     * Sends the current playback state to a single player. Called when a player
     * (re)joins with the mod installed so they pick up the song mid-stream rather
     * than missing it until the next track starts.
     *
     * <p>Does nothing if the session is idle or paused — a paused track will be
     * resumed for everyone at once via the normal resume command.
     */
    public void syncPlayer(Player player) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        if (snapshot.current() == null || snapshot.state() != PlaybackState.PLAYING || currentPlaybackId == null) {
            return;
        }
        Instant scheduledStart = Instant.now().plus(SERVER_STREAM_START_DELAY);
        boolean streamSupported = clientRegistry.supportsServerStream(player.getUniqueId());
        KaraokeTrack selectedTrack = streamSupported ? selectStreamTrack(snapshot.current().track(), List.of(player)).track() : snapshot.current().track();
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId,
                selectedTrack,
                audienceTarget(),
                snapshot.offset(),
                scheduledStart,
                "rejoin-sync",
                Duration.ZERO,
                streamSupported ? AudioDeliveryMode.SERVER_STREAM : AudioDeliveryMode.URL);
        if (clientRegistry.isCompatible(player.getUniqueId())) {
            playbackClientIds.add(player.getUniqueId());
            messenger.send(player, packet);
            if (streamSupported) {
                startAudioRelay(selectedTrack, packet, List.of(player));
            }
        }
    }

    public void setAudienceAll() {
        this.audienceMode = TargetMode.ALL;
        this.audiencePlayer = null;
        this.audienceLabel = "@a";
    }

    public void setAudiencePlayer(UUID playerId, String label) {
        this.audienceMode = TargetMode.PLAYER;
        this.audiencePlayer = playerId;
        this.audienceLabel = label;
    }

    public String audienceLabel() {
        return audienceLabel;
    }

    public KaraokeSession.PlaybackSnapshot snapshot() {
        return session.snapshot();
    }

    public Collection<KaraokeSession.QueuedTrack> queue() {
        return session.queuedTracks();
    }

    /**
     * Removes a track from the queue at the given position (1-indexed for user display).
     * Returns the removed track if successful, empty otherwise.
     */
    public java.util.Optional<KaraokeSession.QueuedTrack> cancelAt(int position) {
        return session.removeAt(position - 1);
    }

    public List<KaraokeSession.QueuedTrack> cancelAll() {
        return session.removeAllQueued();
    }

    public List<KaraokeSession.QueuedTrack> cancelAllByRequester(UUID requester) {
        return session.removeRequestsByRequester(requester);
    }

    public java.util.Optional<KaraokeSession.QueuedTrack> moveRequest(int fromPosition, int toPosition) {
        return session.moveRequest(fromPosition - 1, toPosition - 1);
    }

    public boolean toggleRandomQueue() {
        return session.toggleRandom();
    }

    public boolean toggleQueueLoop() {
        return session.toggleQueueLoop();
    }

    public java.util.Optional<KaraokeSession.SingleLoopChange> toggleSingleLoop(int position) {
        return java.util.Optional.ofNullable(session.toggleSingleLoopAt(position - 1));
    }

    private PlaybackTarget audienceTarget() {
        String selector = audienceMode == TargetMode.PLAYER && audiencePlayer != null ? audiencePlayer.toString() : audienceLabel;
        return new PlaybackTarget(
                audienceMode,
                selector,
                soundCategory(),
                null,
                config.defaultVolume(),
                config.defaultPitch(),
                config.defaultMinVolume());
    }

    private SoundCategory soundCategory() {
        try {
            return SoundCategory.valueOf(config.defaultSource().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SoundCategory.MUSIC;
        }
    }

    private void broadcastControl(AudioCommandType type, String reason) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        String playbackId = currentPlaybackId == null ? "none" : currentPlaybackId;
        broadcast(new AudioCommandPacket(
                type,
                KaraokeSession.GLOBAL_SESSION_ID,
                playbackId,
                snapshot.current() == null ? null : snapshot.current().track(),
                audienceTarget(),
                snapshot.offset(),
                Instant.now(),
                reason,
                Duration.ZERO));
    }

    private void broadcast(ProtocolPacket packet) {
        for (Player player : recipients()) {
            if (!config.requireClientMod() || clientRegistry.isCompatible(player.getUniqueId())) {
                messenger.send(player, packet);
            }
        }
    }

    private void broadcastTo(List<Player> players, ProtocolPacket packet) {
        for (Player player : players) {
            messenger.send(player, packet);
        }
    }

    private void startAudioRelay(KaraokeTrack track, AudioCommandPacket packet, List<Player> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        String playbackId = packet.playbackId();
        audioStreamRelay.relay(
                packet.sessionId(),
                playbackId,
                track,
                packet.target(),
                packet.offset(),
                packet.serverTime(),
                packet.reason(),
                streamPacket -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (playbackId.equals(currentPlaybackId)) {
                        broadcastTo(recipients, streamPacket);
                    }
                }),
                () -> playbackId.equals(currentPlaybackId)
        ).exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Server audio relay failed for " + track.title(), error);
            return null;
        });
    }

    private List<Player> recipients() {
        if (audienceMode == TargetMode.PLAYER && audiencePlayer != null) {
            Player target = Bukkit.getPlayer(audiencePlayer);
            return target == null ? List.of() : List.of(target);
        }
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private void schedulePlaybackStartFallback(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            autoAdvanceTask = -1;
            if (currentPlaybackId != null && currentPlaybackId.equals(playbackId) && !timerStarted) {
                startPlaybackTimer(track, playbackId, "client start timeout", false);
            }
        }, Math.max(1L, CLIENT_START_TIMEOUT_SECONDS * 20L)).getTaskId();
    }

    private void scheduleAutoAdvance(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        track.finiteDuration().ifPresent(duration -> {
            Duration remaining = duration.minus(session.snapshot().offset()).plus(Duration.ofSeconds(BUFFER_TIME_SECONDS));
            long delayTicks = ticksCeil(remaining);
            autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                autoAdvanceTask = -1;
                if (currentPlaybackId != null && currentPlaybackId.equals(playbackId)) {
                    playNext();
                }
            }, delayTicks).getTaskId();
        });
    }

    private void scheduleClientDrivenWatchdog(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        track.finiteDuration().ifPresent(duration -> {
            Duration remaining = duration.minus(session.snapshot().offset()).plus(Duration.ofSeconds(BUFFER_TIME_SECONDS));
            autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                autoAdvanceTask = -1;
                checkClientDrivenPlayback(playbackId);
            }, ticksCeil(remaining)).getTaskId();
        });
    }

    private void scheduleClientDrivenWatchdogPoll(String playbackId) {
        cancelAutoAdvance();
        autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            autoAdvanceTask = -1;
            checkClientDrivenPlayback(playbackId);
        }, CLIENT_WATCHDOG_POLL_SECONDS * 20L).getTaskId();
    }

    private void checkClientDrivenPlayback(String playbackId) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        if (currentPlaybackId == null
                || !currentPlaybackId.equals(playbackId)
                || snapshot.current() == null
                || snapshot.state() != PlaybackState.PLAYING) {
            return;
        }
        if (maybeAdvanceAfterClientReports()) {
            return;
        }
        List<ClientPlaybackReport> reports = activeStartedClientReports();
        if (reports.isEmpty()) {
            playNext();
            return;
        }
        Instant now = Instant.now();
        boolean hasFreshActiveClient = reports.stream()
                .anyMatch(report -> isActive(report.state()) && !isStale(report, now));
        if (hasFreshActiveClient) {
            scheduleClientDrivenWatchdogPoll(playbackId);
            return;
        }
        playNext();
    }

    private boolean maybeAdvanceAfterClientReports() {
        List<UUID> clientIds = activePlaybackClientIds();
        if (clientIds.isEmpty()) {
            return false;
        }
        boolean allTerminal = clientIds.stream()
                .allMatch(playerId -> {
                    ClientPlaybackReport report = clientStates.get(playerId);
                    return report != null && report.started() && isTerminal(report.state());
                });
        if (allTerminal) {
            playNext();
            return true;
        }
        return false;
    }

    private List<ClientPlaybackReport> activeStartedClientReports() {
        return activePlaybackClientIds().stream()
                .map(clientStates::get)
                .filter(report -> report != null && report.started())
                .toList();
    }

    private List<UUID> activePlaybackClientIds() {
        Set<UUID> recipientIds = new HashSet<>(compatibleRecipientIds());
        return playbackClientIds.stream()
                .filter(recipientIds::contains)
                .toList();
    }

    private List<UUID> compatibleRecipientIds() {
        return compatibleRecipients().stream()
                .map(Player::getUniqueId)
                .toList();
    }

    private List<Player> compatibleRecipients() {
        return recipients().stream()
                .filter(player -> clientRegistry.isCompatible(player.getUniqueId()))
                .toList();
    }

    private List<Player> serverStreamRecipients() {
        return recipients().stream()
                .filter(player -> clientRegistry.supportsServerStream(player.getUniqueId()))
                .toList();
    }

    private List<Player> urlRecipients() {
        return compatibleRecipients().stream()
                .filter(player -> !clientRegistry.supportsServerStream(player.getUniqueId()))
                .toList();
    }

    private ServerStreamQualitySelector.Selection selectStreamTrack(KaraokeTrack track, List<Player> streamRecipients) {
        Instant now = Instant.now();
        ServerStreamQualitySelector.Selection selection = ServerStreamQualitySelector.select(
                track,
                streamRecipients.stream()
                        .map(player -> clientHealth(player, now))
                        .toList());
        if (track.fallbackAsset() != null) {
            plugin.getLogger().fine("Selected " + selection.quality() + " server stream for " + track.title() + " (" + selection.reason() + ")");
        }
        return selection;
    }

    private ClientHealth clientHealth(Player player, Instant now) {
        ClientPlaybackReport report = clientStates.get(player.getUniqueId());
        if (report == null || isStale(report, now)) {
            return new ClientHealth(player.getPing(), 0L, 0L, 0, 0L);
        }
        return new ClientHealth(
                player.getPing(),
                report.streamBytesReceived(),
                report.streamBytesRead(),
                report.streamQueuedBytes(),
                report.streamMissingChunks());
    }

    private static boolean isActive(ClientPlaybackState state) {
        return state == ClientPlaybackState.BUFFERING
                || state == ClientPlaybackState.PLAYING
                || state == ClientPlaybackState.PAUSED;
    }

    private static boolean isTerminal(ClientPlaybackState state) {
        return state == ClientPlaybackState.READY
                || state == ClientPlaybackState.STOPPED
                || state == ClientPlaybackState.ERROR;
    }

    private static boolean isStale(ClientPlaybackReport report, Instant now) {
        return Duration.between(report.updatedAt(), now).compareTo(Duration.ofSeconds(CLIENT_STATUS_STALE_SECONDS)) > 0;
    }

    private static long ticksCeil(Duration duration) {
        long millis = Math.max(1L, duration.toMillis());
        return Math.max(1L, (millis + 49L) / 50L);
    }

    private void cancelAutoAdvance() {
        if (autoAdvanceTask != -1) {
            Bukkit.getScheduler().cancelTask(autoAdvanceTask);
            autoAdvanceTask = -1;
        }
    }

    private record ClientPlaybackReport(
            ClientPlaybackState state,
            Instant updatedAt,
            boolean started,
            long streamBytesReceived,
            long streamBytesRead,
            int streamQueuedBytes,
            long streamMissingChunks
    ) {
    }
}
