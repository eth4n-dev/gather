package com.gather.client.mixin;

import com.gather.client.compat.jei.GatherJeiPlugin;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class ScreenKeyMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void gather$addJeiHoveredItem(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (keyEvent.key() == GLFW.GLFW_KEY_G && GatherJeiPlugin.addHoveredItemToGoals()) {
            cir.setReturnValue(true);
        }
    }
}
