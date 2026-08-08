package com.onthehill.chunkyfriends.network;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Server -> client structured list of currently-eligible players, for the config screen's players panel.
 * Carries the same information as {@code /chunkyfriends players}'s chat/log output, structured for GUI
 * rendering rather than as pre-formatted text.
 *
 * @param entries One entry per currently-eligible player, sorted the same way the command output is.
 */
public record PlayersResponsePayload(List<PlayerEntry> entries) implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     */
    public static final CustomPacketPayload.Type<PlayersResponsePayload> TYPE = new Type<>(ChunkyFriends.id("players_response_v1"));

    /**
     * Codec for this payload.
     */
    public static final StreamCodec<FriendlyByteBuf, PlayersResponsePayload> CODEC = CustomPacketPayload.codec(PlayersResponsePayload::write, PlayersResponsePayload::new);

    private PlayersResponsePayload(final FriendlyByteBuf buf)
    {
        this(buf.readList(PlayersResponsePayload::readEntry));
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeCollection(entries, PlayersResponsePayload::writeEntry);
    }

    private static PlayerEntry readEntry(final FriendlyByteBuf buf)
    {
        return new PlayerEntry(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    private static void writeEntry(final FriendlyByteBuf buf, final PlayerEntry entry)
    {
        buf.writeUtf(entry.displayName());
        buf.writeVarInt(entry.ringTier());
        buf.writeVarInt(entry.ringCount());
        buf.writeBoolean(entry.active());
    }

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<PlayersResponsePayload> type()
    {
        return TYPE;
    }

    /**
     * A single eligible player's entry in a {@link PlayersResponsePayload}.
     *
     * @param displayName The player's last-known display name, or their UUID's string form if never resolved.
     * @param ringTier The player's current ring tier.
     * @param ringCount Configured ring tier count at the time of this snapshot.
     * @param active Whether this player currently has the active pregeneration job.
     */
    public record PlayerEntry(String displayName, int ringTier, int ringCount, boolean active)
    {
    }
}
