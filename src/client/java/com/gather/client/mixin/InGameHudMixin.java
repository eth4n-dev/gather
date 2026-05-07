package com.gather.client.mixin;

import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderHotbarItem", at = @At("HEAD"))
    private void gatherTintCollectorHotbarSlot(DrawContext ctx, int x, int y,
            RenderTickCounter ticker, PlayerEntity player, ItemStack stack, int seed, CallbackInfo ci) {
        if (!GatherSettings.get().enabled) return;
        if (!GatherHud.isCollectorShulker(stack)) return;
        ctx.fill(x, y, x + 16, y + 16, 0x55AA44FF);
    }
}
