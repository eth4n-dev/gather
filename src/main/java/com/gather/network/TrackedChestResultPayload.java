package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public record TrackedChestResultPayload(Map<Long, Map<String, Integer>> chestItemCounts) implements CustomPayload {

    public static final Id<TrackedChestResultPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "tracked_chest_result"));

    public static final PacketCodec<RegistryByteBuf, TrackedChestResultPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeInt(v.chestItemCounts().size());
                        v.chestItemCounts().forEach((pos, counts) -> {
                            buf.writeLong(pos);
                            buf.writeInt(counts.size());
                            counts.forEach((id, count) -> {
                                buf.writeString(id);
                                buf.writeInt(count);
                            });
                        });
                    },
                    buf -> {
                        int chestCount = buf.readInt();
                        Map<Long, Map<String, Integer>> map = new HashMap<>();
                        for (int i = 0; i < chestCount; i++) {
                            long pos = buf.readLong();
                            int size = buf.readInt();
                            Map<String, Integer> counts = new HashMap<>();
                            for (int j = 0; j < size; j++) {
                                counts.put(buf.readString(), buf.readInt());
                            }
                            map.put(pos, counts);
                        }
                        return new TrackedChestResultPayload(map);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
