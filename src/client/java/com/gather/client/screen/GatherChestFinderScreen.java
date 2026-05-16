package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherState;
import com.gather.client.GatherUi;
import com.gather.client.ListNode;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

public class GatherChestFinderScreen extends Screen {

    private static final int ROW_H    = 22;
    private static final int LIST_W   = 260;
    private static final int SCROLL_W = 8;
    private static final int HEADER_H = 64; // space above list
    private static final int FOOTER_H = 34; // space below list

    private final Screen parent;

    private record ItemEntry(String itemId, String displayName, int totalCount) {}

    private final List<ItemEntry> allEntries = new ArrayList<>();
    private final List<ItemEntry> shown      = new ArrayList<>();
    private int     scrollOffset   = 0;
    private boolean onlyNeeded     = false;
    private String  searchQuery    = "";

    private TextFieldWidget searchField;
    private ButtonWidget    onlyNeededBtn;

    // Scrollbar drag state
    private boolean draggingScrollbar = false;

    public GatherChestFinderScreen(Screen parent) {
        super(Text.literal("Find Item in Chest"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx    = width / 2;
        int listX = cx - LIST_W / 2;

        // Search field (left part of header row)
        int searchW = LIST_W - 86;
        searchField = new TextFieldWidget(textRenderer, listX, HEADER_H - 22, searchW, 16,
                Text.literal("Search..."));
        searchField.setPlaceholder(Text.literal("Search..."));
        searchField.setMaxLength(64);
        searchField.setChangedListener(q -> { searchQuery = q; rebuildShown(); });
        addSelectableChild(searchField);
        setFocused(searchField);

        // Only Needed toggle (right part of header row)
        onlyNeededBtn = ButtonWidget.builder(onlyNeededLabel(), btn -> {
            onlyNeeded = !onlyNeeded;
            btn.setMessage(onlyNeededLabel());
            rebuildShown();
        }).dimensions(listX + searchW + 4, HEADER_H - 22, 80, 16).build();
        addDrawableChild(onlyNeededBtn);

        // Bottom buttons
        int bottomY = height - FOOTER_H + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear Finder"), btn -> {
            GatherState.get().setChestFinderItemId(null);
            close();
        }).dimensions(cx - 102, bottomY, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
                .dimensions(cx + 4, bottomY, 98, 20).build());

        buildAllEntries();
        rebuildShown();
    }

    private Text onlyNeededLabel() {
        return Text.literal(onlyNeeded ? "Needed only" : "Any item");
    }

    private void buildAllEntries() {
        allEntries.clear();
        Map<String, Integer> allItems = GatherState.get().getAllChestItems();
        for (Map.Entry<String, Integer> e : allItems.entrySet()) {
            Item item = Registries.ITEM.get(Identifier.of(e.getKey()));
            String name = item != null ? item.getName().getString() : e.getKey();
            allEntries.add(new ItemEntry(e.getKey(), name, e.getValue()));
        }
        allEntries.sort(Comparator.comparingInt(ItemEntry::totalCount).reversed());
    }

    private void rebuildShown() {
        shown.clear();
        Set<String> neededIds = null;
        if (onlyNeeded) {
            neededIds = new HashSet<>();
            for (ListNode node : GatherState.get().getNodes())
                neededIds.add(node.itemId);
        }
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        for (ItemEntry e : allEntries) {
            if (neededIds != null && !neededIds.contains(e.itemId())) continue;
            if (!q.isEmpty() && !fuzzyMatch(e.displayName().toLowerCase(Locale.ROOT), q)) continue;
            shown.add(e);
        }
        int max = Math.max(0, shown.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    private static boolean fuzzyMatch(String target, String query) {
        int ti = 0;
        for (int qi = 0; qi < query.length(); qi++) {
            char c = query.charAt(qi);
            while (ti < target.length() && target.charAt(ti) != c) ti++;
            if (ti >= target.length()) return false;
            ti++;
        }
        return true;
    }

    private int listTop()     { return HEADER_H; }
    private int listBottom()  { return height - FOOTER_H; }
    private int listH()       { return listBottom() - listTop(); }
    private int visibleRows() { return Math.max(1, listH() / ROW_H); }
    private int listX()       { return width / 2 - LIST_W / 2; }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double hDelta, double vDelta) {
        int max = Math.max(0, shown.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) vDelta, max));
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x(), my = (int) click.y();
        int lx = listX();
        int scrollX = lx + LIST_W + 3;

        // Scrollbar track
        if (shown.size() > visibleRows()
                && mx >= scrollX && mx <= scrollX + SCROLL_W
                && my >= listTop() && my <= listBottom()) {
            GatherUi.playClickSound();
            draggingScrollbar = true;
            applyScrollbarDrag(my);
            return true;
        }

        // List row
        if (mx >= lx && mx < lx + LIST_W && my >= listTop() && my < listBottom()) {
            int row = (my - listTop()) / ROW_H + scrollOffset;
            if (row >= 0 && row < shown.size()) {
                GatherUi.playClickSound();
                GatherState.get().setChestFinderItemId(shown.get(row).itemId());
                close();
                return true;
            }
        }

