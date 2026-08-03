package com.onthehill.chunkyfriends.player;

import java.util.UUID;

/**
 * Per-player persisted pregeneration progress and last-known presence.
 */
public final class PlayerPregenState
{
    /**
     * The player's unique identifier.
     */
    private UUID _playerUuid;

    /**
     * Epoch millis at which this player was last seen disconnecting. An online player leaves this value
     * unchanged for the duration of their session.
     */
    private long _lastSeenEpochMillis;

    /**
     * This player's last-known display name, resolved from their game profile at join/disconnect/refresh.
     * Not authoritative identity (only {@link #_playerUuid} is) — for log and command readability only.
     */
    private String _lastKnownName;

    /**
     * Identifier of the dimension this player was last known to be in, e.g. {@code "minecraft:overworld"}.
     */
    private String _lastKnownDimension;

    /**
     * Block x coordinate this player was last known to be at.
     */
    private double _lastKnownX;

    /**
     * Block z coordinate this player was last known to be at.
     */
    private double _lastKnownZ;

    /**
     * Ring tier this player's pregeneration coverage has completed up to. {@code 0} means no job has ever
     * been fired for this player yet.
     */
    private int _currentRingTier;

    /**
     * Epoch millis at which a job was last selected for this player. Used to break selection ties.
     */
    private long _lastServicedEpochMillis;

    /**
     * Constructs a new, never-serviced state record for a player.
     *
     * @param playerUuid The player's unique identifier.
     */
    public PlayerPregenState(final UUID playerUuid)
    {
        _playerUuid = playerUuid;
        _lastServicedEpochMillis = Long.MIN_VALUE;
    }

    /**
     * Gets the player's unique identifier.
     *
     * @return The player UUID.
     */
    public UUID getPlayerUuid()
    {
        return _playerUuid;
    }

    /**
     * Gets the epoch millis at which this player was last seen disconnecting.
     *
     * @return Epoch millis of the last disconnect, or {@code 0} if never disconnected.
     */
    public long getLastSeenEpochMillis()
    {
        return _lastSeenEpochMillis;
    }

    /**
     * Sets the epoch millis at which this player was last seen disconnecting.
     *
     * @param lastSeenEpochMillis Epoch millis of the disconnect.
     */
    public void setLastSeenEpochMillis(final long lastSeenEpochMillis)
    {
        _lastSeenEpochMillis = lastSeenEpochMillis;
    }

    /**
     * Gets this player's last-known display name.
     *
     * @return The last-known display name, or {@code null} if never resolved.
     */
    public String getLastKnownName()
    {
        return _lastKnownName;
    }

    /**
     * Sets this player's last-known display name.
     *
     * @param lastKnownName The last-known display name.
     */
    public void setLastKnownName(final String lastKnownName)
    {
        _lastKnownName = lastKnownName;
    }

    /**
     * Gets the identifier of the dimension this player was last known to be in.
     *
     * @return The dimension identifier, e.g. {@code "minecraft:overworld"}.
     */
    public String getLastKnownDimension()
    {
        return _lastKnownDimension;
    }

    /**
     * Sets the identifier of the dimension this player was last known to be in.
     *
     * @param lastKnownDimension The dimension identifier, e.g. {@code "minecraft:overworld"}.
     */
    public void setLastKnownDimension(final String lastKnownDimension)
    {
        _lastKnownDimension = lastKnownDimension;
    }

    /**
     * Gets the block x coordinate this player was last known to be at.
     *
     * @return The last-known x coordinate.
     */
    public double getLastKnownX()
    {
        return _lastKnownX;
    }

    /**
     * Sets the block x coordinate this player was last known to be at.
     *
     * @param lastKnownX The last-known x coordinate.
     */
    public void setLastKnownX(final double lastKnownX)
    {
        _lastKnownX = lastKnownX;
    }

    /**
     * Gets the block z coordinate this player was last known to be at.
     *
     * @return The last-known z coordinate.
     */
    public double getLastKnownZ()
    {
        return _lastKnownZ;
    }

    /**
     * Sets the block z coordinate this player was last known to be at.
     *
     * @param lastKnownZ The last-known z coordinate.
     */
    public void setLastKnownZ(final double lastKnownZ)
    {
        _lastKnownZ = lastKnownZ;
    }

    /**
     * Gets the ring tier this player's pregeneration coverage has completed up to.
     *
     * @return The current ring tier, as a plain integer count starting at {@code 0}.
     */
    public int getCurrentRingTier()
    {
        return _currentRingTier;
    }

    /**
     * Sets the ring tier this player's pregeneration coverage has completed up to.
     *
     * @param currentRingTier The current ring tier, as a plain integer count.
     */
    public void setCurrentRingTier(final int currentRingTier)
    {
        _currentRingTier = currentRingTier;
    }

    /**
     * Gets the epoch millis at which a job was last selected for this player.
     *
     * @return Epoch millis of the last service, or {@link Long#MIN_VALUE} if never serviced.
     */
    public long getLastServicedEpochMillis()
    {
        return _lastServicedEpochMillis;
    }

    /**
     * Sets the epoch millis at which a job was last selected for this player.
     *
     * @param lastServicedEpochMillis Epoch millis of the service.
     */
    public void setLastServicedEpochMillis(final long lastServicedEpochMillis)
    {
        _lastServicedEpochMillis = lastServicedEpochMillis;
    }
}
