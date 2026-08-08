package com.onthehill.chunkyfriends.scheduler;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSelectorEligibilityTest
{
    private static final long NOW = 1_000_000_000_000L;
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    @Test
    void isEligible_recentlySeenWithinWindow_returnsTrue()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final PlayerPregenState state = new PlayerPregenState(UUID.randomUUID());
        state.setLastSeenEpochMillis(NOW - (5 * 60 * 1000L));

        // Act
        final boolean result = PlayerSelector.isEligible(state, NOW, config);

        // Assert
        assertTrue(result);
    }

    @Test
    void isEligible_exactlyAtWindowBoundary_returnsFalse()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final long qualifyingWindowMillis = config.getQualifyingWindowHours() * ONE_HOUR_MILLIS;
        final PlayerPregenState state = new PlayerPregenState(UUID.randomUUID());
        state.setLastSeenEpochMillis(NOW - qualifyingWindowMillis);

        // Act
        final boolean result = PlayerSelector.isEligible(state, NOW, config);

        // Assert
        assertFalse(result);
    }

    @Test
    void isEligible_justInsideWindowBoundary_returnsTrue()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final long qualifyingWindowMillis = config.getQualifyingWindowHours() * ONE_HOUR_MILLIS;
        final PlayerPregenState state = new PlayerPregenState(UUID.randomUUID());
        state.setLastSeenEpochMillis(NOW - qualifyingWindowMillis + 1);

        // Act
        final boolean result = PlayerSelector.isEligible(state, NOW, config);

        // Assert
        assertTrue(result);
    }

    @Test
    void isEligible_neverDisconnectedDefaultLastSeen_returnsFalse()
    {
        // Arrange — documents the known pre-existing edge case from Spec 002's Design Decisions: a
        // freshly-constructed state defaults lastSeenEpochMillis to 0, which reads as "last seen at the Unix
        // epoch" under this check. This is not asserting the behavior is desirable, only that it is what
        // currently happens — revisit this test's expected value if a future spec fixes the edge case.
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final PlayerPregenState state = new PlayerPregenState(UUID.randomUUID());

        // Act
        final boolean result = PlayerSelector.isEligible(state, NOW, config);

        // Assert
        assertFalse(result);
    }
}
