package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ChestScanRequestPayload(int radius) implements CustomPayload {

    public static final Id<ChestScanRequestPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "chest_scan_request"));

    public static final PacketCodec<RegistryByteBuf, ChestScanRequestPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> buf.writeInt(v.radius()),
                    buf -> new ChestScanRequestPayload(buf.readInt())
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
