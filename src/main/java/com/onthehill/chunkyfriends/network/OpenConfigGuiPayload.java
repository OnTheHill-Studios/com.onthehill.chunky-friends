package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Server -> client instruction to open the pregeneration scheduler's configuration screen, sent in response
 * to a player successfully running {@code /chunkyfriends gui}. Always immediately preceded by a
 * {@link ConfigStatePayload} carrying current values, so the screen opens already populated rather than
 * showing built-in defaults for a moment.
 */
public record OpenConfigGuiPayload() implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<OpenConfigGuiPayload> TYPE = new Type<>(ChunkyFriends.id("open_gui_v1"));

    /**
     * Codec for this payload. Carries no data, so it always decodes to the same singleton instance.
     */
    public static final StreamCodec<FriendlyByteBuf, OpenConfigGuiPayload> CODEC = StreamCodec.unit(new OpenConfigGuiPayload());

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<OpenConfigGuiPayload> type()
    {
        return TYPE;
    }
}
