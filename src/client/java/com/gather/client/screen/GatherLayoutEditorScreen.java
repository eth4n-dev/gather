package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherHudLayout;
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
    private static final int[] ZOOM_OPTIONS = {0, 2, 3, 4, 5};

    private final Screen parent;
    private final List<Box> boxes = new ArrayList<>();
    private Box active;
    private boolean resizing;
    private boolean zoomDropdownOpen;
    private int grabX;
    private int grabY;
    private boolean showExtendedBorders = false;
    private final List<Integer> snapXLines = new ArrayList<>();
    private final List<Integer> snapYLines = new ArrayList<>();
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;
    private float canvasScale = 1.0f;

    public GatherLayoutEditorScreen(Screen parent) {
        super(Text.literal("Gather Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        GatherSettings s = GatherSettings.get();
        if (s.layoutEditorZoom == 0) s.layoutEditorZoom = currentGuiScale();
        applyEditorGuiScale(s.layoutEditorZoom);
        loadSelectedZoomLayout();
        reloadBoxes();
    }

    private void loadSelectedZoomLayout() {
        GatherSettings s = GatherSettings.get();
        int zoom = selectedZoom();
        updateCanvas();
        s.applyHudLayoutForZoom(zoom, canvasW, canvasH);
    }

    private void reloadBoxes() {
        boxes.clear();
        GatherSettings s = GatherSettings.get();
        updateCanvas();
        GatherHudLayout.Resolved r = GatherHudLayout.resolve(s, canvasW, canvasH, GatherHudLayout.Metrics.defaults());
        boxes.add(new Box("Goals / Lists",      0xFF66CCFF, r.goals(),      GatherHudLayout.GOAL_CARD_W, true));
        boxes.add(new Box("Base Materials",     0xFFFFCC66, r.materials(),  GatherHudLayout.MAT_CARD_W,  true));
        boxes.add(new Box("Craft Hints",        0xFF88FF88, r.craftHints(), GatherHudLayout.HINT_COL_W,  true));
        boxes.add(new Box("Manual Scan Banner", 0xFFFFAA44, r.manualScan(), 180, false));
        boxes.add(new Box("Scan Badges",        0xFF55DDBB, r.scanBadges(), 86,  false));
        boxes.add(new Box("Find Item Panel",    0xFFFF6666, r.finder(),     104, false));
        boxes.add(new Box("Goal Toast",         0xFF44FFAA, r.toast(),      120, false));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x88050812);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFCCDDFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag modules to reposition."), width / 2, 20, 0xFF778899);
        updateCanvas();

        renderCanvasPreview(ctx, mouseX, mouseY);

        // buttons: Current zoom(62) | Show borders(84) | Preset(54) | Done(54) | Cancel(54) = 326px total
        int by = height - 24;
        int bs = width / 2 - 163;
        drawButton(ctx, bs,       by, 62, 18, zoomLabel(),     mouseX, mouseY, false);
        drawButton(ctx, bs + 66,  by, 84, 18, "Show borders", mouseX, mouseY, showExtendedBorders);
        drawButton(ctx, bs + 154, by, 54, 18, "Preset",       mouseX, mouseY, false);
        drawButton(ctx, bs + 212, by, 54, 18, "Done",         mouseX, mouseY, false);
        drawButton(ctx, bs + 270, by, 54, 18, "Cancel",       mouseX, mouseY, false);
        if (zoomDropdownOpen) drawZoomDropdown(ctx, bs, by, mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawZoomDropdown(DrawContext ctx, int x, int buttonY, int mx, int my) {
        int rowH = 16;
        int w = 62;
        int y = buttonY - ZOOM_OPTIONS.length * rowH - 2;
        GatherTheme.fill(ctx, x, y, x + w, buttonY - 1, 0xEE0B1220);
        for (int i = 0; i < ZOOM_OPTIONS.length; i++) {
            int ry = y + i * rowH;
            boolean hover = mx >= x && mx <= x + w && my >= ry && my <= ry + rowH;
            boolean selected = ZOOM_OPTIONS[i] == GatherSettings.get().layoutEditorZoom;
            int bg = selected ? 0xFF1A3320 : (hover ? 0xFF445577 : 0xFF18243A);
            GatherTheme.fill(ctx, x + 1, ry, x + w - 1, ry + rowH - 1, bg);
            String label = ZOOM_OPTIONS[i] <= 0 ? "Current" : ZOOM_OPTIONS[i] + "x";
            ctx.drawTextWithShadow(textRenderer, Text.literal(label), x + 5, ry + 4, 0xFFCCDDFF);
        }
    }

    private void renderCanvasPreview(DrawContext ctx, int mouseX, int mouseY) {
        // center guide lines — brighten while dragging
        int lineAlpha = (active != null && !resizing) ? 0x55 : 0x22;
        int lineColor = (lineAlpha << 24) | 0x44AAFF;
        int cx = toScreenX(canvasW / 2);
        int cy = toScreenY(canvasH / 2);
        GatherTheme.fill(ctx, cx, canvasY, cx + 1, canvasY + screenH(canvasH), lineColor);
        GatherTheme.fill(ctx, canvasX, cy, canvasX + screenW(canvasW), cy + 1, lineColor);

        // per-box extended border guide lines
        if (showExtendedBorders) {
            for (Box box : boxes) {
                int c = (box.color & 0x00FFFFFF) | 0x22000000;
                int sx = toScreenX(box.x);
                int sy = toScreenY(box.y);
                int ex = toScreenX(box.x + box.w);
                int ey = toScreenY(box.y + box.h);
                int mx = toScreenX(box.x + box.w / 2);
                GatherTheme.fill(ctx, sx, canvasY, sx + 1, canvasY + screenH(canvasH), c);
                GatherTheme.fill(ctx, ex, canvasY, ex + 1, canvasY + screenH(canvasH), c);
                GatherTheme.fill(ctx, mx, canvasY, mx + 1, canvasY + screenH(canvasH), (box.color & 0x00FFFFFF) | 0x44000000);
                GatherTheme.fill(ctx, canvasX, sy, canvasX + screenW(canvasW), sy + 1, c);
                GatherTheme.fill(ctx, canvasX, ey, canvasX + screenW(canvasW), ey + 1, c);
            }
        }

        // active snap highlight lines
        for (int lx : snapXLines) GatherTheme.fill(ctx, toScreenX(lx), canvasY, toScreenX(lx) + 1, canvasY + screenH(canvasH), 0xAAFFDD33);
        for (int ly : snapYLines) GatherTheme.fill(ctx, canvasX, toScreenY(ly), canvasX + screenW(canvasW), toScreenY(ly) + 1, 0xAAFFDD33);

        for (int i = 0; i < boxes.size(); i++) drawBox(ctx, boxes.get(i), mouseX, mouseY, i);
    }

    private void drawBox(DrawContext ctx, Box box, int mx, int my, int idx) {
        int cmx = toCanvasX(mx);
        int cmy = toCanvasY(my);
        boolean hover = contains(box, cmx, cmy);
        int sx = toScreenX(box.x);
        int sy = toScreenY(box.y);
        int sw = screenW(box.w);
        int sh = screenH(box.h);
        int fill = hover || box == active ? 0x66335577 : 0x44223344;
        GatherTheme.fill(ctx, sx, sy, sx + sw, sy + sh, fill);
        GatherTheme.fill(ctx, sx, sy,             sx + sw, sy + 1,        box.color);
        GatherTheme.fill(ctx, sx, sy + sh - 1, sx + sw, sy + sh,   box.color);
        GatherTheme.fill(ctx, sx, sy,             sx + 1,     sy + sh,   box.color);
        GatherTheme.fill(ctx, sx + sw - 1, sy, sx + sw, sy + sh,   box.color);
        if (box.resizable)
            GatherTheme.fill(ctx, sx + sw - HANDLE, sy + sh - HANDLE,
                     sx + sw - 2,      sy + sh - 2, box.color);

        ctx.enableScissor(sx + 1, sy + 1, sx + sw - 1, sy + sh - 1);
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(sx, sy);
        matrices.scale(canvasScale, canvasScale);
        drawBoxPreview(ctx, box, idx);
        matrices.popMatrix();
        ctx.disableScissor();
    }

    private void drawBoxPreview(DrawContext ctx, Box box, int idx) {
        int bx = 0; int by = 0; int bw = box.w; int bh = box.h;
        var tr = textRenderer;
        switch (idx) {
            case 0 -> { // Goals / Lists
                ctx.drawTextWithShadow(tr, Text.literal("§7My Goals"), bx + 2, by + 2, 0xFF778899);
                int ry = by + 13;
                // row: Diamond Sword, 1/2, 50% progress
                GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA00001A);
                ctx.drawItem(Items.DIAMOND_SWORD.getDefaultStack(), bx + 1, ry + 2);
                ctx.drawTextWithShadow(tr, Text.literal("Sword"), bx + 19, ry + 2, 0xFFCCCCCC);
                ctx.drawTextWithShadow(tr, Text.literal("1/2"), bx + Math.min(57, bw - 20), ry + 2, 0xFFFFFF55);
                GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(40, bw / 2), ry + 19, 0xFFFFDD33);
                ry += 20;
                if (ry + 20 <= by + bh - 2) {
                    // row: Arrow, 16/16, full
                    GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA002200);
                    ctx.drawItem(Items.ARROW.getDefaultStack(), bx + 1, ry + 2);
                    ctx.drawTextWithShadow(tr, Text.literal("Arrow"), bx + 19, ry + 2, 0xFFEEFFEE);
                    ctx.drawTextWithShadow(tr, Text.literal("16/16"), bx + Math.min(53, bw - 24), ry + 2, 0xFF88FF88);
                    GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                    GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0xFF44DD66);
                    ry += 20;
                }
                if (ry + 20 <= by + bh - 2) {
                    // row: Iron Ingot, 3/8
                    GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 20, 0xAA00001A);
                    ctx.drawItem(Items.IRON_INGOT.getDefaultStack(), bx + 1, ry + 2);
                    ctx.drawTextWithShadow(tr, Text.literal("Iron"), bx + 19, ry + 2, 0xFFCCCCCC);
                    ctx.drawTextWithShadow(tr, Text.literal("3/8"), bx + Math.min(57, bw - 20), ry + 2, 0xFFFF6666);
                    GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(80, bw), ry + 19, 0x33000000);
                    GatherTheme.fill(ctx, bx, ry + 18, bx + Math.min(30, bw * 3 / 8), ry + 19, 0xFFFF8833);
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
                    GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 16, (0x88 << 24) | bgBase);
                    ctx.drawItem(matItems[i].getDefaultStack(), bx + 1, ry);
                    ctx.drawTextWithShadow(tr, Text.literal(have + "/" + need), bx + 19, ry + 4, col);
                    int lw = (int)(Math.min(80, bw) * (float) have / need);
                    GatherTheme.fill(ctx, bx, ry + 15, bx + Math.min(80, bw), ry + 16, 0x33000000);
                    if (lw > 0) GatherTheme.fill(ctx, bx, ry + 15, bx + lw, ry + 16, have >= need ? 0xFF44DD66 : 0xFFFFDD33);
                    ry += 16;
                }
            }
            case 2 -> { // Craft Hints
                ctx.drawTextWithShadow(tr, Text.literal("§acraft ready"), bx + 2, by + 2, 0xFF66CC66);
                int ry = by + 13;
                if (ry + 18 <= by + bh - 2) {
                    GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 18, (0xAA << 24) | (0x22 << 8));
                    ctx.drawItem(Items.OAK_PLANKS.getDefaultStack(), bx + 1,  ry + 1);
                    ctx.drawItem(Items.OAK_PLANKS.getDefaultStack(), bx + 16, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("->"), bx + 33, ry + 5, 0xFF88CC88);
                    ctx.drawItem(Items.CRAFTING_TABLE.getDefaultStack(), bx + 44, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("x4"), bx + 62, ry + 5, 0xFF88FF88);
                    ry += 18;
                }
                if (ry + 18 <= by + bh - 2) {
                    GatherTheme.fill(ctx, bx, ry, bx + Math.min(80, bw), ry + 18, (0xAA << 24) | (0x22 << 8));
                    ctx.drawItem(Items.IRON_INGOT.getDefaultStack(), bx + 1, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("->"), bx + 33, ry + 5, 0xFF88CC88);
                    ctx.drawItem(Items.IRON_SWORD.getDefaultStack(), bx + 44, ry + 1);
                    ctx.drawTextWithShadow(tr, Text.literal("x1"), bx + 62, ry + 5, 0xFF88FF88);
                }
            }
            case 3 -> { // Manual Scan Banner
                GatherTheme.fill(ctx, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xCC001A1A);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("◎ MANUAL SCAN ACTIVE"), bx + bw / 2, by + 4,  0xFFFFCC44);
                if (bh > 22) ctx.drawCenteredTextWithShadow(tr, Text.literal("Right-click chests to tag"), bx + bw / 2, by + 15, 0xFFCCBB88);
                if (bh > 32) ctx.drawCenteredTextWithShadow(tr, Text.literal("2 chests tagged"), bx + bw / 2, by + 26, 0xFFFFDD99);
            }
            case 4 -> { // Scan Badges
                String badge = "• SCAN ALL (8)";
                int badgeW = tr.getWidth(badge) + 8;
                int badgeX = bx + bw - 4 - badgeW;
                GatherTheme.fill(ctx, badgeX, by + 2, badgeX + badgeW, by + 13, 0xAA00332B);
                ctx.drawTextWithShadow(tr, Text.literal(badge), badgeX + 4, by + 4, 0xFF33D6AA);
            }
            case 5 -> { // Find Item Panel
                GatherTheme.fill(ctx, bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xCC1A0000);
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
                GatherTheme.fill(ctx, px, by, px + pw, by + 14, 0xFF001A00);
                GatherTheme.fill(ctx, px, by, px + pw, by + 1,  0xFF33CC66);
                ctx.drawTextWithShadow(tr, Text.literal(msg), px + 9, by + 3, 0xFFAAFFCC);
            }
        }
    }

    private void drawButton(DrawContext ctx, int x, int y, int w, int h,
                            String label, int mx, int my, boolean lit) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg     = lit ? 0xFF1A3320 : (hov ? 0xFF445577 : 0xFF24344F);
        int border = lit ? 0xFF44AA55 : 0xFF667799;
        GatherTheme.fill(ctx, x, y, x + w, y + h, bg);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, border);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
                x + (w - textRenderer.getWidth(label)) / 2, y + 5, 0xFFCCDDFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (zoomDropdownOpen) {
            int bs = width / 2 - 163;
            int rowH = 16;
            int menuY = (height - 24) - ZOOM_OPTIONS.length * rowH - 2;
            if (mx >= bs && mx <= bs + 62 && my >= menuY && my <= height - 26) {
                GatherUi.playClickSound();
                saveBoxes();
                int idx = clamp((my - menuY) / rowH, 0, ZOOM_OPTIONS.length - 1);
                GatherSettings s = GatherSettings.get();
                s.layoutEditorZoom = ZOOM_OPTIONS[idx] <= 0 ? currentGuiScale() : ZOOM_OPTIONS[idx];
                applyEditorGuiScale(s.layoutEditorZoom);
                s.save();
                loadSelectedZoomLayout();
                reloadBoxes();
                zoomDropdownOpen = false;
                return true;
            }
            if (!(my >= height - 24 && my <= height - 6 && mx >= bs && mx <= bs + 62)) {
                zoomDropdownOpen = false;
            }
        }
        if (my >= height - 24 && my <= height - 6) {
            int bs = width / 2 - 163;
            if (mx >= bs && mx <= bs + 62) {
                GatherUi.playClickSound();
                saveBoxes();
                zoomDropdownOpen = !zoomDropdownOpen;
                return true;
            }
            if (mx >= bs + 66 && mx <= bs + 150) {
                GatherUi.playClickSound();
                showExtendedBorders = !showExtendedBorders;
                return true;
            }
            if (mx >= bs + 154 && mx <= bs + 208) {
                GatherUi.playClickSound();
                updateCanvas();
                GatherSettings.get().applyPresetForZoom(selectedZoom(), canvasW, canvasH);
                reloadBoxes();
                return true;
            }
            if (mx >= bs + 212 && mx <= bs + 266) {
                GatherUi.playClickSound();
                saveBoxes();
                close();
                return true;
            }
            if (mx >= bs + 270 && mx <= bs + 324) {
                GatherUi.playClickSound();
                close();
                return true;
            }
        }

        for (int i = boxes.size() - 1; i >= 0; i--) {
            Box box = boxes.get(i);
            int cmx = toCanvasX(mx);
            int cmy = toCanvasY(my);
            if (!contains(box, cmx, cmy)) continue;
            active = box;
            resizing = box.resizable && cmx >= box.x + box.w - HANDLE && cmy >= box.y + box.h - HANDLE;
            grabX = cmx - box.x;
            grabY = cmy - box.y;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (active == null) return false;
        int mx = toCanvasX((int) click.x());
        int my = toCanvasY((int) click.y());
        if (resizing) {
            active.w = Math.max(active.minW, mx - active.x);
        } else {
            active.x = clamp(mx - grabX, 0, Math.max(0, canvasW - active.w));
            active.y = clamp(my - grabY, 0, Math.max(0, canvasH - active.h - 14));
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
        int scx = canvasW / 2;
        int scy = canvasH / 2;

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
            int otherCenterX = other.x + other.w / 2;
            xCands.add(new int[]{otherCenterX - active.w / 2,  otherCenterX});
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
        s.layoutBaseScreenW = canvasW;
        s.layoutBaseScreenH = canvasH;
        s.saveHudLayoutForZoom(selectedZoom());
        s.save();
    }

    private int selectedZoom() {
        int zoom = GatherSettings.get().layoutEditorZoom;
        return zoom <= 0 ? currentGuiScale() : zoom;
    }

    private int currentGuiScale() {
        if (client == null) return 0;
        Integer value = client.options.getGuiScale().getValue();
        return value == null ? 0 : value;
    }

    private String zoomLabel() {
        return "Zoom " + selectedZoom() + "x";
    }

    private void applyEditorGuiScale(int zoom) {
        if (client == null || zoom <= 0 || zoom == currentGuiScale()) return;
        client.options.getGuiScale().setValue(zoom);
        client.options.write();
        client.onResolutionChanged();
    }

    private void updateCanvas() {
        canvasX = 0;
        canvasY = 0;
        canvasW = width;
        canvasH = height;
        canvasScale = 1.0f;
    }

    private int toScreenX(int canvasValue) {
        return canvasX + Math.round(canvasValue * canvasScale);
    }

    private int toScreenY(int canvasValue) {
        return canvasY + Math.round(canvasValue * canvasScale);
    }

    private int toCanvasX(int screenValue) {
        return Math.round((screenValue - canvasX) / Math.max(0.001f, canvasScale));
    }

    private int toCanvasY(int screenValue) {
        return Math.round((screenValue - canvasY) / Math.max(0.001f, canvasScale));
    }

    private int screenW(int canvasValue) {
        return Math.max(1, Math.round(canvasValue * canvasScale));
    }

    private int screenH(int canvasValue) {
        return Math.max(1, Math.round(canvasValue * canvasScale));
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
        final int minH;
        final boolean resizable;
        final boolean dynamicHeight;
        int x, y, w, h;

        Box(String label, int color, int x, int y, int w, int h, int minW, boolean resizable) {
            this(label, color, x, y, w, h, minW, resizable, false);
        }

        Box(String label, int color, int x, int y, int w, int h, int minW, boolean resizable, boolean dynamicHeight) {
            this.label = label; this.color = color; this.minW = minW; this.minH = Math.min(h, 84); this.resizable = resizable; this.dynamicHeight = dynamicHeight;
            this.x = x; this.y = y; this.w = Math.max(minW, w); this.h = Math.max(minH, h);
        }

        Box(String label, int color, GatherHudLayout.Rect rect, int minW, boolean dynamicHeight) {
            this(label, color, rect.x(), rect.y(), rect.w(), rect.h(), minW, false, dynamicHeight);
        }
    }
}
