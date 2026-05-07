package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ChestDirtyPayload(long posLong) implements CustomPayload {

    public static final Id<ChestDirtyPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "chest_dirty"));

    public static final PacketCodec<RegistryByteBuf, ChestDirtyPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> buf.writeLong(v.posLong()),
                    buf -> new ChestDirtyPayload(buf.readLong())
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
