package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record XrayCommandPayload(boolean enable) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<XrayCommandPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "xray_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, XrayCommandPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> buf.writeBoolean(v.enable()),
                    buf -> new XrayCommandPayload(buf.readBoolean())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
