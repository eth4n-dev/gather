package com.gather.client.mixin;

import com.gather.client.GatherTheme;
import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(AbstractContainerScreen.class)
public class HandledScreenMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void gatherTintCollectorSlot(GuiGraphicsExtractor ctx, Slot slot, int mx, int my, CallbackInfo ci) {
        if (!GatherSettings.get().enabled) return;
        if (!GatherHud.isCollectorShulker(slot.getItem())) return;
        GatherTheme.fill(ctx, slot.x, slot.y, slot.x + 16, slot.y + 16, 0x55AA44FF);
    }
}
