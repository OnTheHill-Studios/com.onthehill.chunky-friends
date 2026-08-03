package com.othstudios.chunkyshared.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import com.othstudios.chunkyshared.client.screen.ChunkySharedConfigScreen;

/**
 * ModMenu integration. Only ever loaded if ModMenu itself is present — nothing else in this mod references
 * ModMenu's API, so it remains a purely optional dependency.
 */
public final class ChunkySharedModMenu implements ModMenuApi
{
    /**
     * Supplies the "config" button ModMenu shows next to this mod in its mod list.
     *
     * @return A factory that opens {@link ChunkySharedConfigScreen}, returning to ModMenu's mod list on close.
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return ChunkySharedConfigScreen::new;
    }
}
