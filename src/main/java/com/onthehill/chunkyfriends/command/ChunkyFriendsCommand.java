package com.onthehill.chunkyfriends.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.network.ConfigNetworking;
import com.onthehill.chunkyfriends.network.PlayersResponsePayload;
import com.onthehill.chunkyfriends.network.StatusResponsePayload;
import com.onthehill.chunkyfriends.player.PlayerPregenState;
import com.onthehill.chunkyfriends.scheduler.ActiveJobSnapshot;
import com.onthehill.chunkyfriends.scheduler.PregenScheduler;

/**
 * Server-side {@code /chunkyfriends config} and {@code /chunkyfriends gui} commands — the command-line/RCON
 * equivalent of the client configuration GUI's Save button and its own launch point, respectively, both
 * sharing the same validation and apply logic via {@link ConfigNetworking}.
 */
public final class ChunkyFriendsCommand
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkyFriendsCommand.class);

    /**
     * Minimum milliseconds between repeated "permission denied" log lines for the same source. Without this,
     * a single denied source spams the log once per keystroke — {@code .requires()} is evaluated on nearly
     * every tab-completion attempt, not just on actual command execution.
     */
    private static final long DENIAL_LOG_THROTTLE_MILLIS = 30_000L;

    private static final Map<String, Long> LAST_DENIAL_LOG_EPOCH_MILLIS = new ConcurrentHashMap<>();

    private ChunkyFriendsCommand()
    {
    }

    /**
     * Registers {@code /chunkyfriends config}, {@code /chunkyfriends config ringcount <value>},
     * {@code /chunkyfriends config maxradius <value>}, {@code /chunkyfriends config curve linear|quadratic},
     * and {@code /chunkyfriends gui}.
     *
     * @param dispatcher The command dispatcher to register into.
     * @param configSupplier Supplies the live scheduler configuration this command reads from and mutates.
     *     Resolved fresh on every invocation, not captured once — Minecraft builds the command dispatcher
     *     before this mod's configuration exists, so a value captured at registration time would be stale.
     * @param schedulerSupplier Supplies the live pregeneration scheduler {@code status} and {@code players}
     *     read from. Resolved fresh on every invocation for the same reason as {@code configSupplier}.
     * @param onCurveChanged Invoked whenever an applied update actually changes {@code ringCount},
     *     {@code maxRadiusChunks}, or {@code curveExponent} — see {@link ConfigNetworking#applyUpdate}.
     * @implNote {@code maxradius}'s argument is a bare {@code word()} string, not an {@code integer()} —
     *     {@link ConfigNetworking#parseRadiusChunks} needs to see an optional trailing unit letter
     *     ({@code b}/{@code c}), which Brigadier's integer argument type can't accommodate.
     * @implNote {@code gui} is a real <em>server</em> command, deliberately not a Fabric client-only command —
     *     that was tried first and doesn't work: Fabric's client command dispatcher intercepts and fully owns
     *     any input matching a client-registered node before it ever reaches the server, and there is no way
     *     to register a client-side node under {@code chunkyfriends} without that interception swallowing
     *     every other {@code /chunkyfriends config ...} the player types (confirmed against Brigadier's own
     *     {@code CommandDispatcher.execute}, which throws {@code dispatcherUnknownArgument} — not the
     *     ignorable {@code dispatcherUnknownCommand} — the moment {@code chunkyfriends} matches as a root
     *     literal at all). Registering {@code gui} here instead means it's a completely normal server command:
     *     visible in tab-completion and chat validity coloring via the standard {@code ClientboundCommandsPacket}
     *     sync (subject to the same {@code .requires()} permission gate as everything else), reachable from
     *     RCON/console, and — since executing it server-side has no way to directly open a screen on a
     *     specific player's client — it works by sending that player {@link ConfigNetworking#openGuiFor}'s
     *     two packets (current values, then an open instruction) rather than by running any client-side code.
     */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final Supplier<ChunkyFriendsConfig> configSupplier, final Supplier<PregenScheduler> schedulerSupplier, final Runnable onCurveChanged)
    {
        dispatcher.register(literal("chunkyfriends")
                .then(literal("config")
                        .requires(ChunkyFriendsCommand::hasPermission)
                        .executes(context -> showCurrent(context, configSupplier))
                        .then(literal("ringcount")
                                .then(argument("value", integer(ConfigNetworking.MIN_RING_COUNT, ConfigNetworking.MAX_RING_COUNT))
                                        .executes(context -> setRingCount(context, configSupplier, onCurveChanged))))
                        .then(literal("maxradius")
                                .then(argument("value", word())
                                        .executes(context -> setMaxRadius(context, configSupplier, onCurveChanged))))
                        .then(literal("curve")
                                .then(literal("linear").executes(context -> setCurve(context, configSupplier, false, onCurveChanged)))
                                .then(literal("quadratic").executes(context -> setCurve(context, configSupplier, true, onCurveChanged)))))
                .then(literal("gui")
                        .requires(ChunkyFriendsCommand::hasPermission)
                        .executes(context -> openGui(context, configSupplier)))
                .then(literal("status")
                        .requires(ChunkyFriendsCommand::hasPermission)
                        .executes(context -> status(context, configSupplier, schedulerSupplier)))
                .then(literal("players")
                        .requires(ChunkyFriendsCommand::hasPermission)
                        .executes(context -> players(context, configSupplier, schedulerSupplier))));
    }

    private static int openGui(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null)
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-friends.gui_requires_player"));
            return 0;
        }
        if (!ConfigNetworking.openGuiFor(player, config))
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-friends.gui_client_missing_mod"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.chunky-friends.gui_opening"), false);
        return 1;
    }

    private static boolean hasPermission(final CommandSourceStack source)
    {
        final boolean granted;
        try
        {
            granted = ((PermissionContextOwner) source).checkPermission(ConfigNetworking.CONFIG_PERMISSION, PermissionLevel.GAMEMASTERS);
        }
        catch (final RuntimeException exception)
        {
            LOGGER.warn("Permission check for /chunkyfriends config failed unexpectedly for {}; denying access.", source.getTextName(), exception);
            return false;
        }
        if (!granted)
        {
            // Logged (throttled — see DENIAL_LOG_THROTTLE_MILLIS) rather than left silent: a Brigadier node
            // this predicate rejects is invisible to the source (no autocomplete, "Unknown command" on
            // execution) with no other indication why — this is the only place that ever records the denial.
            final String name = source.getTextName();
            final long now = System.currentTimeMillis();
            final Long lastLogged = LAST_DENIAL_LOG_EPOCH_MILLIS.get(name);
            if (lastLogged == null || now - lastLogged >= DENIAL_LOG_THROTTLE_MILLIS)
            {
                LAST_DENIAL_LOG_EPOCH_MILLIS.put(name, now);
                LOGGER.info("/chunkyfriends config and /chunkyfriends gui are hidden from {} — permission check ({}) returned false.", name, ConfigNetworking.CONFIG_PERMISSION);
            }
        }
        return granted;
    }

    private static boolean requireReadyConfig(final CommandContext<CommandSourceStack> context, final ChunkyFriendsConfig config)
    {
        if (config != null)
        {
            return true;
        }
        context.getSource().sendFailure(Component.translatable("message.chunky-friends.config.not_ready"));
        return false;
    }

    private static int showCurrent(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.chunky-friends.config.current",
                config.getRingCount(), config.getMaxRadiusChunks() * ConfigNetworking.BLOCKS_PER_CHUNK, config.getMaxRadiusChunks(),
                ConfigNetworking.isQuadratic(config.getCurveExponent())
                        ? Component.translatable("gui.chunky-friends.config.curve_quadratic")
                        : Component.translatable("gui.chunky-friends.config.curve_linear")), false);
        return 1;
    }

    private static int setRingCount(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier, final Runnable onCurveChanged)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final int value = getInteger(context, "value");
        final boolean applied = ConfigNetworking.applyUpdate(config, value, config.getMaxRadiusChunks(), ConfigNetworking.isQuadratic(config.getCurveExponent()), onCurveChanged);
        return respond(context, applied);
    }

    private static int setMaxRadius(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier, final Runnable onCurveChanged)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final OptionalInt parsed = ConfigNetworking.parseRadiusChunks(getString(context, "value"));
        if (parsed.isEmpty())
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-friends.config.invalid_radius_format"));
            return 0;
        }
        final boolean applied = ConfigNetworking.applyUpdate(config, config.getRingCount(), parsed.getAsInt(), ConfigNetworking.isQuadratic(config.getCurveExponent()), onCurveChanged);
        return respond(context, applied);
    }

    private static int setCurve(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier, final boolean quadratic, final Runnable onCurveChanged)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final boolean applied = ConfigNetworking.applyUpdate(config, config.getRingCount(), config.getMaxRadiusChunks(), quadratic, onCurveChanged);
        return respond(context, applied);
    }

    private static int respond(final CommandContext<CommandSourceStack> context, final boolean applied)
    {
        if (applied)
        {
            context.getSource().sendSuccess(() -> Component.translatable("message.chunky-friends.config.saved"), true);
            return 1;
        }
        context.getSource().sendFailure(Component.translatable("message.chunky-friends.config.invalid_values"));
        return 0;
    }

    private static int status(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier, final Supplier<PregenScheduler> schedulerSupplier)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        final PregenScheduler scheduler = schedulerSupplier.get();
        if (!requireReadyScheduler(context, config, scheduler))
        {
            return 0;
        }
        final Optional<ActiveJobSnapshot> snapshot = scheduler.activeJobSnapshot();
        if (snapshot.isEmpty())
        {
            final int eligibleCount = scheduler.eligiblePlayers(System.currentTimeMillis()).size();
            LOGGER.info("Pregeneration status: no active job. {} player(s) currently eligible.", eligibleCount);
            context.getSource().sendSuccess(() -> Component.translatable("command.chunky-friends.status.idle", eligibleCount), false);
            pushStatusToGuiIfPlayer(context, scheduler);
            return 1;
        }
        final ActiveJobSnapshot job = snapshot.get();
        final String displayName = job.playerDisplayName() != null ? job.playerDisplayName() : job.playerUuid().toString();
        LOGGER.info("Pregeneration status: active job for {} in {} — ring tier {} of {}, {}% complete ({} chunks, {} chunks/s).{}",
                displayName, job.world(), job.ringTier(), job.ringCount(), job.progressPercent(), job.chunks(), job.chunksPerSecond(),
                job.presencePaused() ? " (paused — a player is online)" : "");
        context.getSource().sendSuccess(() ->
        {
            MutableComponent message = Component.translatable("command.chunky-friends.status.active",
                    displayName, job.ringTier(), job.ringCount(), job.progressPercent(), job.chunks(), job.chunksPerSecond(), job.world());
            if (job.presencePaused())
            {
                message = message.append(Component.translatable("command.chunky-friends.status.active_paused_suffix"));
            }
            return message;
        }, false);
        pushStatusToGuiIfPlayer(context, scheduler);
        return 1;
    }

    private static int players(final CommandContext<CommandSourceStack> context, final Supplier<ChunkyFriendsConfig> configSupplier, final Supplier<PregenScheduler> schedulerSupplier)
    {
        final ChunkyFriendsConfig config = configSupplier.get();
        final PregenScheduler scheduler = schedulerSupplier.get();
        if (!requireReadyScheduler(context, config, scheduler))
        {
            return 0;
        }
        final List<PlayerPregenState> eligible = scheduler.eligiblePlayers(System.currentTimeMillis());
        if (eligible.isEmpty())
        {
            LOGGER.info("Pregeneration players: no players are currently eligible.");
            context.getSource().sendSuccess(() -> Component.translatable("command.chunky-friends.players.none"), false);
            pushPlayersToGuiIfPlayer(context, scheduler, config);
            return 1;
        }
        final UUID activePlayerUuid = scheduler.activeJobSnapshot().map(ActiveJobSnapshot::playerUuid).orElse(null);
        LOGGER.info("Pregeneration players: {} player(s) currently eligible (not evicted):", eligible.size());
        final MutableComponent message = Component.translatable("command.chunky-friends.players.header", eligible.size()).copy();
        for (final PlayerPregenState state : eligible)
        {
            final String displayName = state.getLastKnownName() != null ? state.getLastKnownName() : state.getPlayerUuid().toString();
            final boolean active = state.getPlayerUuid().equals(activePlayerUuid);
            LOGGER.info("{} — ring tier {} of {}{}", displayName, state.getCurrentRingTier(), config.getRingCount(), active ? " (active job in progress)" : "");
            MutableComponent entry = Component.translatable("command.chunky-friends.players.entry", displayName, state.getCurrentRingTier(), config.getRingCount());
            if (active)
            {
                entry = entry.append(Component.translatable("command.chunky-friends.players.entry_active_suffix"));
            }
            message.append("\n").append(entry);
        }
        context.getSource().sendSuccess(() -> message, false);
        pushPlayersToGuiIfPlayer(context, scheduler, config);
        return 1;
    }

    /**
     * Pushes a structured {@link StatusResponsePayload} to the invoking source's player, if the source is a
     * real connected player whose client declared support for it — so the config screen's status panel stays
     * in sync with the exact same command invocation that just produced this chat response, without a second
     * network round trip.
     */
    private static void pushStatusToGuiIfPlayer(final CommandContext<CommandSourceStack> context, final PregenScheduler scheduler)
    {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player != null && ServerPlayNetworking.canSend(player, StatusResponsePayload.TYPE))
        {
            ServerPlayNetworking.send(player, ConfigNetworking.toStatusResponsePayload(scheduler));
        }
    }

    /**
     * Pushes a structured {@link PlayersResponsePayload} to the invoking source's player, if the source is a
     * real connected player whose client declared support for it — see {@link #pushStatusToGuiIfPlayer}.
     */
    private static void pushPlayersToGuiIfPlayer(final CommandContext<CommandSourceStack> context, final PregenScheduler scheduler, final ChunkyFriendsConfig config)
    {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player != null && ServerPlayNetworking.canSend(player, PlayersResponsePayload.TYPE))
        {
            ServerPlayNetworking.send(player, ConfigNetworking.toPlayersResponsePayload(scheduler, config));
        }
    }

    private static boolean requireReadyScheduler(final CommandContext<CommandSourceStack> context, final ChunkyFriendsConfig config, final PregenScheduler scheduler)
    {
        if (config != null && scheduler != null)
        {
            return true;
        }
        context.getSource().sendFailure(Component.translatable("message.chunky-friends.config.not_ready"));
        return false;
    }
}
