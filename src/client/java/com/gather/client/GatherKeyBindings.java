package com.gather.client;

import com.gather.GatherMod;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class GatherKeyBindings {

    public static KeyBinding openMenu;

    public static void register() {
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.gather.open_menu",
                GLFW.GLFW_KEY_G,
                KeyBinding.Category.create(Identifier.of(GatherMod.MOD_ID, "controls"))
        ));
    }
}
