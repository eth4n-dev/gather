package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record PlacedCollectorPositionsPayload(List<Entry> entries) implements CustomPayload {

    public record Entry(long pos, boolean allMode, List<String> items) {}

    public static final Id<PlacedCollectorPositionsPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "collector_positions"));

    public static final PacketCodec<RegistryByteBuf, PlacedCollectorPositionsPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeInt(v.entries().size());
                        for (Entry e : v.entries()) {
                            buf.writeLong(e.pos());
                            buf.writeBoolean(e.allMode());
                            buf.writeInt(e.items().size());
                            for (String item : e.items()) buf.writeString(item);
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
                            for (int j = 0; j < itemCount; j++) items.add(buf.readString());
                            entries.add(new Entry(pos, allMode, items));
                        }
                        return new PlacedCollectorPositionsPayload(entries);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
