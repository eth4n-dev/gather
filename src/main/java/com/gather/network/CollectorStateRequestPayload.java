package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CollectorStateRequestPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CollectorStateRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "collector_state_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CollectorStateRequestPayload> CODEC =
            StreamCodec.of((buf, v) -> {}, buf -> new CollectorStateRequestPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
