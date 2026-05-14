package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public record BreakdownResultPayload(String originItemId, Map<String, Integer> ingredients, boolean inventoryCraftable) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BreakdownResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "breakdown_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BreakdownResultPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeUtf(v.originItemId());
                        buf.writeInt(v.ingredients().size());
                        v.ingredients().forEach((id, cnt) -> { buf.writeUtf(id); buf.writeInt(cnt); });
                        buf.writeBoolean(v.inventoryCraftable());
                    },
                    buf -> {
                        String origin = buf.readUtf();
                        int size = buf.readInt();
                        Map<String, Integer> map = new HashMap<>();
                        for (int i = 0; i < size; i++) map.put(buf.readUtf(), buf.readInt());
                        boolean inv = buf.readBoolean();
                        return new BreakdownResultPayload(origin, map, inv);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
