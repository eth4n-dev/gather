package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherSettings;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

public class GatherHelpScreen extends Screen {

    private final Screen parent;
    private final boolean showDontShowAgain;
    private int scroll = 0;
    private boolean draggingScrollbar = false;

    private static final int LABEL_W = 86;
    private static final int ROW_PAD = 6;
    private static final int LINE_H = 10;

    private record HelpEntry(String section, String label, String body) {
        static HelpEntry section(String title) {
            return new HelpEntry(title, null, null);
        }

        static HelpEntry tip(String label, String body) {
            return new HelpEntry(null, label, body);
        }

        boolean isSection() {
            return section != null;
        }
    }

    private static final HelpEntry[] ENTRIES = {
            HelpEntry.section("Getting Started"),
            HelpEntry.tip("What it does", "Gather tracks items you want, shows what you still need, and helps you find or craft the materials."),
            HelpEntry.tip("Open menu", "Press G to open the Gather menu. Use it to add goals, change lists, scan chests, edit layout, and open settings."),
            HelpEntry.tip("Add goals", "In Add Items, search for an item, choose the amount, then add it to a list."),
            HelpEntry.tip("Favorites", "Star items you use often. Favorite items sort near the top when you search in Add Items."),
            HelpEntry.tip("Count mode", "+More adds that many more items to gather. Total sets the final target and counts items you already have."),
            HelpEntry.tip("Recent tab", "Recent keeps recently added goals for this world, so you can quickly add the same item again later."),

            HelpEntry.section("Lists And HUD"),
            HelpEntry.tip("Goal lists", "Create separate lists for builds, farms, or trips. Right-click a list header to hide or show it in the HUD."),
            HelpEntry.tip("HUD columns", "Goals show final targets. Base Materials shows raw ingredients. Craft Ready shows items you can craft now."),
            HelpEntry.tip("Hide material", "Material rows can be hidden from the menu so you only see the ingredients you care about."),
            HelpEntry.tip("Layout", "Use Layout from the Gather menu to move or resize HUD modules so they do not cover your normal game UI."),

            HelpEntry.section("Crafting"),
            HelpEntry.tip("Crafting panel", "Open a crafting table while goals are active. Crafting Goals appears beside the table with items you can craft now."),
            HelpEntry.tip("Quick craft", "Click a row to craft needed items. Right-click a row to craft as many as possible."),
            HelpEntry.tip("Shulkers count", "Items inside shulker boxes in your inventory count toward goals and craft-ready checks."),

            HelpEntry.section("Trading"),
            HelpEntry.tip("Trade calculator", "Open a merchant screen to see the trade calculator button. It shows the current offers and lets you add the buy costs as goals."),
            HelpEntry.tip("Pick a trade", "Select an offer, enter how many results you want, then press Add Goals to track the required emeralds and inputs."),
            HelpEntry.tip("Best use", "The trade calculator is useful for villager books, tools, and bulk emerald trades."),

            HelpEntry.section("Chests And Finder"),
            HelpEntry.tip("Scan All", "When Scan All is on, chests you open are remembered and counted even after you walk away."),
            HelpEntry.tip("Manual scan", "When Scan All is off, use the manual scan key or modifier combo to mark nearby chests intentionally."),
            HelpEntry.tip("Item Finder", "Pick a needed item in the menu. The compass arrow near your crosshair points to the nearest scanned chest with that item."),
            HelpEntry.tip("Outlines", "Gather can outline needed blocks and known chests. Use Settings > Outlines to change range, count, color, and xray behavior."),
            HelpEntry.tip("Xray / Glow", "If the server allows it, Settings > Xray / Glow controls xray-style item and block highlights separately."),

            HelpEntry.section("Collector Shulkers"),
            HelpEntry.tip("Collector", "Open a shulker box and enable Collector to move needed items into that shulker as you play."),
            HelpEntry.tip("All / Certain", "All Goals collects everything Gather still needs. Certain collects only the item types you pick."),
            HelpEntry.tip("Keep 1", "Keep 1 leaves one of each stackable item in your inventory instead of moving every copy."),

            HelpEntry.section("Worlds And Settings"),
            HelpEntry.tip("Per world", "Goals, lists, scanned chests, hidden materials, and finder choices are saved per world."),
            HelpEntry.tip("Import / export", "Use Settings > Imports / Exports to copy lists between worlds or exchange JSON files."),
            HelpEntry.tip("Controls", "Use Settings > Controls to change the Gather menu key and manual scan keybind behavior."),
            HelpEntry.tip("Auto remove", "Auto Remove deletes completed goals when all materials are gathered. Leave it off if you want to keep finished goals visible."),
            HelpEntry.tip("Goal sound", "Goal Sound plays a small alert when a goal's materials become fully gathered."),
            HelpEntry.tip("Tutorial", "Settings > Tutorial replays the guided tour at any time."),
            HelpEntry.tip("Master toggle", "The Gather switch disables runtime features like HUD, scans, outlines, finder, and overlays. Settings still stays available."),
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
        int cx = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int px = cx - panelW / 2;
        int py = panelY();

        GatherTheme.fill(ctx, 0, 0, width, height, 0x88000000);
        GatherTheme.fill(ctx, px, py, px + panelW, py + panelH, 0xEE0D1124);
        GatherTheme.fill(ctx, px, py, px + panelW, py + 1, 0xFF2255AA);
        GatherTheme.fill(ctx, px, py + panelH - 1, px + panelW, py + panelH, 0xFF2255AA);
        GatherTheme.fill(ctx, px, py, px + 1, py + panelH, 0xFF2255AA);
        GatherTheme.fill(ctx, px + panelW - 1, py, px + panelW, py + panelH, 0xFF2255AA);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Gather Help"), cx, py + 8, 0xFF88BBFF);
        GatherTheme.fill(ctx, px + 10, py + 19, px + panelW - 10, py + 20, 0x44336699);

        int contentTop = py + 27;
        int contentBot = py + panelH - 34;
        int contentH = contentBot - contentTop;
        int descX = px + 16 + LABEL_W + 8;
        int descW = panelW - LABEL_W - 44;
        int totalH = totalContentHeight(descW);
        int maxScroll = Math.max(0, totalH - contentH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        ctx.enableScissor(px + 4, contentTop, px + panelW - 10, contentBot);
        int y = contentTop - scroll;
        int rowIndex = 0;
        for (HelpEntry entry : ENTRIES) {
            int entryH = entryHeight(entry, descW);
            if (y + entryH >= contentTop && y <= contentBot) {
                if (entry.isSection()) {
                    drawSection(ctx, px, panelW, y, entry.section());
                } else {
                    drawTip(ctx, px, panelW, descX, descW, y, entryH, rowIndex, entry);
                    rowIndex++;
                }
            } else if (!entry.isSection()) {
                rowIndex++;
            }
            y += entryH;
        }
        ctx.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(16, contentH * contentH / totalH);
            int barY = contentTop + (scroll * (contentH - barH) / maxScroll);
            GatherTheme.fill(ctx, px + panelW - 8, contentTop, px + panelW - 5, contentBot, 0x22FFFFFF);
            GatherTheme.fill(ctx, px + panelW - 8, barY, px + panelW - 5, barY + barH, 0x88AACCFF);
        }

        super.render(ctx, mx, my, delta);
    }

