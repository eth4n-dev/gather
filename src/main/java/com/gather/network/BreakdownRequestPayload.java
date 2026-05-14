package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BreakdownRequestPayload(String itemId, int count, int depth) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BreakdownRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "breakdown_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BreakdownRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> { buf.writeUtf(v.itemId()); buf.writeInt(v.count()); buf.writeInt(v.depth()); },
                    buf -> new BreakdownRequestPayload(buf.readUtf(), buf.readInt(), buf.readInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
