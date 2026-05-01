package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client sends the full ingredient list and expected output.
 * Server validates player has ingredients, then performs the swap.
 */
public record AutoCraftPayload(
        String outputItemId,
        int outputCount,
        List<IngredientEntry> consume
) implements CustomPayload {

    public record IngredientEntry(String itemId, int count) {}

    public static final Id<AutoCraftPayload> ID =
            new Id<>(Identifier.of(GatherMod.MOD_ID, "auto_craft"));

    public static final PacketCodec<RegistryByteBuf, AutoCraftPayload> CODEC =
            PacketCodec.of(
                    (v, buf) -> {
                        buf.writeString(v.outputItemId());
                        buf.writeInt(v.outputCount());
                        buf.writeInt(v.consume().size());
                        for (IngredientEntry e : v.consume()) {
                            buf.writeString(e.itemId());
                            buf.writeInt(e.count());
                        }
                    },
                    buf -> {
                        String out = buf.readString();
                        int outCount = buf.readInt();
                        int size = buf.readInt();
                        List<IngredientEntry> list = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            list.add(new IngredientEntry(buf.readString(), buf.readInt()));
                        }
                        return new AutoCraftPayload(out, outCount, list);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
