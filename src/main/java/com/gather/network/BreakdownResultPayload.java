package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record BreakdownResultPayload(String originItemId, List<BreakdownEntry> entries, boolean inventoryCraftable) implements CustomPayload {

    public static final Id<BreakdownResultPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "breakdown_result"));

    public static final PacketCodec<RegistryByteBuf, BreakdownResultPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeString(v.originItemId());
                        buf.writeInt(v.entries().size());
                        for (BreakdownEntry e : v.entries()) {
                            buf.writeInt(e.itemIds().size());
                            for (String id : e.itemIds()) buf.writeString(id);
                            buf.writeInt(e.count());
                        }
                        buf.writeBoolean(v.inventoryCraftable());
                    },
                    buf -> {
                        String origin = buf.readString();
                        int size = buf.readInt();
                        List<BreakdownEntry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            int altSize = buf.readInt();
                            List<String> ids = new ArrayList<>();
                            for (int j = 0; j < altSize; j++) ids.add(buf.readString());
                            entries.add(new BreakdownEntry(ids, buf.readInt()));
                        }
                        return new BreakdownResultPayload(origin, entries, buf.readBoolean());
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
