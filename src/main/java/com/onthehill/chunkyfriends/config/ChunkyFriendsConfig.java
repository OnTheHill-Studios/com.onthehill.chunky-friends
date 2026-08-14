package com.onthehill.chunkyfriends.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GSON-backed configuration for the presence-gated chunk pregeneration scheduler.
 */
public final class ChunkyFriendsConfig
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkyFriendsConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Ceiling radius, in chunks, that the outermost ring tier grows to.
     */
    private int _maxRadiusChunks = 100;

    /**
     * Number of ring tiers a player's coverage progresses through before being fully covered.
     */
    private int _ringCount = 25;

    /**
     * Exponent of the ease-in curve mapping ring tier to radius. {@code 1.0} is linear, {@code 2.0} is quadratic.
     */
    private double _curveExponent = 2.0;

    /**
     * Number of hours since a player was last seen before they stop qualifying for scheduling.
     */
    private int _qualifyingWindowHours = 24;

    /**
     * Number of server ticks with no generation progress event before a stalled job is logged as a warning.
     */
    private int _stallTimeoutTicks = 12000;

    /**
     * Number of server ticks between periodic refreshes of online players' last-known positions.
     */
    private int _checkIntervalTicks = 200;

    /**
     * Number of seconds between throttled, player-attributed pregeneration progress log lines.
     */
    private int _progressLogIntervalSeconds = 30;

    /**
     * Path this configuration was loaded from/should be saved back to. Not serialized.
     */
    private transient Path _configFilePath;

    /**
     * Gets the ceiling radius, in chunks, that the outermost ring tier grows to.
     *
     * @return The maximum radius, in chunks.
     */
    public int getMaxRadiusChunks()
    {
        return _maxRadiusChunks;
    }

    /**
     * Gets the number of ring tiers a player's coverage progresses through before being fully covered.
     *
     * @return The ring tier count.
     */
    public int getRingCount()
    {
        return _ringCount;
    }

    /**
     * Gets the exponent of the ease-in curve mapping ring tier to radius.
     *
     * @return The curve exponent.
     */
    public double getCurveExponent()
    {
        return _curveExponent;
    }

    /**
     * Gets the number of hours since a player was last seen before they stop qualifying for scheduling.
     *
     * @return The qualifying window, in hours.
     */
    public int getQualifyingWindowHours()
    {
        return _qualifyingWindowHours;
    }

    /**
     * Gets the number of server ticks with no generation progress event before a stalled job is logged as a warning.
     *
     * @return The stall timeout, in ticks.
     */
    public int getStallTimeoutTicks()
    {
        return _stallTimeoutTicks;
    }

    /**
     * Gets the number of server ticks between periodic refreshes of online players' last-known positions.
     *
     * @return The check interval, in ticks.
     */
    public int getCheckIntervalTicks()
    {
        return _checkIntervalTicks;
    }

    /**
     * Gets the number of seconds between throttled, player-attributed pregeneration progress log lines.
     *
     * @return The progress log interval, in seconds.
     */
    public int getProgressLogIntervalSeconds()
    {
        return _progressLogIntervalSeconds;
    }

    /**
     * Sets the number of ring tiers a player's coverage progresses through before being fully covered.
     *
     * @param ringCount The ring tier count.
     */
    public void setRingCount(final int ringCount)
    {
        _ringCount = ringCount;
    }

    /**
     * Sets the ceiling radius, in chunks, that the outermost ring tier grows to.
     *
     * @param maxRadiusChunks The maximum radius, in chunks.
     */
    public void setMaxRadiusChunks(final int maxRadiusChunks)
    {
        _maxRadiusChunks = maxRadiusChunks;
    }

    /**
     * Sets the exponent of the ease-in curve mapping ring tier to radius.
     *
     * @param curveExponent The curve exponent.
     */
    public void setCurveExponent(final double curveExponent)
    {
        _curveExponent = curveExponent;
    }

    /**
     * Loads the configuration from the given file, writing a default file at that path first if it does not exist.
     *
     * @param configFilePath Path to the config file, e.g. {@code config/chunky-friends.json}.
     * @return The loaded configuration, or a default instance if the file could not be read.
     */
    public static ChunkyFriendsConfig load(final Path configFilePath)
    {
        final ChunkyFriendsConfig config = loadInternal(configFilePath);
        config._configFilePath = configFilePath;
        return config;
    }

    private static ChunkyFriendsConfig loadInternal(final Path configFilePath)
    {
        if (!Files.exists(configFilePath))
        {
            final ChunkyFriendsConfig defaults = new ChunkyFriendsConfig();
            defaults.save(configFilePath);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(configFilePath))
        {
            final ChunkyFriendsConfig loaded = GSON.fromJson(reader, ChunkyFriendsConfig.class);
            return loaded != null ? loaded : new ChunkyFriendsConfig();
        }
        catch (final IOException exception)
        {
            LOGGER.error("Failed to read config file at {}, falling back to defaults.", configFilePath, exception);
            return new ChunkyFriendsConfig();
        }
    }

    /**
     * Writes this configuration back to the file it was loaded from.
     *
     * @throws IllegalStateException If this configuration was not obtained via {@link #load(Path)}.
     */
    public void save()
    {
        if (_configFilePath == null)
        {
            throw new IllegalStateException("This config was not loaded from a file; call save(Path) instead.");
        }
        save(_configFilePath);
    }

    /**
     * Writes this configuration to the given file, creating parent directories as needed.
     *
     * @param configFilePath Path to the config file, e.g. {@code config/chunky-friends.json}.
     */
    public void save(final Path configFilePath)
    {
        try
        {
            if (configFilePath.getParent() != null)
            {
                Files.createDirectories(configFilePath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configFilePath))
            {
                GSON.toJson(this, writer);
            }
        }
        catch (final IOException exception)
        {
            LOGGER.error("Failed to write config file at {}.", configFilePath, exception);
        }
    }
}
