package com.onthehill.chunkyfriends.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves per-player pregeneration state to {@code <world>/data/chunky-friends_state.json}.
 */
public final class PlayerStateStore
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStateStore.class);
    private static final String STATE_FILE_NAME = "chunky-friends_state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STATE_MAP_TYPE = new TypeToken<Map<UUID, PlayerPregenState>>() { }.getType();

    private PlayerStateStore()
    {
    }

    /**
     * Loads the persisted player pregeneration state for the given server's world.
     *
     * @param server The server whose world's data directory the state file lives under.
     * @return The loaded state map, or an empty map if no state file exists yet or it could not be read.
     */
    public static Map<UUID, PlayerPregenState> load(final MinecraftServer server)
    {
        return load(resolvePath(server));
    }

    /**
     * Loads the persisted player pregeneration state from the given file.
     *
     * @param stateFilePath Path to the state file.
     * @return The loaded state map, or an empty map if the file does not exist or could not be read.
     */
    public static Map<UUID, PlayerPregenState> load(final Path stateFilePath)
    {
        if (!Files.exists(stateFilePath))
        {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(stateFilePath))
        {
            final Map<UUID, PlayerPregenState> loaded = GSON.fromJson(reader, STATE_MAP_TYPE);
            return loaded != null ? loaded : new HashMap<>();
        }
        catch (final IOException exception)
        {
            LOGGER.error("Failed to read player pregeneration state at {}, starting with an empty state.", stateFilePath, exception);
            return new HashMap<>();
        }
    }

    /**
     * Persists the given player pregeneration state for the given server's world.
     *
     * @param server The server whose world's data directory the state file lives under.
     * @param states The state map to persist.
     */
    public static void save(final MinecraftServer server, final Map<UUID, PlayerPregenState> states)
    {
        save(resolvePath(server), states);
    }

    /**
     * Persists the given player pregeneration state to the given file, creating parent directories as needed.
     *
     * @param stateFilePath Path to the state file.
     * @param states The state map to persist.
     */
    public static void save(final Path stateFilePath, final Map<UUID, PlayerPregenState> states)
    {
        try
        {
            if (stateFilePath.getParent() != null)
            {
                Files.createDirectories(stateFilePath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(stateFilePath))
            {
                GSON.toJson(states, STATE_MAP_TYPE, writer);
            }
        }
        catch (final IOException exception)
        {
            LOGGER.error("Failed to write player pregeneration state to {}.", stateFilePath, exception);
        }
    }

    private static Path resolvePath(final MinecraftServer server)
    {
        return server.getWorldPath(LevelResource.DATA).resolve(STATE_FILE_NAME);
    }
}
