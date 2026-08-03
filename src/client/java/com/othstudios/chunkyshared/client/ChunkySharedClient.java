package com.othstudios.chunkyshared.client;

import net.fabricmc.api.ClientModInitializer;

import com.othstudios.chunkyshared.client.network.ConfigNetworkingClient;

/**
 * Client entrypoint for Chunky Shared. Registers the client side of the configuration GUI's network protocol.
 *
 * @implNote There is deliberately no client-registered command here. Opening the GUI is reached through the
 *     real server command {@code /chunkyshared gui} instead (see {@code ChunkySharedCommand}'s own
 *     {@code @implNote} for why a Fabric client-only command under the same root doesn't work) — the server
 *     sends this client an {@code OpenConfigGuiPayload} in response, which {@link ConfigNetworkingClient}
 *     handles.
 */
public class ChunkySharedClient implements ClientModInitializer
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
