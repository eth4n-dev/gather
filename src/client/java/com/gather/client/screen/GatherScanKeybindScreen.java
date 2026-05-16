package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherKeyBindings;
import com.gather.client.GatherSettings;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class GatherScanKeybindScreen extends Screen {

    private final Screen parent;

    private boolean pendingShift;
    private boolean pendingCtrl;
    private boolean pendingAlt;
    private boolean capturingMenuKey = false;
    private boolean capturingManualKey = false;
    private String warning = "";

    private Button shiftBtn;
    private Button ctrlBtn;
    private Button altBtn;
    private Button menuKeyBtn;
    private Button manualKeyBtn;

    public GatherScanKeybindScreen(Screen parent) {
        super(Component.literal("Gather Controls"));
        this.parent = parent;
        GatherSettings s = GatherSettings.get();
        this.pendingShift = s.scanToggleShift;
        this.pendingCtrl  = s.scanToggleCtrl;
        this.pendingAlt   = s.scanToggleAlt;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        menuKeyBtn = Button.builder(menuKeyLabel(), btn -> {
            capturingMenuKey = true;
            btn.setMessage(Component.literal("Press any key..."));
        }).bounds(cx - 100, cy - 48, 200, 20).build();
        addRenderableWidget(menuKeyBtn);

        manualKeyBtn = Button.builder(manualKeyLabel(), btn -> {
            capturingManualKey = true;
            btn.setMessage(Component.literal("Press any key..."));
        }).bounds(cx - 100, cy - 18, 200, 20).build();
        addRenderableWidget(manualKeyBtn);

        shiftBtn = Button.builder(modLabel("Shift", pendingShift), btn -> {
            pendingShift = !pendingShift;
            warning = "";
            btn.setMessage(modLabel("Shift", pendingShift));
        }).bounds(cx - 100, cy + 26, 60, 20).build();
        addRenderableWidget(shiftBtn);

        ctrlBtn = Button.builder(modLabel("Ctrl", pendingCtrl), btn -> {
            pendingCtrl = !pendingCtrl;
            warning = "";
            btn.setMessage(modLabel("Ctrl", pendingCtrl));
        }).bounds(cx - 35, cy + 26, 60, 20).build();
        addRenderableWidget(ctrlBtn);

        altBtn = Button.builder(modLabel("Alt", pendingAlt), btn -> {
            pendingAlt = !pendingAlt;
            warning = "";
            btn.setMessage(modLabel("Alt", pendingAlt));
        }).bounds(cx + 30, cy + 26, 60, 20).build();
        addRenderableWidget(altBtn);

        addRenderableWidget(Button.builder(Component.literal("Clear modifiers"), btn -> {
            pendingShift = false;
            pendingCtrl  = false;
            pendingAlt   = false;
            warning = "";
            shiftBtn.setMessage(modLabel("Shift", pendingShift));
            ctrlBtn .setMessage(modLabel("Ctrl",  pendingCtrl));
            altBtn  .setMessage(modLabel("Alt",   pendingAlt));
        }).bounds(cx - 100, cy + 58, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> save())
                .bounds(cx + 5, cy + 58, 95, 20).build());
    }

    private Component modLabel(String name, boolean active) {
        return Component.literal(active ? "§a[" + name + "]" : name);
    }

    private Component menuKeyLabel() {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.openMenu);
        return Component.literal("Menu Key: " + key.getDisplayName().getString());
    }

    private Component manualKeyLabel() {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        return Component.literal("Manual Scan Key: " + key.getDisplayName().getString());
    }

    static String buildComboLabel(GatherSettings s) {
        StringBuilder sb = new StringBuilder();
        if (s.scanToggleCtrl)  sb.append("Ctrl+");
        if (s.scanToggleAlt)   sb.append("Alt+");
        if (s.scanToggleShift) sb.append("Shift+");
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        sb.append(key.getDisplayName().getString());
        return sb.toString();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (capturingMenuKey || capturingManualKey) {
            int keyCode = input.key();
            KeyMapping binding = capturingMenuKey ? GatherKeyBindings.openMenu : GatherKeyBindings.manualScanToggle;
            int fallback = capturingMenuKey ? GLFW.GLFW_KEY_G : GLFW.GLFW_KEY_V;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                binding.setKey(InputConstants.Type.KEYSYM.getOrCreate(fallback));
            } else {
                binding.setKey(InputConstants.Type.KEYSYM.getOrCreate(keyCode));
            }
            KeyMapping.resetMapping();
            minecraft.options.save();
            warning = exactConflict() ? "Add Shift, Ctrl, or Alt when both keys match." : "";
            capturingMenuKey = false;
            capturingManualKey = false;
            menuKeyBtn.setMessage(menuKeyLabel());
            manualKeyBtn.setMessage(manualKeyLabel());
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(input);
    }

    private void save() {
        if (exactConflict()) {
            warning = "Manual scan cannot exactly match the menu key.";
            return;
        }
        GatherSettings s = GatherSettings.get();
        s.scanToggleShift = pendingShift;
        s.scanToggleCtrl  = pendingCtrl;
        s.scanToggleAlt   = pendingAlt;
        s.save();
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        int cx = width / 2;
        int cy = height / 2;
        ctx.centeredText(font, title, cx, cy - 95, 0xFFCCDDFF);
        ctx.centeredText(font,
                Component.literal("Menu opens and closes with the menu key or E."),
                cx, cy - 78, 0xFF667788);
        ctx.centeredText(font,
                Component.literal("Manual scan uses its own key plus optional modifiers."),
                cx, cy - 66, 0xFF667788);

        // Live preview
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        String keyName = key.getDisplayName().getString();
        StringBuilder live = new StringBuilder();
        if (pendingCtrl)  live.append("Ctrl+");
        if (pendingAlt)   live.append("Alt+");
        if (pendingShift) live.append("Shift+");
        live.append(capturingManualKey ? "..." : keyName);
        ctx.centeredText(font, Component.literal("Manual scan: " + live), cx, cy + 7, 0xFFAAFF88);
        if (!warning.isEmpty()) {
            ctx.centeredText(font, Component.literal(warning), cx, cy + 88, 0xFFFF7777);
        }

        super.extractRenderState(ctx, mx, my, delta);
    }

    private boolean exactConflict() {
        if (pendingShift || pendingCtrl || pendingAlt) return false;
        return KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.openMenu)
                .equals(KeyMappingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
