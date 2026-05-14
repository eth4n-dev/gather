package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherHud;
import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

public class GatherSettingsScreen extends Screen {

    private final Screen parent;
    private List<Component> hoveredTooltipLines = null;
    private int tooltipX, tooltipY;
    private int layoutTop;
    private int layoutLeft;
    private int layoutWidth;
    private int panelW;
    private int panelGap;
    private int panelPad;
    private int visualPanelY;
    private int prefPanelY;
    private int cardFirstButtonY;

    public GatherSettingsScreen(Screen parent) {
        super(Component.literal("Gather Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelGap = 12;
        panelPad = 10;
        cardFirstButtonY = 29;
        panelW = Math.min(154, Math.max(124, (width - 44 - panelGap) / 2));
        layoutWidth = panelW * 2 + panelGap;
        layoutLeft = width / 2 - layoutWidth / 2;
        layoutTop = Math.max(26, height / 2 - 170);
        visualPanelY = layoutTop + 66;
        prefPanelY = visualPanelY + 118 + 14;
        int cx = width / 2;
        int buttonW = panelW - panelPad * 2;

        addRenderableWidget(Button
                .builder(stateText("Gather", GatherSettings.get().enabled), btn -> {
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
                    btn.setMessage(stateText("Gather", settings.enabled));
                })
                .bounds(layoutLeft, layoutTop + 24, layoutWidth, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Outlines"), btn -> minecraft.setScreen(new GatherOutlinesSettingsScreen(this)))
                .bounds(layoutLeft + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)
                .build());

        Button xraySettingsBtn = Button
                .builder(Component.literal("Xray / Glow"), btn -> minecraft.setScreen(new GatherXraySettingsScreen(this)))
                .bounds(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)
                .build();
        xraySettingsBtn.active = GatherState.isServerXrayAllowed();
        addRenderableWidget(xraySettingsBtn);

        addRenderableWidget(Button
                .builder(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation), btn -> {
                    GatherSettings.get().menuSpinAnimation = !GatherSettings.get().menuSpinAnimation;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation));
                })
                .bounds(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)
                .build());

        int workflowX = layoutLeft + panelW + panelGap;
        addRenderableWidget(Button
                .builder(Component.literal("Scan"), btn -> minecraft.setScreen(new GatherScanSettingsScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Imports / Exports"), btn -> minecraft.setScreen(new GatherTransferScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Controls"), btn -> minecraft.setScreen(new GatherScanKeybindScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)
                .build());

        int prefButtonGap = 12;
        int prefButtonW = (layoutWidth - panelPad * 2 - prefButtonGap) / 2;
        addRenderableWidget(Button
                .builder(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled), btn -> {
                    GatherSettings.get().goalSoundEnabled = !GatherSettings.get().goalSoundEnabled;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled));
                })
                .bounds(layoutLeft + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted), btn -> {
                    GatherSettings.get().autoRemoveCompleted = !GatherSettings.get().autoRemoveCompleted;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted));
                })
                .bounds(layoutLeft + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Theme: Modern"), btn -> {})
                .bounds(layoutLeft + panelPad, prefPanelY + cardFirstButtonY + 28, layoutWidth - panelPad * 2, 20)
                .build());

        int footerY = prefPanelY + 116;
        int footerGap = 8;
        int footerButtonW = 86;
        int footerX = cx - (footerButtonW * 3 + footerGap * 2) / 2;
        addRenderableWidget(Button
                .builder(Component.literal("Help"), btn -> minecraft.setScreen(new GatherHelpScreen(this, false)))
                .bounds(footerX, footerY, footerButtonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Tutorial"), btn -> minecraft.setScreen(new GatherTutorialScreen()))
                .bounds(footerX + footerButtonW + footerGap, footerY, footerButtonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Done"), btn -> onClose())
                .bounds(footerX + (footerButtonW + footerGap) * 2, footerY, footerButtonW, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        hoveredTooltipLines = null;
        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);
        ctx.centeredText(font, title, width / 2, layoutTop + 6, 0xFFCCDDFF);
        drawPanel(ctx, layoutLeft, visualPanelY, panelW, 118, "Visuals");
        drawPanel(ctx, layoutLeft + panelW + panelGap, visualPanelY, panelW, 118, "Workflow");
        drawPanel(ctx, layoutLeft, prefPanelY, layoutWidth, 98, "Preferences");
        String version = FabricLoader.getInstance().getModContainer("gather")
                .map(c -> "v" + c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        ctx.centeredText(font, Component.literal(version), width / 2, prefPanelY + 103, 0xFF2A3A4A);
        super.extractRenderState(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.setComponentTooltipForNextFrame(font, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private void drawPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label) {
        GatherTheme.fill(ctx, x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
        GatherTheme.fill(ctx, x + 1, y + h, x + w + 2, y + h + 3, 0x55000000);
        GatherTheme.fill(ctx, x + w, y + 1, x + w + 3, y + h + 2, 0x44000000);
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0x66182438);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0x88556688);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0x55334466);
        ctx.text(font, Component.literal(label), x + panelPad, y + 10, 0xFF99AACC);
    }

    private static Component stateText(String label, boolean enabled) {
        return Component.literal(label + ": ")
                .append(Component.literal(enabled ? "ON" : "OFF").withStyle(style ->
                        style.withColor(enabled ? 0x55FF77 : 0xFF6666)));
    }

    private static Component themeText() {
        return Component.literal("Theme: " + GatherTheme.currentThemeLabel());
    }

    private void drawHoverInfo(int mx, int my) {
        int buttonW = panelW - panelPad * 2;
        int workflowX = layoutLeft + panelW + panelGap;
        int prefButtonGap = 12;
        int prefButtonW = (layoutWidth - panelPad * 2 - prefButtonGap) / 2;
        if (inside(mx, my, layoutLeft, layoutTop + 24, layoutWidth, 20)) {
            setTooltip(mx, my,
                    Component.literal("Master switch for Gather runtime features."),
                    Component.literal("OFF disables HUD, outlines, scans, item glow, and overlays."),
                    Component.literal("This settings screen remains available so you can re-enable it."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Settings for normal block outlines and block xray outlines."),
                    Component.literal("Includes on/off, range, max count, and color."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Toggle xray glow for dropped items and placed blocks."),
                    Component.literal("Each has an independent on/off switch."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Spin the Gather menu while it opens and closes."),
                    Component.literal("OFF keeps the regular scale animation."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Options for Scan All, including marking nearby chests"),
                    Component.literal("so they appear in scan results."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Import from other worlds or JSON files."),
                    Component.literal("Exported JSON files go in the Gather exchange folder."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Configure the Gather key and scan-toggle modifier combo."),
                    Component.literal("Use this to separate opening the menu from manual scan mode."));
        } else if (inside(mx, my, layoutLeft + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Play a sound when a goal's materials are fully gathered."));
        } else if (inside(mx, my, layoutLeft + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Automatically remove goals once all materials are gathered."),
                    Component.literal("Goals are permanently deleted; they won't come back."));
        } else if (inside(mx, my, layoutLeft + panelPad, prefPanelY + cardFirstButtonY + 28, layoutWidth - panelPad * 2, 20)) {
            setTooltip(mx, my,
                    Component.literal("Only the Modern theme is currently available."),
                    Component.literal("More themes coming soon."));
        }
    }

    private void setTooltip(int mx, int my, Component... lines) {
        hoveredTooltipLines = List.of(lines);
        tooltipX = mx;
        tooltipY = my + 18;
    }

    private boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