    private void drawSection(DrawContext ctx, int px, int panelW, int y, String label) {
        GatherTheme.fill(ctx, px + 8, y + 10, px + panelW - 14, y + 11, 0x22336699);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), px + 14, y + 5, 0xFFFFFF66);
    }

    private void drawTip(DrawContext ctx, int px, int panelW, int descX, int descW, int y, int h, int rowIndex, HelpEntry entry) {
        if (rowIndex % 2 == 0) GatherTheme.fill(ctx, px + 6, y, px + panelW - 10, y + h - 2, 0x11AACCFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal(entry.label()), px + 14, y + ROW_PAD + 1, 0xFF55CCFF);
        GatherTheme.fill(ctx, px + 14 + LABEL_W, y + 4, px + 15 + LABEL_W, y + h - 5, 0x33336699);

        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(entry.body()), descW);
        int ty = y + ROW_PAD + 1;
        for (OrderedText line : lines) {
            ctx.drawTextWithShadow(textRenderer, line, descX, ty, 0xFFAABBCC);
            ty += LINE_H;
        }
    }

    private int totalContentHeight(int descW) {
        int h = 0;
        for (HelpEntry entry : ENTRIES) h += entryHeight(entry, descW);
        return h;
    }

    private int entryHeight(HelpEntry entry, int descW) {
        if (entry.isSection()) return 24;
        int lines = Math.max(1, textRenderer.wrapLines(Text.literal(entry.body()), descW).size());
        return Math.max(24, ROW_PAD * 2 + lines * LINE_H);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int)(v * 14));
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (click.button() == 0 && insideScrollbar(mx, my)) {
            draggingScrollbar = true;
            applyScrollbarDrag(my);
            return true;
        }
        return super.mouseClicked(click, focused);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingScrollbar) {
            applyScrollbarDrag((int) click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingScrollbar = false;
        return super.mouseReleased(click);
    }

    private boolean insideScrollbar(int mx, int my) {
        ScrollMetrics metrics = scrollMetrics();
        return metrics.maxScroll() > 0
                && mx >= metrics.x() && mx <= metrics.x() + 6
                && my >= metrics.contentTop() && my <= metrics.contentBot();
    }

    private void applyScrollbarDrag(int my) {
        ScrollMetrics metrics = scrollMetrics();
        if (metrics.maxScroll() <= 0) {
            scroll = 0;
            return;
        }
        int maxThumbY = metrics.contentH() - metrics.thumbH();
        if (maxThumbY <= 0) {
            scroll = 0;
            return;
        }
        int relY = Math.max(0, Math.min(my - metrics.contentTop() - metrics.thumbH() / 2, maxThumbY));
        scroll = (int) Math.round((double) relY / maxThumbY * metrics.maxScroll());
        scroll = Math.max(0, Math.min(scroll, metrics.maxScroll()));
    }

    private ScrollMetrics scrollMetrics() {
        int panelW = panelW();
        int panelH = panelH();
        int px = width / 2 - panelW / 2;
        int py = panelY();
        int contentTop = py + 27;
        int contentBot = py + panelH - 34;
        int contentH = contentBot - contentTop;
        int descW = panelW - LABEL_W - 44;
        int totalH = totalContentHeight(descW);
        int maxScroll = Math.max(0, totalH - contentH);
        int thumbH = maxScroll > 0 ? Math.max(16, contentH * contentH / totalH) : contentH;
        return new ScrollMetrics(px + panelW - 10, contentTop, contentBot, contentH, thumbH, maxScroll);
    }

    private record ScrollMetrics(int x, int contentTop, int contentBot, int contentH, int thumbH, int maxScroll) {}

    private int panelW() { return Math.min(560, Math.max(340, width - 32)); }
    private int panelH() { return Math.min(340, Math.max(260, height - 40)); }
    private int panelY() { return height / 2 - panelH() / 2; }

    @Override
    public void close() { client.setScreen(parent); }
}
