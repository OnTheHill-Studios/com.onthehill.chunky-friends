package com.othstudios.chunkyshared.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.othstudios.chunkyshared.config.ChunkySharedConfig;
import com.othstudios.chunkyshared.network.ConfigNetworking;

/**
 * Server-side {@code /chunkyshared config} and {@code /chunkyshared gui} commands — the command-line/RCON
 * equivalent of the client configuration GUI's Save button and its own launch point, respectively, both
 * sharing the same validation and apply logic via {@link ConfigNetworking}.
 */
public final class ChunkySharedCommand
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkySharedCommand.class);

    /**
     * Minimum milliseconds between repeated "permission denied" log lines for the same source. Without this,
     * a single denied source spams the log once per keystroke — {@code .requires()} is evaluated on nearly
     * every tab-completion attempt, not just on actual command execution.
     */
    private static final long DENIAL_LOG_THROTTLE_MILLIS = 30_000L;

    private static final Map<String, Long> LAST_DENIAL_LOG_EPOCH_MILLIS = new ConcurrentHashMap<>();

    private ChunkySharedCommand()
    {
    }

    /**
     * Registers {@code /chunkyshared config}, {@code /chunkyshared config ringcount <value>},
     * {@code /chunkyshared config maxradius <value>}, {@code /chunkyshared config curve linear|quadratic},
     * and {@code /chunkyshared gui}.
     *
     * @param dispatcher The command dispatcher to register into.
     * @param configSupplier Supplies the live scheduler configuration this command reads from and mutates.
     *     Resolved fresh on every invocation, not captured once — Minecraft builds the command dispatcher
     *     before this mod's configuration exists, so a value captured at registration time would be stale.
     * @param onCurveChanged Invoked whenever an applied update actually changes {@code ringCount},
     *     {@code maxRadiusChunks}, or {@code curveExponent} — see {@link ConfigNetworking#applyUpdate}.
     * @implNote {@code maxradius}'s argument is a bare {@code word()} string, not an {@code integer()} —
     *     {@link ConfigNetworking#parseRadiusChunks} needs to see an optional trailing unit letter
     *     ({@code b}/{@code c}), which Brigadier's integer argument type can't accommodate.
     * @implNote {@code gui} is a real <em>server</em> command, deliberately not a Fabric client-only command —
     *     that was tried first and doesn't work: Fabric's client command dispatcher intercepts and fully owns
     *     any input matching a client-registered node before it ever reaches the server, and there is no way
     *     to register a client-side node under {@code chunkyshared} without that interception swallowing
     *     every other {@code /chunkyshared config ...} the player types (confirmed against Brigadier's own
     *     {@code CommandDispatcher.execute}, which throws {@code dispatcherUnknownArgument} — not the
     *     ignorable {@code dispatcherUnknownCommand} — the moment {@code chunkyshared} matches as a root
     *     literal at all). Registering {@code gui} here instead means it's a completely normal server command:
     *     visible in tab-completion and chat validity coloring via the standard {@code ClientboundCommandsPacket}
     *     sync (subject to the same {@code .requires()} permission gate as everything else), reachable from
     *     RCON/console, and — since executing it server-side has no way to directly open a screen on a
     *     specific player's client — it works by sending that player {@link ConfigNetworking#openGuiFor}'s
     *     two packets (current values, then an open instruction) rather than by running any client-side code.
     */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final Supplier<ChunkySharedConfig> configSupplier, final Runnable onCurveChanged)
    {
        dispatcher.register(literal("chunkyshared")
                .then(literal("config")
                        .requires(ChunkySharedCommand::hasPermission)
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
                        .requires(ChunkySharedCommand::hasPermission)
                        .executes(context -> openGui(context, configSupplier))));
    }

    private static int openGui(final CommandContext<CommandSourceStack> context, final Supplier<ChunkySharedConfig> configSupplier)
    {
        final ChunkySharedConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null)
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-shared.gui_requires_player"));
            return 0;
        }
        if (!ConfigNetworking.openGuiFor(player, config))
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-shared.gui_client_missing_mod"));
            return 0;
        }
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
            LOGGER.warn("Permission check for /chunkyshared config failed unexpectedly for {}; denying access.", source.getTextName(), exception);
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
                LOGGER.info("/chunkyshared config and /chunkyshared gui are hidden from {} — permission check ({}) returned false.", name, ConfigNetworking.CONFIG_PERMISSION);
            }
        }
        return granted;
    }

    private static boolean requireReadyConfig(final CommandContext<CommandSourceStack> context, final ChunkySharedConfig config)
    {
        if (config != null)
        {
            return true;
        }
        context.getSource().sendFailure(Component.translatable("message.chunky-shared.config.not_ready"));
        return false;
    }

    private static int showCurrent(final CommandContext<CommandSourceStack> context, final Supplier<ChunkySharedConfig> configSupplier)
    {
        final ChunkySharedConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.chunky-shared.config.current",
                config.getRingCount(), config.getMaxRadiusChunks() * ConfigNetworking.BLOCKS_PER_CHUNK, config.getMaxRadiusChunks(),
                ConfigNetworking.isQuadratic(config.getCurveExponent())
                        ? Component.translatable("gui.chunky-shared.config.curve_quadratic")
                        : Component.translatable("gui.chunky-shared.config.curve_linear")), false);
        return 1;
    }

    private static int setRingCount(final CommandContext<CommandSourceStack> context, final Supplier<ChunkySharedConfig> configSupplier, final Runnable onCurveChanged)
    {
        final ChunkySharedConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final int value = getInteger(context, "value");
        final boolean applied = ConfigNetworking.applyUpdate(config, value, config.getMaxRadiusChunks(), ConfigNetworking.isQuadratic(config.getCurveExponent()), onCurveChanged);
        return respond(context, applied);
    }

    private static int setMaxRadius(final CommandContext<CommandSourceStack> context, final Supplier<ChunkySharedConfig> configSupplier, final Runnable onCurveChanged)
    {
        final ChunkySharedConfig config = configSupplier.get();
        if (!requireReadyConfig(context, config))
        {
            return 0;
        }
        final OptionalInt parsed = ConfigNetworking.parseRadiusChunks(getString(context, "value"));
        if (parsed.isEmpty())
        {
            context.getSource().sendFailure(Component.translatable("message.chunky-shared.config.invalid_radius_format"));
            return 0;
        }
        final boolean applied = ConfigNetworking.applyUpdate(config, config.getRingCount(), parsed.getAsInt(), ConfigNetworking.isQuadratic(config.getCurveExponent()), onCurveChanged);
        return respond(context, applied);
    }

    private static int setCurve(final CommandContext<CommandSourceStack> context, final Supplier<ChunkySharedConfig> configSupplier, final boolean quadratic, final Runnable onCurveChanged)
    {
        final ChunkySharedConfig config = configSupplier.get();
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
            context.getSource().sendSuccess(() -> Component.translatable("message.chunky-shared.config.saved"), true);
            return 1;
        }
        context.getSource().sendFailure(Component.translatable("message.chunky-shared.config.invalid_values"));
        return 0;
    }
}
