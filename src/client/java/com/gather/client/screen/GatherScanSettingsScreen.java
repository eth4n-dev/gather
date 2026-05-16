package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class GatherScanSettingsScreen extends Screen {

    private static final int[] REFRESH_PRESETS = {15, 30, 45, 60, 120};
    private static final String[] REFRESH_LABELS = {"15s", "30s", "45s", "1m", "2m"};

    private final Screen parent;
    private ButtonWidget scanAllBtn;
    private ButtonWidget markBtn;
    private ButtonWidget clearAutoBtn;
    private ButtonWidget clearManualBtn;
    private ButtonWidget refreshIntervalBtn;
    private int markFeedbackTicks = 0;
    private List<Text> hoveredTooltipLines = null;
    private int tooltipX, tooltipY;

    public GatherScanSettingsScreen(Screen parent) {
        super(Text.literal("Scan Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        scanAllBtn = ButtonWidget.builder(scanAllLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.countChests = !settings.countChests;
            if (settings.countChests) GatherState.get().setChestScanMode(false);
            settings.save();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
            btn.setMessage(scanAllLabel());
        }).dimensions(cx - 100, cy - 35, 200, 20).build();
        addDrawableChild(scanAllBtn);

        addDrawableChild(ButtonWidget.builder(scanToggleKeybindLabel(), btn ->
                client.setScreen(new GatherScanKeybindScreen(this)))
                .dimensions(cx - 100, cy - 10, 200, 20).build());

        markBtn = ButtonWidget.builder(Text.literal("Mark Nearby Chests (5x5 chunks)"), btn -> {
            GatherClientNetworking.forceMarkNearby();
            markFeedbackTicks = 60;
            btn.setMessage(Text.literal("Marked! Run Scan All to apply."));
        }).dimensions(cx - 100, cy + 15, 200, 20).build();
        addDrawableChild(markBtn);

        clearAutoBtn = ButtonWidget.builder(Text.literal("Clear Scan All Chests"), btn -> {
            GatherState.get().clearTrackedChests();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
        }).dimensions(cx - 100, cy + 40, 200, 20).build();
        addDrawableChild(clearAutoBtn);

        clearManualBtn = ButtonWidget.builder(Text.literal("Clear Manual Chests"), btn -> {
            GatherState.get().clearManualChests();
            GatherHud.markDirty();
            WorldHighlightRenderer.invalidateCache();
        }).dimensions(cx - 100, cy + 65, 200, 20).build();
        addDrawableChild(clearManualBtn);

        refreshIntervalBtn = ButtonWidget.builder(refreshIntervalLabel(), btn -> {
            GatherSettings s = GatherSettings.get();
            int cur = s.chestFallbackRefreshSeconds;
            int next = REFRESH_PRESETS[0];
            for (int i = 0; i < REFRESH_PRESETS.length - 1; i++) {
                if (cur == REFRESH_PRESETS[i]) { next = REFRESH_PRESETS[i + 1]; break; }
            }
            s.chestFallbackRefreshSeconds = next;
            s.save();
            btn.setMessage(refreshIntervalLabel());
        }).dimensions(cx - 100, cy + 90, 200, 20).build();
        addDrawableChild(refreshIntervalBtn);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
                .dimensions(cx - 50, cy + 120, 100, 20).build());
    }

    private Text scanAllLabel() {
        return Text.literal("Scan All: " + (GatherSettings.get().countChests ? "ON" : "OFF"));
    }

    private Text scanToggleKeybindLabel() {
        return Text.literal("Manual Scan Key: " + GatherScanKeybindScreen.buildComboLabel(GatherSettings.get()));
    }

    private Text refreshIntervalLabel() {
        int cur = GatherSettings.get().chestFallbackRefreshSeconds;
        String label = cur + "s";
        for (int i = 0; i < REFRESH_PRESETS.length; i++) {
            if (REFRESH_PRESETS[i] == cur) { label = REFRESH_LABELS[i]; break; }
        }
        return Text.literal("Fallback Refresh: " + label);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltipLines = null;
        if (markFeedbackTicks > 0) {
            markFeedbackTicks--;
            if (markFeedbackTicks == 0) markBtn.setMessage(Text.literal("Mark Nearby Chests (5x5 chunks)"));
        }
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        int cx = width / 2;
        int cy = height / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, cy - 82, 0xFFCCDDFF);
        drawStatus(ctx, cx, cy - 68);
        super.render(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.drawTooltip(textRenderer, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private void drawStatus(DrawContext ctx, int cx, int y) {
        GatherState state = GatherState.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        int auto = state.getTrackedChests().size();
        int manual = state.getManualChests().size();
        int autoLoaded = auto;
        int autoUnloaded = 0;
        int manualLoaded = manual;
        int manualUnloaded = 0;
        if (mc.world != null) {
            autoUnloaded = state.getUnloadedTrackedChestCount(mc.world);
            autoLoaded = Math.max(0, auto - autoUnloaded);
            manualUnloaded = countUnloadedManualChests(state, mc.world);
            manualLoaded = Math.max(0, manual - manualUnloaded);
        }

        String mode = GatherSettings.get().countChests
                ? "Scan All counts opened tracked chests"
                : "Manual mode counts tagged chests";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(mode), cx, y, 0xFF88BBFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Scan All: " + auto + " (" + autoLoaded + " loaded, " + autoUnloaded + " saved away)"),
                cx, y + 10, auto == 0 ? 0xFF667788 : 0xFF55CCAA);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Manual: " + manual + " (" + manualLoaded + " loaded, " + manualUnloaded + " saved away)"),
                cx, y + 21, manual == 0 ? 0xFF667788 : 0xFFFFAA44);
    }

    private int countUnloadedManualChests(GatherState state, ClientWorld world) {
        int unloaded = 0;
        for (long encoded : state.getManualChests()) {
            BlockPos pos = BlockPos.fromLong(encoded);
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) unloaded++;
        }
        return unloaded;
    }

    private void drawHoverInfo(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2;
        if (inside(mx, my, cx - 100, cy - 35, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("ON: automatically tracks opened containers nearby."),
                    Text.literal("OFF: manual tagging controls what counts."),
                    Text.literal("Saved far-away chests still count from last known contents."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy - 10, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("Configure the key combo that toggles manual scan mode."),
                    Text.literal("Supports modifier keys (Shift/Ctrl/Alt) plus any key."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 15, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("Marks all containers in the 5x5 chunks around you"),
                    Text.literal("as visited so Scan All can read their contents."),
                    Text.literal("Chunks must be loaded. Loot-table chests skipped at scan time."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 40, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("Removes all Scan All tracked chest positions"),
                    Text.literal("and their saved contents for this world."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 65, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("Removes all manually tagged chest positions"),
                    Text.literal("and their saved contents for this world."));
            tooltipX = mx;
            tooltipY = my + 18;
        } else if (inside(mx, my, cx - 100, cy + 90, 200, 20)) {
            hoveredTooltipLines = List.of(
                    Text.literal("How often Gather re-reads chest contents as a fallback."),
                    Text.literal("Chest opens update instantly via dirty events."),
                    Text.literal("This covers hopper/dispenser changes and missed events."),
                    Text.literal("Chests are refreshed gradually, not all at once."));
            tooltipX = mx;
            tooltipY = my + 18;
        }
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
