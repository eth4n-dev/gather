package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record PlacedCollectorPositionsPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(long pos, boolean allMode, List<String> items) {}

    public static final CustomPacketPayload.Type<PlacedCollectorPositionsPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "collector_positions"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlacedCollectorPositionsPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.entries().size());
                        for (Entry e : v.entries()) {
                            buf.writeLong(e.pos());
                            buf.writeBoolean(e.allMode());
                            buf.writeInt(e.items().size());
                            for (String item : e.items()) buf.writeUtf(item);
                        }
                    },
                    buf -> {
                        int size = buf.readInt();
                        List<Entry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            long pos = buf.readLong();
                            boolean allMode = buf.readBoolean();
                            int itemCount = buf.readInt();
                            List<String> items = new ArrayList<>();
                            for (int j = 0; j < itemCount; j++) items.add(buf.readUtf());
                            entries.add(new Entry(pos, allMode, items));
                        }
                        return new PlacedCollectorPositionsPayload(entries);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
