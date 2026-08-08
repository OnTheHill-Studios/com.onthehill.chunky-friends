package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Client -> server request for a terrain preview image sampled around the requesting player's current
 * position, for the config screen's ring preview panel.
 *
 * @param requestedRadiusChunks The config screen's currently-entered {@code maxRadiusChunks} value — not
 *     necessarily saved yet.
 * @param requestId A client-assigned, monotonically increasing identifier for this request, echoed back on
 *     every {@link MapPreviewResponsePayload} sampling it produces. Sampling is streamed as a sequence of
 *     incremental responses (see {@code TerrainPreviewSampler}), so if the player edits the radius again
 *     before an earlier request's stream finishes, the client uses this to tell which responses belong to its
 *     latest request and discard the rest, rather than showing a mix of two different radii's data.
 */
public record MapPreviewRequestPayload(int requestedRadiusChunks, int requestId) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<MapPreviewRequestPayload> TYPE = new Type<>(ChunkyFriends.id("map_preview_request_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, MapPreviewRequestPayload> CODEC = CustomPacketPayload.codec(MapPreviewRequestPayload::write, MapPreviewRequestPayload::new);

    private MapPreviewRequestPayload(final FriendlyByteBuf buf)
    {
        this(buf.readVarInt(), buf.readVarInt());
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeVarInt(requestedRadiusChunks);
        buf.writeVarInt(requestId);
    }

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<MapPreviewRequestPayload> type()
    {
        return TYPE;
    }
}
