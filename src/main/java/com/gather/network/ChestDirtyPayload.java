package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ChestDirtyPayload(long posLong) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChestDirtyPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "chest_dirty"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestDirtyPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> buf.writeLong(v.posLong()),
                    buf -> new ChestDirtyPayload(buf.readLong())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
