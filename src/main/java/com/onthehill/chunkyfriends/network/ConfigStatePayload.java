package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Server -> client snapshot of the pregeneration scheduler's client-editable configuration values.
 *
 * @param ringCount Number of ring tiers a player's coverage progresses through.
 * @param maxRadiusChunks Ceiling radius, in chunks, that the outermost ring tier grows to.
 * @param quadratic {@code true} for the quadratic ease-in curve, {@code false} for linear.
 */
public record ConfigStatePayload(int ringCount, int maxRadiusChunks, boolean quadratic) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<ConfigStatePayload> TYPE = new Type<>(ChunkyFriends.id("config_state_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, ConfigStatePayload> CODEC = CustomPacketPayload.codec(ConfigStatePayload::write, ConfigStatePayload::new);

    private ConfigStatePayload(final FriendlyByteBuf buf)
    {
        this(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeVarInt(ringCount);
        buf.writeVarInt(maxRadiusChunks);
        buf.writeBoolean(quadratic);
    }

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<ConfigStatePayload> type()
    {
        return TYPE;
    }
}
