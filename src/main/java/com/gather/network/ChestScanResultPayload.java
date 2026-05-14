package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public record ChestScanResultPayload(Map<String, Integer> itemCounts) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChestScanResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "chest_scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestScanResultPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.itemCounts().size());
                        v.itemCounts().forEach((id, count) -> {
                            buf.writeUtf(id);
                            buf.writeInt(count);
                        });
                    },
                    buf -> {
                        int size = buf.readInt();
                        Map<String, Integer> map = new HashMap<>();
                        for (int i = 0; i < size; i++) {
                            map.put(buf.readUtf(), buf.readInt());
                        }
                        return new ChestScanResultPayload(map);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
