package com.gather.client.mixin;

import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(HandledScreen.class)
public class HandledScreenMixin {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void gatherTintCollectorSlot(DrawContext ctx, Slot slot, int mx, int my, CallbackInfo ci) {
        if (!GatherSettings.get().enabled) return;
        if (!GatherHud.isCollectorShulker(slot.getStack())) return;
        ctx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x55AA44FF);
    }
}
