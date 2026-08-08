package com.onthehill.chunkyfriends.scheduler;

import java.util.Collection;
import java.util.Optional;

import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;

/**
 * Pure utility implementing the qualifying-pool filter and min-tier-first selection with tie-break.
 */
public final class PlayerSelector
{
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private PlayerSelector()
    {
    }

    /**
     * Selects the next player to fire a pregeneration job for, from among the qualifying pool.
     *
     * @param states All known players' persisted pregeneration state.
     * @param nowEpochMillis Current time, in epoch millis.
     * @param config Scheduling configuration, supplying the qualifying window and ring count.
     * @return The selected player's state, or {@link Optional#empty()} if no player qualifies or every
     *     qualifying player is already at the maximum ring tier — both valid, expected steady states.
     * @implNote Selects arg-min tier(p) among qualifying players with tier(p) &lt; ringCount, ties broken by
     *     oldest lastServicedEpochMillis (never-serviced treated as -infinity, i.e. serviced first).
     */
    public static Optional<PlayerPregenState> selectNext(final Collection<PlayerPregenState> states, final long nowEpochMillis, final ChunkyFriendsConfig config)
    {
        PlayerPregenState best = null;
        for (final PlayerPregenState state : states)
        {
            if (!isEligible(state, nowEpochMillis, config))
            {
                continue;
            }
            if (state.getCurrentRingTier() >= config.getRingCount())
            {
                continue;
            }
            if (best == null || isBetterCandidate(state, best))
            {
                best = state;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Checks whether a player is "eligible" — i.e. not evicted by the qualifying window — independent of
     * whether their ring coverage is already complete. This is a strict subset of {@link #selectNext}'s
     * candidacy check: a fully-covered player who was seen recently is still eligible in this sense, they are
     * just no longer a selection candidate (see {@code PregenScheduler.eligiblePlayers}).
     *
     * @param state The player state to check.
     * @param nowEpochMillis Current time, in epoch millis.
     * @param config Scheduling configuration, supplying the qualifying window.
     * @return {@code true} if the player was seen within the qualifying window.
     */
    public static boolean isEligible(final PlayerPregenState state, final long nowEpochMillis, final ChunkyFriendsConfig config)
    {
        final long qualifyingWindowMillis = config.getQualifyingWindowHours() * MILLIS_PER_HOUR;
        return nowEpochMillis - state.getLastSeenEpochMillis() < qualifyingWindowMillis;
    }

    private static boolean isBetterCandidate(final PlayerPregenState candidate, final PlayerPregenState current)
    {
        if (candidate.getCurrentRingTier() != current.getCurrentRingTier())
        {
            return candidate.getCurrentRingTier() < current.getCurrentRingTier();
        }
        return candidate.getLastServicedEpochMillis() < current.getLastServicedEpochMillis();
    }
}
