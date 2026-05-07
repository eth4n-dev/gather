package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record XrayPermissionPayload(boolean allowed) implements CustomPayload {
    public static final Id<XrayPermissionPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "xray_permission"));

    public static final PacketCodec<RegistryByteBuf, XrayPermissionPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> buf.writeBoolean(v.allowed()),
                    buf -> new XrayPermissionPayload(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
