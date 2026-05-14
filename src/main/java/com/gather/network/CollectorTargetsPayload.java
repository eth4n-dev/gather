package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record CollectorTargetsPayload(List<String> neededItemIds) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CollectorTargetsPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "collector_targets"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CollectorTargetsPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.neededItemIds().size());
                        for (String id : v.neededItemIds()) buf.writeUtf(id);
                    },
                    buf -> {
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readUtf());
                        return new CollectorTargetsPayload(ids);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
