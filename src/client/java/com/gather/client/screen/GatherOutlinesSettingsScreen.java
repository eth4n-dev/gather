package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherSettings;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.List;

public class GatherOutlinesSettingsScreen extends Screen {
    private static final int MIN_RADIUS = 8;
    private static final int MAX_RADIUS = 128;
    private static final int MIN_OUTLINES = 8;
    private static final int MAX_OUTLINES = 512;
    private static final String[] LOAD_LABELS = {
            "Slowest", "Slower", "Slow", "Normal", "Fast", "Ultra Fast", "Ultra Duper Fast"
    };
    private static final int[] LOAD_SECONDS = {12, 8, 6, 4, 3, 2, 1};
    private static final String[] PRESET_LABELS = {"Performance", "Balanced", "Fancy", "Extreme"};
    private static final int[] PRESET_BUDGETS = {2, 4, 6, 10};
    private static final int[] PRESET_RADII = {8, 32, 64, 128};
    private static final int[] PRESET_MAX_OUTLINES = {32, 100, 200, 512};
    private static final int[] PRESET_RAMP_SECONDS = {12, 6, 3, 1};

    private final Screen parent;
    private List<Component> hoveredTooltip = null;
    private int tooltipX, tooltipY;

    public GatherOutlinesSettingsScreen(Screen parent) {
        super(Component.literal("Outlines Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2 + 12;

        int presetY = cy - 82;
        addRenderableWidget(Button
                .builder(presetText(), btn -> {
                    applyPreset(nextPresetIndex());
                    minecraft.setScreen(new GatherOutlinesSettingsScreen(parent));
                })
                .bounds(cx - 100, presetY, 200, 20)
                .build());

        addRenderableWidget(Button
                .builder(stateText("Outlines", GatherSettings.get().highlightEnabled), btn -> {
                    GatherSettings.get().highlightEnabled = !GatherSettings.get().highlightEnabled;
                    GatherSettings.get().save();
                    WorldHighlightRenderer.invalidateCache();
                    btn.setMessage(stateText("Outlines", GatherSettings.get().highlightEnabled));
                })
                .bounds(cx - 100, cy - 55, 200, 20)
                .build());

        addRenderableWidget(new AbstractSliderButton(cx - 100, cy - 30, 200, 20,
                Component.literal("Outline Range: " + GatherSettings.get().highlightRadius),
                (clamp(GatherSettings.get().highlightRadius, MIN_RADIUS, MAX_RADIUS) - MIN_RADIUS)
                        / (double) (MAX_RADIUS - MIN_RADIUS)) {
            @Override protected void updateMessage() { setMessage(Component.literal("Outline Range: " + toRadius())); }
            @Override protected void applyValue() {
                GatherSettings.get().highlightRadius = toRadius();
                GatherSettings.get().save();
                WorldHighlightRenderer.invalidateCache();
            }
            private int toRadius() { return MIN_RADIUS + (int) Math.round(value * (MAX_RADIUS - MIN_RADIUS)); }
        });

        addRenderableWidget(new AbstractSliderButton(cx - 100, cy - 5, 200, 20,
                Component.literal("Max Outlines: " + GatherSettings.get().maxBlockHighlights),
                (clamp(GatherSettings.get().maxBlockHighlights, MIN_OUTLINES, MAX_OUTLINES) - MIN_OUTLINES)
                        / (double) (MAX_OUTLINES - MIN_OUTLINES)) {
            @Override protected void updateMessage() { setMessage(Component.literal("Max Outlines: " + toVal())); }
            @Override protected void applyValue() {
                GatherSettings.get().maxBlockHighlights = toVal();
                GatherSettings.get().save();
                WorldHighlightRenderer.invalidateCache();
            }
            private int toVal() { return MIN_OUTLINES + (int) Math.round(value * (MAX_OUTLINES - MIN_OUTLINES)); }
        });

        addRenderableWidget(new AbstractSliderButton(cx - 100, cy + 20, 200, 20,
                Component.literal("Outline Load: " + loadLabelForSeconds(GatherSettings.get().highlightRampSeconds)),
                loadIndexForSeconds(GatherSettings.get().highlightRampSeconds) / (double) (LOAD_SECONDS.length - 1)) {
            @Override protected void updateMessage() { setMessage(Component.literal("Outline Load: " + LOAD_LABELS[toIndex()])); }
            @Override protected void applyValue() {
                GatherSettings.get().highlightRampSeconds = LOAD_SECONDS[toIndex()];
                GatherSettings.get().save();
            }
            private int toIndex() {
                return clamp((int) Math.round(value * (LOAD_SECONDS.length - 1)), 0, LOAD_SECONDS.length - 1);
            }
        });

        String[] colors = {"rainbow", "blue", "red", "green", "yellow", "white"};
        addRenderableWidget(Button
                .builder(Component.literal("Outline Color: " + GatherSettings.get().outlineColor), btn -> {
                    String cur = GatherSettings.get().outlineColor;
                    int idx = 0;
                    for (int i = 0; i < colors.length; i++) if (colors[i].equals(cur)) { idx = i; break; }
                    String next = colors[(idx + 1) % colors.length];
                    GatherSettings.get().outlineColor = next;
                    GatherSettings.get().save();
                    btn.setMessage(Component.literal("Outline Color: " + next));
                })
                .bounds(cx - 100, cy + 45, 200, 20)
                .build());

        addRenderableWidget(Button
                .builder(stateText("Collector Outlines", GatherSettings.get().collectorOutlinesEnabled), btn -> {
                    GatherSettings.get().collectorOutlinesEnabled = !GatherSettings.get().collectorOutlinesEnabled;
                    GatherSettings.get().save();
                    WorldHighlightRenderer.invalidateCache();
                    btn.setMessage(stateText("Collector Outlines", GatherSettings.get().collectorOutlinesEnabled));
                })
                .bounds(cx - 100, cy + 70, 200, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Back"), btn -> onClose())
                .bounds(cx - 50, cy + 95, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        hoveredTooltip = null;
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        ctx.centeredText(font, title, width / 2, height / 2 - 88, 0xFFCCDDFF);
        super.extractRenderState(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltip != null) ctx.setComponentTooltipForNextFrame(font, hoveredTooltip, tooltipX, tooltipY);
    }

    private void drawHoverInfo(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2 + 12;
        if (inside(mx, my, cx - 100, cy - 82, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("Cycles Performance, Balanced, Fancy, and Extreme."),
                    Component.literal("Changes range, max outlines, load speed, and scan budget."));
            return;
        }
        if (inside(mx, my, cx - 100, cy - 55, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("Master toggle for Gather block outlines."),
                    Component.literal("Applies to normal outlines and block xray outlines."));
        } else if (inside(mx, my, cx - 100, cy - 30, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("How far Gather scans for matching placed blocks."),
                    Component.literal("Higher values cost more rendering and scanning work."));
        } else if (inside(mx, my, cx - 100, cy - 5, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("Max number of block outlines shown at once."),
                    Component.literal("High values can reduce FPS and mainly use GPU power."),
                    Component.literal("Nearest blocks load first."));
        } else if (inside(mx, my, cx - 100, cy + 20, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("How fast extra outlines appear."),
                    Component.literal("Slower settings spread GPU load over more time."),
                    Component.literal("High Max Outlines can still impact FPS."));
        } else if (inside(mx, my, cx - 100, cy + 45, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("Color for placed-block outlines."),
                    Component.literal("Used by normal outlines and block xray outlines."));
        } else if (inside(mx, my, cx - 100, cy + 70, 200, 20)) {
            setTooltip(mx, my,
                    Component.literal("Show purple outlines for placed collector shulkers."),
                    Component.literal("Shows even when chest outlines are OFF."));
        }
    }

    private void setTooltip(int mx, int my, Component... lines) {
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

    private static String presetLabel() {
        int exact = exactPresetIndex();
        return exact >= 0 ? PRESET_LABELS[exact] : "Custom";
    }

    private static Component presetText() {
        String label = presetLabel();
        return Component.literal("Preset: ")
                .append(Component.literal(label).withStyle(style -> style.withColor(presetColor(label))));
    }

    private static int presetColor(String label) {
        return switch (label) {
            case "Performance" -> 0x55FF77;
            case "Balanced" -> 0x66CCFF;
            case "Fancy" -> 0xFFD966;
            case "Extreme" -> 0xFF6666;
            default -> 0xB8C6D8;
        };
    }

    private static int nextPresetIndex() {
        int exact = exactPresetIndex();
        return exact >= 0 ? (exact + 1) % PRESET_LABELS.length : 1;
    }

    private static int exactPresetIndex() {
        GatherSettings settings = GatherSettings.get();
        for (int i = 0; i < PRESET_LABELS.length; i++) {
            if (settings.highlightScanBudget == PRESET_BUDGETS[i]
                    && settings.highlightRadius == PRESET_RADII[i]
                    && settings.maxBlockHighlights == PRESET_MAX_OUTLINES[i]
                    && settings.highlightRampSeconds == PRESET_RAMP_SECONDS[i]) {
                return i;
            }
        }
        return -1;
    }

    private static void applyPreset(int index) {
        GatherSettings settings = GatherSettings.get();
        settings.highlightScanBudget = PRESET_BUDGETS[index];
        settings.highlightRadius = PRESET_RADII[index];
        settings.maxBlockHighlights = PRESET_MAX_OUTLINES[index];
        settings.highlightRampSeconds = PRESET_RAMP_SECONDS[index];
        settings.save();
        WorldHighlightRenderer.invalidateCache();
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
