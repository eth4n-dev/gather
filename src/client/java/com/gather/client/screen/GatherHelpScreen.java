package com.gather.client.screen;

import com.gather.client.GatherSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class GatherHelpScreen extends Screen {

    private final Screen parent;
    private final boolean showDontShowAgain;

    private static final String[][] TIPS = {
        { "G",              "Open the Gather menu." },
        { "Add Items",      "Search items, set an amount, and choose a list." },
        { "List Rows",      "Click material chips to hide HUD rows and outlines." },
        { "Crafting",       "Crafting tables show ready Gather goals beside recipes." },
        { "Shift+G",        "Manual chest tagging when Scan All is off." },
        { "Scan All",       "Counts opened tracked chests; far saved chests still count." },
        { "Chest Finder",   "Pick a scanned item and follow the HUD direction marker." },
        { "Collector",      "Shulker collector pulls needed materials into that shulker." },
        { "Keep 1 Item",    "For material stacks, leave one item in your inventory." },
        { "Layout",         "Move and resize HUD modules from the Gather menu." },
        { "Outlines",       "Needed blocks and scanned chests can be outlined or xray." },
        { "Per-World",      "Goals, chests, hidden materials, and lists save per world." },
        { "Transfer",       "Copy lists between worlds from Settings." },
        { "Master Toggle",  "Settings can disable all Gather runtime features." },
    };

    public GatherHelpScreen(Screen parent, boolean showDontShowAgain) {
        super(Text.literal("Gather Help"));
        this.parent = parent;
        this.showDontShowAgain = showDontShowAgain;
    }

    @Override
    protected void init() {
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
        int cx = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int px = cx - panelW / 2;
        int py = panelY();

        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px, py, px + panelW, py + panelH, 0xEE0D1124);
        ctx.fill(px, py,     px + panelW, py + 1,     0xFF2255AA);
        ctx.fill(px, py + panelH - 1, px + panelW, py + panelH, 0xFF2255AA);
        ctx.fill(px, py,     px + 1, py + panelH,     0xFF2255AA);
        ctx.fill(px + panelW - 1, py, px + panelW, py + panelH, 0xFF2255AA);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Gather Help"), cx, py + 8, 0xFF88BBFF);
        ctx.fill(px + 10, py + 19, px + panelW - 10, py + 20, 0x44336699);

        int contentTop = py + 28;
        int colGap = 12;
        int colW = (panelW - 28 - colGap) / 2;
        int rowsPerCol = (int) Math.ceil(TIPS.length / 2.0);
        for (int i = 0; i < TIPS.length; i++) {
            int col = i / rowsPerCol;
            int row = i % rowsPerCol;
            int x = px + 14 + col * (colW + colGap);
            int y = contentTop + row * 24;
            ctx.drawTextWithShadow(textRenderer, Text.literal(TIPS[i][0]), x, y, 0xFF55CCFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal(fit(TIPS[i][1], colW)), x, y + 10, 0xFF778899);
        }

        super.render(ctx, mx, my, delta);
    }

    private int panelW() {
        return Math.min(520, Math.max(320, width - 32));
    }

    private int panelH() {
        return Math.min(260, Math.max(238, height - 24));
    }

    private int panelY() {
        return height / 2 - panelH() / 2;
    }

    private String fit(String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) return text;
        String clipped = text;
        while (clipped.length() > 3 && textRenderer.getWidth(clipped + "..") > maxWidth) {
            clipped = clipped.substring(0, clipped.length() - 1);
        }
        return clipped + "..";
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
