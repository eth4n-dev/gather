package com.gather.client.screen;

import com.gather.client.GatherSettings;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.List;

public class GatherOutlinesSettingsScreen extends Screen {
    private static final int MIN_OUTLINES = 8;
    private static final int MAX_OUTLINES = 512;
    private static final String[] LOAD_LABELS = {
            "Ultra Duper Fast", "Ultra Fast", "Fast", "Normal", "Slow", "Slower", "Slowest"
    };
    private static final int[] LOAD_SECONDS = {1, 2, 3, 4, 6, 8, 12};

    private final Screen parent;
    private List<Text> hoveredTooltip = null;
    private int tooltipX, tooltipY;

    public GatherOutlinesSettingsScreen(Screen parent) {
        super(Text.literal("Outlines Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Outlines: " + (GatherSettings.get().highlightEnabled ? "ON" : "OFF")), btn -> {
                    GatherSettings.get().highlightEnabled = !GatherSettings.get().highlightEnabled;
                    GatherSettings.get().save();
                    WorldHighlightRenderer.invalidateCache();
                    btn.setMessage(Text.literal("Outlines: " + (GatherSettings.get().highlightEnabled ? "ON" : "OFF")));
                })
                .dimensions(cx - 100, cy - 55, 200, 20)
                .build());

        addDrawableChild(new SliderWidget(cx - 100, cy - 30, 200, 20,
                Text.literal("Outline Range: " + GatherSettings.get().highlightRadius),
                (GatherSettings.get().highlightRadius - 8) / 56.0) {
            @Override protected void updateMessage() { setMessage(Text.literal("Outline Range: " + toRadius())); }
            @Override protected void applyValue() {
                GatherSettings.get().highlightRadius = toRadius();
                GatherSettings.get().save();
                WorldHighlightRenderer.invalidateCache();
            }
            private int toRadius() { return 8 + (int) Math.round(value * 56); }
        });

        addDrawableChild(new SliderWidget(cx - 100, cy - 5, 200, 20,
                Text.literal("Max Outlines: " + GatherSettings.get().maxBlockHighlights),
                (clamp(GatherSettings.get().maxBlockHighlights, MIN_OUTLINES, MAX_OUTLINES) - MIN_OUTLINES)
                        / (double) (MAX_OUTLINES - MIN_OUTLINES)) {
            @Override protected void updateMessage() { setMessage(Text.literal("Max Outlines: " + toVal())); }
            @Override protected void applyValue() {
                GatherSettings.get().maxBlockHighlights = toVal();
                GatherSettings.get().save();
                WorldHighlightRenderer.invalidateCache();
            }
            private int toVal() { return MIN_OUTLINES + (int) Math.round(value * (MAX_OUTLINES - MIN_OUTLINES)); }
        });

        addDrawableChild(new SliderWidget(cx - 100, cy + 20, 200, 20,
                Text.literal("Outline Load: " + loadLabelForSeconds(GatherSettings.get().highlightRampSeconds)),
                loadIndexForSeconds(GatherSettings.get().highlightRampSeconds) / (double) (LOAD_SECONDS.length - 1)) {
            @Override protected void updateMessage() { setMessage(Text.literal("Outline Load: " + LOAD_LABELS[toIndex()])); }
            @Override protected void applyValue() {
                GatherSettings.get().highlightRampSeconds = LOAD_SECONDS[toIndex()];
                GatherSettings.get().save();
            }
            private int toIndex() {
                return clamp((int) Math.round(value * (LOAD_SECONDS.length - 1)), 0, LOAD_SECONDS.length - 1);
            }
        });

        String[] colors = {"rainbow", "blue", "red", "green", "yellow", "white"};
        addDrawableChild(ButtonWidget
                .builder(Text.literal("Outline Color: " + GatherSettings.get().outlineColor), btn -> {
                    String cur = GatherSettings.get().outlineColor;
                    int idx = 0;
                    for (int i = 0; i < colors.length; i++) if (colors[i].equals(cur)) { idx = i; break; }
                    String next = colors[(idx + 1) % colors.length];
                    GatherSettings.get().outlineColor = next;
                    GatherSettings.get().save();
                    btn.setMessage(Text.literal("Outline Color: " + next));
                })
                .dimensions(cx - 100, cy + 45, 200, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Back"), btn -> close())
                .dimensions(cx - 50, cy + 75, 100, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltip = null;
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 85, 0xFFCCDDFF);
        super.render(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltip != null) ctx.drawTooltip(textRenderer, hoveredTooltip, tooltipX, tooltipY);
    }

    private void drawHoverInfo(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2;
        if (inside(mx, my, cx - 100, cy - 55, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Master toggle for Gather block outlines."),
                    Text.literal("Applies to normal outlines and block xray outlines."));
        } else if (inside(mx, my, cx - 100, cy - 30, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("How far Gather scans for matching placed blocks."),
                    Text.literal("Higher values cost more rendering and scanning work."));
        } else if (inside(mx, my, cx - 100, cy - 5, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Max number of block outlines shown at once."),
                    Text.literal("High values can reduce FPS and mainly use GPU power."),
                    Text.literal("Nearest blocks load first."));
        } else if (inside(mx, my, cx - 100, cy + 20, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("How fast extra outlines appear."),
                    Text.literal("Slower settings spread GPU load over more time."),
                    Text.literal("High Max Outlines can still impact FPS."));
        } else if (inside(mx, my, cx - 100, cy + 45, 200, 20)) {
            setTooltip(mx, my,
                    Text.literal("Color for placed-block outlines."),
                    Text.literal("Used by normal outlines and block xray outlines."));
        }
    }

    private void setTooltip(int mx, int my, Text... lines) {
        hoveredTooltip = List.of(lines);
        tooltipX = mx;
        tooltipY = my + 18;
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int loadIndexForSeconds(int seconds) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < LOAD_SECONDS.length; i++) {
            int distance = Math.abs(LOAD_SECONDS[i] - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static String loadLabelForSeconds(int seconds) {
        return LOAD_LABELS[loadIndexForSeconds(seconds)];
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
