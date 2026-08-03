package com.onthehill.chunkyfriends.scheduler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSelectorTest
{
    private static final long NOW = 1_000_000_000_000L;
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    @Test
    void selectNext_multipleQualifyingAtDifferentTiers_picksLowestTier()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final PlayerPregenState lowTier = qualifyingPlayer(3, NOW - 1000);
        final PlayerPregenState highTier = qualifyingPlayer(7, NOW - 1000);

        // Act
        final Optional<PlayerPregenState> result = PlayerSelector.selectNext(List.of(highTier, lowTier), NOW, config);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(lowTier.getPlayerUuid(), result.get().getPlayerUuid());
    }

    @Test
    void selectNext_tiedLowestTier_picksOldestLastServiced()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final PlayerPregenState recentlyServiced = qualifyingPlayer(3, NOW - 1000);
        recentlyServiced.setLastServicedEpochMillis(NOW - 1000);
        final PlayerPregenState neverServiced = qualifyingPlayer(3, NOW - 1000);

        // Act
        final Optional<PlayerPregenState> result = PlayerSelector.selectNext(List.of(recentlyServiced, neverServiced), NOW, config);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(neverServiced.getPlayerUuid(), result.get().getPlayerUuid());
    }

    @Test
    void selectNext_allPlayersAtMaxTier_returnsEmpty()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final PlayerPregenState maxedOut = qualifyingPlayer(config.getRingCount(), NOW - 1000);

        // Act
        final Optional<PlayerPregenState> result = PlayerSelector.selectNext(List.of(maxedOut), NOW, config);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void selectNext_noQualifyingPlayers_returnsEmpty()
    {
        // Arrange
        final ChunkyFriendsConfig config = new ChunkyFriendsConfig();
        final long outsideWindow = NOW - (config.getQualifyingWindowHours() + 1) * ONE_HOUR_MILLIS;
        final PlayerPregenState stalePlayer = qualifyingPlayer(3, outsideWindow);

        // Act
        final Optional<PlayerPregenState> result = PlayerSelector.selectNext(List.of(stalePlayer), NOW, config);

        // Assert
        assertTrue(result.isEmpty());
    }

    private static PlayerPregenState qualifyingPlayer(final int ringTier, final long lastSeenEpochMillis)
    {
        final PlayerPregenState state = new PlayerPregenState(UUID.randomUUID());
        state.setCurrentRingTier(ringTier);
        state.setLastSeenEpochMillis(lastSeenEpochMillis);
        return state;
    }
}
