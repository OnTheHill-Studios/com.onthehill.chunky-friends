package com.othstudios.chunkyshared.client.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.OptionalInt;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.othstudios.chunkyshared.client.network.ConfigNetworkingClient;
import com.othstudios.chunkyshared.network.ConfigNetworking;
import com.othstudios.chunkyshared.network.ConfigRequestPayload;
import com.othstudios.chunkyshared.network.ConfigStatePayload;
import com.othstudios.chunkyshared.network.ConfigUpdatePayload;

/**
 * Client-side screen for viewing and changing the pregeneration scheduler's server-side configuration:
 * ring tier count, maximum radius, and curve shape (linear or quadratic). Values submitted here are
 * re-validated and permission-checked on the server before being applied.
 */
public final class ChunkySharedConfigScreen extends Screen
{
    private static final int DEFAULT_RING_COUNT = 10;
    private static final int DEFAULT_MAX_RADIUS_CHUNKS = 500;
    private static final boolean DEFAULT_QUADRATIC = true;
    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;

    private static ChunkySharedConfigScreen _openInstance;

    private final Screen _parent;

    private EditBox _ringCountBox;
    private EditBox _maxRadiusBox;
    private CycleButton<Boolean> _curveButton;
    private boolean _quadratic;

    /**
     * Constructs the configuration screen, returning to the in-game view on close.
     */
    public ChunkySharedConfigScreen()
    {
        this(null);
    }

    /**
     * Constructs the configuration screen.
     *
     * @param parent The screen to return to on close, e.g. ModMenu's mod list — or {@code null} to return to
     *     the in-game view instead.
     */
    public ChunkySharedConfigScreen(final Screen parent)
    {
        super(Component.translatable("gui.chunky-shared.config.title"));
        _parent = parent;
    }

    /**
     * Gets the currently open instance of this screen, if any.
     *
     * @return The open instance, or {@code null} if this screen is not currently displayed.
     */
    public static ChunkySharedConfigScreen getOpenInstance()
    {
        return _openInstance;
    }

    /**
     * Builds this screen's widgets, seeded from the last configuration snapshot received from the server,
     * or built-in defaults if none has arrived yet — and requests a fresh one regardless, since a snapshot
     * cached from an earlier session (or none at all, e.g. when opened directly from ModMenu rather than via
     * {@code /chunkyshared gui}, which already sends one alongside the open instruction) could be stale.
     * {@link #applyServerState} refreshes the fields in place once the response arrives.
     */
    @Override
    protected void init()
    {
        _openInstance = this;
        ClientPlayNetworking.send(new ConfigRequestPayload());

        final ConfigStatePayload lastKnownState = ConfigNetworkingClient.getLastKnownState();
        final int initialRingCount = lastKnownState != null ? lastKnownState.ringCount() : DEFAULT_RING_COUNT;
        final int initialMaxRadius = lastKnownState != null ? lastKnownState.maxRadiusChunks() : DEFAULT_MAX_RADIUS_CHUNKS;
        _quadratic = lastKnownState != null ? lastKnownState.quadratic() : DEFAULT_QUADRATIC;

        final GridLayout layout = new GridLayout().columnSpacing(8).rowSpacing(8);
        final GridLayout.RowHelper rows = layout.createRowHelper(1);

        rows.addChild(new StringWidget(getTitle(), font));

        rows.addChild(new StringWidget(Component.translatable("gui.chunky-shared.config.ring_count"), font));
        _ringCountBox = rows.addChild(new EditBox(font, FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("gui.chunky-shared.config.ring_count")));
        _ringCountBox.setValue(String.valueOf(initialRingCount));

        rows.addChild(new StringWidget(Component.translatable("gui.chunky-shared.config.max_radius"), font));
        _maxRadiusBox = rows.addChild(new EditBox(font, FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("gui.chunky-shared.config.max_radius")));
        _maxRadiusBox.setValue(String.valueOf(initialMaxRadius * ConfigNetworking.BLOCKS_PER_CHUNK));

        _curveButton = rows.addChild(CycleButton.booleanBuilder(
                        Component.translatable("gui.chunky-shared.config.curve_quadratic"),
                        Component.translatable("gui.chunky-shared.config.curve_linear"),
                        _quadratic)
                .create(Component.translatable("gui.chunky-shared.config.curve"), (button, value) -> _quadratic = value));

        rows.addChild(Button.builder(Component.translatable("gui.chunky-shared.config.save"), button -> onSave()).build());
        rows.addChild(Button.builder(Component.translatable("gui.chunky-shared.config.cancel"), button -> onClose()).build());

        layout.arrangeElements();
        layout.setX((width - layout.getWidth()) / 2);
        layout.setY((height - layout.getHeight()) / 2);
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
    }

    private void onSave()
    {
        final OptionalInt maxRadiusChunks = ConfigNetworking.parseRadiusChunks(_maxRadiusBox.getValue());
        if (maxRadiusChunks.isEmpty())
        {
            // Left as-is on screen; the invalid text remains visible for the player to correct.
            return;
        }
        try
        {
            final int ringCount = Integer.parseInt(_ringCountBox.getValue().trim());
            ClientPlayNetworking.send(new ConfigUpdatePayload(ringCount, maxRadiusChunks.getAsInt(), _quadratic));
            onClose();
        }
        catch (final NumberFormatException exception)
        {
            // Left as-is on screen; the invalid text remains visible for the player to correct.
        }
    }

    /**
     * Refreshes this screen's fields from a newly received server snapshot. Safe to call at any time; a
     * no-op if this screen's widgets have not been created yet.
     *
     * @param state The latest configuration snapshot from the server.
     */
    public void applyServerState(final ConfigStatePayload state)
    {
        if (_ringCountBox == null)
        {
            return;
        }
        _ringCountBox.setValue(String.valueOf(state.ringCount()));
        _maxRadiusBox.setValue(String.valueOf(state.maxRadiusChunks() * ConfigNetworking.BLOCKS_PER_CHUNK));
        _quadratic = state.quadratic();
        _curveButton.setValue(state.quadratic());
    }

    /**
     * Closes this screen, returning to its parent screen if it was given one, or to the in-game view otherwise.
     */
    @Override
    public void onClose()
    {
        _openInstance = null;
        minecraft.setScreenAndShow(_parent);
    }
}
