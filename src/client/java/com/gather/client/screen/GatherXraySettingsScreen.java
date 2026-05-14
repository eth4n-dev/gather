package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class GatherXraySettingsScreen extends Screen {

    private final Screen parent;
    private java.util.List<net.minecraft.network.chat.Component> hoveredTooltip = null;
    private int tooltipX, tooltipY;

    public GatherXraySettingsScreen(Screen parent) {
        super(Component.literal("Xray / Glow Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        boolean xrayAllowed = GatherState.isServerXrayAllowed();

        Button dropXray = Button
                .builder(stateText("Drop Xray", GatherSettings.get().droppedItemXray), btn -> {
                    GatherSettings.get().droppedItemXray = !GatherSettings.get().droppedItemXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Drop Xray", GatherSettings.get().droppedItemXray));
                })
                .bounds(cx - 100, cy - 37, 200, 20)
                .build();
        dropXray.active = xrayAllowed;
        addRenderableWidget(dropXray);

        Button blockXray = Button
                .builder(stateText("Block Xray", GatherSettings.get().blockXray), btn -> {
                    GatherSettings.get().blockXray = !GatherSettings.get().blockXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Block Xray", GatherSettings.get().blockXray));
                })
                .bounds(cx - 100, cy - 12, 200, 20)
                .build();
        blockXray.active = xrayAllowed;
        addRenderableWidget(blockXray);

        Button chestXray = Button
                .builder(stateText("Chest Outlines Xray", GatherSettings.get().chestXray), btn -> {
                    GatherSettings.get().chestXray = !GatherSettings.get().chestXray;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Chest Outlines Xray", GatherSettings.get().chestXray));
                })
                .bounds(cx - 100, cy + 13, 200, 20)
                .build();
        chestXray.active = xrayAllowed;
        addRenderableWidget(chestXray);

        addRenderableWidget(Button
                .builder(Component.literal("Back"), btn -> onClose())
                .bounds(cx - 50, cy + 48, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        hoveredTooltip = null;
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        ctx.centeredText(font, title, width / 2, height / 2 - 55, 0xFFCCDDFF);
        super.extractRenderState(ctx, mx, my, delta);

        int cx = width / 2, cy = height / 2;
        boolean xrayAllowed = GatherState.isServerXrayAllowed();
        if (!xrayAllowed) {
            ctx.centeredText(font,
                    Component.literal("Xray disabled by server"),
                    width / 2, height / 2 - 55 + 14, 0xFFFF6655);
        }
        if (inside(mx, my, cx - 100, cy - 37, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Component.literal("Needed dropped items glow through walls."),
                        Component.literal("Toggleable. Uses entity glow effect."));
            } else {
                setTooltip(mx, my,
                        Component.literal("Xray not allowed on this server."),
                        Component.literal("Ask an admin to run: /gatherop xray on"));
            }
        } else if (inside(mx, my, cx - 100, cy - 12, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Component.literal("Needed placed blocks glow through walls."),
                        Component.literal("WARNING: reveals ores/structures underground."),
                        Component.literal("Can spoil cave and dungeon exploration."),
                        Component.literal("Requires WhereIsIt/ChestTracker installed."));
            } else {
                setTooltip(mx, my,
                        Component.literal("Xray not allowed on this server."),
                        Component.literal("Ask an admin to run: /gatherop xray on"));
            }
        } else if (inside(mx, my, cx - 100, cy + 13, 200, 20)) {
            if (xrayAllowed) {
                setTooltip(mx, my,
                        Component.literal("Chest outlines show through walls (xray)."),
                        Component.literal("OFF: depth-tested outlines, slightly better FPS."),
                        Component.literal("Collector outlines always use xray regardless."));
            } else {
                setTooltip(mx, my,
                        Component.literal("Xray not allowed on this server."),
                        Component.literal("Ask an admin to run: /gatherop xray on"));
            }
        }

        if (hoveredTooltip != null) {
            ctx.setComponentTooltipForNextFrame(font, hoveredTooltip, tooltipX, tooltipY);
        }
    }

    private void setTooltip(int mx, int my, Component... lines) {
        hoveredTooltip = java.util.List.of(lines);
        tooltipX = mx;
        tooltipY = my + 18;
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static Component stateText(String label, boolean enabled) {
        return Component.literal(label + ": ")
                .append(Component.literal(enabled ? "ON" : "OFF").withStyle(style ->
                        style.withColor(enabled ? 0x55FF77 : 0xFF6666)));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
