package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record XrayCommandPayload(boolean enable) implements CustomPayload {
    public static final Id<XrayCommandPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "xray_command"));

    public static final PacketCodec<RegistryByteBuf, XrayCommandPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> buf.writeBoolean(v.enable()),
                    buf -> new XrayCommandPayload(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
