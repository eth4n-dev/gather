package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record AutoTrackResultPayload(List<Long> positions) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AutoTrackResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "auto_track_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoTrackResultPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeInt(v.positions().size());
                        for (long pos : v.positions()) buf.writeLong(pos);
                    },
                    buf -> {
                        int size = buf.readInt();
                        List<Long> positions = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) positions.add(buf.readLong());
                        return new AutoTrackResultPayload(positions);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
