package com.onthehill.chunkyfriends.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onthehill.chunkyfriends.ChunkyFriends;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;
import com.onthehill.chunkyfriends.scheduler.ActiveJobSnapshot;
import com.onthehill.chunkyfriends.scheduler.PregenScheduler;
import com.onthehill.chunkyfriends.scheduler.TerrainPreviewSampler;

/**
 * Registers the network protocol backing the client-side configuration GUI, and applies changes to the
 * live scheduler configuration on the server's behalf — shared by both that protocol and the equivalent
 * {@code /chunkyfriends config} server command, so the GUI's buttons and the command line always agree on
 * what values are valid and how they're applied.
 *
 * @implNote {@link ConfigUpdatePayload} is re-validated here exactly as if it came from an untrusted source,
 *     because it does — a client is free to send out-of-range values regardless of what its own GUI permits.
 */
public final class ConfigNetworking
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigNetworking.class);

    /**
     * Permission required to read or change the pregeneration scheduler's configuration, from either the
     * client GUI or the {@code /chunkyfriends config} command. Falls back to vanilla op level
     * {@link PermissionLevel#GAMEMASTERS} when no permission mod is installed.
     */
    public static final Identifier CONFIG_PERMISSION = Identifier.fromNamespaceAndPath(ChunkyFriends.MOD_ID, "config");

    /**
     * Smallest ring tier count accepted from either the GUI or the command.
     */
    public static final int MIN_RING_COUNT = 1;

    /**
     * Largest ring tier count accepted from either the GUI or the command.
     */
    public static final int MAX_RING_COUNT = 64;

    /**
     * Smallest maximum radius, in chunks, accepted from either the GUI or the command.
     */
    public static final int MIN_RADIUS_CHUNKS = 1;

    /**
     * Largest maximum radius, in chunks, accepted from either the GUI or the command.
     */
    public static final int MAX_RADIUS_CHUNKS = 100_000;

    /**
     * Number of blocks per chunk edge — the conversion factor for the {@code 'b'} unit suffix
     * {@link #parseRadiusChunks} accepts, and for displaying a chunk value's block equivalent. See
     * {@code ChunkyGateway}'s own {@code BLOCKS_PER_CHUNK} for how this same factor gets applied again, in
     * the opposite direction, right before a radius reaches Chunky.
     */
    public static final int BLOCKS_PER_CHUNK = 16;

    private static final double LINEAR_CURVE_EXPONENT = 1.0;
    private static final double QUADRATIC_CURVE_EXPONENT = 2.0;

    private ConfigNetworking()
    {
    }

    /**
     * Parses a max-radius input, accepting a bare integer as <strong>blocks</strong> or the same integer
     * followed by {@code 'c'} to specify chunks instead, converted to blocks by multiplying by
     * {@value #BLOCKS_PER_CHUNK}. A trailing {@code 'b'} is also accepted as an explicit-but-redundant
     * "blocks".
     *
     * @param input The raw text to parse, e.g. {@code "8000"} (8000 blocks) or {@code "500c"} (500 chunks,
     *     i.e. 8000 blocks).
     * @return The parsed value, converted to chunks (this mod's internal/persisted unit — see
     *     {@code ChunkyFriendsConfig.getMaxRadiusChunks}), or empty if the input isn't a recognized number
     *     with or without a unit suffix.
     * @implNote Bare-number-means-blocks is deliberately chosen to match Chunky's own {@code /chunky radius}
     *     and {@code /chunky start} commands exactly (see {@code Input.tryDoubleSuffixed} and
     *     {@code Selection}'s {@code radiusChunksX = ceil(radiusX / 16f)} in Chunky's source) — an admin who
     *     already knows Chunky's convention should not have to learn a different one here.
     */
    public static OptionalInt parseRadiusChunks(final String input)
    {
        if (input == null || input.isEmpty())
        {
            return OptionalInt.empty();
        }
        final String trimmed = input.trim();
        if (trimmed.isEmpty())
        {
            return OptionalInt.empty();
        }
        final char last = Character.toLowerCase(trimmed.charAt(trimmed.length() - 1));
        final boolean isChunks = last == 'c';
        final boolean hasUnitSuffix = isChunks || last == 'b';
        final String numericPart = hasUnitSuffix ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        try
        {
            final int value = Integer.parseInt(numericPart);
            return OptionalInt.of(isChunks ? value : (int) Math.round(value / (double) BLOCKS_PER_CHUNK));
        }
        catch (final NumberFormatException exception)
        {
            return OptionalInt.empty();
        }
    }

    /**
     * Registers this protocol's payload types — the shape of the packets, not what handles them. Must run
     * unconditionally at mod init on every physical install (client and server alike), regardless of whether
     * a server ever actually starts.
     *
     * @implNote This was previously bundled into {@link #registerServerReceivers}, which is deferred until a
     *     real server starts — that broke a pure client launch entirely, since {@code ClientPlayNetworking}
     *     tries to register a receiver for {@link ConfigStatePayload} at client mod-init time, before any
     *     server could possibly have started, and errors loudly if the type it's registering for was never
     *     declared. Payload <em>types</em> must be registered independently of, and earlier than, anything
     *     that depends on this mod's live configuration existing.
     */
    public static void registerPayloadTypes()
    {
        PayloadTypeRegistry.serverboundPlay().register(ConfigRequestPayload.TYPE, ConfigRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigStatePayload.TYPE, ConfigStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenConfigGuiPayload.TYPE, OpenConfigGuiPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPreviewRequestPayload.TYPE, MapPreviewRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPreviewResponsePayload.TYPE, MapPreviewResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StatusRequestPayload.TYPE, StatusRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatusResponsePayload.TYPE, StatusResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlayersRequestPayload.TYPE, PlayersRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayersResponsePayload.TYPE, PlayersResponsePayload.CODEC);
    }

    /**
     * Registers the server-side receivers that read from and mutate the given live configuration. Requires an
     * actual {@link ChunkyFriendsConfig} instance to close over, so — unlike {@link #registerPayloadTypes} —
     * this can only run once a real server has started and that configuration has been loaded.
     *
     * @param config The live scheduler configuration this protocol reads from and mutates.
     * @param scheduler The live pregeneration scheduler the status/players GUI panels read from.
     * @param onCurveChanged Invoked whenever an applied update actually changes {@code ringCount},
     *     {@code maxRadiusChunks}, or {@code curveExponent} — see {@link #applyUpdate}.
     */
    public static void registerServerReceivers(final ChunkyFriendsConfig config, final PregenScheduler scheduler, final Runnable onCurveChanged)
    {
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestPayload.TYPE, (payload, context) -> handleRequest(context.player(), config));
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> handleUpdate(context.player(), payload, config, onCurveChanged));
        ServerPlayNetworking.registerGlobalReceiver(MapPreviewRequestPayload.TYPE, (payload, context) -> handleMapPreviewRequest(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(StatusRequestPayload.TYPE, (payload, context) -> handleStatusRequest(context.player(), scheduler));
        ServerPlayNetworking.registerGlobalReceiver(PlayersRequestPayload.TYPE, (payload, context) -> handlePlayersRequest(context.player(), scheduler, config));
    }

    /**
     * Validates and applies a ring count / max radius / curve change, persisting it on success. This is the
     * single source of truth for what a "valid" configuration change is — both the network handler above and
     * {@code ChunkyFriendsCommand} call this rather than duplicating validation.
     *
     * @param config The live scheduler configuration to mutate.
     * @param ringCount Requested ring tier count.
     * @param maxRadiusChunks Requested ceiling radius, in chunks.
     * @param quadratic {@code true} for the quadratic ease-in curve, {@code false} for linear.
     * @param onCurveChanged Invoked exactly once, after saving, if and only if at least one of
     *     {@code ringCount}/{@code maxRadiusChunks}/{@code quadratic} actually differs from the current
     *     config — never for a no-op resubmission of unchanged values.
     * @return {@code true} if the values were in range and applied; {@code false} if rejected.
     * @implNote A player already at {@code currentRingTier == ringCount} is permanently excluded from future
     *     selection ({@link PlayerSelector} never picks them again — there is no tier beyond the max). If the
     *     curve shape changes underneath them, that exclusion can go stale: a larger {@code maxRadiusChunks},
     *     for instance, means there is real additional coverage they should now grow into, but nothing will
     *     ever re-select them to do it. The caller is expected to pass a {@code onCurveChanged} callback that
     *     resets every tracked player back to tier {@code 0} (see {@code PregenScheduler.resetAllProgress}) —
     *     cheap to redo since every job requests a full disk and Chunky skips chunks it already has.
     */
    public static boolean applyUpdate(final ChunkyFriendsConfig config, final int ringCount, final int maxRadiusChunks, final boolean quadratic, final Runnable onCurveChanged)
    {
        if (ringCount < MIN_RING_COUNT || ringCount > MAX_RING_COUNT
                || maxRadiusChunks < MIN_RADIUS_CHUNKS || maxRadiusChunks > MAX_RADIUS_CHUNKS)
        {
            return false;
        }
        final boolean changed = config.getRingCount() != ringCount
                || config.getMaxRadiusChunks() != maxRadiusChunks
                || isQuadratic(config.getCurveExponent()) != quadratic;
        config.setRingCount(ringCount);
        config.setMaxRadiusChunks(maxRadiusChunks);
        config.setCurveExponent(quadratic ? QUADRATIC_CURVE_EXPONENT : LINEAR_CURVE_EXPONENT);
        config.save();
        if (changed)
        {
            onCurveChanged.run();
        }
        return true;
    }

    /**
     * Gets whether the given curve exponent represents the quadratic curve rather than linear.
     *
     * @param curveExponent The curve exponent to classify.
     * @return {@code true} for quadratic, {@code false} for linear.
     */
    public static boolean isQuadratic(final double curveExponent)
    {
        return curveExponent >= (LINEAR_CURVE_EXPONENT + QUADRATIC_CURVE_EXPONENT) / 2;
    }

    /**
     * Checks whether the given permission-context owner (a player or a command source, including console/RCON)
     * is allowed to read or change this configuration.
     *
     * @param owner The permission context owner to check.
     * @return {@code true} if permitted.
     */
    public static boolean hasPermission(final PermissionContextOwner owner)
    {
        return owner.checkPermission(CONFIG_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    private static void handleRequest(final ServerPlayer player, final ChunkyFriendsConfig config)
    {
        LOGGER.info("Config GUI request from {} ({}).", player.getGameProfile().name(), player.getUUID());
        if (!hasPermission((PermissionContextOwner) player))
        {
            LOGGER.warn("Denied config GUI request from {} ({}) — insufficient permission ({}).",
                    player.getGameProfile().name(), player.getUUID(), CONFIG_PERMISSION);
            player.sendSystemMessage(Component.translatable("message.chunky-friends.config.no_permission"));
            return;
        }
        ServerPlayNetworking.send(player, toStatePayload(config));
    }

    private static void handleUpdate(final ServerPlayer player, final ConfigUpdatePayload payload, final ChunkyFriendsConfig config, final Runnable onCurveChanged)
    {
        LOGGER.info("Config update attempt from {} ({}): ringCount={}, maxRadiusChunks={}, quadratic={}.",
                player.getGameProfile().name(), player.getUUID(), payload.ringCount(), payload.maxRadiusChunks(), payload.quadratic());
        if (!hasPermission((PermissionContextOwner) player))
        {
            LOGGER.warn("Denied config update from {} ({}) — insufficient permission ({}).",
                    player.getGameProfile().name(), player.getUUID(), CONFIG_PERMISSION);
            player.sendSystemMessage(Component.translatable("message.chunky-friends.config.no_permission"));
            return;
        }
        if (!applyUpdate(config, payload.ringCount(), payload.maxRadiusChunks(), payload.quadratic(), onCurveChanged))
        {
            LOGGER.warn("Rejected config update from {} ({}) — values out of range.", player.getGameProfile().name(), player.getUUID());
            player.sendSystemMessage(Component.translatable("message.chunky-friends.config.invalid_values"));
            return;
        }
        LOGGER.info("Config updated by {} ({}): ringCount={}, maxRadiusChunks={}, quadratic={}.",
                player.getGameProfile().name(), player.getUUID(), payload.ringCount(), payload.maxRadiusChunks(), payload.quadratic());
        player.sendSystemMessage(Component.translatable("message.chunky-friends.config.saved"));
        ServerPlayNetworking.send(player, toStatePayload(config));
    }

    private static void handleMapPreviewRequest(final ServerPlayer player, final MapPreviewRequestPayload payload)
    {
        if (!hasPermission((PermissionContextOwner) player))
        {
            LOGGER.warn("Denied map preview request from {} ({}) — insufficient permission ({}).",
                    player.getGameProfile().name(), player.getUUID(), CONFIG_PERMISSION);
            return;
        }
        TerrainPreviewSampler.sampleAsync(player, payload, response ->
        {
            if (player.hasDisconnected())
            {
                return;
            }
            ServerPlayNetworking.send(player, response);
        });
    }

    private static void handleStatusRequest(final ServerPlayer player, final PregenScheduler scheduler)
    {
        if (scheduler == null || !hasPermission((PermissionContextOwner) player))
        {
            return;
        }
        ServerPlayNetworking.send(player, toStatusResponsePayload(scheduler));
    }

    private static void handlePlayersRequest(final ServerPlayer player, final PregenScheduler scheduler, final ChunkyFriendsConfig config)
    {
        if (scheduler == null || !hasPermission((PermissionContextOwner) player))
        {
            return;
        }
        ServerPlayNetworking.send(player, toPlayersResponsePayload(scheduler, config));
    }

    /**
     * Builds a structured status snapshot for the config screen's status panel, from the same data
     * {@code /chunkyfriends status} reports in chat. Shared by the silent GUI-open request handler above and
     * {@code ChunkyFriendsCommand.status}, so both paths always agree on what "the current status" is.
     *
     * @param scheduler The live pregeneration scheduler to read from.
     * @return The corresponding {@link StatusResponsePayload}.
     */
    public static StatusResponsePayload toStatusResponsePayload(final PregenScheduler scheduler)
    {
        final Optional<ActiveJobSnapshot> snapshot = scheduler.activeJobSnapshot();
        final int eligibleCount = scheduler.eligiblePlayers(System.currentTimeMillis()).size();
        if (snapshot.isEmpty())
        {
            return new StatusResponsePayload(false, "", "", 0, 0, 0, 0, 0, false, eligibleCount);
        }
        final ActiveJobSnapshot job = snapshot.get();
        final String displayName = job.playerDisplayName() != null ? job.playerDisplayName() : job.playerUuid().toString();
        return new StatusResponsePayload(true, displayName, job.world(), job.ringTier(), job.ringCount(),
                job.progressPercent(), job.chunks(), job.chunksPerSecond(), job.presencePaused(), eligibleCount);
    }

    /**
     * Builds a structured eligible-players snapshot for the config screen's players panel, from the same data
     * {@code /chunkyfriends players} reports in chat. Shared by the silent GUI-open request handler above and
     * {@code ChunkyFriendsCommand.players}, so both paths always agree on what "the current players" is.
     *
     * @param scheduler The live pregeneration scheduler to read from.
     * @param config The live scheduler configuration, supplying {@code ringCount} for each entry.
     * @return The corresponding {@link PlayersResponsePayload}.
     */
    public static PlayersResponsePayload toPlayersResponsePayload(final PregenScheduler scheduler, final ChunkyFriendsConfig config)
    {
        final List<PlayerPregenState> eligible = scheduler.eligiblePlayers(System.currentTimeMillis());
        final UUID activePlayerUuid = scheduler.activeJobSnapshot().map(ActiveJobSnapshot::playerUuid).orElse(null);
        final List<PlayersResponsePayload.PlayerEntry> entries = new ArrayList<>();
        for (final PlayerPregenState state : eligible)
        {
            final String displayName = state.getLastKnownName() != null ? state.getLastKnownName() : state.getPlayerUuid().toString();
            entries.add(new PlayersResponsePayload.PlayerEntry(displayName, state.getCurrentRingTier(), config.getRingCount(), state.getPlayerUuid().equals(activePlayerUuid)));
        }
        return new PlayersResponsePayload(entries);
    }

    /**
     * Sends the given player a current-values snapshot followed by an instruction to open the configuration
     * screen. Called from {@code /chunkyfriends gui}, executed by a real connected player.
     *
     * @param player The player to open the screen for.
     * @param config The live scheduler configuration to snapshot.
     * @return {@code true} if the player's client declared support for {@link OpenConfigGuiPayload} and both
     *     packets were sent; {@code false} if it didn't (most likely: the client doesn't have this mod
     *     installed), in which case nothing is sent.
     */
    public static boolean openGuiFor(final ServerPlayer player, final ChunkyFriendsConfig config)
    {
        if (!ServerPlayNetworking.canSend(player, OpenConfigGuiPayload.TYPE))
        {
            LOGGER.info("Could not open the config GUI for {} ({}) — their client did not declare support for it.",
                    player.getGameProfile().name(), player.getUUID());
            return false;
        }
        LOGGER.info("Opening config GUI for {} ({}).", player.getGameProfile().name(), player.getUUID());
        ServerPlayNetworking.send(player, toStatePayload(config));
        ServerPlayNetworking.send(player, new OpenConfigGuiPayload());
        return true;
    }

    /**
     * Builds a snapshot payload of the given configuration's client-editable values.
     *
     * @param config The configuration to snapshot.
     * @return The corresponding {@link ConfigStatePayload}.
     */
    public static ConfigStatePayload toStatePayload(final ChunkyFriendsConfig config)
    {
        return new ConfigStatePayload(config.getRingCount(), config.getMaxRadiusChunks(), isQuadratic(config.getCurveExponent()));
    }
}
