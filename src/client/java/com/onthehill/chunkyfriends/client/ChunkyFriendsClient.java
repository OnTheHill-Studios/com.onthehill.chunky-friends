package com.onthehill.chunkyfriends.client;

import net.fabricmc.api.ClientModInitializer;

import com.onthehill.chunkyfriends.client.network.ConfigNetworkingClient;

/**
 * Client entrypoint for Chunky Friends. Registers the client side of the configuration GUI's network protocol.
 *
 * @implNote There is deliberately no client-registered command here. Opening the GUI is reached through the
 *     real server command {@code /chunkyfriends gui} instead (see {@code ChunkyFriendsCommand}'s own
 *     {@code @implNote} for why a Fabric client-only command under the same root doesn't work) — the server
 *     sends this client an {@code OpenConfigGuiPayload} in response, which {@link ConfigNetworkingClient}
 *     handles.
 */
public class ChunkyFriendsClient implements ClientModInitializer
{
    /**
     * Registers client-side networking receivers.
     */
    @Override
    public void onInitializeClient()
    {
        ConfigNetworkingClient.register();
    }
}
