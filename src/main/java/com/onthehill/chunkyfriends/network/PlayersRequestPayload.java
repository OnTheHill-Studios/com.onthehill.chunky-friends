package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Client -> server silent request for the current list of eligible players, used to populate the config
 * screen's players panel when it opens, without running the {@code /chunkyfriends players} command (which
 * would also print chat feedback and log lines).
 */
public record PlayersRequestPayload() implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<PlayersRequestPayload> TYPE = new Type<>(ChunkyFriends.id("players_request_v1"));

    /**
     * Codec for this payload. Carries no data, so it always decodes to the same singleton instance.
     */
    public static final StreamCodec<FriendlyByteBuf, PlayersRequestPayload> CODEC = StreamCodec.unit(new PlayersRequestPayload());

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<PlayersRequestPayload> type()
    {
        return TYPE;
    }
}
