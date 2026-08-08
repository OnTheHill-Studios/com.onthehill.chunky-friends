package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Server -> client structured pregeneration status snapshot, for the config screen's status panel. Carries
 * the same information as {@code /chunkyfriends status}'s chat/log output, structured for GUI rendering
 * rather than as pre-formatted text.
 *
 * @param active {@code true} if a pregeneration job is currently active.
 * @param playerDisplayName The active job's player display name; empty if {@code active} is {@code false}.
 * @param world The active job's world identifier; empty if {@code active} is {@code false}.
 * @param ringTier The active job's in-progress ring tier; {@code 0} if {@code active} is {@code false}.
 * @param ringCount Configured ring tier count at the time of this snapshot.
 * @param progressPercent The active job's most recent progress percentage; {@code 0} if {@code active} is
 *     {@code false}.
 * @param chunks The active job's most recent chunk count; {@code 0} if {@code active} is {@code false}.
 * @param chunksPerSecond The active job's most recent generation rate; {@code 0} if {@code active} is
 *     {@code false}.
 * @param presencePaused Whether the active job is currently presence-paused; {@code false} if {@code active}
 *     is {@code false}.
 * @param eligibleCount Number of players currently eligible (not evicted by the qualifying window).
 */
public record StatusResponsePayload(boolean active, String playerDisplayName, String world, int ringTier, int ringCount,
                                     double progressPercent, long chunks, double chunksPerSecond, boolean presencePaused,
                                     int eligibleCount) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<StatusResponsePayload> TYPE = new Type<>(ChunkyFriends.id("status_response_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, StatusResponsePayload> CODEC = CustomPacketPayload.codec(StatusResponsePayload::write, StatusResponsePayload::new);

    private StatusResponsePayload(final FriendlyByteBuf buf)
    {
        this(buf.readBoolean(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(), buf.readVarLong(), buf.readDouble(), buf.readBoolean(), buf.readVarInt());
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeBoolean(active);
        buf.writeUtf(playerDisplayName);
        buf.writeUtf(world);
        buf.writeVarInt(ringTier);
        buf.writeVarInt(ringCount);
        buf.writeDouble(progressPercent);
        buf.writeVarLong(chunks);
        buf.writeDouble(chunksPerSecond);
        buf.writeBoolean(presencePaused);
        buf.writeVarInt(eligibleCount);
    }

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<StatusResponsePayload> type()
    {
        return TYPE;
    }
}
