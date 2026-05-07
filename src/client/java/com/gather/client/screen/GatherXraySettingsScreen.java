package com.gather.client.screen;

import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
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
        boolean xrayAllowed = GatherState.isServerXrayAllowed();

        ButtonWidget dropXray = ButtonWidget
                .builder(stateText("Drop Xray", GatherSettings.get().droppedItemXray), btn -> {
                    GatherSettings.get().droppedItemXray = !GatherSettings.get().droppedItemXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Drop Xray", GatherSettings.get().droppedItemXray));
                })
                .dimensions(cx - 100, cy - 37, 200, 20)
                .build();
        dropXray.active = xrayAllowed;
        addDrawableChild(dropXray);

        ButtonWidget blockXray = ButtonWidget
                .builder(stateText("Block Xray", GatherSettings.get().blockXray), btn -> {
                    GatherSettings.get().blockXray = !GatherSettings.get().blockXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Block Xray", GatherSettings.get().blockXray));
                })
                .dimensions(cx - 100, cy - 12, 200, 20)
                .build();
        blockXray.active = xrayAllowed;
        addDrawableChild(blockXray);

        ButtonWidget chestXray = ButtonWidget
                .builder(stateText("Chest Outlines Xray", GatherSettings.get().chestXray), btn -> {
                    GatherSettings.get().chestXray = !GatherSettings.get().chestXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Chest Outlines Xray", GatherSettings.get().chestXray));
                })
                .dimensions(cx - 100, cy + 13, 200, 20)
                .build();
        chestXray.active = xrayAllowed;
        addDrawableChild(chestXray);

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Back"), btn -> close())
                .dimensions(cx - 50, cy + 48, 100, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltip = null;
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 55, 0xFFCCDDFF);
        super.render(ctx, mx, my, delta);

        int cx = width / 2, cy = height / 2;
        boolean xrayAllowed = GatherState.isServerXrayAllowed();
        if (!xrayAllowed) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Xray disabled by server"),
                    width / 2, height / 2 - 55 + 14, 0xFFFF6655);
        }
        if (inside(mx, my, cx - 100, cy - 37, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Text.literal("Needed dropped items glow through walls."),
                        Text.literal("Toggleable. Uses entity glow effect."));
            } else {
                setTooltip(mx, my,
                        Text.literal("Xray not allowed on this server."),
                        Text.literal("Ask an admin to run: /gatherop xray on"));
            }
        } else if (inside(mx, my, cx - 100, cy - 12, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Text.literal("Needed placed blocks glow through walls."),
                        Text.literal("WARNING: reveals ores/structures underground."),
                        Text.literal("Can spoil cave and dungeon exploration."),
                        Text.literal("Requires WhereIsIt/ChestTracker installed."));
            } else {
                setTooltip(mx, my,
                        Text.literal("Xray not allowed on this server."),
                        Text.literal("Ask an admin to run: /gatherop xray on"));
            }
        } else if (inside(mx, my, cx - 100, cy + 13, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Text.literal("Chest outlines show through walls (xray)."),
                        Text.literal("OFF: depth-tested outlines, slightly better FPS."),
                        Text.literal("Collector outlines always use xray regardless."));
            } else {
                setTooltip(mx, my,
                        Text.literal("Xray not allowed on this server."),
                        Text.literal("Ask an admin to run: /gatherop xray on"));
            }
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

    private static Text stateText(String label, boolean enabled) {
        return Text.literal(label + ": ")
                .append(Text.literal(enabled ? "ON" : "OFF").styled(style ->
                        style.withColor(enabled ? 0x55FF77 : 0xFF6666)));
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
