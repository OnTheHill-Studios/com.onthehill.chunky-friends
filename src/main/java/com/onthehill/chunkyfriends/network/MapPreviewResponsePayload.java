package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Server -> client response carrying a sampled terrain preview image, for the config screen's ring preview
 * panel background. Sampling a large radius is streamed as a sequence of these — each covering strictly more
 * of {@code colorGrid} than the last, nearest-to-player chunks resolved first — rather than one response sent
 * only once every pixel is ready, so the client can start showing terrain immediately and fill it in
 * progressively instead of waiting for the whole thing.
 *
 * @param imageWidthPixels Width of {@code colorGrid}, in pixels.
 * @param imageHeightPixels Height of {@code colorGrid}, in pixels.
 * @param blocksPerPixel Blocks of world space represented by one pixel of {@code colorGrid}.
 * @param originBlockX Block x coordinate the image is centered on (the requesting player's position).
 * @param originBlockZ Block z coordinate the image is centered on (the requesting player's position).
 * @param clampedToMaxPreviewRadius {@code true} if the requested radius exceeded the supported preview
 *     ceiling and this image only covers a clamped subset of it.
 * @param colorGrid {@code imageWidthPixels * imageHeightPixels} bytes, row-major, one byte per pixel — either
 *     a packed vanilla map color id/brightness byte, or the sentinel {@code 0} meaning "not resolved yet, or
 *     ungenerated — render as fog."
 * @param requestId Echoes the {@link MapPreviewRequestPayload#requestId()} this response is sampling for, so
 *     the client can discard responses that no longer belong to its most recent request.
 * @param isFinalUpdate {@code true} if this is the last response in the stream for this request — every pixel
 *     that will ever be resolved has been.
 */
public record MapPreviewResponsePayload(int imageWidthPixels, int imageHeightPixels, double blocksPerPixel,
                                         int originBlockX, int originBlockZ, boolean clampedToMaxPreviewRadius,
                                         byte[] colorGrid, int requestId, boolean isFinalUpdate) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<MapPreviewResponsePayload> TYPE = new Type<>(ChunkyFriends.id("map_preview_response_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, MapPreviewResponsePayload> CODEC = CustomPacketPayload.codec(MapPreviewResponsePayload::write, MapPreviewResponsePayload::new);

    private MapPreviewResponsePayload(final FriendlyByteBuf buf)
    {
        this(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readByteArray(),
                buf.readVarInt(), buf.readBoolean());
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeVarInt(imageWidthPixels);
        buf.writeVarInt(imageHeightPixels);
        buf.writeDouble(blocksPerPixel);
        buf.writeVarInt(originBlockX);
        buf.writeVarInt(originBlockZ);
        buf.writeBoolean(clampedToMaxPreviewRadius);
        buf.writeByteArray(colorGrid);
        buf.writeVarInt(requestId);
        buf.writeBoolean(isFinalUpdate);
    }

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<MapPreviewResponsePayload> type()
    {
        return TYPE;
    }
}
