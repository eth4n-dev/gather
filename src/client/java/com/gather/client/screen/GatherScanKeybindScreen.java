package com.gather.client.screen;

import com.gather.client.GatherKeyBindings;
import com.gather.client.GatherSettings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class GatherScanKeybindScreen extends Screen {

    private final Screen parent;

    private boolean pendingShift;
    private boolean pendingCtrl;
    private boolean pendingAlt;
    private boolean capturingMenuKey = false;
    private boolean capturingManualKey = false;

    private ButtonWidget shiftBtn;
    private ButtonWidget ctrlBtn;
    private ButtonWidget altBtn;
    private ButtonWidget menuKeyBtn;
    private ButtonWidget manualKeyBtn;

    public GatherScanKeybindScreen(Screen parent) {
        super(Text.literal("Gather Controls"));
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

        menuKeyBtn = ButtonWidget.builder(menuKeyLabel(), btn -> {
            capturingMenuKey = true;
            btn.setMessage(Text.literal("Press any key..."));
        }).dimensions(cx - 100, cy - 48, 200, 20).build();
        addDrawableChild(menuKeyBtn);

        manualKeyBtn = ButtonWidget.builder(manualKeyLabel(), btn -> {
            capturingManualKey = true;
            btn.setMessage(Text.literal("Press any key..."));
        }).dimensions(cx - 100, cy - 18, 200, 20).build();
        addDrawableChild(manualKeyBtn);

        shiftBtn = ButtonWidget.builder(modLabel("Shift", pendingShift), btn -> {
            pendingShift = !pendingShift;
            btn.setMessage(modLabel("Shift", pendingShift));
        }).dimensions(cx - 100, cy + 26, 60, 20).build();
        addDrawableChild(shiftBtn);

        ctrlBtn = ButtonWidget.builder(modLabel("Ctrl", pendingCtrl), btn -> {
            pendingCtrl = !pendingCtrl;
            btn.setMessage(modLabel("Ctrl", pendingCtrl));
        }).dimensions(cx - 35, cy + 26, 60, 20).build();
        addDrawableChild(ctrlBtn);

        altBtn = ButtonWidget.builder(modLabel("Alt", pendingAlt), btn -> {
            pendingAlt = !pendingAlt;
            btn.setMessage(modLabel("Alt", pendingAlt));
        }).dimensions(cx + 30, cy + 26, 60, 20).build();
        addDrawableChild(altBtn);

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear modifiers"), btn -> {
            pendingShift = false;
            pendingCtrl  = false;
            pendingAlt   = false;
            shiftBtn.setMessage(modLabel("Shift", pendingShift));
            ctrlBtn .setMessage(modLabel("Ctrl",  pendingCtrl));
            altBtn  .setMessage(modLabel("Alt",   pendingAlt));
        }).dimensions(cx - 100, cy + 58, 95, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> save())
                .dimensions(cx + 5, cy + 58, 95, 20).build());
    }

    private Text modLabel(String name, boolean active) {
        return Text.literal(active ? "§a[" + name + "]" : name);
    }

    private Text menuKeyLabel() {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.openMenu);
        return Text.literal("Menu Key: " + key.getLocalizedText().getString());
    }

    private Text manualKeyLabel() {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        return Text.literal("Manual Scan Key: " + key.getLocalizedText().getString());
    }

    static String buildComboLabel(GatherSettings s) {
        StringBuilder sb = new StringBuilder();
        if (s.scanToggleCtrl)  sb.append("Ctrl+");
        if (s.scanToggleAlt)   sb.append("Alt+");
        if (s.scanToggleShift) sb.append("Shift+");
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        sb.append(key.getLocalizedText().getString());
        return sb.toString();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (capturingMenuKey || capturingManualKey) {
            int keyCode = input.key();
            KeyBinding binding = capturingMenuKey ? GatherKeyBindings.openMenu : GatherKeyBindings.manualScanToggle;
            int fallback = capturingMenuKey ? GLFW.GLFW_KEY_G : GLFW.GLFW_KEY_V;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(fallback));
            } else {
                binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
            }
            KeyBinding.updateKeysByCode();
            client.options.write();
            capturingMenuKey = false;
            capturingManualKey = false;
            menuKeyBtn.setMessage(menuKeyLabel());
            manualKeyBtn.setMessage(manualKeyLabel());
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    private void save() {
        GatherSettings s = GatherSettings.get();
        s.scanToggleShift = pendingShift;
        s.scanToggleCtrl  = pendingCtrl;
        s.scanToggleAlt   = pendingAlt;
        s.save();
        close();
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xCC111122);
        int cx = width / 2;
        int cy = height / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, cy - 95, 0xFFCCDDFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Menu opens and closes with the menu key or E."),
                cx, cy - 78, 0xFF667788);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Manual scan uses its own key plus optional modifiers."),
                cx, cy - 66, 0xFF667788);

        // Live preview
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle);
        String keyName = key.getLocalizedText().getString();
        StringBuilder live = new StringBuilder();
        if (pendingCtrl)  live.append("Ctrl+");
        if (pendingAlt)   live.append("Alt+");
        if (pendingShift) live.append("Shift+");
        live.append(capturingManualKey ? "..." : keyName);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Manual scan: " + live), cx, cy + 7, 0xFFAAFF88);

        super.render(ctx, mx, my, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
