package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public record TrackedChestResultPayload(Map<Long, Map<String, Integer>> chestItemCounts) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TrackedChestResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "tracked_chest_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackedChestResultPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.chestItemCounts().size());
                        v.chestItemCounts().forEach((pos, counts) -> {
                            buf.writeLong(pos);
                            buf.writeInt(counts.size());
                            counts.forEach((id, count) -> {
                                buf.writeUtf(id);
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
                                counts.put(buf.readUtf(), buf.readInt());
                            }
                            map.put(pos, counts);
                        }
                        return new TrackedChestResultPayload(map);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
