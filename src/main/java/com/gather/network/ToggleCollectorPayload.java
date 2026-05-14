package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ToggleCollectorPayload(boolean enabled, boolean allMode, List<String> selectedItemIds, boolean leaveOne) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ToggleCollectorPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "toggle_collector"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleCollectorPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeBoolean(v.enabled());
                        buf.writeBoolean(v.allMode());
                        buf.writeInt(v.selectedItemIds().size());
                        for (String id : v.selectedItemIds()) buf.writeUtf(id);
                        buf.writeBoolean(v.leaveOne());
                    },
                    buf -> {
                        boolean enabled = buf.readBoolean();
                        boolean allMode = buf.readBoolean();
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readUtf());
                        boolean leaveOne = buf.readBoolean();
                        return new ToggleCollectorPayload(enabled, allMode, ids, leaveOne);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
