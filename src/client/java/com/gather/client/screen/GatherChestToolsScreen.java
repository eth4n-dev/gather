package com.gather.client.screen;

import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherHud;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.GatherTheme;
import com.gather.client.GatherUi;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GatherChestToolsScreen extends Screen {
    private final Screen parent;
    private int rescanFeedbackTicks = 0;

    public GatherChestToolsScreen(Screen parent) {
        super(Text.literal("Chest Tools"));
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

        addDrawableChild(ButtonWidget.builder(scanAllLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.countChests = !settings.countChests;
            if (settings.countChests) GatherState.get().setChestScanMode(false);
            settings.save();
            btn.setMessage(scanAllLabel());
            requestScanIfEnabled();
        }).dimensions(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addDrawableChild(ButtonWidget.builder(manualLabel(), btn -> {
            GatherState state = GatherState.get();
            state.setChestScanMode(!state.isChestScanMode());
            btn.setMessage(manualLabel());
        }).dimensions(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addDrawableChild(ButtonWidget.builder(outlinesLabel(), btn -> {
            GatherSettings settings = GatherSettings.get();
            settings.chestOutlinesEnabled = !settings.chestOutlinesEnabled;
            settings.save();
            btn.setMessage(outlinesLabel());
        }).dimensions(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        ButtonWidget rescan = ButtonWidget.builder(Text.literal(rescanFeedbackTicks > 0 ? "Rescanning..." : "Rescan Now"), btn -> {
            rescanFeedbackTicks = 50;
            requestScanIfEnabled();
            clearChildren();
            init();
        }).dimensions(buttonX, row, buttonW, buttonH).build();
        rescan.active = rescanFeedbackTicks == 0;
        addDrawableChild(rescan);
        row += rowStep;

        ButtonWidget finder = ButtonWidget.builder(Text.literal("Find Item..."), btn -> client.setScreen(new GatherChestFinderScreen(this)))
                .dimensions(buttonX, row, buttonW, buttonH).build();
        finder.active = searchableChestCount() > 0;
        addDrawableChild(finder);
        row += rowStep;

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear Scanned"), btn -> GatherState.get().clearTrackedChests())
                .dimensions(buttonX, row, buttonW, buttonH).build());
        row += rowStep;

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear Tagged"), btn -> GatherState.get().clearManualChests())
                .dimensions(buttonX, row, buttonW, buttonH).build());
        row += compact ? 20 : 28;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
                .dimensions(buttonX, row, buttonW, buttonH).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        if (rescanFeedbackTicks > 0) rescanFeedbackTicks--;
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height < 240 ? 6 : 16, 0xFFCCDDFF);
        super.render(ctx, mx, my, delta);
        if (height >= 210) ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(statusText()), width / 2, height - 12, 0xFF778899);
    }

    private Text scanAllLabel() {
        return Text.literal("Scan All: " + (GatherSettings.get().countChests ? "ON" : "OFF"));
    }

    private Text manualLabel() {
        return Text.literal("Manual Scan: " + (GatherState.get().isChestScanMode() ? "ON" : "OFF"));
    }

    private Text outlinesLabel() {
        return Text.literal("Chest Outlines: " + (GatherSettings.get().chestOutlinesEnabled ? "ON" : "OFF"));
    }

    private String statusText() {
        GatherState state = GatherState.get();
        return state.getTrackedChests().size() + " scanned, " + state.getManualChests().size() + " tagged";
    }

    private int searchableChestCount() {
        return GatherSettings.get().countChests ? GatherState.get().getTrackedChests().size() : GatherState.get().getManualChests().size();
    }

    private void requestScanIfEnabled() {
        if (client == null || !GatherSettings.get().countChests) return;
        GatherState state = GatherState.get();
        List<String> targets = state.getChestScanTargets(id -> {
            Item item = Registries.ITEM.get(Identifier.of(id));
            return item == null ? 0 : GatherHud.countInventoryTagAware(client, item);
        });
        GatherClientNetworking.requestAutoTrack(GatherSettings.get().chestScanRadius, targets);
        Set<Long> all = new HashSet<>(state.getTrackedChests());
        all.addAll(state.getManualChests());
        if (!all.isEmpty()) GatherClientNetworking.requestTrackedChests(all);
    }

    @Override
    public void close() {
        WorldHighlightRenderer.invalidateCache();
        GatherUi.playClickSound();
        if (client != null) client.setScreen(parent);
    }
}
