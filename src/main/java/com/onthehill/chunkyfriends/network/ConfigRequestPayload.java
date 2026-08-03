package com.onthehill.chunkyfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.onthehill.chunkyfriends.ChunkyFriends;

/**
 * Client -> server request for the pregeneration scheduler's current client-editable configuration values.
 */
public record ConfigRequestPayload() implements CustomPacketPayload
{
    /**
     * This payload's registered type and identifier.
     *
     * @implNote The {@code _v1} suffix is a schema version, not a mod version. A future incompatible change
     *     to this payload's fields must bump the suffix on all three config payloads' channel identifiers,
     *     never change the fields under the same identifier — an unversioned change risks a client and
     *     server silently disagreeing on byte layout instead of cleanly reporting "unsupported."
     */
    public static final CustomPacketPayload.Type<ConfigRequestPayload> TYPE = new Type<>(ChunkyFriends.id("config_request_v1"));

    /**
     * Codec for this payload. Carries no data, so it always decodes to the same singleton instance.
     */
    public static final StreamCodec<FriendlyByteBuf, ConfigRequestPayload> CODEC = StreamCodec.unit(new ConfigRequestPayload());

    /**
     * Gets this payload's registered type.
     *
     * @return {@link #TYPE}.
     */
    @Override
    public Type<ConfigRequestPayload> type()
    {
        return TYPE;
    }
}
