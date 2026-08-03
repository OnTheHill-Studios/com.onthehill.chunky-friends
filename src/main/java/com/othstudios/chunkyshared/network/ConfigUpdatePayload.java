package com.othstudios.chunkyshared.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.othstudios.chunkyshared.ChunkyShared;

/**
 * Client -> server request to change the pregeneration scheduler's client-editable configuration values.
 *
 * @param ringCount Requested number of ring tiers.
 * @param maxRadiusChunks Requested ceiling radius, in chunks.
 * @param quadratic {@code true} to request the quadratic ease-in curve, {@code false} for linear.
 */
public record ConfigUpdatePayload(int ringCount, int maxRadiusChunks, boolean quadratic) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<ConfigUpdatePayload> TYPE = new Type<>(ChunkyShared.id("config_update_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, ConfigUpdatePayload> CODEC = CustomPacketPayload.codec(ConfigUpdatePayload::write, ConfigUpdatePayload::new);

    private ConfigUpdatePayload(final FriendlyByteBuf buf)
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
    public Type<ConfigUpdatePayload> type()
    {
        return TYPE;
    }
}
