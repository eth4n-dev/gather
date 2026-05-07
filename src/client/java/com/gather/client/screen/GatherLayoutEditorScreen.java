package com.gather.client.screen;

import com.gather.client.GatherSettings;
import com.gather.client.GatherUi;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class GatherLayoutEditorScreen extends Screen {
    private static final int HANDLE = 8;
    private static final int SNAP   = 8;

    private final Screen parent;
    private final List<Box> boxes = new ArrayList<>();
    private Box active;
    private boolean resizing;
    private int grabX;
    private int grabY;
    private boolean showExtendedBorders = false;
    private final List<Integer> snapXLines = new ArrayList<>();
    private final List<Integer> snapYLines = new ArrayList<>();

    public GatherLayoutEditorScreen(Screen parent) {
        super(Text.literal("Gather Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reloadBoxes();
    }

    private void reloadBoxes() {
        boxes.clear();
        GatherSettings s = GatherSettings.get();
        boxes.add(new Box("Goals / Lists",      0xFF66CCFF, s.layoutGoalsX,      s.layoutGoalsY,      s.layoutGoalsW,      120, 84,  false));
        boxes.add(new Box("Base Materials",     0xFFFFCC66, s.layoutMaterialsX,  s.layoutMaterialsY,  s.layoutMaterialsW,  100, 84,  true));
        boxes.add(new Box("Craft Hints",        0xFF88FF88, s.layoutCraftHintsX, s.layoutCraftHintsY, s.layoutCraftHintsW,  80, 84,  true));
        int manualX = s.layoutManualScanX < 0 ? width / 2 - s.layoutManualScanW / 2 : s.layoutManualScanX;
        boxes.add(new Box("Manual Scan Banner", 0xFFFFAA44, manualX,             s.layoutManualScanY, s.layoutManualScanW,  38, 180, true));
        int badgeX  = s.layoutScanBadgesX < 0 ? width - 4 - s.layoutScanBadgesW : s.layoutScanBadgesX;
        boxes.add(new Box("Scan Badges",        0xFF55DDBB, badgeX,              s.layoutScanBadgesY, s.layoutScanBadgesW,  26, 86,  true));
        int finderX = s.layoutFinderX < 0 ? width - 4 - s.layoutFinderW : s.layoutFinderX;
        boxes.add(new Box("Find Item Panel",    0xFFFF6666, finderX,             s.layoutFinderY,     s.layoutFinderW,      38, 104, true));
        int toastX  = s.layoutToastX < 0 ? width / 2 - 100 : s.layoutToastX;
        boxes.add(new Box("Goal Toast",         0xFF44FFAA, toastX,              s.layoutToastY,      200,                  14, 120, false));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xCC050812);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFCCDDFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag modules. Resize with the lower-right handle."), width / 2, 20, 0xFF778899);

        // center guide lines — brighten while dragging
        int lineAlpha = (active != null && !resizing) ? 0x55 : 0x22;
        int lineColor = (lineAlpha << 24) | 0x44AAFF;
        ctx.fill(width / 2, 32, width / 2 + 1, height - 28, lineColor);
        ctx.fill(4, height / 2, width - 4, height / 2 + 1, lineColor);

        // per-box extended border guide lines
        if (showExtendedBorders) {
            for (Box box : boxes) {
                int c = (box.color & 0x00FFFFFF) | 0x22000000;
                ctx.fill(box.x,           32,        box.x + 1,           height - 28, c);
                ctx.fill(box.x + box.w,   32,        box.x + box.w + 1,   height - 28, c);
                ctx.fill(4, box.y,         width - 4, box.y + 1,                        c);
                ctx.fill(4, box.y + box.h, width - 4, box.y + box.h + 1,               c);
            }
        }

        // active snap highlight lines
        for (int lx : snapXLines) ctx.fill(lx, 32, lx + 1, height - 28, 0xAAFFDD33);
        for (int ly : snapYLines) ctx.fill(4, ly, width - 4, ly + 1, 0xAAFFDD33);

        for (int i = 0; i < boxes.size(); i++) drawBox(ctx, boxes.get(i), mouseX, mouseY, i);

        // buttons: Show borders(84) | Reset(54) | Done(54) | Cancel(54) = 258px total
        int by = height - 24;
        int bs = width / 2 - 129;
        drawButton(ctx, bs,       by, 84, 18, "Show borders", mouseX, mouseY, showExtendedBorders);
        drawButton(ctx, bs + 88,  by, 54, 18, "Reset",        mouseX, mouseY, false);
        drawButton(ctx, bs + 146, by, 54, 18, "Done",         mouseX, mouseY, false);
        drawButton(ctx, bs + 204, by, 54, 18, "Cancel",       mouseX, mouseY, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBox(DrawContext ctx, Box box, int mx, int my, int idx) {
        boolean hover = contains(box, mx, my);
        int fill = hover || box == active ? 0x66335577 : 0x44223344;
        ctx.fill(box.x, box.y, box.x + box.w, box.y + box.h, fill);
        ctx.fill(box.x, box.y,             box.x + box.w, box.y + 1,        box.color);
        ctx.fill(box.x, box.y + box.h - 1, box.x + box.w, box.y + box.h,   box.color);
        ctx.fill(box.x, box.y,             box.x + 1,     box.y + box.h,   box.color);
        ctx.fill(box.x + box.w - 1, box.y, box.x + box.w, box.y + box.h,   box.color);
        if (box.resizable)
            ctx.fill(box.x + box.w - HANDLE, box.y + box.h - HANDLE,
                     box.x + box.w - 2,      box.y + box.h - 2, box.color);

        ctx.enableScissor(box.x + 1, box.y + 1, box.x + box.w - 1, box.y + box.h - 1);
        drawBoxPreview(ctx, box, idx);
        ctx.disableScissor();
    }

    private void drawBoxPreview(DrawContext ctx, Box box, int idx) {
        int bx = box.x; int by = box.y; int bw = box.w; int bh = box.h;
        var tr = textRenderer;
        switch (idx) {
            case 0 -> { // Goals / Lists
                ctx.drawTextWithShadow(tr, Text.literal("§7My Goals"), bx + 2, by + 2, 0xFF778899);
                int ry = by + 13;
                // row: Diamond Sword, 1/2, 50% progress
                ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA00001A);
                ctx.drawItem(Items.DIAMOND_SWORD.getDefaultStack(), bx + 1, ry + 2);
                ctx.drawTextWithShadow(tr, Text.literal("Sword"), bx + 19, ry + 2, 0xFFCCCCCC);
                ctx.drawTextWithShadow(tr, Text.literal("1/2"), bx + Math.min(57, bw - 20), ry + 2, 0xFFFFFF55);
                ctx.fill(bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                ctx.fill(bx, ry + 18, bx + Math.min(40, bw / 2), ry + 19, 0xFFFFDD33);
                ry += 20;
                if (ry + 20 <= by + bh - 2) {
                    // row: Arrow, 16/16, full
                    ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA002200);
                    ctx.drawItem(Items.ARROW.getDefaultStack(), bx + 1, ry + 2);
                    ctx.drawTextWithShadow(tr, Text.literal("Arrow"), bx + 19, ry + 2, 0xFFEEFFEE);
                    ctx.drawTextWithShadow(tr, Text.literal("16/16"), bx + Math.min(53, bw - 24), ry + 2, 0xFF88FF88);
                    ctx.fill(bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                    ctx.fill(bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0xFF44DD66);
                    ry += 20;
                }
                if (ry + 20 <= by + bh - 2) {
                    // row: Iron Ingot, 3/8
                    ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA00001A);
                    ctx.drawItem(Items.IRON_INGOT.getDefaultStack(), bx + 1, ry + 2);
                    ctx.drawTextWithShadow(tr, Text.literal("Iron"), bx + 19, ry + 2, 0xFFCCCCCC);
                    ctx.drawTextWithShadow(tr, Text.literal("3/8"), bx + Math.min(57, bw - 20), ry + 2, 0xFFFF6666);
                    ctx.fill(bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                    ctx.fill(bx, ry + 18, bx + Math.min(30, bw * 3 / 8), ry + 19, 0xFFFF8833);
                }
            }
            case 1 -> { // Base Materials
                ctx.drawTextWithShadow(tr, Text.literal("§7base materials"), bx + 2, by + 2, 0xFF778899);
                int ry = by + 13;
                int[][]     matData  = {{4, 16, 0xFFFF6666}, {10, 16, 0xFFFFFF55}, {16, 16, 0xFF88FF88}};
                net.minecraft.item.Item[] matItems = {Items.OAK_LOG, Items.STICK, Items.IRON_INGOT};
                for (int i = 0; i < 3 && ry + 16 <= by + bh - 2; i++) {
                    int have = matData[i][0], need = matData[i][1], col = matData[i][2];
                    int bgBase = have >= need ? 0x00AA44 : 0x220033;
                    ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 16, (0x88 << 24) | bgBase);
                    ctx.drawItem(matItems[i].getDefaultStack(), bx + 1, ry);
                    ctx.drawTextWithShadow(tr, Text.literal(have + "/" + need), bx + 19, ry + 4, col);
                    int lw = (int)(Math.min(80, bw) * (float) have / need);
                    ctx.fill(bx, ry + 15, bx + Math.min(80, bw), ry + 16, 0x33000000);
                    if (lw > 0) ctx.fill(bx, ry + 15, bx + lw, ry + 16, have >= need ? 0xFF44DD66 : 0xFFFFDD33);
                    ry += 16;
                }
            }
            case 2 -> { // Craft Hints
                ctx.drawTextWithShadow(tr, Text.literal("§acraft ready"), bx + 2, by + 2, 0xFF66CC66);
                int ry = by + 13;
                if (ry + 18 <= by + bh - 2) {
                    ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 18, (0xAA << 24) | (0x22 << 8));
                    ctx.drawItem(Items.OAK_PLANKS.getDefaultStack(), bx + 1,  ry + 1);
                    ctx.drawItem(Items.OAK_PLANKS.getDefaultStack(), bx + 16, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("->"), bx + 33, ry + 5, 0xFF88CC88);
                    ctx.drawItem(Items.CRAFTING_TABLE.getDefaultStack(), bx + 44, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("x4"), bx + 62, ry + 5, 0xFF88FF88);
                    ry += 18;
                }
                if (ry + 18 <= by + bh - 2) {
                    ctx.fill(bx, ry, bx + Math.min(80, bw), ry + 18, (0xAA << 24) | (0x22 << 8));
                    ctx.drawItem(Items.IRON_INGOT.getDefaultStack(), bx + 1, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("->"), bx + 33, ry + 5, 0xFF88CC88);
                    ctx.drawItem(Items.IRON_SWORD.getDefaultStack(), bx + 44, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("x1"), bx + 62, ry + 5, 0xFF88FF88);
                }
            }
            case 3 -> { // Manual Scan Banner
                ctx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xCC001A1A);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("◎ MANUAL SCAN ACTIVE"), bx + bw / 2, by + 4,  0xFFFFCC44);
                if (bh > 22) ctx.drawCenteredTextWithShadow(tr, Text.literal("Right-click chests to tag"), bx + bw / 2, by + 15, 0xFFCCBB88);
                if (bh > 32) ctx.drawCenteredTextWithShadow(tr, Text.literal("2 chests tagged"), bx + bw / 2, by + 26, 0xFFFFDD99);
            }
            case 4 -> { // Scan Badges
                String badge = "• SCAN ALL (8)";
                int badgeW = tr.getWidth(badge) + 8;
                int badgeX = bx + bw - 4 - badgeW;
                ctx.fill(badgeX, by + 2, badgeX + badgeW, by + 13, 0xAA00332B);
                ctx.drawTextWithShadow(tr, Text.literal(badge), badgeX + 4, by + 4, 0xFF33D6AA);
            }
            case 5 -> { // Find Item Panel
                ctx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xCC1A0000);
                ctx.drawItem(Items.DIAMOND.getDefaultStack(), bx + 2, by + 3);
                ctx.drawTextWithShadow(tr, Text.literal("Diamond"),         bx + 22, by + 4,  0xFFFF9999);
                ctx.drawTextWithShadow(tr, Text.literal("x8  in 2 chests"), bx + 22, by + 15, 0xFFAA7744);
                ctx.drawTextWithShadow(tr, Text.literal("24m"),             bx + 22, by + 26, 0xFFFF5533);
            }
            case 6 -> { // Goal Toast
                String msg  = "✔  Diamond Sword";
                int    tw   = tr.getWidth(msg);
                int    pw   = tw + 18;
                int    px   = bx + (bw - pw) / 2;
                ctx.fill(px, by, px + pw, by + 14, 0xFF001A00);
                ctx.fill(px, by, px + pw, by + 1,  0xFF33CC66);
                ctx.drawTextWithShadow(tr, Text.literal(msg), px + 9, by + 3, 0xFFAAFFCC);
            }
        }
    }

    private void drawButton(DrawContext ctx, int x, int y, int w, int h,
                            String label, int mx, int my, boolean lit) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg     = lit ? 0xFF1A3320 : (hov ? 0xFF445577 : 0xFF24344F);
        int border = lit ? 0xFF44AA55 : 0xFF667799;
        ctx.fill(x, y, x + w, y + h, bg);
        ctx.fill(x, y, x + w, y + 1, border);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
                x + (w - textRenderer.getWidth(label)) / 2, y + 5, 0xFFCCDDFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (my >= height - 24 && my <= height - 6) {
            int bs = width / 2 - 129;
            if (mx >= bs && mx <= bs + 84) {
                GatherUi.playClickSound();
                showExtendedBorders = !showExtendedBorders;
                return true;
            }
            if (mx >= bs + 88 && mx <= bs + 142) {
                GatherUi.playClickSound();
                GatherSettings.get().resetHudLayout();
                reloadBoxes();
                return true;
            }
            if (mx >= bs + 146 && mx <= bs + 200) {
                GatherUi.playClickSound();
                saveBoxes();
                close();
                return true;
            }
            if (mx >= bs + 204 && mx <= bs + 258) {
                GatherUi.playClickSound();
                close();
                return true;
            }
        }

        for (int i = boxes.size() - 1; i >= 0; i--) {
            Box box = boxes.get(i);
            if (!contains(box, mx, my)) continue;
            active = box;
            resizing = box.resizable && mx >= box.x + box.w - HANDLE && my >= box.y + box.h - HANDLE;
            grabX = mx - box.x;
            grabY = my - box.y;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (active == null) return false;
        int mx = (int) click.x();
        int my = (int) click.y();
        if (resizing) {
            active.w = Math.max(active.minW, mx - active.x);
        } else {
            active.x = clamp(mx - grabX, 0, Math.max(0, width - active.w));
            active.y = clamp(my - grabY, 0, Math.max(0, height - active.h - 28));
            applySnap();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (active != null) saveBoxes();
        active = null;
        resizing = false;
        snapXLines.clear();
        snapYLines.clear();
        return true;
    }

    private void applySnap() {
        snapXLines.clear();
        snapYLines.clear();
        int scx = width  / 2;
        int scy = height / 2;

        List<int[]> xCands = new ArrayList<>();
        xCands.add(new int[]{scx - active.w / 2, scx});
        xCands.add(new int[]{scx,                 scx});
        xCands.add(new int[]{scx - active.w,      scx});
        for (Box other : boxes) {
            if (other == active) continue;
            xCands.add(new int[]{other.x - active.w,           other.x});
            xCands.add(new int[]{other.x + other.w,            other.x + other.w});
            xCands.add(new int[]{other.x,                      other.x});
            xCands.add(new int[]{other.x + other.w - active.w, other.x + other.w});
        }

        List<int[]> yCands = new ArrayList<>();
        yCands.add(new int[]{scy - active.h / 2, scy});
        yCands.add(new int[]{scy,                 scy});
        yCands.add(new int[]{scy - active.h,      scy});
        for (Box other : boxes) {
            if (other == active) continue;
            yCands.add(new int[]{other.y - active.h,           other.y});
            yCands.add(new int[]{other.y + other.h,            other.y + other.h});
            yCands.add(new int[]{other.y,                      other.y});
            yCands.add(new int[]{other.y + other.h - active.h, other.y + other.h});
        }

        int bestXDist = SNAP; int snapX = Integer.MIN_VALUE; int lineX = 0;
        for (int[] c : xCands) {
            int dist = Math.abs(active.x - c[0]);
            if (dist < bestXDist) { bestXDist = dist; snapX = c[0]; lineX = c[1]; }
        }
        if (snapX != Integer.MIN_VALUE) { active.x = snapX; snapXLines.add(lineX); }

        int bestYDist = SNAP; int snapY = Integer.MIN_VALUE; int lineY = 0;
        for (int[] c : yCands) {
            int dist = Math.abs(active.y - c[0]);
            if (dist < bestYDist) { bestYDist = dist; snapY = c[0]; lineY = c[1]; }
        }
        if (snapY != Integer.MIN_VALUE) { active.y = snapY; snapYLines.add(lineY); }
    }

    private void saveBoxes() {
        GatherSettings s = GatherSettings.get();
        Box goals  = boxes.get(0); Box mats   = boxes.get(1); Box hints  = boxes.get(2);
        Box manual = boxes.get(3); Box badges = boxes.get(4); Box finder = boxes.get(5);
        Box toast  = boxes.get(6);
        s.layoutGoalsX      = goals.x;  s.layoutGoalsY      = goals.y;  s.layoutGoalsW      = Math.max(goals.minW,  goals.w);
        s.layoutMaterialsX  = mats.x;   s.layoutMaterialsY  = mats.y;   s.layoutMaterialsW  = Math.max(mats.minW,   mats.w);
        s.layoutCraftHintsX = hints.x;  s.layoutCraftHintsY = hints.y;  s.layoutCraftHintsW = Math.max(hints.minW,  hints.w);
        s.layoutManualScanX = manual.x; s.layoutManualScanY = manual.y; s.layoutManualScanW = Math.max(manual.minW, manual.w);
        s.layoutScanBadgesX = badges.x; s.layoutScanBadgesY = badges.y; s.layoutScanBadgesW = Math.max(badges.minW, badges.w);
        s.layoutFinderX     = finder.x; s.layoutFinderY     = finder.y; s.layoutFinderW     = Math.max(finder.minW, finder.w);
        s.layoutToastX      = toast.x;  s.layoutToastY      = toast.y;
        s.save();
    }

    private static boolean contains(Box b, int x, int y) {
        return x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private static class Box {
        final String label;
        final int color;
        final int minW;
        final boolean resizable;
        int x, y, w, h;

        Box(String label, int color, int x, int y, int w, int h, int minW, boolean resizable) {
            this.label = label; this.color = color; this.minW = minW; this.resizable = resizable;
            this.x = x; this.y = y; this.w = Math.max(minW, w); this.h = h;
        }
    }
}
