package com.gather.client.screen;

import com.gather.client.GatherSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class GatherHelpScreen extends Screen {

    private final Screen parent;
    private final boolean showDontShowAgain;
    private int scroll = 0;

    private static final int ROW_H   = 22;
    private static final int LABEL_W = 88;

    private static final String[][] TIPS = {
        { "G",              "Open the Gather menu." },
        { "Add Items",      "Search items, set an amount, and choose a list." },
        { "Recent",         "Re-add the last 10 goals from the Recent tab in the Gather menu." },
        { "Lists",          "Create multiple goal lists and toggle them on/off with right-click." },
        { "Crafting",       "Crafting tables show ready Gather goals beside recipes. Items inside shulkers in your inventory count." },
        { "Shift+G",        "Manual chest tagging when Scan All is off." },
        { "Scan All",       "Counts opened tracked chests; far saved chests still count." },
        { "Chest Finder",   "Pick a scanned item and follow the orbiting arrow near your crosshair. Shows distance and chest count." },
        { "Collector",      "Shulker collector pulls needed materials into that shulker." },
        { "Keep 1 Item",    "Shulker collector leaves one of each stackable material in your inventory." },
        { "Layout",         "Move and resize HUD modules from the Gather menu." },
        { "Outlines",       "Needed blocks and scanned chests can be outlined or xray." },
        { "Per-World",      "Goals, chests, hidden materials, and lists save per world." },
        { "Import",         "Copy lists between worlds from Settings." },
        { "Master Toggle",  "Settings can disable all Gather runtime features." },
    };

    public GatherHelpScreen(Screen parent, boolean showDontShowAgain) {
        super(Text.literal("Gather Help"));
        this.parent = parent;
        this.showDontShowAgain = showDontShowAgain;
    }

    @Override
    protected void init() {
        scroll = 0;
        int cx = width / 2;
        int by = panelY() + panelH() - 28;

        if (showDontShowAgain) {
            addDrawableChild(ButtonWidget
                    .builder(Text.literal("Don't show again"), btn -> {
                        GatherSettings.get().hasShownWelcome = true;
                        GatherSettings.get().save();
                        close();
                    })
                    .dimensions(cx - 106, by, 100, 20)
                    .build());
            addDrawableChild(ButtonWidget
                    .builder(Text.literal("Close"), btn -> close())
                    .dimensions(cx + 6, by, 100, 20)
                    .build());
        } else {
            addDrawableChild(ButtonWidget
                    .builder(Text.literal("Close"), btn -> close())
                    .dimensions(cx - 50, by, 100, 20)
                    .build());
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int cx     = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int px     = cx - panelW / 2;
        int py     = panelY();

        // Background + border
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px, py, px + panelW, py + panelH, 0xEE0D1124);
        ctx.fill(px,            py,            px + panelW, py + 1,        0xFF2255AA);
        ctx.fill(px,            py + panelH-1, px + panelW, py + panelH,   0xFF2255AA);
        ctx.fill(px,            py,            px + 1,      py + panelH,   0xFF2255AA);
        ctx.fill(px + panelW-1, py,            px + panelW, py + panelH,   0xFF2255AA);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Gather Help"), cx, py + 8, 0xFF88BBFF);
        ctx.fill(px + 10, py + 19, px + panelW - 10, py + 20, 0x44336699);

        // Content area bounds
        int contentTop = py + 26;
        int contentBot = py + panelH - 34;
        int contentH   = contentBot - contentTop;
        int totalH     = TIPS.length * ROW_H;
        int maxScroll  = Math.max(0, totalH - contentH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int descX  = px + 14 + LABEL_W + 6;
        int descW  = panelW - LABEL_W - 34;

        ctx.enableScissor(px + 4, contentTop, px + panelW - 10, contentBot);
        for (int i = 0; i < TIPS.length; i++) {
            int y = contentTop + i * ROW_H - scroll;
            if (y + ROW_H < contentTop || y > contentBot) continue;
            // Alternating row tint
            if (i % 2 == 0)
                ctx.fill(px + 6, y, px + panelW - 10, y + ROW_H - 2, 0x11AACCFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal(TIPS[i][0]),
                    px + 14, y + 7, 0xFF55CCFF);
            ctx.fill(px + 14 + LABEL_W, y + 3, px + 15 + LABEL_W, y + ROW_H - 3, 0x33336699);
            ctx.drawTextWithShadow(textRenderer, Text.literal(TIPS[i][1]),
                    descX, y + 7, 0xFFAABBCC);
        }
        ctx.disableScissor();

        // Scroll bar
        if (maxScroll > 0) {
            int barH = Math.max(16, contentH * contentH / totalH);
            int barY = contentTop + (scroll * (contentH - barH) / maxScroll);
            ctx.fill(px + panelW - 8, contentTop, px + panelW - 5, contentBot, 0x22FFFFFF);
            ctx.fill(px + panelW - 8, barY,       px + panelW - 5, barY + barH, 0x88AACCFF);
        }

        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int)(v * 10));
        return true;
    }

    private int panelW() { return Math.min(500, Math.max(340, width - 32)); }
    private int panelH() { return Math.min(320, Math.max(260, height - 40)); }
    private int panelY() { return height / 2 - panelH() / 2; }

    @Override
    public void close() { client.setScreen(parent); }
}
