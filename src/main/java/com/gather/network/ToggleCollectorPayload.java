package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ToggleCollectorPayload(boolean enabled, boolean allMode, List<String> selectedItemIds, boolean leaveOne) implements CustomPayload {

    public static final Id<ToggleCollectorPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "toggle_collector"));

    public static final PacketCodec<RegistryByteBuf, ToggleCollectorPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeBoolean(v.enabled());
                        buf.writeBoolean(v.allMode());
                        buf.writeInt(v.selectedItemIds().size());
                        for (String id : v.selectedItemIds()) buf.writeString(id);
                        buf.writeBoolean(v.leaveOne());
                    },
                    buf -> {
                        boolean enabled = buf.readBoolean();
                        boolean allMode = buf.readBoolean();
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readString());
                        boolean leaveOne = buf.readBoolean();
                        return new ToggleCollectorPayload(enabled, allMode, ids, leaveOne);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
