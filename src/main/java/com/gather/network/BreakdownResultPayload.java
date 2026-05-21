package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record BreakdownResultPayload(String originItemId, List<BreakdownEntry> entries, boolean inventoryCraftable) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BreakdownResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "breakdown_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BreakdownResultPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeUtf(v.originItemId());
                        buf.writeInt(v.entries().size());
                        for (BreakdownEntry e : v.entries()) {
                            buf.writeInt(e.itemIds().size());
                            for (String id : e.itemIds()) buf.writeUtf(id);
                            buf.writeInt(e.count());
                        }
                        buf.writeBoolean(v.inventoryCraftable());
                    },
                    buf -> {
                        String origin = buf.readUtf();
                        int size = buf.readInt();
                        List<BreakdownEntry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            int altSize = buf.readInt();
                            List<String> ids = new ArrayList<>();
                            for (int j = 0; j < altSize; j++) ids.add(buf.readUtf());
                            entries.add(new BreakdownEntry(ids, buf.readInt()));
                        }
                        return new BreakdownResultPayload(origin, entries, buf.readBoolean());
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
