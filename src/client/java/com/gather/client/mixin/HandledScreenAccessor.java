package com.gather.client.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int gather$getX();

    @Accessor("y")
    int gather$getY();

    @Accessor("backgroundWidth")
    int gather$getBackgroundWidth();

    @Accessor("backgroundHeight")
    int gather$getBackgroundHeight();
}
