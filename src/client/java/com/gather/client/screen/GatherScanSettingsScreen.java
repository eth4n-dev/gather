package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.List;

public class GatherScanSettingsScreen extends Screen {

    private static final int[] REFRESH_PRESETS = {15, 30, 45, 60, 120};
    private static final String[] REFRESH_LABELS = {"15s", "30s", "45s", "1m", "2m"};

    private final Screen parent;
    private Button scanAllBtn;
    private Button markBtn;
    private Button clearAutoBtn;
    private Button clearManualBtn;
    private Button refreshIntervalBtn;
    private int markFeedbackTicks = 0;
    private List<Component> hoveredTooltipLines = null;
    private int tooltipX, tooltipY;

    public GatherScanSettingsScreen(Screen parent) {
        super(Component.literal("Scan Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        scanAllBtn = Button.builder(scanAllLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.countChests = !settings.countChests;
            if (settings.countChests) GatherState.get().setChestScanMode(false);
            settings.save();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
            btn.setMessage(scanAllLabel());
        }).bounds(cx - 100, cy - 35, 200, 20).build();
        addRenderableWidget(scanAllBtn);

        addRenderableWidget(Button.builder(scanToggleKeybindLabel(), btn ->
                minecraft.setScreen(new GatherScanKeybindScreen(this)))
                .bounds(cx - 100, cy - 10, 200, 20).build());

        markBtn = Button.builder(Component.literal("Mark Nearby Chests (5x5 chunks)"), btn -> {
            GatherClientNetworking.forceMarkNearby();
            markFeedbackTicks = 60;
            btn.setMessage(Component.literal("Marked! Run Scan All to apply."));
        }).bounds(cx - 100, cy + 15, 200, 20).build();
        addRenderableWidget(markBtn);

        clearAutoBtn = Button.builder(Component.literal("Clear Scan All Chests"), btn -> {
            GatherState.get().clearTrackedChests();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
        }).bounds(cx - 100, cy + 40, 200, 20).build();
        addRenderableWidget(clearAutoBtn);

        clearManualBtn = Button.builder(Component.literal("Clear Manual Chests"), btn -> {
            GatherState.get().clearManualChests();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
        }).bounds(cx - 100, cy + 65, 200, 20).build();
        addRenderableWidget(clearManualBtn);

        refreshIntervalBtn = Button.builder(refreshIntervalLabel(), btn -> {
            GatherSettings s = GatherSettings.get();
            int cur = s.chestFallbackRefreshSeconds;
            int next = REFRESH_PRESETS[0];
            for (int i = 0; i < REFRESH_PRESETS.length - 1; i++) {
                if (cur == REFRESH_PRESETS[i]) { next = REFRESH_PRESETS[i + 1]; break; }
            }
            s.chestFallbackRefreshSeconds = next;
            s.save();
            btn.setMessage(refreshIntervalLabel());
        }).bounds(cx - 100, cy + 90, 200, 20).build();
        addRenderableWidget(refreshIntervalBtn);

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(cx - 50, cy + 120, 100, 20).build());
    }

    private Component scanAllLabel() {
        return Component.literal("Scan All: " + (GatherSettings.get().countChests ? "ON" : "OFF"));
    }

    private Component scanToggleKeybindLabel() {
        return Component.literal("Manual Scan Key: " + GatherScanKeybindScreen.buildComboLabel(GatherSettings.get()));
    }

    private Component refreshIntervalLabel() {
        int cur = GatherSettings.get().chestFallbackRefreshSeconds;
        String label = cur + "s";
        for (int i = 0; i < REFRESH_PRESETS.length; i++) {
            if (REFRESH_PRESETS[i] == cur) { label = REFRESH_LABELS[i]; break; }
        }
        return Component.literal("Fallback Refresh: " + label);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        hoveredTooltipLines = null;
        if (markFeedbackTicks > 0) {
            markFeedbackTicks--;
            if (markFeedbackTicks == 0) markBtn.setMessage(Component.literal("Mark Nearby Chests (5x5 chunks)"));
        }
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        int cx = width / 2;
        int cy = height / 2;
        ctx.centeredText(font, title, cx, cy - 82, 0xFFCCDDFF);
        drawStatus(ctx, cx, cy - 68);
        super.extractRenderState(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.setComponentTooltipForNextFrame(font, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private void drawStatus(GuiGraphicsExtractor ctx, int cx, int y) {
        GatherState state = GatherState.get();
        Minecraft mc = Minecraft.getInstance();
        int auto = state.getTrackedChests().size();
        int manual = state.getManualChests().size();
        int autoLoaded = auto;
        int autoUnloaded = 0;
        int manualLoaded = manual;
        int manualUnloaded = 0;
        if (mc.level != null) {
            autoUnloaded = state.getUnloadedTrackedChestCount(mc.level);
            autoLoaded = Math.max(0, auto - autoUnloaded);
            manualUnloaded = countUnloadedManualChests(state, mc.level);
            manualLoaded = Math.max(0, manual - manualUnloaded);
        }

        String mode = GatherSettings.get().countChests
                ? "Scan All counts opened tracked chests"
                : "Manual mode counts tagged chests";
        ctx.centeredText(font, Component.literal(mode), cx, y, 0xFF88BBFF);
        ctx.centeredText(font,
                Component.literal("Scan All: " + auto + " (" + autoLoaded + " loaded, " + autoUnloaded + " saved away)"),
                cx, y + 10, auto == 0 ? 0xFF667788 : 0xFF55CCAA);
        ctx.centeredText(font,
                Component.literal("Manual: " + manual + " (" + manualLoaded + " loaded, " + manualUnloaded + " saved away)"),
                cx, y + 21, manual == 0 ? 0xFF667788 : 0xFFFFAA44);
    }

    private int countUnloadedManualChests(GatherState state, ClientLevel world) {
        int unloaded = 0;
        for (long encoded : state.getManualChests()) {
            BlockPos pos = BlockPos.of(encoded);
            if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) unloaded++;
        }
        return unloaded;
    }

    private void drawHoverInfo(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2;
        if (inside(mx, my, cx - 100, cy - 35, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("ON: automatically tracks opened containers nearby."),
                    Component.literal("OFF: manual tagging controls what counts."),
                    Component.literal("Saved far-away chests still count from last known contents."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy - 10, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("Configure the key combo that toggles manual scan mode."),
                    Component.literal("Supports modifier keys (Shift/Ctrl/Alt) plus any key."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 15, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("Marks all containers in the 5x5 chunks around you"),
                    Component.literal("as visited so Scan All can read their contents."),
                    Component.literal("Chunks must be loaded. Loot-table chests skipped at scan time."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 40, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("Removes all Scan All tracked chest positions"),
                    Component.literal("and their saved contents for this world."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 65, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("Removes all manually tagged chest positions"),
                    Component.literal("and their saved contents for this world."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 90, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Component.literal("How often Gather re-reads chest contents as a fallback."),
                    Component.literal("Chest opens update instantly via dirty events."),
                    Component.literal("This covers hopper/dispenser changes and missed events."),
                    Component.literal("Chests are refreshed gradually, not all at once."));
            tooltipX = mx;
            tooltipY = my + 18;
        }
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
