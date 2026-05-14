package com.gather.client;

import com.gather.GatherMod;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class GatherKeyBindings {

    private static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "controls"));

    public static KeyMapping openMenu;
    public static KeyMapping manualScanToggle;

    public static void register() {
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gather.open_menu",
                GLFW.GLFW_KEY_G,
                CATEGORY
        ));
        manualScanToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gather.manual_scan_toggle",
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));
    }
}
