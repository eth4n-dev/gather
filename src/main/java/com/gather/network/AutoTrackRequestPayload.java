package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record AutoTrackRequestPayload(int radius, List<String> neededItemIds) implements CustomPayload {

    public static final Id<AutoTrackRequestPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "auto_track_request"));

    public static final PacketCodec<RegistryByteBuf, AutoTrackRequestPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeInt(v.radius());
                        buf.writeInt(v.neededItemIds().size());
                        for (String id : v.neededItemIds()) buf.writeString(id);
                    },
                    buf -> {
                        int radius = buf.readInt();
                        int size = buf.readInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) ids.add(buf.readString());
                        return new AutoTrackRequestPayload(radius, ids);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
