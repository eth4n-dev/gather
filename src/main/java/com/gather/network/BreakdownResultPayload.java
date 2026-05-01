package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public record BreakdownResultPayload(String originItemId, Map<String, Integer> ingredients, boolean inventoryCraftable) implements CustomPayload {

    public static final Id<BreakdownResultPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "breakdown_result"));

    public static final PacketCodec<RegistryByteBuf, BreakdownResultPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeString(v.originItemId());
                        buf.writeInt(v.ingredients().size());
                        v.ingredients().forEach((id, cnt) -> { buf.writeString(id); buf.writeInt(cnt); });
                        buf.writeBoolean(v.inventoryCraftable());
                    },
                    buf -> {
                        String origin = buf.readString();
                        int size = buf.readInt();
                        Map<String, Integer> map = new HashMap<>();
                        for (int i = 0; i < size; i++) map.put(buf.readString(), buf.readInt());
                        boolean inv = buf.readBoolean();
                        return new BreakdownResultPayload(origin, map, inv);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
