package org.evilproject.evilkaraoke.paper.playback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.common.protocol.LyricsDisplayAction;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.config.EvilKaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;

public final class PlaybackCoordinator {
    private static final long BUFFER_TIME_SECONDS = 10L; // Extra time to account for client buffering
    private static final long CLIENT_START_TIMEOUT_SECONDS = 10L;
    private static final int MAX_CLIENT_START_WAIT_ATTEMPTS = 3;
    private static final long CLIENT_STATUS_STALE_SECONDS = 15L;
    private static final long CLIENT_WATCHDOG_POLL_SECONDS = 5L;
    // Shared start instant for all stream clients. The relay grants an immediate
    // multi-second PCM prebuffer, so clients only need enough lead time for the
    // origin's first bytes to arrive; a late stream simply starts on arrival.
    private static final Duration SERVER_STREAM_START_DELAY = Duration.ofSeconds(2);
    // Rolling tail of already-sent stream chunks kept for late joiners. Must match
    // the relay's prebuffer lead: connected clients hold that many seconds of
    // unplayed audio, so replaying exactly this much tail puts a joiner's first
    // audible byte at the same position everyone else is hearing right now.
    private static final Duration STREAM_REJOIN_TAIL = Duration.ofSeconds(8);
    private static final long MIN_REJOIN_TAIL_BYTES = 256L * 1024L;

    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final PlaybackMessenger messenger;
    private final AudioStreamRelay audioStreamRelay = new ServerAudioStreamRelay();
    private final KaraokeSession session = new KaraokeSession();
    private NeurokaraokeClient neurokaraokeClient;
    private EvilKaraokeConfig config;
    private int autoAdvanceTask = -1;
    private final AtomicBoolean playbackStartQueued = new AtomicBoolean();

    private TargetMode audienceMode = TargetMode.ALL;
    private UUID audiencePlayer;
    private String audienceLabel = "@a";

