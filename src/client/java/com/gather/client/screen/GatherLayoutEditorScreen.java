package com.gather.client.screen;

import com.gather.client.GatherSettings;
import com.gather.client.GatherUi;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class GatherLayoutEditorScreen extends Screen {
    private static final int HANDLE = 8;

    private final Screen parent;
    private final List<Box> boxes = new ArrayList<>();
    private Box active;
    private boolean resizing;
    private int grabX;
    private int grabY;

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
        boxes.add(new Box("Goals / Lists", 0xFF66CCFF, s.layoutGoalsX, s.layoutGoalsY, s.layoutGoalsW, 120, 84, false));
        boxes.add(new Box("Base Materials", 0xFFFFCC66, s.layoutMaterialsX, s.layoutMaterialsY, s.layoutMaterialsW, 100, 84, true));
        boxes.add(new Box("Craft Hints", 0xFF88FF88, s.layoutCraftHintsX, s.layoutCraftHintsY, s.layoutCraftHintsW, 80, 84, true));
        int manualX = s.layoutManualScanX < 0 ? width / 2 - s.layoutManualScanW / 2 : s.layoutManualScanX;
        boxes.add(new Box("Manual Scan Banner", 0xFFFFAA44, manualX, s.layoutManualScanY, s.layoutManualScanW, 38, 180, true));
        int badgeX = s.layoutScanBadgesX < 0 ? width - 4 - s.layoutScanBadgesW : s.layoutScanBadgesX;
        boxes.add(new Box("Scan Badges", 0xFF55DDBB, badgeX, s.layoutScanBadgesY, s.layoutScanBadgesW, 26, 86, true));
        int finderX = s.layoutFinderX < 0 ? width - 4 - s.layoutFinderW : s.layoutFinderX;
        boxes.add(new Box("Find Item Panel", 0xFFFF6666, finderX, s.layoutFinderY, s.layoutFinderW, 24, 104, true));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xCC050812);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFCCDDFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Drag modules. Resize with the lower-right handle."), width / 2, 20, 0xFF778899);

        for (Box box : boxes) {
            drawBox(ctx, box, mouseX, mouseY);
        }

        int y = height - 24;
        drawButton(ctx, width / 2 - 86, y, 54, 18, "Reset", mouseX, mouseY);
        drawButton(ctx, width / 2 - 27, y, 54, 18, "Done", mouseX, mouseY);
        drawButton(ctx, width / 2 + 32, y, 54, 18, "Cancel", mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBox(DrawContext ctx, Box box, int mx, int my) {
        boolean hover = contains(box, mx, my);
        int fill = hover || box == active ? 0x66335577 : 0x44223344;
        ctx.fill(box.x, box.y, box.x + box.w, box.y + box.h, fill);
        ctx.fill(box.x, box.y, box.x + box.w, box.y + 1, box.color);
        ctx.fill(box.x, box.y + box.h - 1, box.x + box.w, box.y + box.h, box.color);
        ctx.fill(box.x, box.y, box.x + 1, box.y + box.h, box.color);
        ctx.fill(box.x + box.w - 1, box.y, box.x + box.w, box.y + box.h, box.color);
        ctx.drawTextWithShadow(textRenderer, Text.literal(box.label), box.x + 5, box.y + 5, 0xFFFFFFFF);
        if (box.resizable) {
            ctx.fill(box.x + box.w - HANDLE, box.y + box.h - HANDLE, box.x + box.w - 2, box.y + box.h - 2, box.color);
        }
    }

    private void drawButton(DrawContext ctx, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        ctx.fill(x, y, x + w, y + h, hov ? 0xFF445577 : 0xFF24344F);
        ctx.fill(x, y, x + w, y + 1, 0xFF667799);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), x + (w - textRenderer.getWidth(label)) / 2, y + 5, 0xFFCCDDFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (my >= height - 24 && my <= height - 6) {
            if (mx >= width / 2 - 86 && mx <= width / 2 - 32) {
                GatherUi.playClickSound();
                GatherSettings.get().resetHudLayout();
                reloadBoxes();
                return true;
            }
            if (mx >= width / 2 - 27 && mx <= width / 2 + 27) {
                GatherUi.playClickSound();
                saveBoxes();
                close();
                return true;
            }
            if (mx >= width / 2 + 32 && mx <= width / 2 + 86) {
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
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (active != null) saveBoxes();
        active = null;
        resizing = false;
        return true;
    }

    private void saveBoxes() {
        GatherSettings s = GatherSettings.get();
        Box goals = boxes.get(0);
        Box mats = boxes.get(1);
        Box hints = boxes.get(2);
        Box manual = boxes.get(3);
        Box badges = boxes.get(4);
        Box finder = boxes.get(5);
        s.layoutGoalsX = goals.x; s.layoutGoalsY = goals.y; s.layoutGoalsW = Math.max(goals.minW, goals.w);
        s.layoutMaterialsX = mats.x; s.layoutMaterialsY = mats.y; s.layoutMaterialsW = Math.max(mats.minW, mats.w);
        s.layoutCraftHintsX = hints.x; s.layoutCraftHintsY = hints.y; s.layoutCraftHintsW = Math.max(hints.minW, hints.w);
        s.layoutManualScanX = manual.x; s.layoutManualScanY = manual.y; s.layoutManualScanW = Math.max(manual.minW, manual.w);
        s.layoutScanBadgesX = badges.x; s.layoutScanBadgesY = badges.y; s.layoutScanBadgesW = Math.max(badges.minW, badges.w);
        s.layoutFinderX = finder.x; s.layoutFinderY = finder.y; s.layoutFinderW = Math.max(finder.minW, finder.w);
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
        int x;
        int y;
        int w;
        int h;

        Box(String label, int color, int x, int y, int w, int h, int minW, boolean resizable) {
            this.label = label;
            this.color = color;
            this.x = x;
            this.y = y;
            this.w = Math.max(minW, w);
            this.h = h;
            this.minW = minW;
            this.resizable = resizable;
        }
    }
}
