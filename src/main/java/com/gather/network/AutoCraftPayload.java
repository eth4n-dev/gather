package com.gather.network;

import com.gather.GatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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
) implements CustomPacketPayload {

    public record IngredientEntry(String itemId, int count) {}

    public static final CustomPacketPayload.Type<AutoCraftPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "auto_craft"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoCraftPayload> CODEC =
            StreamCodec.of(
                    (buf, v) -> {
                        buf.writeUtf(v.outputItemId());
                        buf.writeInt(v.outputCount());
                        buf.writeInt(v.consume().size());
                        for (IngredientEntry e : v.consume()) {
                            buf.writeUtf(e.itemId());
                            buf.writeInt(e.count());
                        }
                    },
                    buf -> {
                        String out = buf.readUtf();
                        int outCount = buf.readInt();
                        int size = buf.readInt();
                        List<IngredientEntry> list = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            list.add(new IngredientEntry(buf.readUtf(), buf.readInt()));
                        }
                        return new AutoCraftPayload(out, outCount, list);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
