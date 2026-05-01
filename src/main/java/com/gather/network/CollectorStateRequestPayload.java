package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CollectorStateRequestPayload() implements CustomPayload {
    public static final Id<CollectorStateRequestPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "collector_state_request"));

    public static final PacketCodec<RegistryByteBuf, CollectorStateRequestPayload> CODEC =
            PacketCodec.of((v, buf) -> {}, buf -> new CollectorStateRequestPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
