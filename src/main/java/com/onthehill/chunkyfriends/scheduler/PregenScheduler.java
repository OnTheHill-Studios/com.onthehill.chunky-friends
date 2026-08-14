package com.onthehill.chunkyfriends.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onthehill.chunkyfriends.chunky.ChunkyGateway;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;
import com.onthehill.chunkyfriends.player.PlayerStateStore;

/**
 * Minecraft-facing orchestrator for the presence-gated chunk pregeneration scheduler.
 *
 * <p>Owns the in-memory active-job state, wires {@link ChunkyGateway}'s progress/completion callbacks to
 * completion detection, wires join/disconnect handling to presence-driven pause/resume, and calls into
 * {@link RingCurve}/{@link PlayerSelector} for the actual scheduling decisions.
 */
public final class PregenScheduler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PregenScheduler.class);

    private final ChunkyGateway _chunkyGateway;
    private final ChunkyFriendsConfig _config;
    private final SchedulerState _schedulerState = new SchedulerState();

    private MinecraftServer _server;
    private Map<UUID, PlayerPregenState> _playerStates = new HashMap<>();
    private int _onlinePlayerCount;
    private int _ticksSinceLastProgress;
    private int _ticksSinceLastPositionRefresh;
    private long _lastProgressLogEpochMillis;
    private boolean _initialized;

    /**
     * Constructs a scheduler bound to the given gateway and configuration.
     *
     * @param chunkyGateway The gateway used to start/pause/continue Chunky's generation tasks.
     * @param config The scheduling configuration.
     */
    public PregenScheduler(final ChunkyGateway chunkyGateway, final ChunkyFriendsConfig config)
    {
        _chunkyGateway = chunkyGateway;
        _config = config;
    }

    /**
     * Loads persisted player state, resolves the Chunky API connection, registers the tick/progress
     * callbacks this scheduler depends on, and — since the server always starts with nobody online yet —
     * immediately runs selection so a server that never sees a join/disconnect cycle before you check on it
     * still starts pregenerating rather than sitting idle waiting for a presence transition that may never
     * come. Safe to call more than once; only the first call registers callbacks.
     *
     * @param server The now-started server.
     * @implNote Chunky's progress/completion callbacks are invoked from whichever internal thread is doing
     *     generation work at the time — observed firing concurrently from several different threads
     *     (generic worker threads, C2ME's own worker pool, a dedicated per-world Chunky thread) in the same
     *     session. None of this scheduler's state (the player map, {@code SchedulerState}) is synchronized,
     *     so handling those callbacks directly on whatever thread calls them is a genuine race. Every callback
     *     is wrapped to marshal its actual handling onto the main server thread via
     *     {@link MinecraftServer#execute}, serializing it with everything else this class does (join/disconnect
     *     handling, the tick loop), which is also simply the correct place for anything that touches shared
     *     server-side state to run. Marshaling only serializes execution though — it does not impose an
     *     ordering between a progress event and a completion event that originated on two different Chunky
     *     threads. That was previously load-bearing: completion detection tried to key off whichever progress
     *     event had most recently set a {@code complete} flag, which silently assumed the terminal
     *     complete-flagged progress event always lands before the completion event. It doesn't for a
     *     near-instant task (e.g. a ring whose disk is already fully generated) — the whole task can finish
     *     faster than the progress-event cadence, so completion fires with no complete-flagged progress event
     *     ever recorded, misread as a manual cancel, and immediately restart the identical tier — observed in
     *     practice as the same tier being started repeatedly in a tight loop. {@link #onTaskDisappeared} no
     *     longer tries to distinguish a genuine completion from a manual cancel at all; both advance the ring
     *     tier, consistent with this class's existing tolerance elsewhere (see {@link #updatePosition}) for its
     *     own tier bookkeeping being an approximate heuristic rather than a source of truth.
     */
    public void init(final MinecraftServer server)
    {
        _server = server;
        _playerStates = PlayerStateStore.load(server);
        _chunkyGateway.init();
        if (!_initialized)
        {
            _initialized = true;
            _chunkyGateway.onGenerationProgress(event -> _server.execute(() -> onProgressEvent(event)));
            _chunkyGateway.onGenerationComplete(event -> _server.execute(() -> onTaskDisappeared(event)));
            ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        }
        selectAndStartNext();
    }

    /**
     * Persists current player state. Intended to be called from {@code ServerLifecycleEvents.SERVER_STOPPING}.
     *
     * @param server The stopping server.
     */
    public void shutdown(final MinecraftServer server)
    {
        PlayerStateStore.save(server, _playerStates);
    }

    /**
     * Resets every tracked player's ring tier back to {@code 0}. Call this whenever {@code ringCount},
     * {@code maxRadiusChunks}, or {@code curveExponent} changes.
     *
     * @implNote A player sitting at {@code currentRingTier == ringCount} is permanently excluded from
     *     selection by {@link PlayerSelector} — there is no "tier N+1" to grow into. If {@code maxRadiusChunks}
     *     grows (or {@code ringCount} shrinks) after that point, that exclusion becomes wrong: there is
     *     real, larger coverage the player should now grow into, but nothing will ever select them again to
     *     do it. Rather than try to translate an old tier number into an equivalent one under a different
     *     curve — not generally well-defined, especially across a {@code curveExponent} change — this just
     *     restarts everyone from {@code 0}. That sounds wasteful but isn't: every job requests a full disk at
     *     the target tier's radius, and Chunky skips already-generated chunks on its own, so re-climbing
     *     through previously-completed tiers is fast; only genuinely new area does real work.
     *
     *     <p>If the server is currently empty and nothing is active, this also re-runs selection immediately
     *     — mirroring {@link #init} and the empty-server branch of {@link #onPlayerDisconnect}. Without this,
     *     a config change applied while nobody is online would reset everyone's progress but then just sit
     *     idle, waiting for a join/disconnect cycle that might not come for hours.
     */
    public void resetAllProgress()
    {
        for (final PlayerPregenState state : _playerStates.values())
        {
            state.setCurrentRingTier(0);
        }
        persist();
        LOGGER.info("Reset all tracked players' pregeneration ring tier to 0 following a scheduling curve configuration change.");
        if (_onlinePlayerCount == 0 && _schedulerState._activePlayerUuid == null)
        {
            selectAndStartNext();
        }
    }

    /**
     * Handles a player joining the server: pauses any active job if the server was previously empty, and
     * updates the joining player's last-known position.
     *
     * @param player The joining player.
     */
    public void onPlayerJoin(final ServerPlayer player)
    {
        final boolean wasEmpty = _onlinePlayerCount == 0;
        _onlinePlayerCount++;
        final PlayerPregenState state = getOrCreateState(player.getUUID());
        updatePosition(state, player);
        if (wasEmpty && _schedulerState._activePlayerUuid != null)
        {
            final PlayerPregenState activeState = _playerStates.get(_schedulerState._activePlayerUuid);
            LOGGER.info("Pausing pregeneration job for {} in {} — {} joined and the server is no longer empty.",
                    describe(activeState), _schedulerState._activeWorld, player.getGameProfile().name());
            _chunkyGateway.pauseTask(_schedulerState._activeWorld);
            _schedulerState._presencePaused = true;
        }
        persist();
    }

    /**
     * Handles a player disconnecting from the server: records their last-seen time and position, and either
     * resumes a presence-paused job or starts fresh selection once the server becomes empty.
     *
     * @param player The disconnecting player.
     */
    public void onPlayerDisconnect(final ServerPlayer player)
    {
        final PlayerPregenState state = getOrCreateState(player.getUUID());
        state.setLastSeenEpochMillis(System.currentTimeMillis());
        updatePosition(state, player);
        _onlinePlayerCount = Math.max(0, _onlinePlayerCount - 1);
        if (_onlinePlayerCount == 0)
        {
            if (_schedulerState._activePlayerUuid != null && _schedulerState._presencePaused)
            {
                final PlayerPregenState activeState = _playerStates.get(_schedulerState._activePlayerUuid);
                LOGGER.info("Resuming pregeneration job for {} in {} — server is now empty.", describe(activeState), _schedulerState._activeWorld);
                _chunkyGateway.continueTask(_schedulerState._activeWorld);
                _schedulerState._presencePaused = false;
            }
            else if (_schedulerState._activePlayerUuid == null)
            {
                selectAndStartNext();
            }
        }
        persist();
    }

    /**
     * Gets a snapshot of the single currently-active pregeneration job, if any.
     *
     * @return A snapshot of the active job, or {@link Optional#empty()} if no job is currently active.
     */
    public Optional<ActiveJobSnapshot> activeJobSnapshot()
    {
        if (_schedulerState._activePlayerUuid == null)
        {
            return Optional.empty();
        }
        final PlayerPregenState state = _playerStates.get(_schedulerState._activePlayerUuid);
        final String displayName = state != null ? state.getLastKnownName() : null;
        final int ringTier = state != null ? state.getCurrentRingTier() + 1 : 0;
        return Optional.of(new ActiveJobSnapshot(
                _schedulerState._activePlayerUuid,
                displayName,
                _schedulerState._activeWorld,
                ringTier,
                _config.getRingCount(),
                _schedulerState._lastProgressPercent,
                _schedulerState._lastProgressChunks,
                _schedulerState._lastProgressRate,
                _schedulerState._presencePaused,
                _schedulerState._lastProgressEventEpochMillis));
    }

    /**
     * Gets every tracked player currently eligible — i.e. not evicted by the qualifying window — independent
     * of whether their ring coverage is already complete.
     *
     * @param nowEpochMillis Current time, in epoch millis.
     * @return Eligible players, sorted by last-known display name (nulls last, case-insensitive) for stable,
     *     readable command output.
     */
    public List<PlayerPregenState> eligiblePlayers(final long nowEpochMillis)
    {
        final List<PlayerPregenState> eligible = new ArrayList<>();
        for (final PlayerPregenState state : _playerStates.values())
        {
            if (PlayerSelector.isEligible(state, nowEpochMillis, _config))
            {
                eligible.add(state);
            }
        }
        eligible.sort(Comparator.comparing(PlayerPregenState::getLastKnownName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return eligible;
    }

    private void onServerTick(final MinecraftServer server)
    {
        if (_schedulerState._activePlayerUuid != null && !_schedulerState._presencePaused)
        {
            _ticksSinceLastProgress++;
            if (_ticksSinceLastProgress == _config.getStallTimeoutTicks())
            {
                LOGGER.warn("Pregeneration job for {} in {} has received no progress event in {} ticks; it may be stalled.",
                        describe(_playerStates.get(_schedulerState._activePlayerUuid)), _schedulerState._activeWorld, _config.getStallTimeoutTicks());
            }
        }
        if (_onlinePlayerCount > 0)
        {
            _ticksSinceLastPositionRefresh++;
            if (_ticksSinceLastPositionRefresh >= _config.getCheckIntervalTicks())
            {
                _ticksSinceLastPositionRefresh = 0;
                refreshOnlinePlayerPositions(server);
            }
        }
    }

    private void refreshOnlinePlayerPositions(final MinecraftServer server)
    {
        for (final ServerPlayer player : server.getPlayerList().getPlayers())
        {
            updatePosition(getOrCreateState(player.getUUID()), player);
        }
        persist();
    }

    private void onProgressEvent(final GenerationProgressEvent event)
    {
        if (!event.world().equals(_schedulerState._activeWorld))
        {
            return;
        }
        _ticksSinceLastProgress = 0;
        _schedulerState._lastProgressPercent = event.progress();
        _schedulerState._lastProgressChunks = event.chunks();
        _schedulerState._lastProgressRate = event.rate();
        _schedulerState._lastProgressEventEpochMillis = System.currentTimeMillis();

        final long now = System.currentTimeMillis();
        if (now - _lastProgressLogEpochMillis >= _config.getProgressLogIntervalSeconds() * 1000L)
        {
            _lastProgressLogEpochMillis = now;
            final PlayerPregenState activeState = _playerStates.get(_schedulerState._activePlayerUuid);
            LOGGER.info("Pregeneration progress for {} in {}: {}% complete, {} chunks, {} chunks/s.",
                    describe(activeState), event.world(), event.progress(), event.chunks(), event.rate());
        }
    }

    private void onTaskDisappeared(final GenerationCompleteEvent event)
    {
        if (_schedulerState._activePlayerUuid == null || !event.world().equals(_schedulerState._activeWorld))
        {
            return;
        }
        if (_schedulerState._presencePaused)
        {
            return;
        }
        final PlayerPregenState state = _playerStates.get(_schedulerState._activePlayerUuid);
        if (state != null)
        {
            state.setCurrentRingTier(state.getCurrentRingTier() + 1);
            LOGGER.info("Pregeneration job for {} in {} completed — now at ring tier {} of {}.",
                    describe(state), _schedulerState._activeWorld, state.getCurrentRingTier(), _config.getRingCount());
        }
        clearActiveJob();
        persist();
        selectAndStartNext();
    }

    private void selectAndStartNext()
    {
        final Optional<PlayerPregenState> next = PlayerSelector.selectNext(_playerStates.values(), System.currentTimeMillis(), _config);
        if (next.isEmpty())
        {
            return;
        }
        final PlayerPregenState state = next.get();
        final int nextTier = state.getCurrentRingTier() + 1;
        final int radius = RingCurve.radiusForTier(nextTier, _config.getRingCount(), _config.getMaxRadiusChunks(), _config.getCurveExponent());
        final boolean started = _chunkyGateway.startTask(state.getLastKnownDimension(), state.getLastKnownX(), state.getLastKnownZ(), radius);
        if (!started)
        {
            LOGGER.warn("Chunky refused to start a pregeneration task for {} in {}.", describe(state), state.getLastKnownDimension());
            return;
        }
        _schedulerState._activePlayerUuid = state.getPlayerUuid();
        _schedulerState._activeWorld = state.getLastKnownDimension();
        _schedulerState._presencePaused = false;
        _ticksSinceLastProgress = 0;
        _lastProgressLogEpochMillis = 0L;
        state.setLastServicedEpochMillis(System.currentTimeMillis());
        persist();
        LOGGER.info("Started tier {} pregeneration job for {} in {} — radius {} chunks.",
                nextTier, describe(state), state.getLastKnownDimension(), radius);
    }

    private static String describe(final PlayerPregenState state)
    {
        if (state == null)
        {
            return "an unknown player";
        }
        final String name = state.getLastKnownName();
        return name != null ? name + " (" + state.getPlayerUuid() + ")" : "player " + state.getPlayerUuid();
    }

    private void clearActiveJob()
    {
        _schedulerState._activePlayerUuid = null;
        _schedulerState._activeWorld = null;
        _schedulerState._presencePaused = false;
        _schedulerState._lastProgressPercent = 0;
        _schedulerState._lastProgressChunks = 0;
        _schedulerState._lastProgressRate = 0;
        _schedulerState._lastProgressEventEpochMillis = 0;
    }

    private PlayerPregenState getOrCreateState(final UUID playerUuid)
    {
        return _playerStates.computeIfAbsent(playerUuid, PlayerPregenState::new);
    }

    /**
     * Updates a player's last-known name/dimension/position, invalidating their accumulated ring tier if
     * either the dimension or the coordinates actually changed.
     *
     * @implNote Every ring tier's job is centered on wherever {@code lastKnownX}/{@code lastKnownZ} happened
     *     to be when that specific job fired — there is no independently-tracked "center" a player's rings
     *     are relative to. If the player moves between service cycles, {@code currentRingTier} stops meaning
     *     anything: it would be some rings around an old position and some around a new one, not N rings of
     *     contiguous coverage around wherever they are now. Resetting to {@code 0} on any change, however
     *     small, keeps that guarantee simple and always true, at the cost of some redundant re-climbing for a
     *     player who wanders during a session — a cost {@link ChunkyGateway#startTask} already made cheap by
     *     design (see its own {@code @implNote}): every job re-requests a full disk, and Chunky's own
     *     chunk-level dedup means re-covering ground it already generated (from this player's own earlier
     *     rings, or from any other player's overlapping full-disk request) is nearly free. This mod's own
     *     ring-tier bookkeeping is purely a scheduling heuristic — Chunky's on-disk state is the actual
     *     source of truth for what's generated, and is immune to this bookkeeping ever being "wrong."
     */
    private void updatePosition(final PlayerPregenState state, final ServerPlayer player)
    {
        state.setLastKnownName(player.getGameProfile().name());
        final String dimension = player.level().dimension().identifier().toString();
        final double x = player.getX();
        final double z = player.getZ();
        final boolean moved = !dimension.equals(state.getLastKnownDimension()) || x != state.getLastKnownX() || z != state.getLastKnownZ();
        state.setLastKnownDimension(dimension);
        state.setLastKnownX(x);
        state.setLastKnownZ(z);
        if (moved && state.getCurrentRingTier() != 0)
        {
            LOGGER.info("Invalidating pregeneration progress for {} — position changed; was at ring tier {}, now back to 0.",
                    describe(state), state.getCurrentRingTier());
            state.setCurrentRingTier(0);
        }
    }

    private void persist()
    {
        if (_server != null)
        {
            PlayerStateStore.save(_server, _playerStates);
        }
    }

    /**
     * Small in-memory (not persisted) holder for the currently active pregeneration job, if any.
     */
    private static final class SchedulerState
    {
        private UUID _activePlayerUuid;
        private String _activeWorld;
        private boolean _presencePaused;
        private double _lastProgressPercent;
        private long _lastProgressChunks;
        private double _lastProgressRate;
        private long _lastProgressEventEpochMillis;
    }
}
