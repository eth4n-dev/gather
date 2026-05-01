package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record AutoTrackResultPayload(List<Long> positions) implements CustomPayload {

    public static final Id<AutoTrackResultPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "auto_track_result"));

    public static final PacketCodec<RegistryByteBuf, AutoTrackResultPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
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
    public Id<? extends CustomPayload> getId() { return ID; }
}
