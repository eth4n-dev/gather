package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BreakdownRequestPayload(String itemId, int count, int depth) implements CustomPayload {

    public static final Id<BreakdownRequestPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "breakdown_request"));

    public static final PacketCodec<RegistryByteBuf, BreakdownRequestPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> { buf.writeString(v.itemId()); buf.writeInt(v.count()); buf.writeInt(v.depth()); },
                    buf -> new BreakdownRequestPayload(buf.readString(), buf.readInt(), buf.readInt())
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
