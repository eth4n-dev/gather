package com.gather.client.screen;

import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.GatherTheme;
import com.gather.client.GatherUi;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GatherChestToolsScreen extends Screen {
    private final Screen parent;
    private int rescanFeedbackTicks = 0;

    public GatherChestToolsScreen(Screen parent) {
        super(Component.literal("Chest Tools"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelW = Math.min(220, width - 16);
        int x = width / 2 - panelW / 2;
        boolean compact = height < 240;
        int buttonH = compact ? 16 : 20;
        int rowStep = compact ? 18 : 24;
        int y = compact ? 6 : Math.max(28, height / 2 - 124);
        int buttonW = panelW - 20;
        int buttonX = x + 10;
        int row = y + (compact ? 16 : 28);

        addRenderableWidget(Button.builder(scanAllLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.countChests = !settings.countChests;
            if (settings.countChests) GatherState.get().setChestScanMode(false);
            settings.save();
            btn.setMessage(scanAllLabel());
            requestScanIfEnabled();
        }).bounds(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addRenderableWidget(Button.builder(manualLabel(), btn -> {
            GatherState state = GatherState.get();
            state.setChestScanMode(!state.isChestScanMode());
            btn.setMessage(manualLabel());
        }).bounds(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addRenderableWidget(Button.builder(outlinesLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.chestOutlinesEnabled = !settings.chestOutlinesEnabled;
            settings.save();
            btn.setMessage(outlinesLabel());
        }).bounds(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        Button rescan = Button.builder(Component.literal(rescanFeedbackTicks > 0 ? "Rescanning..." : "Rescan Now"), btn -> {
            rescanFeedbackTicks = 50;
            requestScanIfEnabled();
            clearWidgets();
            init();
        }).bounds(buttonX, row, buttonW, buttonH).build();
        rescan.active = rescanFeedbackTicks == 0;
        addRenderableWidget(rescan);
        row += rowStep;

        Button finder = Button.builder(Component.literal("Find Item..."), btn -> minecraft.setScreen(new GatherChestFinderScreen(this)))
                .bounds(buttonX, row, buttonW, buttonH).build();
        finder.active = searchableChestCount() > 0;
        addRenderableWidget(finder);
        row += rowStep;

        addRenderableWidget(Button.builder(Component.literal("Clear Scanned"), btn -> GatherState.get().clearTrackedChests())
                .bounds(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addRenderableWidget(Button.builder(Component.literal("Clear Tagged"), btn -> GatherState.get().clearManualChests())
                .bounds(buttonX, row, buttonW, buttonH).build());
        row += compact ? 20 : 28;

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(buttonX, row, buttonW, buttonH).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (rescanFeedbackTicks > 0) rescanFeedbackTicks--;
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        ctx.centeredText(font, title, width / 2, height < 240 ? 6 : 16, 0xFFCCDDFF);
        super.extractRenderState(ctx, mx, my, delta);
        if (height >= 210) ctx.centeredText(font, Component.literal(statusText()), width / 2, height - 12, 0xFF778899);
    }

    private Component scanAllLabel() {
        return Component.literal("Scan All: " + (GatherSettings.get().countChests ? "ON" : "OFF"));
    }

    private Component manualLabel() {
        return Component.literal("Manual Scan: " + (GatherState.get().isChestScanMode() ? "ON" : "OFF"));
    }

    private Component outlinesLabel() {
        return Component.literal("Chest Outlines: " + (GatherSettings.get().chestOutlinesEnabled ? "ON" : "OFF"));
    }

    private String statusText() {
        GatherState state = GatherState.get();
        return state.getTrackedChests().size() + " scanned, " + state.getManualChests().size() + " tagged";
    }

    private int searchableChestCount() {
        return GatherSettings.get().countChests ? GatherState.get().getTrackedChests().size() : GatherState.get().getManualChests().size();
    }

    private void requestScanIfEnabled() {
        if (minecraft == null || !GatherSettings.get().countChests) return;
        GatherState state = GatherState.get();
        List<String> targets = state.getChestScanTargets(id -> {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return item == null ? 0 : GatherHud.countInventoryTagAware(minecraft, item);
        });
        GatherClientNetworking.requestAutoTrack(GatherSettings.get().chestScanRadius, targets);
        Set<Long> all = new HashSet<>(state.getTrackedChests());
        all.addAll(state.getManualChests());
        if (!all.isEmpty()) GatherClientNetworking.requestTrackedChests(all);
    }

    @Override
    public void onClose() {
        WorldHighlightRenderer.invalidateCache();
        GatherUi.playClickSound();
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
