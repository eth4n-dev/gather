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
    private int bSpacing;
    private boolean settingsCompact;
    private boolean settingsSuperCompact;
    private int settingsScroll;
    private int maxSettingsScroll;
    private int prefColX;
    private int prefPanelWidth;

    public GatherSettingsScreen(Screen parent) {
        super(Component.literal("Gather Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        settingsCompact = height < 372;
        settingsSuperCompact = height < 300;
        panelGap = 12;
        panelPad = 10;
        cardFirstButtonY = settingsCompact ? 24 : 29;
        bSpacing = settingsCompact ? 26 : 28;
        if (settingsSuperCompact) {
            panelW = Math.max(90, (width - 44 - panelGap * 2) / 3);
            layoutWidth = panelW * 3 + panelGap * 2;
        } else {
            panelW = Math.min(154, Math.max(124, (width - 44 - panelGap) / 2));
            layoutWidth = panelW * 2 + panelGap;
        }
        layoutLeft = width / 2 - layoutWidth / 2;
        layoutTop = Math.max(settingsCompact ? 22 : 26, height / 2 - 170) - settingsScroll;
        visualPanelY = layoutTop + (settingsCompact ? 58 : 66);
        if (settingsSuperCompact) {
            prefPanelY = visualPanelY;
            prefColX = layoutLeft + 2 * (panelW + panelGap);
            prefPanelWidth = panelW;
        } else {
            prefPanelY = visualPanelY + (settingsCompact ? 102 : 118) + (settingsCompact ? 8 : 14);
            prefColX = layoutLeft;
            prefPanelWidth = layoutWidth;
        }
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
                .bounds(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + bSpacing, buttonW, 20)
                .build();
        xraySettingsBtn.active = GatherState.isServerXrayAllowed();
        addRenderableWidget(xraySettingsBtn);

        addRenderableWidget(Button
                .builder(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation), btn -> {
                    GatherSettings.get().menuSpinAnimation = !GatherSettings.get().menuSpinAnimation;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation));
                })
                .bounds(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + bSpacing * 2, buttonW, 20)
                .build());

        int workflowX = layoutLeft + panelW + panelGap;
        addRenderableWidget(Button
                .builder(Component.literal("Scan"), btn -> minecraft.setScreen(new GatherScanSettingsScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Imports / Exports"), btn -> minecraft.setScreen(new GatherTransferScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY + bSpacing, buttonW, 20)
                .build());

        addRenderableWidget(Button
                .builder(Component.literal("Controls"), btn -> minecraft.setScreen(new GatherScanKeybindScreen(this)))
                .bounds(workflowX + panelPad, visualPanelY + cardFirstButtonY + bSpacing * 2, buttonW, 20)
                .build());

        if (settingsSuperCompact) {
            int pbw = prefPanelWidth - panelPad * 2;
            addRenderableWidget(Button
                    .builder(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled), btn -> {
                        GatherSettings.get().goalSoundEnabled = !GatherSettings.get().goalSoundEnabled;
                        GatherSettings.get().save();
                        btn.setMessage(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled));
                    })
                    .bounds(prefColX + panelPad, prefPanelY + cardFirstButtonY, pbw, 20)
                    .build());
            addRenderableWidget(Button
                    .builder(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted), btn -> {
                        GatherSettings.get().autoRemoveCompleted = !GatherSettings.get().autoRemoveCompleted;
                        GatherSettings.get().save();
                        btn.setMessage(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted));
                    })
                    .bounds(prefColX + panelPad, prefPanelY + cardFirstButtonY + bSpacing, pbw, 20)
                    .build());
            addRenderableWidget(Button
                    .builder(stateText("Updates", GatherSettings.get().updateNotifications && !GatherSettings.get().suppressUpdateNotif), btn -> {
                        GatherSettings settings = GatherSettings.get();
                        boolean enabled = !(settings.updateNotifications && !settings.suppressUpdateNotif);
                        settings.updateNotifications = enabled;
                        settings.suppressUpdateNotif = !enabled;
                        settings.save();
                        btn.setMessage(stateText("Updates", enabled));
                    })
                    .bounds(prefColX + panelPad, prefPanelY + cardFirstButtonY + bSpacing * 2, pbw, 20)
                    .build());
        } else {
            int prefButtonGap = 12;
            int prefButtonW = (prefPanelWidth - panelPad * 2 - prefButtonGap) / 2;
            addRenderableWidget(Button
                    .builder(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled), btn -> {
                        GatherSettings.get().goalSoundEnabled = !GatherSettings.get().goalSoundEnabled;
                        GatherSettings.get().save();
                        btn.setMessage(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled));
                    })
                    .bounds(prefColX + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                    .build());
            addRenderableWidget(Button
                    .builder(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted), btn -> {
                        GatherSettings.get().autoRemoveCompleted = !GatherSettings.get().autoRemoveCompleted;
                        GatherSettings.get().save();
                        btn.setMessage(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted));
                    })
                    .bounds(prefColX + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                    .build());
            addRenderableWidget(Button
                    .builder(Component.literal("Theme: Modern"), btn -> {})
                    .bounds(prefColX + panelPad, prefPanelY + cardFirstButtonY + bSpacing, prefButtonW, 20)
                    .build());
            addRenderableWidget(Button
                    .builder(stateText("Updates", GatherSettings.get().updateNotifications && !GatherSettings.get().suppressUpdateNotif), btn -> {
                        GatherSettings settings = GatherSettings.get();
                        boolean enabled = !(settings.updateNotifications && !settings.suppressUpdateNotif);
                        settings.updateNotifications = enabled;
                        settings.suppressUpdateNotif = !enabled;
                        settings.save();
                        btn.setMessage(stateText("Updates", enabled));
                    })
                    .bounds(prefColX + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY + bSpacing, prefButtonW, 20)
                    .build());
        }

        int visPanelH = settingsCompact ? 102 : 118;
        int footerY = settingsSuperCompact
                ? visualPanelY + visPanelH + 8
                : prefPanelY + (settingsCompact ? 82 : 116);
        maxSettingsScroll = Math.max(0, footerY + (settingsCompact ? 26 : 32) + settingsScroll - height);
        if (settingsScroll > maxSettingsScroll) {
            settingsScroll = maxSettingsScroll;
            rebuildSettingsWidgets();
            return;
        }
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
        ctx.centeredText(font, title, width / 2, 12, 0xFFCCDDFF);
        int visPanelH  = settingsCompact ? 102 : 118;
        int prefPanelH = settingsSuperCompact ? 102 : (settingsCompact ? 76 : 98);
        drawPanel(ctx, layoutLeft, visualPanelY, panelW, visPanelH, "Visuals");
        drawPanel(ctx, layoutLeft + panelW + panelGap, visualPanelY, panelW, visPanelH, "Workflow");
        drawPanel(ctx, prefColX, prefPanelY, prefPanelWidth, prefPanelH, "Preferences");
        String version = FabricLoader.getInstance().getModContainer("gather")
                .map(c -> "v" + c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        int approxFooterY = settingsSuperCompact ? visualPanelY + visPanelH + 8 : prefPanelY + (settingsCompact ? 82 : 116);
        boolean versionFits = (height - (approxFooterY + 26)) >= 12;
        if (versionFits) {
            int versionY = settingsCompact ? height - 10 : prefPanelY + 104;
            ctx.centeredText(font, Component.literal(version), width / 2, versionY, 0xFF2A3A4A);
        }
        super.extractRenderState(ctx, mx, my, delta);
        drawScrollIndicator(ctx);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.setComponentTooltipForNextFrame(font, hoveredTooltipLines, tooltipX, tooltipY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (maxSettingsScroll <= 0) return super.mouseScrolled(mx, my, hx, vy);
        int next = Math.max(0, Math.min(maxSettingsScroll, settingsScroll - (int) Math.signum(vy) * 18));
        if (next == settingsScroll) return true;
        settingsScroll = next;
        rebuildSettingsWidgets();
        return true;
    }

    private void rebuildSettingsWidgets() {
        clearWidgets();
        init();
    }

    private void drawScrollIndicator(GuiGraphicsExtractor ctx) {
        if (maxSettingsScroll <= 0) return;
        int trackX = width - 7;
        int trackTop = 28;
        int trackBottom = height - 28;
        if (trackBottom <= trackTop + 10) return;

        GatherTheme.fill(ctx, trackX, trackTop, trackX + 3, trackBottom, 0x66334466);
        int trackH = trackBottom - trackTop;
        int thumbH = Math.max(12, trackH * trackH / Math.max(trackH + maxSettingsScroll, 1));
        int thumbY = trackTop + (trackH - thumbH) * settingsScroll / Math.max(maxSettingsScroll, 1);
        GatherTheme.fill(ctx, trackX - 1, thumbY, trackX + 4, thumbY + thumbH, 0xCC88CCFF);
        if (width >= 190) {
            ctx.text(font, Component.literal("Scroll"), width - font.width("Scroll") - 12, 12, 0xFF88AACC);
        }
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
        int prefButtonW = settingsSuperCompact
                ? prefPanelWidth - panelPad * 2
                : (prefPanelWidth - panelPad * 2 - prefButtonGap) / 2;
        if (inside(mx, my, layoutLeft, layoutTop + 24, layoutWidth, 20)) {
            setTooltip(mx, my,
                    Component.literal("Master switch for Gather runtime features."),
                    Component.literal("OFF disables HUD, outlines, scans, item glow, and overlays."),
                    Component.literal("This settings screen remains available so you can re-enable it."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Settings for normal block outlines and block xray outlines."),
                    Component.literal("Includes on/off, range, max count, and color."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + bSpacing, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Toggle xray glow for dropped items and placed blocks."),
                    Component.literal("Each has an independent on/off switch."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + bSpacing * 2, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Spin the Gather menu while it opens and closes."),
                    Component.literal("OFF keeps the regular scale animation."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Options for Scan All, including marking nearby chests"),
                    Component.literal("so they appear in scan results."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + bSpacing, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Import from other worlds or JSON files."),
                    Component.literal("Exported JSON files go in the Gather exchange folder."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + bSpacing * 2, buttonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Configure the Gather key and scan-toggle modifier combo."),
                    Component.literal("Use this to separate opening the menu from manual scan mode."));
        } else if (inside(mx, my, prefColX + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Play a sound when a goal's materials are fully gathered."));
        } else if (settingsSuperCompact
                ? inside(mx, my, prefColX + panelPad, prefPanelY + cardFirstButtonY + bSpacing, prefButtonW, 20)
                : inside(mx, my, prefColX + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Component.literal("Automatically remove goals once all materials are gathered."),
                    Component.literal("Goals are permanently deleted; they won't come back."));
        } else if (inside(mx, my, prefColX + panelPad, prefPanelY + cardFirstButtonY + (settingsSuperCompact ? bSpacing * 2 : bSpacing), prefPanelWidth - panelPad * 2, 20)) {
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
