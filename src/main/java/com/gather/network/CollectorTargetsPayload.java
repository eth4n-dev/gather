package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record CollectorTargetsPayload(List<String> neededItemIds) implements CustomPayload {

    public static final Id<CollectorTargetsPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "collector_targets"));

    public static final PacketCodec<RegistryByteBuf, CollectorTargetsPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeInt(v.neededItemIds().size());
                        for (String id : v.neededItemIds()) buf.writeString(id);
                    },
                    buf -> {
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readString());
                        return new CollectorTargetsPayload(ids);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
