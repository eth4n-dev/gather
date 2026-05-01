package com.gather.client.screen;

import com.gather.client.GatherSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class GatherXraySettingsScreen extends Screen {

    private final Screen parent;
    private java.util.List<net.minecraft.text.Text> hoveredTooltip = null;
    private int tooltipX, tooltipY;

    public GatherXraySettingsScreen(Screen parent) {
        super(Text.literal("Xray / Glow Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Drop Xray: " + (GatherSettings.get().droppedItemXray ? "ON" : "OFF")), btn -> {
                    GatherSettings.get().droppedItemXray = !GatherSettings.get().droppedItemXray;
                    GatherSettings.get().save();
                    btn.setMessage(Text.literal("Drop Xray: " + (GatherSettings.get().droppedItemXray ? "ON" : "OFF")));
                })
                .dimensions(cx - 100, cy - 25, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Block Xray: " + (GatherSettings.get().blockXray ? "ON" : "OFF")), btn -> {
                    GatherSettings.get().blockXray = !GatherSettings.get().blockXray;
                    GatherSettings.get().save();
                    btn.setMessage(Text.literal("Block Xray: " + (GatherSettings.get().blockXray ? "ON" : "OFF")));
                })
                .dimensions(cx - 100, cy, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Back"), btn -> close())
                .dimensions(cx - 50, cy + 35, 100, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltip = null;
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 55, 0xFFCCDDFF);
        super.render(ctx, mx, my, delta);

        int cx = width / 2, cy = height / 2;
        if (inside(mx, my, cx - 100, cy - 25, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Needed dropped items glow through walls."),
                    Text.literal("Toggleable. Uses entity glow effect."));
        } else if (inside(mx, my, cx - 100, cy, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Needed placed blocks glow through walls."),
                    Text.literal("WARNING: reveals ores/structures underground."),
                    Text.literal("Can spoil cave and dungeon exploration."),
                    Text.literal("Requires WhereIsIt/ChestTracker installed."));
        }

        if (hoveredTooltip != null) {
            ctx.drawTooltip(textRenderer, hoveredTooltip, tooltipX, tooltipY);
        }
    }

    private void setTooltip(int mx, int my, Text... lines) {
        hoveredTooltip = java.util.List.of(lines);
        tooltipX = mx;
        tooltipY = my + 18;
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
