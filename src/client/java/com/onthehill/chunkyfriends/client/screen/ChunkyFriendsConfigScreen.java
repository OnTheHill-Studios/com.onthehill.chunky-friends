package com.onthehill.chunkyfriends.client.screen;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

import java.util.List;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.onthehill.chunkyfriends.ChunkyFriends;
import com.onthehill.chunkyfriends.client.network.ConfigNetworkingClient;
import com.onthehill.chunkyfriends.network.ConfigNetworking;
import com.onthehill.chunkyfriends.network.ConfigRequestPayload;
import com.onthehill.chunkyfriends.network.ConfigStatePayload;
import com.onthehill.chunkyfriends.network.ConfigUpdatePayload;
import com.onthehill.chunkyfriends.network.MapPreviewRequestPayload;
import com.onthehill.chunkyfriends.network.MapPreviewResponsePayload;
import com.onthehill.chunkyfriends.network.PlayersRequestPayload;
import com.onthehill.chunkyfriends.network.PlayersResponsePayload;
import com.onthehill.chunkyfriends.network.StatusRequestPayload;
import com.onthehill.chunkyfriends.network.StatusResponsePayload;
import com.onthehill.chunkyfriends.scheduler.ChunkGridLayout;
import com.onthehill.chunkyfriends.scheduler.RingCurve;

/**
 * Client-side screen for viewing and changing the pregeneration scheduler's server-side configuration:
 * ring tier count, maximum radius, and curve shape (linear or quadratic); a live-editable, pannable/zoomable
 * ring preview over real sampled terrain; and read-only status/players text boxes. Values submitted here are
 * re-validated and permission-checked on the server before being applied.
 *
 * @implNote Laid out as three responsive columns — status/players text boxes on the left, the configuration
 *     form centered, the ring preview on the right — with every position computed from the screen's current
 *     {@code width}/{@code height} in {@link #init()} rather than fixed pixel constants, since {@code init()}
 *     re-runs whenever the window is resized. The one exception is the players box's vertical position, which
 *     is additionally recomputed every frame from the status box's actual measured content height (not a
 *     fixed guess), so a status line long enough to wrap never overlaps the players box below it.
 * @implNote All player-visible text on this screen is rendered through real widgets ({@link StringWidget},
 *     {@link MultiLineTextWidget}, {@link EditBox}, {@link Button}), added via {@link #addRenderableWidget},
 *     never via a raw {@code GuiGraphicsExtractor.text(...)} call from custom render code — the latter was
 *     tried first for the status/players/preview-info text and never actually appeared on screen, while
 *     fills, lines, and blits issued the same way (used for the ring circle and chunk grid) rendered fine.
 * @implNote The preview shows exactly one marker — the viewing player's own real position — rather than
 *     several synthetic offset players as an earlier version did. This preview exists to show what
 *     {@code PregenScheduler} would actually do for whoever is looking at it, and the scheduler only ever
 *     centers a ring job on a real player's own position; showing invented positions made it unclear what, if
 *     anything, the preview corresponded to.
 */
public final class ChunkyFriendsConfigScreen extends Screen
{
    private static final int DEFAULT_RING_COUNT = 10;
    private static final int DEFAULT_MAX_RADIUS_CHUNKS = 500;
    private static final boolean DEFAULT_QUADRATIC = true;
    private static final int FIELD_HEIGHT = 20;
    private static final int FORM_MARGIN = 10;
    private static final int COLUMN_GAP = 16;
    private static final int MIN_COLUMN_WIDTH = 150;
    private static final int MAX_FIELD_WIDTH = 200;
    private static final int MIN_FIELD_WIDTH = 120;

    private static final double LINEAR_CURVE_EXPONENT = 1.0;
    private static final double QUADRATIC_CURVE_EXPONENT = 2.0;

    private static final Identifier PREVIEW_TEXTURE_ID = ChunkyFriends.id("map_preview");
    private static final int MIN_PREVIEW_PANEL_SIZE = 100;
    private static final int PREVIEW_INFO_RESERVE = 70;
    private static final long PREVIEW_DEBOUNCE_MILLIS = 500L;
    private static final long LOADING_ANIMATION_STEP_MILLIS = 500L;
    private static final int FOG_ARGB = 0x80404040;
    private static final int GRID_LINE_COLOR = 0x50FFFFFF;
    private static final int PLAYER_RING_COLOR = 0xFFFFFF55;
    private static final int HEAD_ICON_SIZE = 8;

    /**
     * Lowest supported zoom. Below {@code 1.0} (which exactly fits the sampled square) purely to leave a
     * little visible margin around the outermost ring, which otherwise touches the panel's edge exactly at
     * {@code 1.0} since the ring is centered on the same point the terrain was sampled around.
     */
    private static final double MIN_PREVIEW_ZOOM = 0.4;
    private static final double MAX_PREVIEW_ZOOM = 8.0;
    private static final double PREVIEW_ZOOM_STEP_FACTOR = 1.25;

    private static final int BOX_TEXT_INSET = 4;
    private static final int BOX_GAP = 10;
    private static final int MAX_PLAYERS_LINES_SHOWN = 12;

    private static ChunkyFriendsConfigScreen _openInstance;

