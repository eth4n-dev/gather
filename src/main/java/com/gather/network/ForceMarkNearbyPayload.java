package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ForceMarkNearbyPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ForceMarkNearbyPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "force_mark_nearby"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForceMarkNearbyPayload> CODEC =
            StreamCodec.of((buf, v) -> {}, buf -> new ForceMarkNearbyPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
