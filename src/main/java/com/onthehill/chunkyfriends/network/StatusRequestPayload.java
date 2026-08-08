package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Client -> server silent request for the current pregeneration status, used to populate the config screen's
 * status panel when it opens, without running the {@code /chunkyfriends status} command (which would also
 * print chat feedback and a log line).
 */
public record StatusRequestPayload() implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<StatusRequestPayload> TYPE = new Type<>(ChunkyFriends.id("status_request_v1"));

    /**
     * Codec for this payload. Carries no data, so it always decodes to the same singleton instance.
     */
    public static final StreamCodec<FriendlyByteBuf, StatusRequestPayload> CODEC = StreamCodec.unit(new StatusRequestPayload());

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<StatusRequestPayload> type()
    {
        return TYPE;
    }
}
