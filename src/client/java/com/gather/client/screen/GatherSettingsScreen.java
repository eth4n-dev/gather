package com.gather.client.screen;

import com.gather.client.GatherHud;
import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public class GatherSettingsScreen extends Screen {

    private final Screen parent;
    private List<Text> hoveredTooltipLines = null;
    private int tooltipX, tooltipY;

    public GatherSettingsScreen(Screen parent) {
        super(Text.literal("Gather Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Gather: " + (GatherSettings.get().enabled ? "ON" : "OFF")), btn -> {
                    GatherSettings settings = GatherSettings.get();
                    boolean nextEnabled = !settings.enabled;
                    if (!nextEnabled) {
                        GatherState.get().setChestScanMode(false);
                        GatherClientNetworking.configureCollector(false, true, List.of(), true);
                    }
                    settings.enabled = nextEnabled;
                    settings.save();
                    GatherHud.markDirty();
                    WorldHighlightRenderer.invalidateCache();
                    btn.setMessage(Text.literal("Gather: " + (settings.enabled ? "ON" : "OFF")));
                })
                .dimensions(cx - 100, cy - 80, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Outlines Settings..."), btn -> client.setScreen(new GatherOutlinesSettingsScreen(this)))
                .dimensions(cx - 100, cy - 55, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Xray Settings..."), btn -> client.setScreen(new GatherXraySettingsScreen(this)))
                .dimensions(cx - 100, cy - 30, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Scan Settings..."), btn -> client.setScreen(new GatherScanSettingsScreen(this)))
                .dimensions(cx - 100, cy - 5, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Transfer Lists..."), btn -> client.setScreen(new GatherTransferScreen(this)))
                .dimensions(cx - 100, cy + 20, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Help"), btn -> client.setScreen(new GatherHelpScreen(this, false)))
                .dimensions(cx - 100, cy + 45, 95, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Done"), btn -> close())
                .dimensions(cx + 5, cy + 45, 95, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltipLines = null;
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 90, 0xFFCCDDFF);
        super.render(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.drawTooltip(textRenderer, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private void drawHoverInfo(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2;
        if (inside(mx, my, cx - 100, cy - 80, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Master switch for Gather runtime features."),
                    Text.literal("OFF disables HUD, outlines, scans, item glow, and overlays."),
                    Text.literal("This settings screen remains available so you can re-enable it."));
        } else if (inside(mx, my, cx - 100, cy - 55, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Settings for normal block outlines and block xray outlines."),
                    Text.literal("Includes on/off, range, max count, and color."));
        } else if (inside(mx, my, cx - 100, cy - 30, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Toggle xray glow for dropped items and placed blocks."),
                    Text.literal("Each has an independent on/off switch."));
        } else if (inside(mx, my, cx - 100, cy - 5, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Options for Scan All — including marking nearby chests"),
                    Text.literal("so they appear in scan results."));
        }
    }

    private void setTooltip(int mx, int my, Text... lines) {
        hoveredTooltipLines = List.of(lines);
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
