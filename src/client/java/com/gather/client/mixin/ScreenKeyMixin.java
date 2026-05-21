package com.gather.client.mixin;

import com.gather.client.compat.jei.GatherJeiPlugin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class ScreenKeyMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void gather$addJeiHoveredItem(KeyInput keyInput, CallbackInfoReturnable<Boolean> cir) {
        if (keyInput.key() == GLFW.GLFW_KEY_G && GatherJeiPlugin.addHoveredItemToGoals()) {
            cir.setReturnValue(true);
        }
    }
}
