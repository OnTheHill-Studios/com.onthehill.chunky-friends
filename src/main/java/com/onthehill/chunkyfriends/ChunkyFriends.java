package com.onthehill.chunkyfriends;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onthehill.chunkyfriends.chunky.ChunkyGateway;
import com.onthehill.chunkyfriends.command.ChunkyFriendsCommand;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.network.ConfigNetworking;
import com.onthehill.chunkyfriends.scheduler.PregenScheduler;

/**
 * Common entrypoint for Chunky Friends. Wires server lifecycle and player connection events to the
 * presence-gated chunk pregeneration scheduler.
 */
public class ChunkyFriends implements ModInitializer
{
    /**
     * This mod's Fabric mod identifier.
     */
    public static final String MOD_ID = "chunky-friends";

    /**
     * Logger for this mod, named after {@link #MOD_ID} so log lines are attributable at a glance.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ChunkyFriendsConfig _config;
    private PregenScheduler _pregenScheduler;

    /**
     * Registers server lifecycle and player connection listeners that drive the pregeneration scheduler,
     * the network protocol backing the client-side configuration GUI, and its command-line equivalent.
     *
     * @implNote The {@code main} entrypoint this class implements runs on every physical install of the mod —
     *     a dedicated server, an integrated (singleplayer/LAN) server, <em>and</em> a pure client that only
     *     ever joins someone else's remote server. Loading/writing {@link #_config} and constructing
     *     {@link #_pregenScheduler} are deliberately deferred to {@code ServerLifecycleEvents.SERVER_STARTED}
     *     — which only fires when a real {@link MinecraftServer} actually starts — rather than done eagerly
     *     here, so a pure remote-joining client never touches disk or spins up scheduling machinery it will
     *     never use. The command tree below is registered here at mod-init time regardless (registering it is
     *     itself inert without a server), but reads {@link #_config} through a supplier rather than capturing
     *     it directly, since Minecraft builds the command dispatcher before {@code SERVER_STARTED} fires —
     *     capturing the field's value at registration time would have permanently baked in {@code null}.
     *     {@link ConfigNetworking#registerPayloadTypes()}, by contrast, is called unconditionally right here,
     *     not deferred — payload <em>type</em> registration has no dependency on {@link #_config} and must
     *     happen on every physical side regardless of whether a server ever starts, since the client's own
     *     mod-init path registers a receiver for one of these types before any server could exist.
     */
    @Override
    public void onInitialize()
    {
        ConfigNetworking.registerPayloadTypes();
        ServerLifecycleEvents.SERVER_STARTED.register(this::initializeForServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            if (_pregenScheduler != null)
            {
                _pregenScheduler.shutdown(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) ->
        {
            if (_pregenScheduler != null)
            {
                _pregenScheduler.onPlayerJoin(listener.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
        {
            if (_pregenScheduler != null)
            {
                _pregenScheduler.onPlayerDisconnect(listener.getPlayer());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> ChunkyFriendsCommand.register(dispatcher, () -> _config, this::onCurveConfigChanged));
    }

    private void initializeForServer(final MinecraftServer server)
    {
        if (_config == null)
        {
            _config = ChunkyFriendsConfig.load(FabricLoader.getInstance().getConfigDir().resolve("chunky-friends.json"));
            _pregenScheduler = new PregenScheduler(new ChunkyGateway(), _config);
            ConfigNetworking.registerServerReceivers(_config, this::onCurveConfigChanged);
        }
        _pregenScheduler.init(server);
    }

    private void onCurveConfigChanged()
    {
        if (_pregenScheduler != null)
        {
            _pregenScheduler.resetAllProgress();
        }
    }

    /**
     * Builds a namespaced identifier under this mod's namespace.
     *
     * @param path The identifier's path.
     * @return An identifier of the form {@code chunky-friends:<path>}.
     */
    public static Identifier id(final String path)
    {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
