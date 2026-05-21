package com.gather.client;

public final class GatherHudLayout {
    public static final int GOAL_CARD_W = 76;
    public static final int MAT_CARD_W = 80;
    public static final int HINT_COL_W = 80;
    public static final int GOAL_ROW_H = 22;
    public static final int MAT_ROW_H = 18;
    public static final int HINT_ROW_H = 20;
    public static final int LABEL_H = 11;

    private GatherHudLayout() {}

    public record Rect(int x, int y, int w, int h) {}

    public record Metrics(int manualW, int finderW, int toastW) {
        public static Metrics defaults() {
            return new Metrics(196, 120, 200);
        }
    }

    public record Resolved(Rect goals, Rect materials, Rect craftHints, Rect manualScan,
                           Rect scanBadges, Rect finder, Rect toast) {}

    public static Resolved resolve(GatherSettings settings, int screenW, int screenH, Metrics metrics) {
        Metrics m = metrics == null ? Metrics.defaults() : metrics;
        int goalsX = clampX(settings.layoutGoalsX, GOAL_CARD_W, screenW);
        int goalsY = clampY(settings.layoutGoalsY, screenH);
        int matsX = clampX(settings.layoutMaterialsX, MAT_CARD_W, screenW);
        int matsY = clampY(settings.layoutMaterialsY, screenH);
        int hintsX = clampX(settings.layoutCraftHintsX, HINT_COL_W, screenW);
        int hintsY = clampY(settings.layoutCraftHintsY, screenH);
        int manualW = Math.max(196, m.manualW());
        int manualX = settings.layoutManualScanX < 0
                ? screenW / 2 - manualW / 2
                : clampX(settings.layoutManualScanX, manualW, screenW);
        int badgeW = Math.max(88, settings.layoutScanBadgesW);
        int badgeX = settings.layoutScanBadgesX < 0
                ? screenW - 4 - badgeW
                : clampX(settings.layoutScanBadgesX, badgeW, screenW);
        int finderW = Math.max(120, m.finderW());
        int finderX = settings.layoutFinderX < 0
                ? screenW - 4 - finderW
                : clampX(settings.layoutFinderX, finderW, screenW);
        int toastW = Math.max(120, m.toastW());
        int toastX = settings.layoutToastX < 0
                ? screenW / 2 - toastW / 2
                : clampX(settings.layoutToastX, toastW, screenW);
        return new Resolved(
                new Rect(goalsX, goalsY, GOAL_CARD_W, Math.max(GOAL_ROW_H, screenH - goalsY - 12)),
                new Rect(matsX, matsY, MAT_CARD_W, Math.max(MAT_ROW_H, screenH - matsY - 12)),
                new Rect(hintsX, hintsY, HINT_COL_W, Math.max(HINT_ROW_H, screenH - hintsY - 12)),
                new Rect(manualX, clampY(settings.layoutManualScanY, screenH), manualW, 38),
                new Rect(badgeX, clampY(settings.layoutScanBadgesY, screenH), badgeW, 26),
                new Rect(finderX, clampY(settings.layoutFinderY, screenH), finderW, 38),
                new Rect(toastX, clampY(settings.layoutToastY, screenH), toastW, 14)
        );
    }

    private static int clampX(int x, int w, int screenW) {
        if (x < 0) return x;
        return clamp(x, 0, Math.max(0, screenW - w - 4));
    }

    private static int clampY(int y, int screenH) {
        if (y < 0) return y;
        return clamp(y, 0, Math.max(0, screenH - 14));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