        return super.mouseClicked(click, focused);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingScrollbar) { applyScrollbarDrag((int) click.y()); return true; }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingScrollbar = false;
        return super.mouseReleased(click);
    }

    private void applyScrollbarDrag(int my) {
        int trackH  = listH();
        int total   = shown.size();
        int visible = visibleRows();
        if (total <= visible) { scrollOffset = 0; return; }
        int thumbH   = Math.max(20, trackH * visible / total);
        int maxThumb = trackH - thumbH;
        int relY     = Math.max(0, Math.min(my - listTop(), maxThumb));
        int maxScroll = total - visible;
        scrollOffset = (int) Math.round((double) relY / maxThumb * maxScroll);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int cx = width / 2;
        int lx = listX();

        GatherTheme.fill(ctx, 0, 0, width, height, 0xCC111122);

        // Title + active label
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFCCDDFF);
        String current = GatherState.get().getChestFinderItemId();
        if (current != null) {
            Item cur = Registries.ITEM.get(Identifier.of(current));
            String curName = cur != null ? cur.getName().getString() : current;
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Active: " + curName), cx, 22, 0xFFFF8888);
        }

        // Search field background
        GatherTheme.fill(ctx, lx - 1, listTop() - 23, lx + LIST_W - 83, listTop() - 3, 0xFF1A2A3A);
        searchField.render(ctx, mx, my, delta);

        // List border + background
        GatherTheme.fill(ctx, lx - 1, listTop() - 1, lx + LIST_W + 1, listBottom() + 1, 0xFF223344);
        GatherTheme.fill(ctx, lx, listTop(), lx + LIST_W, listBottom(), 0xFF0A1522);

        // Rows
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int idx = i + scrollOffset;
            if (idx >= shown.size()) break;
            ItemEntry entry = shown.get(idx);
            int rowY = listTop() + i * ROW_H;
            boolean hovered  = mx >= lx && mx < lx + LIST_W && my >= rowY && my < rowY + ROW_H;
            boolean selected = entry.itemId().equals(current);

            GatherTheme.fill(ctx, lx, rowY, lx + LIST_W, rowY + ROW_H - 1,
                    selected ? 0xFF3A0A0A : (hovered ? 0xFF1A2A3A : 0xFF0A1522));
            if (selected)
                GatherTheme.fill(ctx, lx, rowY, lx + 2, rowY + ROW_H - 1, 0xFFFF4444);

            Item item = Registries.ITEM.get(Identifier.of(entry.itemId()));
            if (item != null) ctx.drawItem(item.getDefaultStack(), lx + 3, rowY + 3);

            int nameMaxW = LIST_W - 54;
            String name = entry.displayName();
            while (textRenderer.getWidth(name) > nameMaxW && name.length() > 1)
                name = name.substring(0, name.length() - 1);
            if (!name.equals(entry.displayName())) name += "..";

            ctx.drawTextWithShadow(textRenderer, Text.literal(name),
                    lx + 24, rowY + 7,
                    selected ? 0xFFFF9999 : (hovered ? 0xFFEEEEEE : 0xFFCCCCCC));

            String countStr = "x" + entry.totalCount();
            int cw = textRenderer.getWidth(countStr);
            ctx.drawTextWithShadow(textRenderer, Text.literal(countStr),
                    lx + LIST_W - cw - 5, rowY + 7, 0xFF7799BB);

            // Row separator
            GatherTheme.fill(ctx, lx, rowY + ROW_H - 1, lx + LIST_W, rowY + ROW_H, 0xFF0E1A28);
        }

        // Empty state
        if (shown.isEmpty()) {
            int emptyMidY = (listTop() + listBottom()) / 2;
            if (allEntries.isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("No items in scanned chests."),
                        cx, emptyMidY - 9, 0xFF556677);
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("Open chests or press Shift+G to tag them."),
                        cx, emptyMidY + 3, 0xFF3A4A58);
            } else {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("No matches."),
                        cx, emptyMidY - 4, 0xFF556677);
            }
        }

        // Scrollbar
        if (shown.size() > visible) {
            int scrollX = lx + LIST_W + 3;
            int trackH  = listH();
            int thumbH  = Math.max(20, trackH * visible / shown.size());
            int maxScroll = Math.max(1, shown.size() - visible);
            int thumbY  = listTop() + (trackH - thumbH) * scrollOffset / maxScroll;
            GatherTheme.fill(ctx, scrollX, listTop(), scrollX + SCROLL_W, listBottom(), 0xFF0E1A28);
            GatherTheme.fill(ctx, scrollX + 1, thumbY + 1, scrollX + SCROLL_W - 1, thumbY + thumbH - 1,
                    draggingScrollbar ? 0xFF99BBDD : 0xFF446688);
        }

        super.render(ctx, mx, my, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
