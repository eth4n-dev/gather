package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record XrayPermissionPayload(boolean allowed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<XrayPermissionPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "xray_permission"));

    public static final StreamCodec<RegistryFriendlyByteBuf, XrayPermissionPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> buf.writeBoolean(v.allowed()),
                    buf -> new XrayPermissionPayload(buf.readBoolean())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