    private final Screen _parent;

    private EditBox _ringCountBox;
    private EditBox _maxRadiusBox;
    private CycleButton<Boolean> _curveButton;
    private boolean _quadratic;

    private MultiLineTextWidget _previewInfoWidget;
    private MultiLineTextWidget _statusTextWidget;
    private MultiLineTextWidget _playersTextWidget;

    // Layout metrics, recomputed in init() from the screen's current width/height.
    private int _previewPanelX;
    private int _previewPanelY;
    private int _previewPanelSize;
    private int _statusBoxX;
    private int _statusBoxY;
    private int _statusBoxWidth;
    // Recomputed every frame from the status box's actual measured content height.
    private int _statusBoxHeight;
    private int _playersBoxY;
    private int _playersBoxHeight;

    private MapPreviewResponsePayload _lastPreviewResponse;
    private DynamicTexture _previewTexture;
    private int _previewTextureSize;
    private boolean _previewRequestPending;
    private long _lastMaxRadiusEditEpochMillis;
    private boolean _previewTextureDirty;

    private double _previewZoom = 1.0;
    private double _previewPanBlockX;
    private double _previewPanBlockZ;
    private boolean _previewViewInitialized;
    private boolean _draggingPreview;
    private int _lastSentPreviewRequestId;
    private int _lastMouseX;
    private int _lastMouseY;

    private StatusResponsePayload _lastStatusResponse;
    private long _statusLastRefreshedEpochMillis;
    private PlayersResponsePayload _lastPlayersResponse;
    private long _playersLastRefreshedEpochMillis;

    /**
     * Constructs the configuration screen, returning to the in-game view on close.
     */
    public ChunkyFriendsConfigScreen()
    {
        this(null);
    }

    /**
     * Constructs the configuration screen.
     *
     * @param parent The screen to return to on close, e.g. ModMenu's mod list — or {@code null} to return to
     *     the in-game view instead.
     */
    public ChunkyFriendsConfigScreen(final Screen parent)
    {
        super(Component.translatable("gui.chunky-friends.config.title"));
        _parent = parent;
    }

    /**
     * Gets the currently open instance of this screen, if any.
     *
     * @return The open instance, or {@code null} if this screen is not currently displayed.
     */
    public static ChunkyFriendsConfigScreen getOpenInstance()
    {
        return _openInstance;
    }

    /**
     * Computes this screen's three-column layout (left: status/players, center: form, right: preview) from
     * the current window size, builds every widget, and requests fresh config/status/players/preview data —
     * so the screen opens already populated rather than blank. Re-run automatically whenever the window is
     * resized, since Minecraft re-invokes {@code init()} on resize; recomputing every position here from
     * {@code width}/{@code height} (rather than fixed pixel constants) is what makes the layout responsive.
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

        final int usableWidth = Math.max(0, width - 2 * FORM_MARGIN - 2 * COLUMN_GAP);
        final int columnWidth = Math.max(MIN_COLUMN_WIDTH, usableWidth / 3);
        final int leftColumnX = FORM_MARGIN;
        final int centerColumnX = FORM_MARGIN + columnWidth + COLUMN_GAP;
        final int rightColumnX = FORM_MARGIN + 2 * (columnWidth + COLUMN_GAP);
        final int fieldWidth = Math.max(MIN_FIELD_WIDTH, Math.min(MAX_FIELD_WIDTH, columnWidth - 20));

        _statusBoxX = leftColumnX;
        _statusBoxY = FORM_MARGIN + 20;
        _statusBoxWidth = columnWidth;

        _previewPanelY = FORM_MARGIN + 20;
        _previewPanelSize = Math.max(MIN_PREVIEW_PANEL_SIZE, Math.min(columnWidth, height - _previewPanelY - FORM_MARGIN - PREVIEW_INFO_RESERVE));
        _previewPanelX = rightColumnX + Math.max(0, (columnWidth - _previewPanelSize) / 2);

        final GridLayout layout = new GridLayout().columnSpacing(8).rowSpacing(8);
        final GridLayout.RowHelper rows = layout.createRowHelper(1);

        rows.addChild(new StringWidget(getTitle(), font));

        rows.addChild(new StringWidget(Component.translatable("gui.chunky-friends.config.ring_count"), font));
        _ringCountBox = rows.addChild(new EditBox(font, fieldWidth, FIELD_HEIGHT, Component.translatable("gui.chunky-friends.config.ring_count")));
        _ringCountBox.setValue(String.valueOf(initialRingCount));

        rows.addChild(new StringWidget(Component.translatable("gui.chunky-friends.config.max_radius"), font));
        _maxRadiusBox = rows.addChild(new EditBox(font, fieldWidth, FIELD_HEIGHT, Component.translatable("gui.chunky-friends.config.max_radius")));
        _maxRadiusBox.setValue(String.valueOf(initialMaxRadius * ConfigNetworking.BLOCKS_PER_CHUNK));
        _maxRadiusBox.setResponder(value ->
        {
            _previewRequestPending = true;
            _lastMaxRadiusEditEpochMillis = System.currentTimeMillis();
        });

        _curveButton = rows.addChild(CycleButton.booleanBuilder(
                        Component.translatable("gui.chunky-friends.config.curve_quadratic"),
                        Component.translatable("gui.chunky-friends.config.curve_linear"),
                        _quadratic)
                .create(0, 0, fieldWidth, FIELD_HEIGHT, Component.translatable("gui.chunky-friends.config.curve"), (button, value) -> _quadratic = value));

        rows.addChild(Button.builder(Component.translatable("gui.chunky-friends.config.save"), button -> onSave()).width(fieldWidth).build());
        rows.addChild(Button.builder(Component.translatable("gui.chunky-friends.config.cancel"), button -> onClose()).width(fieldWidth).build());

        rows.addChild(Button.builder(Component.translatable("gui.chunky-friends.config.status_button"), button -> refreshStatus()).width(fieldWidth).build());
        rows.addChild(Button.builder(Component.translatable("gui.chunky-friends.config.players_button"), button -> refreshPlayers()).width(fieldWidth).build());

        layout.arrangeElements();
        layout.setX(centerColumnX + Math.max(0, (columnWidth - layout.getWidth()) / 2));
        layout.setY(FORM_MARGIN);
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);

        final int previewInfoY = _previewPanelY + _previewPanelSize + 6;
        _previewInfoWidget = addRenderableWidget(new MultiLineTextWidget(_previewPanelX, previewInfoY, Component.empty(), font).setMaxWidth(_previewPanelSize));
        _statusTextWidget = addRenderableWidget(new MultiLineTextWidget(_statusBoxX + BOX_TEXT_INSET, _statusBoxY + BOX_TEXT_INSET, Component.empty(), font)
                .setMaxWidth(_statusBoxWidth - BOX_TEXT_INSET * 2));
        _playersTextWidget = addRenderableWidget(new MultiLineTextWidget(_statusBoxX + BOX_TEXT_INSET, _statusBoxY + BOX_TEXT_INSET, Component.empty(), font)
                .setMaxWidth(_statusBoxWidth - BOX_TEXT_INSET * 2));

        requestPreview();
        requestStatus();
        requestPlayers();
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
     * Fires {@code /chunkyfriends status} exactly as if the player had typed it — so chat/log feedback still
     * appears exactly like today — and the server additionally pushes a structured status snapshot back to
     * this screen from that same command invocation (see {@code ChunkyFriendsCommand.pushStatusToGuiIfPlayer}),
     * which {@link #applyStatusResponse} then renders in the status text box.
     */
    private void refreshStatus()
    {
        sendCommand("chunkyfriends status");
    }

