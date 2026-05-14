package com.gather.client.mixin;

import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class ItemEntityMixin {
    private static long gatherLastColorMs = 0L;
    private static int gatherLastColor = 0xFFFFFF;

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void gather_isGlowing(CallbackInfoReturnable<Boolean> cir) {
        if (!GatherState.isServerXrayAllowed()) return;
        if (!GatherSettings.get().enabled || !GatherSettings.get().highlightEnabled || !GatherSettings.get().droppedItemXray) return;
        if ((Object)this instanceof ItemEntity ie
                && !ie.getItem().isEmpty()
                && GatherState.get().isNeeded(ie.getItem().getItem())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void gather_glowColor(CallbackInfoReturnable<Integer> cir) {
        if (!GatherState.isServerXrayAllowed()) return;
        if (!GatherSettings.get().enabled || !GatherSettings.get().highlightEnabled || !GatherSettings.get().droppedItemXray) return;
        if ((Object)this instanceof ItemEntity ie
                && !ie.getItem().isEmpty()
                && GatherState.get().isNeeded(ie.getItem().getItem())) {
            long now = System.currentTimeMillis();
            if (now - gatherLastColorMs > 50L) {
                gatherLastColorMs = now;
                gatherLastColor = WorldHighlightRenderer.rainbowColorInt(now);
            }
            cir.setReturnValue(gatherLastColor);
        }
    }
}
