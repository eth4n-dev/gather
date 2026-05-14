package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record AutoTrackRequestPayload(int radius, List<String> neededItemIds) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AutoTrackRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "auto_track_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoTrackRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.radius());
                        buf.writeInt(v.neededItemIds().size());
                        for (String id : v.neededItemIds()) buf.writeUtf(id);
                    },
                    buf -> {
                        int radius = buf.readInt();
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readUtf());
                        return new AutoTrackRequestPayload(radius, ids);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
