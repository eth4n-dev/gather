package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ChestScanRequestPayload(int radius) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChestScanRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "chest_scan_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestScanRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> buf.writeInt(v.radius()),
                    buf -> new ChestScanRequestPayload(buf.readInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
