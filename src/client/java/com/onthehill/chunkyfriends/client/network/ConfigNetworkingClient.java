package com.onthehill.chunkyfriends.client.network;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.onthehill.chunkyfriends.ChunkyFriends;
import com.onthehill.chunkyfriends.client.screen.ChunkyFriendsConfigScreen;
import com.onthehill.chunkyfriends.network.ConfigRequestPayload;
import com.onthehill.chunkyfriends.network.ConfigStatePayload;
import com.onthehill.chunkyfriends.network.ConfigUpdatePayload;
import com.onthehill.chunkyfriends.network.MapPreviewResponsePayload;
import com.onthehill.chunkyfriends.network.OpenConfigGuiPayload;
import com.onthehill.chunkyfriends.network.PlayersResponsePayload;
import com.onthehill.chunkyfriends.network.StatusResponsePayload;

/**
 * Registers and caches the client side of the configuration GUI's network protocol, and detects whether the
 * currently connected server supports it at all.
 */
public final class ConfigNetworkingClient
{
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId OPENING_TOAST_ID = new SystemToast.SystemToastId();

    private static ConfigStatePayload _lastKnownState;
    private static ProtocolSupport _protocolSupport = ProtocolSupport.NOT_INSTALLED;

    private ConfigNetworkingClient()
    {
    }

    /**
     * Registers the client-side receivers for {@link ConfigStatePayload} and {@link OpenConfigGuiPayload}, and
     * a join hook that determines whether the connected server supports this protocol at all — see
     * {@link ProtocolSupport}.
     */
    public static void register()
    {
        ClientPlayNetworking.registerGlobalReceiver(ConfigStatePayload.TYPE, (payload, context) ->
        {
            _lastKnownState = payload;
            final ChunkyFriendsConfigScreen openScreen = ChunkyFriendsConfigScreen.getOpenInstance();
            if (openScreen != null)
            {
                openScreen.applyServerState(payload);
            }
        });

        // Always preceded by a ConfigStatePayload (see ConfigNetworking.openGuiFor), so _lastKnownState is
        // already fresh by the time this fires and the screen opens pre-populated with real values.
        ClientPlayNetworking.registerGlobalReceiver(OpenConfigGuiPayload.TYPE, (payload, context) ->
        {
            // The GUI is actually open now — the "opening…" toast (see showOpeningToast) has served its
            // purpose and would otherwise sit on screen for its full default duration regardless, reading as
            // a stale leftover next to the screen it was announcing.
            SystemToast.forceHide(Minecraft.getInstance().gui.toastManager(), OPENING_TOAST_ID);
            Minecraft.getInstance().setScreenAndShow(new ChunkyFriendsConfigScreen());
        });

        ClientPlayNetworking.registerGlobalReceiver(MapPreviewResponsePayload.TYPE, (payload, context) ->
        {
            final ChunkyFriendsConfigScreen openScreen = ChunkyFriendsConfigScreen.getOpenInstance();
            if (openScreen != null)
            {
                openScreen.applyMapPreview(payload);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(StatusResponsePayload.TYPE, (payload, context) ->
        {
            final ChunkyFriendsConfigScreen openScreen = ChunkyFriendsConfigScreen.getOpenInstance();
            if (openScreen != null)
            {
                openScreen.applyStatusResponse(payload);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(PlayersResponsePayload.TYPE, (payload, context) ->
        {
            final ChunkyFriendsConfigScreen openScreen = ChunkyFriendsConfigScreen.getOpenInstance();
            if (openScreen != null)
            {
                openScreen.applyPlayersResponse(payload);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
        {
            _lastKnownState = null;
            _protocolSupport = detectProtocolSupport();
            if (_protocolSupport != ProtocolSupport.SUPPORTED)
            {
                showUnsupportedToast(_protocolSupport);
            }
        });

        // Fired the instant the player sends /chunkyfriends gui, before any server round trip — the command
        // itself gives no feedback until ChunkyFriendsCommand.openGui's response arrives, which could be a
        // tick or several away under server load, and a player with no visible acknowledgement at all tends to
        // just run the command again (and again). This shows a toast immediately so they know it was received.
        ClientSendMessageEvents.COMMAND.register(command ->
        {
            if ("chunkyfriends gui".equalsIgnoreCase(command.trim()))
            {
                showOpeningToast();
            }
        });
    }

    private static void showOpeningToast()
    {
        SystemToast.add(Minecraft.getInstance().gui.toastManager(), OPENING_TOAST_ID,
                Component.translatable("gui.chunky-friends.config.opening_toast_title"),
                Component.translatable("gui.chunky-friends.config.opening_toast_description"));
    }

    private static ProtocolSupport detectProtocolSupport()
    {
        final Set<Identifier> serverChannels = ClientPlayNetworking.getSendable();
        if (serverChannels.contains(ConfigRequestPayload.TYPE.id()) && serverChannels.contains(ConfigUpdatePayload.TYPE.id()))
        {
            return ProtocolSupport.SUPPORTED;
        }
        final boolean anyChunkyFriendsChannel = serverChannels.stream().anyMatch(id -> id.getNamespace().equals(ChunkyFriends.MOD_ID));
        return anyChunkyFriendsChannel ? ProtocolSupport.VERSION_MISMATCH : ProtocolSupport.NOT_INSTALLED;
    }

    private static void showUnsupportedToast(final ProtocolSupport support)
    {
        final Component description = support == ProtocolSupport.VERSION_MISMATCH
                ? Component.translatable("message.chunky-friends.client_version_mismatch")
                : Component.translatable("message.chunky-friends.client_not_supported");
        SystemToast.add(Minecraft.getInstance().gui.toastManager(), TOAST_ID, Component.translatable("gui.chunky-friends.config.title"), description);
    }

    /**
     * Gets the most recently received configuration snapshot from the server, if any.
     *
     * @return The last known state, or {@code null} if no snapshot has been received this session.
     */
    public static ConfigStatePayload getLastKnownState()
    {
        return _lastKnownState;
    }

    /**
     * Gets whether the currently connected server supports this configuration protocol.
     *
     * @return The current protocol support state.
     */
    public static ProtocolSupport getProtocolSupport()
    {
        return _protocolSupport;
    }

    /**
     * Whether the connected server can understand this mod's configuration network protocol.
     */
    public enum ProtocolSupport
    {
        /**
         * The server registered receivers matching this protocol's exact channel identifiers.
         */
        SUPPORTED,

        /**
         * The server registered at least one channel under this mod's namespace, but not the exact ones this
         * client expects — most likely a server-side Chunky Friends build on a different protocol version.
         */
        VERSION_MISMATCH,

        /**
         * The server registered no channels under this mod's namespace at all — it does not have Chunky
         * Shared installed.
         */
        NOT_INSTALLED
    }
}
