package com.onthehill.chunkyfriends.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import com.onthehill.chunkyfriends.client.screen.ChunkyFriendsConfigScreen;

/**
 * ModMenu integration. Only ever loaded if ModMenu itself is present — nothing else in this mod references
 * ModMenu's API, so it remains a purely optional dependency.
 */
public final class ChunkyFriendsModMenu implements ModMenuApi
{
    /**
     * Supplies the "config" button ModMenu shows next to this mod in its mod list.
     *
     * @return A factory that opens {@link ChunkyFriendsConfigScreen}, returning to ModMenu's mod list on close.
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return ChunkyFriendsConfigScreen::new;
    }
}
