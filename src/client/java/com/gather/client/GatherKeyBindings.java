package com.gather.client;

import com.gather.GatherMod;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class GatherKeyBindings {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(GatherMod.MOD_ID, "controls"));

    public static KeyBinding openMenu;
    public static KeyBinding manualScanToggle;

    public static void register() {
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.gather.open_menu",
                GLFW.GLFW_KEY_G,
                CATEGORY
        ));
        manualScanToggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.gather.manual_scan_toggle",
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));
    }
}
