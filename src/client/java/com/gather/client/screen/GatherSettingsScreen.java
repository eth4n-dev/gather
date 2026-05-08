package com.gather.client.screen;

import com.gather.client.GatherHud;
import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public class GatherSettingsScreen extends Screen {

    private final Screen parent;
    private List<Text> hoveredTooltipLines = null;
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
        super(Text.literal("Gather Settings"));
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

        addDrawableChild(ButtonWidget
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
                .dimensions(layoutLeft, layoutTop + 24, layoutWidth, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Outlines"), btn -> client.setScreen(new GatherOutlinesSettingsScreen(this)))
                .dimensions(layoutLeft + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)
                .build());

        ButtonWidget xraySettingsBtn = ButtonWidget
                .builder(Text.literal("Xray / Glow"), btn -> client.setScreen(new GatherXraySettingsScreen(this)))
                .dimensions(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)
                .build();
        xraySettingsBtn.active = GatherState.isServerXrayAllowed();
        addDrawableChild(xraySettingsBtn);

        addDrawableChild(ButtonWidget
                .builder(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation), btn -> {
                    GatherSettings.get().menuSpinAnimation = !GatherSettings.get().menuSpinAnimation;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Menu Spin", GatherSettings.get().menuSpinAnimation));
                })
                .dimensions(layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)
                .build());

        int workflowX = layoutLeft + panelW + panelGap;
        addDrawableChild(ButtonWidget
                .builder(Text.literal("Scan"), btn -> client.setScreen(new GatherScanSettingsScreen(this)))
                .dimensions(workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Imports / Exports"), btn -> client.setScreen(new GatherTransferScreen(this)))
                .dimensions(workflowX + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Controls"), btn -> client.setScreen(new GatherScanKeybindScreen(this)))
                .dimensions(workflowX + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)
                .build());

        int prefButtonGap = 12;
        int prefButtonW = (layoutWidth - panelPad * 2 - prefButtonGap) / 2;
        addDrawableChild(ButtonWidget
                .builder(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled), btn -> {
                    GatherSettings.get().goalSoundEnabled = !GatherSettings.get().goalSoundEnabled;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Goal Sound", GatherSettings.get().goalSoundEnabled));
                })
                .dimensions(layoutLeft + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted), btn -> {
                    GatherSettings.get().autoRemoveCompleted = !GatherSettings.get().autoRemoveCompleted;
                    GatherSettings.get().save();
                    btn.setMessage(stateText("Auto Remove", GatherSettings.get().autoRemoveCompleted));
                })
                .dimensions(layoutLeft + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)
                .build());

        int footerY = prefPanelY + 88;
        int footerGap = 8;
        int footerButtonW = 86;
        int footerX = cx - (footerButtonW * 3 + footerGap * 2) / 2;
        addDrawableChild(ButtonWidget
                .builder(Text.literal("Help"), btn -> client.setScreen(new GatherHelpScreen(this, false)))
                .dimensions(footerX, footerY, footerButtonW, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Tutorial"), btn -> client.setScreen(new GatherTutorialScreen()))
                .dimensions(footerX + footerButtonW + footerGap, footerY, footerButtonW, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Done"), btn -> close())
                .dimensions(footerX + (footerButtonW + footerGap) * 2, footerY, footerButtonW, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hoveredTooltipLines = null;
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, layoutTop + 6, 0xFFCCDDFF);
        drawPanel(ctx, layoutLeft, visualPanelY, panelW, 118, "Visuals");
        drawPanel(ctx, layoutLeft + panelW + panelGap, visualPanelY, panelW, 118, "Workflow");
        drawPanel(ctx, layoutLeft, prefPanelY, layoutWidth, 70, "Preferences");
        String version = FabricLoader.getInstance().getModContainer("gather")
                .map(c -> "v" + c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(version), width / 2, prefPanelY + 113, 0xFF2A3A4A);
        super.render(ctx, mx, my, delta);
        drawHoverInfo(mx, my);
        if (hoveredTooltipLines != null) ctx.drawTooltip(textRenderer, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private void drawPanel(DrawContext ctx, int x, int y, int w, int h, String label) {
        ctx.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
        ctx.fill(x + 1, y + h, x + w + 2, y + h + 3, 0x55000000);
        ctx.fill(x + w, y + 1, x + w + 3, y + h + 2, 0x44000000);
        ctx.fill(x, y, x + w, y + h, 0x66182438);
        ctx.fill(x, y, x + w, y + 1, 0x88556688);
        ctx.fill(x, y, x + 1, y + h, 0x55334466);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), x + panelPad, y + 10, 0xFF99AACC);
    }

    private static Text stateText(String label, boolean enabled) {
        return Text.literal(label + ": ")
                .append(Text.literal(enabled ? "ON" : "OFF").styled(style ->
                        style.withColor(enabled ? 0x55FF77 : 0xFF6666)));
    }

    private void drawHoverInfo(int mx, int my) {
        int buttonW = panelW - panelPad * 2;
        int workflowX = layoutLeft + panelW + panelGap;
        int prefButtonGap = 12;
        int prefButtonW = (layoutWidth - panelPad * 2 - prefButtonGap) / 2;
        if (inside(mx, my, layoutLeft, layoutTop + 24, layoutWidth, 20)) {
            setTooltip(mx, my,
                    Text.literal("Master switch for Gather runtime features."),
                    Text.literal("OFF disables HUD, outlines, scans, item glow, and overlays."),
                    Text.literal("This settings screen remains available so you can re-enable it."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Settings for normal block outlines and block xray outlines."),
                    Text.literal("Includes on/off, range, max count, and color."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Toggle xray glow for dropped items and placed blocks."),
                    Text.literal("Each has an independent on/off switch."));
        } else if (inside(mx, my, layoutLeft + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Spin the Gather menu while it opens and closes."),
                    Text.literal("OFF keeps the regular scale animation."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Options for Scan All — including marking nearby chests"),
                    Text.literal("so they appear in scan results."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + 28, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Import from other worlds or JSON files."),
                    Text.literal("Exported JSON files go in the Gather exchange folder."));
        } else if (inside(mx, my, workflowX + panelPad, visualPanelY + cardFirstButtonY + 56, buttonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Configure the Gather key and scan-toggle modifier combo."),
                    Text.literal("Use this to separate opening the menu from manual scan mode."));
        } else if (inside(mx, my, layoutLeft + panelPad, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Play a sound when a goal's materials are fully gathered."));
        } else if (inside(mx, my, layoutLeft + panelPad + prefButtonW + prefButtonGap, prefPanelY + cardFirstButtonY, prefButtonW, 20)) {
            setTooltip(mx, my,
                    Text.literal("Automatically remove goals once all materials are gathered."),
                    Text.literal("Goals are permanently deleted — they won't come back."));
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