    /** Tracks the current playback ID and which clients have started playing */
    private volatile String currentPlaybackId = null;
    private final Map<UUID, ClientPlaybackReport> clientStates = new HashMap<>();
    private final Set<UUID> playbackClientIds = new HashSet<>();
    // Players currently attached to the global stream. The relay always runs for
    // the whole track — even with zero listeners — so decoding never stops when
    // players leave and joiners can attach to the live stream mid-song.
    private final Set<UUID> streamListenerIds = new HashSet<>();
    private final Deque<AudioStreamChunkPacket> recentStreamChunks = new ArrayDeque<>();
    private long recentStreamChunkBytes = 0L;
    private KaraokeTrack currentStreamTrack;
    private boolean timerStarted = false;
    private boolean clientDrivenPlayback = false;
    private int clientStartWaitAttempts = 0;

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
        return requestRandom(requester, 1).thenApply(List::getFirst);
    }

    public CompletableFuture<List<KaraokeTrack>> requestRandom(Player requester, int count) {
        int limit = Math.max(1, count);
        return neurokaraokeClient.randomSongs().thenCompose(tracks -> {
            List<KaraokeTrack> selected = tracks.stream().limit(limit).toList();
            if (selected.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("No random Neurokaraoke songs returned"));
            }
            return requestAll(selected, requester).thenApply(ignored -> selected);
        });
    }

    public CompletableFuture<KaraokeTrack> requestRadio(String station, Player requester) {
        return neurokaraokeClient.radio(station).thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<Void> request(KaraokeTrack track, Player requester) {
        session.request(track, requester.getUniqueId(), requester.getName());
        schedulePlaybackStartIfIdle();
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Integer> requestAll(List<KaraokeTrack> tracks, Player requester) {
        if (tracks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        int queued = session.requestAll(tracks, requester.getUniqueId(), requester.getName());
        schedulePlaybackStartIfIdle();
        return CompletableFuture.completedFuture(queued);
    }

    private void schedulePlaybackStartIfIdle() {
        if (session.current().isPresent() || !playbackStartQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                playbackStartQueued.set(false);
                if (session.current().isEmpty()) {
                    playNext();
                }
            });
        } catch (RuntimeException ex) {
            playbackStartQueued.set(false);
            throw ex;
        }
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
        streamListenerIds.clear();
        recentStreamChunks.clear();
        recentStreamChunkBytes = 0L;
        currentStreamTrack = streamTrack;
        timerStarted = false;
        clientDrivenPlayback = false;
        clientStartWaitAttempts = 0;
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
        streamListenerIds.addAll(streamRecipients.stream().map(Player::getUniqueId).toList());
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
        startAudioRelay(streamTrack, packet);
        schedulePlaybackStartFallback(streamTrack, currentPlaybackId);
    }

    private void clearPlaybackTracking() {
        cancelAutoAdvance();
        currentPlaybackId = null;
        clientStates.clear();
        playbackClientIds.clear();
        streamListenerIds.clear();
        recentStreamChunks.clear();
        recentStreamChunkBytes = 0L;
        currentStreamTrack = null;
        timerStarted = false;
        clientDrivenPlayback = false;
        clientStartWaitAttempts = 0;
    }

    public boolean pause() {
        if (session.snapshot().state() != PlaybackState.PLAYING) {
            return false;
        }
        session.pause();
        cancelAutoAdvance();
        broadcastControl(AudioCommandType.PAUSE, "pause");
        return true;
    }

    public boolean resume() {
        if (session.snapshot().state() != PlaybackState.PAUSED) {
            return false;
        }
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
        return true;
    }

    public boolean skip() {
        boolean hasCurrent = session.current().isPresent();
        if (!hasCurrent && session.queuedTracks().isEmpty()) {
            return false;
        }
        if (hasCurrent) {
            broadcastControl(AudioCommandType.STOP, "skip");
        }
        playNext();
        return true;
    }

    public boolean previous() {
        java.util.Optional<KaraokeSession.QueuedTrack> previous = session.previous();
        if (previous.isEmpty()) {
            plugin.getLogger().fine("No previous track in history.");
            return false;
        }
        beginPlayback(previous.get(), "previous");
        return true;
    }

    public boolean stop() {
        if (session.current().isEmpty() && session.snapshot().state() == PlaybackState.IDLE) {
            return false;
        }
        broadcastControl(AudioCommandType.STOP, "stop");
        session.stopCurrent();
        clearPlaybackTracking();
        return true;
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
        // A client only counts as participating in this playback once it reports
        // activity (or an error) for it. A bare STOPPED as the very first report
        // is a stale echo of the previous track ending on the client; honoring
        // it as "this track already finished" would skip the fresh track.
        boolean started = (previous != null && previous.started())
                || isActive(status.state())
                || status.state() == ClientPlaybackState.ERROR;
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
        clientStartWaitAttempts = 0;
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
        if (!clientRegistry.isCompatible(player.getUniqueId())) {
            return;
        }
        boolean streamSupported = clientRegistry.supportsServerStream(player.getUniqueId());
        if (streamSupported) {
            // Attach to the always-running global relay instead of spawning a new
            // one: replay the buffered tail (which ends at the live send edge and
            // starts at the position other clients are hearing right now), then
            // let live chunks continue seamlessly. serverTime is "now" because the
            // tail already provides the prebuffer — any scheduled delay would just
            // put this client behind the global clock.
            KaraokeTrack streamTrack = currentStreamTrack != null ? currentStreamTrack : snapshot.current().track();
            AudioCommandPacket packet = new AudioCommandPacket(
                    AudioCommandType.PLAY,
                    KaraokeSession.GLOBAL_SESSION_ID,
                    currentPlaybackId,
                    streamTrack,
                    audienceTarget(),
                    snapshot.offset(),
                    Instant.now(),
                    "rejoin-sync",
                    Duration.ZERO,
                    AudioDeliveryMode.SERVER_STREAM);
            playbackClientIds.add(player.getUniqueId());
            messenger.send(player, packet);
            for (AudioStreamChunkPacket chunk : recentStreamChunks) {
                messenger.send(player, chunk);
            }
            streamListenerIds.add(player.getUniqueId());
            return;
        }
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId,
                snapshot.current().track(),
                audienceTarget(),
                snapshot.offset(),
                Instant.now().plus(SERVER_STREAM_START_DELAY),
                "rejoin-sync",
                Duration.ZERO,
                AudioDeliveryMode.URL);
        playbackClientIds.add(player.getUniqueId());
        messenger.send(player, packet);
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

    /** Sends one lyric-display action to a compatible client. */
    public boolean setLyrics(Player player, LyricsDisplayAction action) {
        if (player == null || !clientRegistry.supportsLyrics(player.getUniqueId())) {
            return false;
        }
        messenger.send(player, new AudioCommandPacket(
                AudioCommandType.LYRICS,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId == null ? "none" : currentPlaybackId,
                null,
                null,
                Duration.ZERO,
                Instant.now(),
                action.reason(),
                Duration.ZERO));
        return true;
    }

    public boolean toggleLyrics(Player player) {
        return setLyrics(player, LyricsDisplayAction.TOGGLE);
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

    private void startAudioRelay(KaraokeTrack track, AudioCommandPacket packet) {
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
                        if (streamPacket instanceof AudioStreamChunkPacket chunk) {
                            recordStreamChunk(chunk);
                        }
                        broadcastTo(streamListeners(), streamPacket);
                    }
                }),
                () -> playbackId.equals(currentPlaybackId)
        ).exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Server audio relay failed for " + track.title(), error);
            return null;
        });
    }

    private List<Player> streamListeners() {
        List<Player> listeners = new ArrayList<>(streamListenerIds.size());
        for (UUID listenerId : streamListenerIds) {
            Player listener = Bukkit.getPlayer(listenerId);
            if (listener != null) {
                listeners.add(listener);
            }
        }
        return listeners;
    }

    private void recordStreamChunk(AudioStreamChunkPacket chunk) {
        recentStreamChunks.addLast(chunk);
        // Base64 expands by 4/3, so this recovers the decoded PCM length closely
        // enough for a buffer cap.
        recentStreamChunkBytes += chunk.data().length() * 3L / 4L;
        long bytesPerSecond = (long) (chunk.sampleRate() * chunk.channels() * (chunk.bitsPerSample() / 8));
        long tailCapBytes = Math.max(MIN_REJOIN_TAIL_BYTES, bytesPerSecond * STREAM_REJOIN_TAIL.getSeconds());
        while (recentStreamChunkBytes > tailCapBytes && recentStreamChunks.size() > 1) {
            AudioStreamChunkPacket evicted = recentStreamChunks.removeFirst();
            recentStreamChunkBytes -= evicted.data().length() * 3L / 4L;
        }
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
            if (currentPlaybackId == null || !currentPlaybackId.equals(playbackId) || timerStarted) {
                return;
            }
            clientStartWaitAttempts++;
            boolean deadlineReached = clientStartWaitAttempts >= MAX_CLIENT_START_WAIT_ATTEMPTS;
            if (noClientCanConfirmStart() || deadlineReached) {
                String reason = deadlineReached
                        ? "client start deadline expired"
                        : "client start timeout with no active clients";
                startPlaybackTimer(track, playbackId, reason, false);
                return;
            }
            schedulePlaybackStartFallback(track, playbackId);
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

    /**
     * True when waiting longer cannot produce a playback confirmation: either no
     * playback client is online, or every one of them already reported a terminal
     * state for this track (a sub-second track can finish between two status
     * ticks, so its only report is STOPPED). Falling back to the metadata timer
     * keeps the queue moving instead of rescheduling the start check forever.
     */
    private boolean noClientCanConfirmStart() {
        List<UUID> activeClients = activePlaybackClientIds();
        if (activeClients.isEmpty()) {
            return true;
        }
        return activeClients.stream().allMatch(playerId -> {
            ClientPlaybackReport report = clientStates.get(playerId);
            return report != null && isTerminal(report.state());
        });
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
        return state == ClientPlaybackState.STOPPED
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
