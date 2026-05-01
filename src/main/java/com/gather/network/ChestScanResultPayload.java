package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public record ChestScanResultPayload(Map<String, Integer> itemCounts) implements CustomPayload {

    public static final Id<ChestScanResultPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "chest_scan_result"));

    public static final PacketCodec<RegistryByteBuf, ChestScanResultPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeInt(v.itemCounts().size());
                        v.itemCounts().forEach((id, count) -> {
                            buf.writeString(id);
                            buf.writeInt(count);
                        });
                    },
                    buf -> {
                        int size = buf.readInt();
                        Map<String, Integer> map = new HashMap<>();
                        for (int i = 0; i < size; i++) {
                            map.put(buf.readString(), buf.readInt());
                        }
                        return new ChestScanResultPayload(map);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