    /**
     * Fires {@code /chunkyfriends players}; see {@link #refreshStatus} for why this alone is enough to also
     * update the players text box.
     */
    private void refreshPlayers()
    {
        sendCommand("chunkyfriends players");
    }

    /**
     * Silently requests a fresh status snapshot for the status text box, without running the chat command —
     * used when the screen first opens, so it isn't blank before the player has clicked anything.
     */
    private void requestStatus()
    {
        ClientPlayNetworking.send(new StatusRequestPayload());
    }

    /**
     * Silently requests a fresh players snapshot for the players text box; see {@link #requestStatus}.
     */
    private void requestPlayers()
    {
        ClientPlayNetworking.send(new PlayersRequestPayload());
    }

    /**
     * Sends a command to the server exactly as if the player had typed it themselves, reusing the client's own
     * chat-command-send path so the response (or a permission-denied failure) shows up in the chat overlay the
     * same way it would for a manually-typed command.
     *
     * @param command The command to send, without the leading {@code /}.
     */
    private void sendCommand(final String command)
    {
        if (minecraft != null && minecraft.player != null)
        {
            minecraft.player.connection.sendCommand(command);
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
     * Caches a newly received status snapshot and records the moment it arrived, for the status box's
     * "refreshed N seconds ago" line.
     *
     * @param response The received status snapshot.
     */
    public void applyStatusResponse(final StatusResponsePayload response)
    {
        _lastStatusResponse = response;
        _statusLastRefreshedEpochMillis = System.currentTimeMillis();
    }

    /**
     * Caches a newly received players snapshot and records the moment it arrived; see
     * {@link #applyStatusResponse}.
     *
     * @param response The received players snapshot.
     */
    public void applyPlayersResponse(final PlayersResponsePayload response)
    {
        _lastPlayersResponse = response;
        _playersLastRefreshedEpochMillis = System.currentTimeMillis();
    }

    /**
     * Closes this screen, returning to its parent screen if it was given one, or to the in-game view otherwise.
     */
    @Override
    public void onClose()
    {
        _openInstance = null;
        if (_previewTexture != null)
        {
            minecraft.getTextureManager().release(PREVIEW_TEXTURE_ID);
            _previewTexture = null;
            _previewTextureSize = 0;
        }
        minecraft.setScreenAndShow(_parent);
    }

    /**
     * Updates every dynamic widget's text and layout position, rebuilds the preview texture if the view
     * changed, draws the fixed-panel backgrounds, then defers to the normal widget rendering pass, then draws
     * the preview panel's map content (background texture, chunk grid, example-player heads/rings) on top.
     *
     * @param guiGraphics The frame's draw-call collector.
     * @param mouseX Current mouse x position.
     * @param mouseY Current mouse y position.
     * @param partialTick Fraction of a tick elapsed since the last full tick.
     */
    @Override
    public void extractRenderState(final GuiGraphicsExtractor guiGraphics, final int mouseX, final int mouseY, final float partialTick)
    {
        _lastMouseX = mouseX;
        _lastMouseY = mouseY;
        updateDynamicWidgetTextAndLayout();
        maybeSendDebouncedPreviewRequest();
        maybeRebuildPreviewTexture();
        renderPanelBackgrounds(guiGraphics);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        renderPreviewMapContent(guiGraphics, mouseX, mouseY);
    }

    /**
     * Refreshes every dynamic text box's content, then repositions the players box below the status box using
     * the status widget's own just-updated, actually-measured height — not a fixed guess — so a status line
     * long enough to wrap to extra lines never overlaps the players box underneath it.
     */
    private void updateDynamicWidgetTextAndLayout()
    {
        _previewInfoWidget.setMessage(buildPreviewInfoText());
        _statusTextWidget.setMessage(buildStatusPanelText());
        _statusBoxHeight = BOX_TEXT_INSET * 2 + _statusTextWidget.getHeight();

        _playersBoxY = _statusBoxY + _statusBoxHeight + BOX_GAP;
        _playersTextWidget.setMessage(buildPlayersPanelText());
        _playersTextWidget.setY(_playersBoxY + BOX_TEXT_INSET);
        _playersBoxHeight = BOX_TEXT_INSET * 2 + _playersTextWidget.getHeight();
    }

    private void renderPanelBackgrounds(final GuiGraphicsExtractor guiGraphics)
    {
        if (_lastPreviewResponse == null)
        {
            guiGraphics.fill(_previewPanelX, _previewPanelY, _previewPanelX + _previewPanelSize, _previewPanelY + _previewPanelSize, 0xFF202020);
        }
        renderBoxBackground(guiGraphics, _statusBoxX, _statusBoxY, _statusBoxWidth, _statusBoxHeight);
        renderBoxBackground(guiGraphics, _statusBoxX, _playersBoxY, _statusBoxWidth, _playersBoxHeight);
    }

    private void renderBoxBackground(final GuiGraphicsExtractor guiGraphics, final int x, final int y, final int boxWidth, final int boxHeight)
    {
        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0xCC101010);
        guiGraphics.fill(x, y, x + boxWidth, y + 1, 0xFF555555);
        guiGraphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFF555555);
        guiGraphics.fill(x, y, x + 1, y + boxHeight, 0xFF555555);
        guiGraphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, 0xFF555555);
    }

    private void maybeSendDebouncedPreviewRequest()
    {
        if (_previewRequestPending && System.currentTimeMillis() - _lastMaxRadiusEditEpochMillis >= PREVIEW_DEBOUNCE_MILLIS)
        {
            _previewRequestPending = false;
            requestPreview();
        }
    }

    private void requestPreview()
    {
        final OptionalInt parsed = ConfigNetworking.parseRadiusChunks(_maxRadiusBox.getValue());
        if (parsed.isEmpty())
        {
            // Left unsent; the field is transiently empty/invalid while the player is still typing. The
            // previous preview stays on screen until a valid value is entered.
            return;
        }
        final int radiusChunks = Math.min(Math.max(parsed.getAsInt(), ConfigNetworking.MIN_RADIUS_CHUNKS), ConfigNetworking.MAX_RADIUS_CHUNKS);
        // Sampling is streamed as several responses (see TerrainPreviewSampler); bumping this id each request
        // lets applyMapPreview tell a stale straggler from an earlier, now-superseded request apart from a
        // genuine next batch of the current one, so editing the radius again mid-stream can't paint a mix of
        // two different radii's data onto the same texture.
        _lastSentPreviewRequestId++;
        _previewViewInitialized = false;
        ClientPlayNetworking.send(new MapPreviewRequestPayload(radiusChunks, _lastSentPreviewRequestId));
    }

    /**
     * Applies a newly received terrain preview response: caches it, initializes the pan/zoom view the first
     * time a response for the current request arrives, and marks the on-screen texture dirty so it is rebuilt
     * from this new data on the next frame. A response whose {@link MapPreviewResponsePayload#requestId()}
     * doesn't match the most recently sent request is a straggler from a now-superseded request and is
     * ignored outright.
     *
     * @param response The received preview response.
     */
    public void applyMapPreview(final MapPreviewResponsePayload response)
    {
        if (response.requestId() != _lastSentPreviewRequestId)
        {
            return;
        }
        _lastPreviewResponse = response;
        if (!_previewViewInitialized)
        {
            _previewPanBlockX = response.originBlockX();
            _previewPanBlockZ = response.originBlockZ();
            _previewViewInitialized = true;
        }
        clampPreviewPan();
        _previewTextureDirty = true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Preview pan/zoom
    // ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(final MouseButtonEvent mouseButtonEvent, final boolean doubleClick)
    {
        if (mouseButtonEvent.button() == 0 && isInsidePreviewPanel((int) mouseButtonEvent.x(), (int) mouseButtonEvent.y()))
        {
            _draggingPreview = true;
            setDragging(true);
            return true;
        }
        return super.mouseClicked(mouseButtonEvent, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent mouseButtonEvent, final double dragX, final double dragY)
    {
        if (_draggingPreview)
        {
            final double effectiveBlocksPerPixel = currentEffectiveBlocksPerPixel();
            _previewPanBlockX -= dragX * effectiveBlocksPerPixel;
            _previewPanBlockZ -= dragY * effectiveBlocksPerPixel;
            clampPreviewPan();
            _previewTextureDirty = true;
            return true;
        }
        return super.mouseDragged(mouseButtonEvent, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent mouseButtonEvent)
    {
        if (mouseButtonEvent.button() == 0 && _draggingPreview)
        {
            _draggingPreview = false;
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseButtonEvent);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY)
    {
        if (scrollY != 0 && isInsidePreviewPanel((int) mouseX, (int) mouseY))
        {
            _previewZoom = clampZoom(_previewZoom * (scrollY > 0 ? PREVIEW_ZOOM_STEP_FACTOR : 1.0 / PREVIEW_ZOOM_STEP_FACTOR));
            clampPreviewPan();
            _previewTextureDirty = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInsidePreviewPanel(final int x, final int y)
    {
        return x >= _previewPanelX && x < _previewPanelX + _previewPanelSize
                && y >= _previewPanelY && y < _previewPanelY + _previewPanelSize;
    }

    private static double clampZoom(final double zoom)
    {
        return Math.min(MAX_PREVIEW_ZOOM, Math.max(MIN_PREVIEW_ZOOM, zoom));
    }

    /**
     * The world-blocks-per-panel-pixel scale the preview is currently rendered at, accounting for zoom. At
     * zoom {@code 1.0} this exactly fits the whole sampled square into the panel.
     */
    private double currentEffectiveBlocksPerPixel()
    {
        if (_lastPreviewResponse == null || _lastPreviewResponse.blocksPerPixel() <= 0)
        {
            return 1.0;
        }
        final double fitBlocksPerPixel = _lastPreviewResponse.blocksPerPixel() * _lastPreviewResponse.imageWidthPixels() / (double) _previewPanelSize;
        return fitBlocksPerPixel / _previewZoom;
    }

    /**
     * Keeps the pan center within the bounds of the actually-sampled square, so panning never shows beyond
     * the edge of real sampled data. At zoom levels below {@code 1.0}, where the view is already wider than
     * the sampled square, this simply locks panning to the center — there is nothing further out to pan to.
     */
    private void clampPreviewPan()
    {
        if (_lastPreviewResponse == null || _lastPreviewResponse.blocksPerPixel() <= 0)
        {
            return;
        }
        final double textureHalfWidthBlocks = (_lastPreviewResponse.imageWidthPixels() / 2.0) * _lastPreviewResponse.blocksPerPixel();
        final double viewHalfWidthBlocks = (_previewPanelSize / 2.0) * currentEffectiveBlocksPerPixel();
        final double maxOffset = Math.max(0, textureHalfWidthBlocks - viewHalfWidthBlocks);
        final double centerX = _lastPreviewResponse.originBlockX();
        final double centerZ = _lastPreviewResponse.originBlockZ();
        _previewPanBlockX = Math.min(centerX + maxOffset, Math.max(centerX - maxOffset, _previewPanBlockX));
        _previewPanBlockZ = Math.min(centerZ + maxOffset, Math.max(centerZ - maxOffset, _previewPanBlockZ));
    }

    private int worldToPanelX(final double worldBlockX, final double effectiveBlocksPerPixel)
    {
        return _previewPanelX + _previewPanelSize / 2 + (int) Math.round((worldBlockX - _previewPanBlockX) / effectiveBlocksPerPixel);
    }

    private int worldToPanelZ(final double worldBlockZ, final double effectiveBlocksPerPixel)
    {
        return _previewPanelY + _previewPanelSize / 2 + (int) Math.round((worldBlockZ - _previewPanBlockZ) / effectiveBlocksPerPixel);
    }

    // ------------------------------------------------------------------------------------------------------
    // Preview texture (CPU-resampled per current pan/zoom, then blitted 1:1 — see class Javadoc for why this
    // avoids relying on an independently-scaling blit overload)
    // ------------------------------------------------------------------------------------------------------

    private void maybeRebuildPreviewTexture()
    {
        if (!_previewTextureDirty || _lastPreviewResponse == null)
        {
            return;
        }
        _previewTextureDirty = false;

        final double effectiveBlocksPerPixel = currentEffectiveBlocksPerPixel();
        final int sourceWidth = _lastPreviewResponse.imageWidthPixels();
        final int sourceHeight = _lastPreviewResponse.imageHeightPixels();
        final byte[] colorGrid = _lastPreviewResponse.colorGrid();
        final double sourceBlocksPerPixel = _lastPreviewResponse.blocksPerPixel();
        final int sourceOriginX = _lastPreviewResponse.originBlockX();
        final int sourceOriginZ = _lastPreviewResponse.originBlockZ();

        final NativeImage image = new NativeImage(_previewPanelSize, _previewPanelSize, false);
        for (int destY = 0; destY < _previewPanelSize; destY++)
        {
            final double worldBlockZ = _previewPanBlockZ + (destY - _previewPanelSize / 2.0) * effectiveBlocksPerPixel;
            final int sourceY = (int) Math.round(sourceHeight / 2.0 + (worldBlockZ - sourceOriginZ) / sourceBlocksPerPixel);
            for (int destX = 0; destX < _previewPanelSize; destX++)
            {
                final double worldBlockX = _previewPanBlockX + (destX - _previewPanelSize / 2.0) * effectiveBlocksPerPixel;
                final int sourceX = (int) Math.round(sourceWidth / 2.0 + (worldBlockX - sourceOriginX) / sourceBlocksPerPixel);

                final int packedId = (sourceX < 0 || sourceX >= sourceWidth || sourceY < 0 || sourceY >= sourceHeight)
                        ? 0 : colorGrid[sourceY * sourceWidth + sourceX] & 0xFF;
                final int argb = packedId == 0 ? FOG_ARGB : (MapColor.getColorFromPackedId(packedId) | 0xFF000000);
                image.setPixelABGR(destX, destY, argbToAbgr(argb));
            }
        }

        if (_previewTexture == null || _previewTextureSize != _previewPanelSize)
        {
            if (_previewTexture != null)
            {
                _previewTexture.close();
            }
            _previewTexture = new DynamicTexture(() -> "chunky-friends-map-preview", image);
            _previewTextureSize = _previewPanelSize;
            minecraft.getTextureManager().register(PREVIEW_TEXTURE_ID, _previewTexture);
        }
        else
        {
            _previewTexture.setPixels(image);
        }
        _previewTexture.upload();
    }

    private static int argbToAbgr(final int argb)
    {
        final int alphaGreen = argb & 0xFF00FF00;
        final int red = (argb >> 16) & 0xFF;
        final int blue = argb & 0xFF;
        return alphaGreen | (blue << 16) | red;
    }

    // ------------------------------------------------------------------------------------------------------
    // Preview panel content (background texture, chunk grid, example-player heads/rings)
    // ------------------------------------------------------------------------------------------------------

    private void renderPreviewMapContent(final GuiGraphicsExtractor guiGraphics, final int mouseX, final int mouseY)
    {
        if (_lastPreviewResponse == null)
        {
            return;
        }

        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_TEXTURE_ID, _previewPanelX, _previewPanelY,
                0.0F, 0.0F, _previewPanelSize, _previewPanelSize, _previewPanelSize, _previewPanelSize);

        final double effectiveBlocksPerPixel = currentEffectiveBlocksPerPixel();
        renderChunkGrid(guiGraphics, effectiveBlocksPerPixel);
        renderPlayerMarker(guiGraphics, effectiveBlocksPerPixel, mouseX, mouseY);
    }

    private void renderChunkGrid(final GuiGraphicsExtractor guiGraphics, final double effectiveBlocksPerPixel)
    {
        final int gridStepChunks = ChunkGridLayout.computeGridStepChunks(effectiveBlocksPerPixel);

        final List<Integer> verticalLines = ChunkGridLayout.computeGridLinePixelPositions(
                (int) Math.round(_previewPanBlockX), effectiveBlocksPerPixel, _previewPanelSize, gridStepChunks);
        for (final int panelXOffset : verticalLines)
        {
            guiGraphics.verticalLine(_previewPanelX + panelXOffset, _previewPanelY, _previewPanelY + _previewPanelSize, GRID_LINE_COLOR);
        }

        final List<Integer> horizontalLines = ChunkGridLayout.computeGridLinePixelPositions(
                (int) Math.round(_previewPanBlockZ), effectiveBlocksPerPixel, _previewPanelSize, gridStepChunks);
        for (final int panelYOffset : horizontalLines)
        {
            guiGraphics.horizontalLine(_previewPanelX, _previewPanelX + _previewPanelSize, _previewPanelY + panelYOffset, GRID_LINE_COLOR);
        }
    }

    /**
     * Draws the viewing player's own ring set — centered on the same real-world position the terrain was
     * sampled around (see {@code TerrainPreviewSampler}) — plus their head icon on top.
     */
    private void renderPlayerMarker(final GuiGraphicsExtractor guiGraphics, final double effectiveBlocksPerPixel, final int mouseX, final int mouseY)
    {
        final int maxRadiusChunks = currentMaxRadiusChunksFieldValue();
        final int ringCount = currentRingCountFieldValue();
        final double curveExponent = _quadratic ? QUADRATIC_CURVE_EXPONENT : LINEAR_CURVE_EXPONENT;
        final int playerPanelX = worldToPanelX(_lastPreviewResponse.originBlockX(), effectiveBlocksPerPixel);
        final int playerPanelY = worldToPanelZ(_lastPreviewResponse.originBlockZ(), effectiveBlocksPerPixel);

        for (int tier = 1; tier <= ringCount; tier++)
        {
            final int radiusChunks = RingCurve.radiusForTier(tier, ringCount, maxRadiusChunks, curveExponent);
            final int radiusBlocks = radiusChunks * ConfigNetworking.BLOCKS_PER_CHUNK;
            final int radiusPanelPixels = (int) Math.round(radiusBlocks / effectiveBlocksPerPixel);
            drawCircleOutline(guiGraphics, playerPanelX, playerPanelY, radiusPanelPixels, PLAYER_RING_COLOR);
        }

        renderPlayerHead(guiGraphics, playerPanelX, playerPanelY, mouseX, mouseY);
    }

    /**
     * Draws a small default-skin player head icon at the viewing player's position, and — if the mouse is
     * currently hovering it — queues a tooltip naming them.
     */
    private void renderPlayerHead(final GuiGraphicsExtractor guiGraphics, final int panelX, final int panelY, final int mouseX, final int mouseY)
    {
        final int iconX = panelX - HEAD_ICON_SIZE / 2;
        final int iconY = panelY - HEAD_ICON_SIZE / 2;
        if (iconX + HEAD_ICON_SIZE < _previewPanelX || iconX > _previewPanelX + _previewPanelSize
                || iconY + HEAD_ICON_SIZE < _previewPanelY || iconY > _previewPanelY + _previewPanelSize)
        {
            // Off-panel at large radii/zoom — skip drawing, matches the ring-clipping behavior below.
            return;
        }
        final Identifier skinTexture = DefaultPlayerSkin.getDefaultTexture();
        // Base skin face layer (8x8 at UV 8,8), then the "hat" overlay layer (8x8 at UV 40,8) on top —
        // together the same two layers vanilla itself composites for a flat player head icon.
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, iconX, iconY, 8.0F, 8.0F, HEAD_ICON_SIZE, HEAD_ICON_SIZE, 64, 64);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, skinTexture, iconX, iconY, 40.0F, 8.0F, HEAD_ICON_SIZE, HEAD_ICON_SIZE, 64, 64);

        if (mouseX >= iconX && mouseX < iconX + HEAD_ICON_SIZE && mouseY >= iconY && mouseY < iconY + HEAD_ICON_SIZE
                && minecraft != null && minecraft.player != null)
        {
            guiGraphics.setTooltipForNextFrame(Component.literal(minecraft.player.getGameProfile().name()), mouseX, mouseY);
        }
    }

    private int currentMaxRadiusChunksFieldValue()
    {
        final OptionalInt parsed = ConfigNetworking.parseRadiusChunks(_maxRadiusBox.getValue());
        return parsed.isPresent() ? Math.max(0, parsed.getAsInt()) : 0;
    }

    private int currentRingCountFieldValue()
    {
        try
        {
            return Math.max(1, Integer.parseInt(_ringCountBox.getValue().trim()));
        }
        catch (final NumberFormatException exception)
        {
            return DEFAULT_RING_COUNT;
        }
    }

    /**
     * Draws a circle outline via the midpoint circle algorithm, clipped to the preview panel's bounds. Points
     * outside the panel (rings partially or fully off-panel at large radii) are silently skipped, not an
     * error condition.
     */
    private void drawCircleOutline(final GuiGraphicsExtractor guiGraphics, final int centerX, final int centerY, final int radius, final int color)
    {
        if (radius <= 0)
        {
            return;
        }
        int x = radius;
        int y = 0;
        int error = 1 - x;
        while (x >= y)
        {
            plotCircleOctants(guiGraphics, centerX, centerY, x, y, color);
            y++;
            if (error < 0)
            {
                error += 2 * y + 1;
            }
            else
            {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private void plotCircleOctants(final GuiGraphicsExtractor guiGraphics, final int cx, final int cy, final int x, final int y, final int color)
    {
        plotPixel(guiGraphics, cx + x, cy + y, color);
        plotPixel(guiGraphics, cx - x, cy + y, color);
        plotPixel(guiGraphics, cx + x, cy - y, color);
        plotPixel(guiGraphics, cx - x, cy - y, color);
        plotPixel(guiGraphics, cx + y, cy + x, color);
        plotPixel(guiGraphics, cx - y, cy + x, color);
        plotPixel(guiGraphics, cx + y, cy - x, color);
        plotPixel(guiGraphics, cx - y, cy - x, color);
    }

    private void plotPixel(final GuiGraphicsExtractor guiGraphics, final int x, final int y, final int color)
    {
        if (x < _previewPanelX || x >= _previewPanelX + _previewPanelSize || y < _previewPanelY || y >= _previewPanelY + _previewPanelSize)
        {
            return;
        }
        guiGraphics.fill(x, y, x + 1, y + 1, color);
    }

    // ------------------------------------------------------------------------------------------------------
    // Text box content builders
    // ------------------------------------------------------------------------------------------------------

    private Component buildPreviewInfoText()
    {
        if (_lastPreviewResponse == null)
        {
            return buildLoadingText();
        }
        final double effectiveBlocksPerPixel = currentEffectiveBlocksPerPixel();
        final double viewHalfWidthBlocks = (_previewPanelSize / 2.0) * effectiveBlocksPerPixel;
        final long minX = Math.round(_previewPanBlockX - viewHalfWidthBlocks);
        final long maxX = Math.round(_previewPanBlockX + viewHalfWidthBlocks);
        final long minZ = Math.round(_previewPanBlockZ - viewHalfWidthBlocks);
        final long maxZ = Math.round(_previewPanBlockZ + viewHalfWidthBlocks);
        final int gridStepChunks = ChunkGridLayout.computeGridStepChunks(effectiveBlocksPerPixel);

        MutableComponent text = Component.literal("X: " + minX + " to " + maxX)
                .append("\n").append(Component.literal("Z: " + minZ + " to " + maxZ))
                .append("\n").append(Component.translatable("gui.chunky-friends.config.scale_legend", gridStepChunks, gridStepChunks * ConfigNetworking.BLOCKS_PER_CHUNK))
                .append("\n").append(buildCursorPositionText(effectiveBlocksPerPixel));
        if (_lastPreviewResponse.clampedToMaxPreviewRadius())
        {
            text = text.append("\n").append(Component.translatable("message.chunky-friends.config.preview_limited"));
        }
        return text;
    }

    /**
     * Reports the world X/Z the mouse is currently hovering over, while it's within the preview panel — a
     * live readout, not a tooltip, so it stays visible and doesn't compete with the head icon's own tooltip.
     */
    private Component buildCursorPositionText(final double effectiveBlocksPerPixel)
    {
        if (!isInsidePreviewPanel(_lastMouseX, _lastMouseY))
        {
            return Component.translatable("gui.chunky-friends.config.cursor_position_none");
        }
        final long worldBlockX = Math.round(_previewPanBlockX + (_lastMouseX - _previewPanelX - _previewPanelSize / 2.0) * effectiveBlocksPerPixel);
        final long worldBlockZ = Math.round(_previewPanBlockZ + (_lastMouseY - _previewPanelY - _previewPanelSize / 2.0) * effectiveBlocksPerPixel);
        return Component.translatable("gui.chunky-friends.config.cursor_position", worldBlockX, worldBlockZ);
    }

    private Component buildStatusPanelText()
    {
        final MutableComponent header = Component.translatable("gui.chunky-friends.config.status_panel_title").copy()
                .append(" ").append(refreshedLabel(_statusLastRefreshedEpochMillis));
        return header.append("\n").append(buildStatusBodyComponent());
    }

    private Component buildStatusBodyComponent()
    {
        if (_lastStatusResponse == null)
        {
            return buildLoadingText();
        }
        if (!_lastStatusResponse.active())
        {
            return Component.translatable("command.chunky-friends.status.idle", _lastStatusResponse.eligibleCount());
        }
        MutableComponent message = Component.translatable("command.chunky-friends.status.active",
                _lastStatusResponse.playerDisplayName(), _lastStatusResponse.ringTier(), _lastStatusResponse.ringCount(),
                _lastStatusResponse.progressPercent(), _lastStatusResponse.chunks(), _lastStatusResponse.chunksPerSecond(),
                _lastStatusResponse.world());
        if (_lastStatusResponse.presencePaused())
        {
            message = message.append(Component.translatable("command.chunky-friends.status.active_paused_suffix"));
        }
        return message;
    }

    private Component buildPlayersPanelText()
    {
        MutableComponent text = Component.translatable("gui.chunky-friends.config.players_panel_title").copy()
                .append(" ").append(refreshedLabel(_playersLastRefreshedEpochMillis));

        if (_lastPlayersResponse == null)
        {
            return text.append("\n").append(buildLoadingText());
        }
        final List<PlayersResponsePayload.PlayerEntry> entries = _lastPlayersResponse.entries();
        if (entries.isEmpty())
        {
            return text.append("\n").append(Component.translatable("command.chunky-friends.players.none"));
        }
        final int shown = Math.min(entries.size(), MAX_PLAYERS_LINES_SHOWN);
        for (int i = 0; i < shown; i++)
        {
            final PlayersResponsePayload.PlayerEntry entry = entries.get(i);
            MutableComponent line = Component.translatable("command.chunky-friends.players.entry", entry.displayName(), entry.ringTier(), entry.ringCount());
            if (entry.active())
            {
                line = line.append(Component.translatable("command.chunky-friends.players.entry_active_suffix"));
            }
            text = text.append("\n").append(line);
        }
        if (entries.size() > shown)
        {
            text = text.append("\n").append(Component.translatable("gui.chunky-friends.config.players_more", entries.size() - shown));
        }
        return text;
    }

    /**
     * Builds an animated "Loading" placeholder cycling zero to three trailing dots — "Loading", "Loading.",
     * "Loading..", "Loading..." — one step every {@value #LOADING_ANIMATION_STEP_MILLIS}ms, repeating. Driven
     * off the wall clock rather than a frame counter, so it stays in sync however often this is called.
     */
    private static Component buildLoadingText()
    {
        final int dotCount = (int) ((System.currentTimeMillis() / LOADING_ANIMATION_STEP_MILLIS) % 4);
        return Component.translatable("message.chunky-friends.config.loading", ".".repeat(dotCount));
    }

    private Component refreshedLabel(final long refreshedEpochMillis)
    {
        if (refreshedEpochMillis == 0)
        {
            return Component.translatable("gui.chunky-friends.config.refreshed_never");
        }
        final int secondsAgo = (int) Math.max(0, (System.currentTimeMillis() - refreshedEpochMillis) / 1000);
        return Component.translatable("gui.chunky-friends.config.refreshed_seconds_ago", secondsAgo);
    }
}
