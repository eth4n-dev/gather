package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ForceMarkNearbyPayload() implements CustomPayload {

    public static final Id<ForceMarkNearbyPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "force_mark_nearby"));

    public static final PacketCodec<RegistryByteBuf, ForceMarkNearbyPayload> CODEC =
            PacketCodec.of((v, buf) -> {}, buf -> new ForceMarkNearbyPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
