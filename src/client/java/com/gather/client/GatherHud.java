package com.gather.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.stream.Collectors;

public class GatherHud {
    private static final long FLASH_MS   = 800;
    private static final long STAY_MS    = 500;
    private static final long FADE_IN_MS = 350;
    private static final long INVENTORY_SNAPSHOT_CACHE_MS = 50;
    private static final long HUD_MODEL_REFRESH_MS = 100;

    private static final long TOAST_FADEIN_MS  = 250;
    private static final long TOAST_HOLD_MS    = 1800;
    private static final long TOAST_FADEOUT_MS = 400;
    private static final long TOAST_TOTAL_MS   = TOAST_FADEIN_MS + TOAST_HOLD_MS + TOAST_FADEOUT_MS;
    private static final int  MAX_TOASTS       = 4;

    private static final int ROW_H_GOAL = 22; // goal rows (two text lines + progress bar)
    private static final int ROW_H_MAT  = 18; // material rows
    private static final int ROW_H_HINT = 20; // craft-hint rows
    private static final int LABEL_H    = 11;
    private static final int SEP_H      = 5;

    // long[4] = { firstSeenMs, lastActiveMs, rawNeeded, completionStartMs }
    // completionStartMs == 0 → not yet completing.
    // Items removed from aggRaw without completionStartMs being set → broken down, not gathered → drop silently.
    private static final Map<String, long[]> animMats = new LinkedHashMap<>();
    private static final Map<String, InventoryCountEntry> inventoryCountCache = new HashMap<>();
    private static InventorySnapshot inventorySnapshot = null;
    private static HudModel cachedModel = HudModel.empty();
    private static long cachedModelAtMs = 0L;
    private static boolean modelDirty = true;

    // Goal completion sound + toast
    private record ToastEntry(String itemName, long startMs) {}
    private static final List<ToastEntry> toastQueue = new ArrayList<>();
    private static Set<String> knownReadyGoals = new HashSet<>();
    private static boolean hudFirstBuild = true;
    private static final Map<Item, Set<TagKey<Item>>> ITEM_TAG_CACHE = new HashMap<>();
    private static final Map<String, Item> ITEM_ID_CACHE = new HashMap<>();
    private static final Set<String> currentReadyBuf = new HashSet<>();

    private record InventoryCountEntry(long expiresAtMs, int count) {}
    private record InventorySnapshot(long expiresAtMs, Map<Item, Integer> counts) {}
    private record MatEntry(String itemId, ItemStack stack, int rawNeeded, int have, long firstSeenMs, long completionStartMs) {}
    private record HintEntry(String rootId, ItemStack rootStack, int craftable, int needed, List<ItemStack> leafStacks) {}
    private record GoalEntry(int listIndex, GatherState.RootInfo root, ItemStack stack, String itemName, boolean header) {}
    private record HudModel(List<GatherList> lists, List<GoalEntry> goalEntries,
                            List<MatEntry> matEntries, List<HintEntry> hints) {
        static HudModel empty() {
            return new HudModel(List.of(), List.of(), List.of(), List.of());
        }
    }

    public static void register() {
        HudRenderCallback.EVENT.register(GatherHud::onHudRender);
    }

    public static void markDirty() {
        modelDirty = true;
        inventoryCountCache.clear();
        inventorySnapshot = null;
    }

    public static boolean isCollectorShulker(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        var cd = stack.get(DataComponentTypes.CUSTOM_DATA);
        return cd != null && cd.copyNbt().getBoolean("gather_collector", false);
    }

    public static void reset() {
        knownReadyGoals.clear();
        toastQueue.clear();
        hudFirstBuild = true;
        animMats.clear();
        modelDirty = true;
        inventoryCountCache.clear();
        inventorySnapshot = null;
    }

    private static void onHudRender(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (!GatherSettings.get().enabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getOverlay() != null) return;

        WorldHighlightRenderer.renderCollectorLabels(context);

        if (!GatherSettings.get().showHud) return;

        GatherState state = GatherState.get();
        long now = System.currentTimeMillis();
        HudModel model = getHudModel(client, state, now);
        List<GatherList> lists = model.lists();
        List<GoalEntry> goalEntries = model.goalEntries();
        List<MatEntry> matEntries = model.matEntries();
        List<HintEntry> hints = model.hints();

        int screenH  = client.getWindow().getScaledHeight();
        int screenW  = client.getWindow().getScaledWidth();
        GatherSettings settings = GatherSettings.get();
        int topY = Math.max(0, settings.layoutGoalsY);
        int maxContentH = Math.max(ROW_H_GOAL, screenH - topY - 12);
        int colW = Math.max(84, settings.layoutGoalsW);
        int rowStartY = topY + LABEL_H;
        int rowContentH = Math.max(ROW_H_GOAL, maxContentH - LABEL_H);

        int goalCols = 0;
        int colH = 0;
        for (GoalEntry entry : goalEntries) {
            int h = entry.header() ? LABEL_H : ROW_H_GOAL;
            if (colH > 0 && colH + h > maxContentH) {
                goalCols++;
                colH = 0;
            }
            colH += h;
        }
        if (!goalEntries.isEmpty()) goalCols++;

        int maxCols = Math.max(1, (screenW - 8) / colW);
        boolean hasMats = !matEntries.isEmpty();
        boolean hasHints = !hints.isEmpty();
        int reservedCols = (hasMats ? 1 : 0) + (hasHints ? 1 : 0);
        int maxGoalCols = Math.max(1, maxCols - reservedCols);
        if (goalCols > maxGoalCols) goalCols = maxGoalCols;

        int matTopY = Math.max(0, settings.layoutMaterialsY);
        int matStartX = Math.max(0, settings.layoutMaterialsX);
        int matColW = Math.max(84, settings.layoutMaterialsW);
        int matRowStartY = matTopY + LABEL_H;
        int matContentH = Math.max(ROW_H_MAT, screenH - matTopY - 12 - LABEL_H);
        int matRowsPerCol = Math.max(1, matContentH / ROW_H_MAT);
        int maxMatCols = Math.max(1, (screenW - matStartX - 4) / matColW);
        int numCols  = matEntries.isEmpty() ? 0
                : (int) Math.ceil((double) matEntries.size() / matRowsPerCol);
        if (numCols > maxMatCols) {
            numCols = maxMatCols;
            matRowsPerCol = (int) Math.ceil((double) matEntries.size() / numCols);
        }

        int hintTopY = Math.max(0, settings.layoutCraftHintsY);
        int hintStartX = Math.max(0, settings.layoutCraftHintsX);
        int hintColW = Math.max(84, settings.layoutCraftHintsW);
        int hintRowStartY = hintTopY + LABEL_H;
        int hintContentH = Math.max(ROW_H_HINT, screenH - hintTopY - 12 - LABEL_H);
        int hintRowsPerCol = Math.max(1, hintContentH / ROW_H_HINT);
        int hintCols = hints.isEmpty() ? 0 : (int) Math.ceil((double) hints.size() / hintRowsPerCol);
        int maxHintCols = Math.max(1, (screenW - hintStartX - 4) / hintColW);
        if (hintCols > maxHintCols) {
            hintCols = maxHintCols;
            hintRowsPerCol = (int) Math.ceil((double) hints.size() / hintCols);
        }

        // === SCAN MODE INDICATORS ===
        boolean countChests = GatherSettings.get().countChests;
        boolean manualScanOn = !countChests && state.isChestScanMode();
        boolean scanAllOn    = countChests;
        int sw = screenW;

        if (manualScanOn) {
            // Centre-top banner while manual mode is active
            int manualCount = state.getManualChests().size();
            String line1 = "◎ MANUAL SCAN ACTIVE";
            String line2 = "Right-click chests to tag / untag";
            String line3 = manualCount == 0 ? "No chests tagged yet"
                    : manualCount + " chest" + (manualCount == 1 ? "" : "s") + " tagged · Contents count toward goals";
            int minBw = Math.max(client.textRenderer.getWidth(line1),
                     Math.max(client.textRenderer.getWidth(line2), client.textRenderer.getWidth(line3))) + 12;
            int bw = Math.max(minBw, settings.layoutManualScanW);
            int bh = 38;
            int bx = settings.layoutManualScanX < 0 ? sw / 2 - bw / 2 : settings.layoutManualScanX;
            int by = settings.layoutManualScanY;
            int textCenterX = bx + bw / 2;
            context.fill(bx, by, bx + bw, by + bh, 0xCC001A1A);
            context.fill(bx, by, bx + bw, by + 1, 0xFFFF9944);
            context.fill(bx, by + bh - 1, bx + bw, by + bh, 0x88AA6622);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line1), textCenterX, by + 4, 0xFFFFCC44);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line2), textCenterX, by + 15, 0xFFCCBB88);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line3), textCenterX, by + 26, manualCount == 0 ? 0xFF776655 : 0xFFFFDD99);
        }

        // Small top-right badge for persistent scan modes
        {
            int badgeX = settings.layoutScanBadgesX < 0 ? sw - 4 : settings.layoutScanBadgesX + settings.layoutScanBadgesW;
            int badgeY = settings.layoutScanBadgesY;
            if (scanAllOn) {
                int autoCount = state.getTrackedChests().size();
                String badge = "• SCAN ALL" + (autoCount > 0 ? " (" + autoCount + ")" : "");
                int bw = client.textRenderer.getWidth(badge) + 8;
                badgeX -= bw;
                context.fill(badgeX, badgeY, badgeX + bw, badgeY + 11, 0xAA00332B);
                context.drawTextWithShadow(client.textRenderer, Text.literal(badge), badgeX + 4, badgeY + 2, 0xFF33D6AA);
                badgeY += 13;
                badgeX = sw - 4;
            }
            if (!scanAllOn && !state.getManualChests().isEmpty()) {
                int mc = state.getManualChests().size();
                String badge = "• MANUAL (" + mc + ")";
                int bw = client.textRenderer.getWidth(badge) + 8;
                badgeX -= bw;
                context.fill(badgeX, badgeY, badgeX + bw, badgeY + 11, 0xAA2B1A00);
                context.drawTextWithShadow(client.textRenderer, Text.literal(badge), badgeX + 4, badgeY + 2, 0xFFFFAA44);
                badgeY += 13;
            }
        }

        // === CHEST FINDER INDICATOR ===
        {
            String finderItemId = state.getChestFinderItemId();
            if (finderItemId != null && client.player != null) {
                Set<Long> finderChests = state.getChestsContaining(finderItemId);
                BlockPos playerPos = client.player.getBlockPos();
                BlockPos nearest = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (long encoded : finderChests) {
                    BlockPos pos = BlockPos.fromLong(encoded);
                    double dist = playerPos.getSquaredDistance(pos);
                    if (dist < nearestDistSq) { nearestDistSq = dist; nearest = pos; }
                }

                Item finderItem = ITEM_ID_CACHE.computeIfAbsent(finderItemId, k -> Registries.ITEM.get(Identifier.of(k)));
                String itemDisplayName = finderItem != null ? finderItem.getName().getString() : finderItemId;
                int totalCount = state.getTrackedChestCountMatching(finderItemId)
                               + state.getManualChestCountMatching(finderItemId);
                int chestCount = finderChests.size();

                double relativeAngle = Double.NaN;
                String distStr = "";
                if (nearest != null) {
                    int dist = (int) Math.sqrt(nearestDistSq);
                    distStr = dist + "m";
                    double dx = nearest.getX() - playerPos.getX();
                    double dz = nearest.getZ() - playerPos.getZ();
                    double chestYaw = Math.toDegrees(Math.atan2(-dx, dz));
                    relativeAngle = ((chestYaw - client.player.getYaw()) % 360 + 540) % 360 - 180;
                }

                // Info panel
                int maxNameW = 90;
                String dispName;
                if (client.textRenderer.getWidth(itemDisplayName) > maxNameW) {
                    dispName = client.textRenderer.trimToWidth(itemDisplayName,
                            maxNameW - client.textRenderer.getWidth("..")) + "..";
                } else {
                    dispName = itemDisplayName;
                }

                String navLine = nearest == null ? "not in scanned chests" : distStr;
                String cntLine = chestCount == 0 ? "scan chests to locate"
                        : "x" + totalCount + "  in " + chestCount + " chest" + (chestCount == 1 ? "" : "s");

                int iconW  = finderItem != null ? 20 : 0;
                int textW  = Math.max(client.textRenderer.getWidth(dispName),
                             Math.max(client.textRenderer.getWidth(navLine),
                                      client.textRenderer.getWidth(cntLine)));
                int panelW = Math.max(settings.layoutFinderW, iconW + textW + 12);
                int panelX = settings.layoutFinderX < 0 ? sw - 4 - panelW : settings.layoutFinderX;
                int panelY = settings.layoutFinderY;
                int panelH = 38;

                context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC1A0000);
                context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFFFF4444);

                int tx = panelX + 4;
                if (finderItem != null) {
                    context.drawItem(finderItem.getDefaultStack(), panelX + 2, panelY + 3);
                    tx = panelX + 22;
                }
                context.drawTextWithShadow(client.textRenderer, Text.literal(dispName), tx, panelY + 4,  0xFFFF9999);
                context.drawTextWithShadow(client.textRenderer, Text.literal(cntLine),  tx, panelY + 15, chestCount == 0 ? 0xFF554433 : 0xFFAA7744);
                context.drawTextWithShadow(client.textRenderer, Text.literal(navLine),  tx, panelY + 26, nearest == null ? 0xFF664433 : 0xFFFF5533);

                // Orbiting chevron near crosshair
                if (!Double.isNaN(relativeAngle)) {
                    int sh = client.getWindow().getScaledHeight();
                    float cx = sw / 2.0f, cy = sh / 2.0f;
                    float radius = 20f;
                    double relRad = Math.toRadians(relativeAngle);
                    float ax = cx + (float)(Math.sin(relRad) * radius);
                    float ay = cy - (float)(Math.cos(relRad) * radius);
                    float pulse = 0.65f + 0.35f * (float)Math.sin(now / 350.0);
                    int alpha = (int)(pulse * 255) << 24;
                    int chevColor = (alpha & 0xFF000000) | 0x00FF6644;
                    var mat = context.getMatrices();
                    mat.pushMatrix();
                    mat.translate(ax, ay);
                    mat.rotate((float)Math.toRadians(relativeAngle - 90));
                    int tw = client.textRenderer.getWidth(">");
                    context.drawTextWithShadow(client.textRenderer, Text.literal(">"), -tw / 2, -4, chevColor);
                    mat.popMatrix();
                    // Distance label further out along same direction, in screen space
                    float labelRadius = radius + 14f;
                    float lx2 = cx + (float)(Math.sin(relRad) * labelRadius);
                    float ly2 = cy - (float)(Math.cos(relRad) * labelRadius);
                    int distLabelColor = (alpha & 0xFF000000) | 0x00BBCCDD;
                    int dw = client.textRenderer.getWidth(distStr);
                    context.drawTextWithShadow(client.textRenderer, Text.literal(distStr),
                            (int)lx2 - dw / 2, (int)ly2 - 4, distLabelColor);
                }
            }
        }

        // === COMPLETION TOASTS ===
        toastQueue.removeIf(t -> now - t.startMs() > TOAST_TOTAL_MS);
        if (!toastQueue.isEmpty()) {
            int sw2 = client.getWindow().getScaledWidth();
            int toastCenterX = settings.layoutToastX < 0 ? sw2 / 2 : settings.layoutToastX + 100;
            for (int ti = toastQueue.size() - 1; ti >= 0; ti--) {
                ToastEntry toast = toastQueue.get(ti);
                long elapsed = now - toast.startMs();
                float alpha;
                if (elapsed < TOAST_FADEIN_MS) alpha = (float) elapsed / TOAST_FADEIN_MS;
                else if (elapsed < TOAST_FADEIN_MS + TOAST_HOLD_MS) alpha = 1.0f;
                else alpha = 1.0f - (float)(elapsed - TOAST_FADEIN_MS - TOAST_HOLD_MS) / TOAST_FADEOUT_MS;
                alpha = Math.max(0f, Math.min(1f, alpha));
                int a = (int)(alpha * 255);
                String msg = "✔ " + toast.itemName();
                int tw2 = client.textRenderer.getWidth(msg);
                int pw = tw2 + 18;
                int px = toastCenterX - pw / 2;
                int py = settings.layoutToastY + (toastQueue.size() - 1 - ti) * 18;
                context.fill(px, py, px + pw, py + 14, (a << 24) | 0x001A00);
                context.fill(px, py, px + pw, py + 1, (a << 24) | 0x33CC66);
                context.fill(px, py + 13, px + pw, py + 14, (a << 24) | 0x1A6633);
                context.drawTextWithShadow(client.textRenderer, Text.literal(msg), px + 9, py + 3, (a << 24) | 0xAAFFCC);
            }
        }

        if (goalEntries.isEmpty() && matEntries.isEmpty() && hints.isEmpty()) return;

        int x = Math.max(0, settings.layoutGoalsX);
        int y = topY;
        int goalCol = 0;
        int hiddenGoalRows = 0;

        // === RENDER GOAL ROWS (multi-column) ===
        for (int gi = 0; gi < goalEntries.size(); gi++) {
            GoalEntry entry = goalEntries.get(gi);
            int entryH = entry.header() ? LABEL_H : ROW_H_GOAL;
            if (y > topY && y + entryH > topY + maxContentH) {
                goalCol++;
                if (goalCol >= goalCols) {
                    hiddenGoalRows = goalEntries.size() - gi;
                    break;
                }
                x = Math.max(0, settings.layoutGoalsX) + goalCol * colW;
                y = topY;
            }

            if (entry.header()) {
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal("§7" + lists.get(entry.listIndex()).name), x + 2, y + 2, 0xFF778899);
                y += LABEL_H;
                continue;
            }

            GatherState.RootInfo root = entry.root();
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) { y += ROW_H_GOAL; continue; }

            int have      = root.effectiveHave();
            int needed    = root.needed();
            boolean ready = root.ready();

            context.fill(x, y, x + 80, y + ROW_H_GOAL - 2, ready ? 0xAA002200 : 0xAA00001A);
            context.drawItem(stack, x + 1, y + 2);

            String haveBadge;
            int haveBadgeCol;
            if (have > needed) {
                haveBadge = "+" + (have - needed);
                haveBadgeCol = 0xFF44FFAA;
            } else {
                haveBadge = have + "/" + needed;
                haveBadgeCol = have >= needed ? 0xFF88FF88 : (have > 0 ? 0xFFFFFF55 : 0xFFFF6666);
            }
            int haveBadgeW = client.textRenderer.getWidth(haveBadge);
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal(haveBadge), x + 78 - haveBadgeW, y + 2, haveBadgeCol);

            int nameMaxW = 78 - 19 - haveBadgeW - 3;
            String fullName = entry.itemName();
            String name;
            if (client.textRenderer.getWidth(fullName) > nameMaxW) {
                name = client.textRenderer.trimToWidth(fullName,
                        nameMaxW - client.textRenderer.getWidth("...")) + "...";
            } else {
                name = fullName;
            }
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal(name), x + 19, y + 2, ready ? 0xFFEEFFEE : 0xFFCCCCCC);

            if (root.craftableNow() >= 0) {
                int cnt = root.craftableNow();
                String craftLabel = "craft: " + cnt;
                int craftCol = cnt >= needed ? 0xFF55FF55 : (cnt > 0 ? 0xFFFFDD33 : 0xFF666666);
                int craftW = client.textRenderer.getWidth(craftLabel);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(craftLabel), x + 78 - craftW, y + 11, craftCol);
            }

            float prog = root.leafProgress();
            int lineW = (int)(80 * prog);
            int barCol = prog >= 1f ? 0x44DD66 : (prog > 0.5f ? 0xFFDD33 : (prog > 0f ? 0xFF8833 : 0x664444));
            context.fill(x, y + ROW_H_GOAL - 2, x + 80, y + ROW_H_GOAL - 1, 0x33000000);
            if (lineW > 0)
                context.fill(x, y + ROW_H_GOAL - 2, x + lineW, y + ROW_H_GOAL - 1, 0xFF000000 | barCol);

            y += ROW_H_GOAL;
        }
        if (hiddenGoalRows > 0) {
            int ox = Math.max(0, settings.layoutGoalsX) + (goalCols - 1) * colW;
            int oy = topY + maxContentH - 11;
            String more = "+" + hiddenGoalRows + " more";
            context.fill(ox, oy, ox + 80, oy + 10, 0xAA111122);
            context.drawTextWithShadow(client.textRenderer, Text.literal(more), ox + 2, oy + 1, 0xFFFFAA44);
        }

        // === RENDER BASE MATERIAL ROWS (multi-column) ===
        if (!matEntries.isEmpty()) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal("§7base materials"), matStartX + 2, matTopY + 2, 0xFF778899);
        }
        for (int mi = 0; mi < matEntries.size(); mi++) {
            int col = numCols > 0 ? mi / matRowsPerCol : 0;
            int row = numCols > 0 ? mi % matRowsPerCol : mi;
            if (col >= numCols) break;
            MatEntry entry = matEntries.get(mi);
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) continue;

            int have = entry.have();
            int need = entry.rawNeeded();
            boolean completing = entry.completionStartMs() != 0;
            float alpha = materialAlpha(entry, now);
            int a    = Math.max(0, Math.min(255, (int)(alpha * 255)));
            int rx   = matStartX + col * matColW + materialXShift(entry, now);
            int ry   = matRowStartY + row * ROW_H_MAT;

            context.fill(rx + 77, ry, rx + 80, ry + ROW_H_MAT - 2, (a << 24) | 0xBB7722);

            if (completing) {
                float pulse = (float)(0.5 + 0.5 * Math.sin(now * 0.020));
                int bgG = (int)(0x33 + pulse * 0x55);
                context.fill(rx, ry, rx + 80, ry + ROW_H_MAT - 2, (a << 24) | (bgG << 8));
                context.drawItem(stack, rx + 1, ry);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(need + "/" + need), rx + 19, ry + 4, (a << 24) | 0x55FF55);
                context.fill(rx, ry + ROW_H_MAT - 2, rx + 80, ry + ROW_H_MAT - 1, (a / 4 << 24) | 0x000000);
                context.fill(rx, ry + ROW_H_MAT - 2, rx + 80, ry + ROW_H_MAT - 1, (a << 24) | 0x44EE66);
            } else {
                float progress = need > 0 ? Math.min(1f, (float) have / need) : 1f;
                int bgBase  = have >= need ? 0x00AA44 : 0x220033;
                int bgAlpha = (int)(alpha * 0x88);
                context.fill(rx, ry, rx + 80, ry + ROW_H_MAT - 2, (bgAlpha << 24) | bgBase);
                context.drawItem(stack, rx + 1, ry);
                if (alpha < 1f) {
                    int maskA = (int)((1f - alpha) * 230);
                    context.fill(rx + 1, ry, rx + 17, ry + 16, maskA << 24);
                }
                int rawCol = have >= need ? 0x88FF88 : (have > 0 ? 0xFFFF55 : 0xFF6666);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(have + "/" + need), rx + 19, ry + 4, (a << 24) | (rawCol & 0xFFFFFF));
                int lineW      = (int)(80 * progress);
                int rawLineCol = progress >= 1f ? 0x44DD66 : (progress > 0 ? 0xFFDD33 : 0x664444);
                int lineAlpha  = (int)(alpha * 0xFF);
                context.fill(rx, ry + ROW_H_MAT - 2, rx + 80, ry + ROW_H_MAT - 1, (lineAlpha / 4 << 24) | 0x000000);
                if (lineW > 0)
                    context.fill(rx, ry + ROW_H_MAT - 2, rx + lineW, ry + ROW_H_MAT - 1, (lineAlpha << 24) | rawLineCol);
            }
        }
        // === RENDER CRAFT HINTS ===
        if (!hints.isEmpty()) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal("§acraft ready"), hintStartX + 2, hintTopY + 2, 0xFF66CC66);

            int arrowW = client.textRenderer.getWidth("->");
            // Pre-pass: find max row width so all rows share same width
            int maxRowW = 40;
            for (HintEntry hint : hints) {
                int lc = hint.leafStacks().size();
                int leafEnd = 1 + lc * 16 - Math.max(0, lc - 1);
                int cntW = client.textRenderer.getWidth("x" + hint.craftable());
                int rw = leafEnd + 2 + arrowW + 2 + 16 + 2 + cntW + 3;
                if (rw > maxRowW) maxRowW = rw;
            }

            for (int hi = 0; hi < hints.size(); hi++) {
                int col = hintCols > 0 ? hi / hintRowsPerCol : 0;
                int row = hintCols > 0 ? hi % hintRowsPerCol : hi;
                if (col >= hintCols) break;
                HintEntry hint = hints.get(hi);
                ItemStack rootStack = hint.rootStack();
                if (rootStack.isEmpty()) continue;

                x = hintStartX + col * hintColW;
                y = hintRowStartY + row * ROW_H_HINT;

                float pulse = (float)(0.5 + 0.5 * Math.sin(now * 0.003));
                int bgG = (int)(0x22 + pulse * 0x33);
                context.fill(x, y, x + maxRowW, y + ROW_H_HINT - 2, (0xAA << 24) | (bgG << 8));

                // Up to 2 leaf icons on the left
                List<ItemStack> leafStacks = hint.leafStacks();
                int lc = leafStacks.size();
                int iconX = x + 1;
                for (int li = 0; li < lc; li++) {
                    ItemStack leafStack = leafStacks.get(li);
                    if (!leafStack.isEmpty()) context.drawItem(leafStack, iconX, y + 2);
                    iconX += 15;
                }
                int leafEnd = x + 1 + lc * 16 - Math.max(0, lc - 1);

                // Arrow — tight against last leaf icon
                int arrowX = leafEnd + 2;
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal("->"), arrowX, y + 6, 0xFF88CC88);

                // Root item icon + count
                int rootX = arrowX + arrowW + 2;
                context.drawItem(rootStack, rootX, y + 2);
                String cnt = "x" + hint.craftable();
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(cnt), rootX + 17, y + 6, 0xFF88FF88);
            }
        }
    }

    private static HudModel getHudModel(MinecraftClient client, GatherState state, long now) {
        if (!modelDirty && now - cachedModelAtMs < HUD_MODEL_REFRESH_MS) {
            return cachedModel;
        }
        cachedModel = buildHudModel(client, state, now);
        cachedModelAtMs = now;
        modelDirty = false;
        return cachedModel;
    }

    private static HudModel buildHudModel(MinecraftClient client, GatherState state, long now) {
        List<GatherList> lists = new ArrayList<>(state.getLists());
        boolean countChests = GatherSettings.get().countChests;
        java.util.function.Function<String, Integer> totalCounter = id -> {
            Item it = Registries.ITEM.get(Identifier.of(id));
            int inInv = it == null ? 0 : countInventoryTagAware(client, it);
            int inChests = countChests ? state.getTrackedChestCountMatching(id) : 0;
            int inManual = countChests ? 0 : state.getManualChestCountMatching(id);
            int inCollectors = state.getCollectorChestCountMatching(id);
            return inInv + inChests + inManual + inCollectors;
        };

        List<List<GatherState.RootInfo>> rootsPerList = new ArrayList<>();
        for (int li = 0; li < lists.size(); li++) {
            rootsPerList.add(state.getRootsWithProgress(li, totalCounter));
        }

        // Detect newly completed goals for sound + toast (only visible lists)
        if (GatherSettings.get().goalSoundEnabled) {
            currentReadyBuf.clear();
            for (int li = 0; li < lists.size(); li++) {
                if (lists.get(li).hudHidden) continue;
                for (GatherState.RootInfo root : rootsPerList.get(li)) {
                    if (root.effectiveHave() >= root.needed()) currentReadyBuf.add(li + ":" + root.itemId());
                }
            }
            if (!hudFirstBuild) {
                for (String key : currentReadyBuf) {
                    if (!knownReadyGoals.contains(key)) {
                        String itemId = key.substring(key.indexOf(':') + 1);
                        Item completedItem = ITEM_ID_CACHE.computeIfAbsent(itemId, k -> Registries.ITEM.get(Identifier.of(k)));
                        String itemName = completedItem != null ? completedItem.getName().getString() : itemId;
                        if (toastQueue.size() < MAX_TOASTS) toastQueue.add(new ToastEntry(itemName, now));
                        client.getSoundManager().play(
                            PositionedSoundInstance.ui(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f));
                    }
                }
            } else {
                hudFirstBuild = false;
            }
            knownReadyGoals.clear();
            knownReadyGoals.addAll(currentReadyBuf);
        }

        Map<String, Integer> aggRaw = new LinkedHashMap<>();
        for (int li = 0; li < lists.size(); li++) {
            if (lists.get(li).hudHidden) continue;
            state.getScaledIngredientLeaves(li, totalCounter)
                    .forEach((id, n) -> aggRaw.merge(id, n, Integer::sum));
        }

        for (Map.Entry<String, Integer> e : aggRaw.entrySet()) {
            String id = e.getKey();
            int globalEff = Math.max(0, e.getValue() - totalCounter.apply(id));
            long[] ae = animMats.get(id);

            if (globalEff > 0) {
                if (ae == null) {
                    animMats.put(id, new long[]{now, now, e.getValue(), 0L});
                } else {
                    ae[1] = now;
                    ae[2] = e.getValue();
                    if (ae[3] != 0) ae[3] = 0;
                }
            } else if (ae != null && ae[3] == 0) {
                ae[3] = now;
            }
        }

        animMats.entrySet().removeIf(e -> {
            if (aggRaw.containsKey(e.getKey())) return false;
            long[] ae = e.getValue();
            if (ae[3] == 0) return true;
            return now - ae[3] > FLASH_MS;
        });

        List<MatEntry> matEntries = new ArrayList<>();
        for (Map.Entry<String, long[]> e : animMats.entrySet()) {
            String id = e.getKey();
            long[] ae = e.getValue();
            if (ae[3] != 0 && now - ae[3] >= FLASH_MS) continue;
            Item matItem = ITEM_ID_CACHE.computeIfAbsent(id, k -> Registries.ITEM.get(Identifier.of(k)));
            ItemStack matStack = matItem != null ? matItem.getDefaultStack() : ItemStack.EMPTY;
            matEntries.add(new MatEntry(id, matStack, (int) ae[2], totalCounter.apply(id), ae[0], ae[3]));
        }

        List<HintEntry> hints = new ArrayList<>();
        for (int li = 0; li < lists.size(); li++) {
            if (lists.get(li).hudHidden) continue;
            for (GatherState.RootInfo root : rootsPerList.get(li)) {
                if (!root.ready() && root.craftableNow() > 0) {
                    Item rootItem = ITEM_ID_CACHE.computeIfAbsent(root.itemId(), k -> Registries.ITEM.get(Identifier.of(k)));
                    ItemStack rootStack = rootItem != null ? rootItem.getDefaultStack() : ItemStack.EMPTY;
                    List<String> leafIds = state.getLeafIdsForRoot(li, root.itemId());
                    int leafCount = Math.min(2, leafIds.size());
                    List<ItemStack> leafStacks = new ArrayList<>(leafCount);
                    for (int k = 0; k < leafCount; k++) {
                        Item leafItem = ITEM_ID_CACHE.computeIfAbsent(leafIds.get(k), id -> Registries.ITEM.get(Identifier.of(id)));
                        leafStacks.add(leafItem != null ? leafItem.getDefaultStack() : ItemStack.EMPTY);
                    }
                    hints.add(new HintEntry(root.itemId(), rootStack, root.craftableNow(), root.needed(), leafStacks));
                }
            }
        }

        List<GoalEntry> goalEntries = new ArrayList<>();
        for (int li = 0; li < lists.size(); li++) {
            if (lists.get(li).hudHidden) continue;
            if (!rootsPerList.get(li).isEmpty()) {
                goalEntries.add(new GoalEntry(li, null, ItemStack.EMPTY, "", true));
                for (GatherState.RootInfo root : rootsPerList.get(li)) {
                    Item goalItem = ITEM_ID_CACHE.computeIfAbsent(root.itemId(), k -> Registries.ITEM.get(Identifier.of(k)));
                    ItemStack goalStack = goalItem != null ? goalItem.getDefaultStack() : ItemStack.EMPTY;
                    String goalName = goalItem != null ? goalItem.getName().getString() : root.itemId();
                    goalEntries.add(new GoalEntry(li, root, goalStack, goalName, false));
                }
            }
        }

        return new HudModel(List.copyOf(lists), List.copyOf(goalEntries),
                List.copyOf(matEntries), List.copyOf(hints));
    }

    private static float materialAlpha(MatEntry entry, long now) {
        if (entry.completionStartMs() == 0) {
            return Math.min(1f, (float) (now - entry.firstSeenMs()) / FADE_IN_MS);
        }
        long elapsed = now - entry.completionStartMs();
        if (elapsed < STAY_MS) return 1f;
        float t = (float) (elapsed - STAY_MS) / (FLASH_MS - STAY_MS);
        return Math.max(0f, 1f - t);
    }

    private static int materialXShift(MatEntry entry, long now) {
        if (entry.completionStartMs() == 0) return 0;
        long elapsed = now - entry.completionStartMs();
        if (elapsed < STAY_MS) return 0;
        float t = (float) (elapsed - STAY_MS) / (FLASH_MS - STAY_MS);
        return (int) (Math.max(0f, Math.min(1f, t)) * 60);
    }

    public static int countInventoryTagAware(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        String itemId = Registries.ITEM.getId(item).toString();
        long now = System.currentTimeMillis();
        InventoryCountEntry cached = inventoryCountCache.get(itemId);
        if (cached != null && now < cached.expiresAtMs()) return cached.count();
        InventorySnapshot snapshot = inventorySnapshot(client, now);

        Set<TagKey<Item>> typeTags = ITEM_TAG_CACHE.computeIfAbsent(item, it -> {
            String path = Registries.ITEM.getId(it).getPath();
            Set<String> expectedPaths = expectedTagPaths(path);
            return it.getRegistryEntry().streamTags()
                    .filter(tag -> expectedPaths.contains(tag.id().getPath()))
                    .collect(Collectors.toSet());
        });

        int count = 0;
        for (Map.Entry<Item, Integer> entry : snapshot.counts().entrySet()) {
            Item inv = entry.getKey();
            String invId = Registries.ITEM.getId(inv).toString();
            if (inv == item
                    || GatherState.isSameWoodFamily(itemId, invId)
                    || (!typeTags.isEmpty() && inv.getRegistryEntry().streamTags().anyMatch(typeTags::contains))) {
                count += entry.getValue();
            }
        }
        inventoryCountCache.put(itemId, new InventoryCountEntry(now + INVENTORY_SNAPSHOT_CACHE_MS, count));
        return count;
    }

    private static InventorySnapshot inventorySnapshot(MinecraftClient client, long now) {
        if (inventorySnapshot != null && now < inventorySnapshot.expiresAtMs()) return inventorySnapshot;
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                var container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) continue;
                for (var inner : container.iterateNonEmpty()) {
                    if (!inner.isEmpty()) counts.merge(inner.getItem(), inner.getCount(), Integer::sum);
                }
            }
        }
        inventorySnapshot = new InventorySnapshot(now + INVENTORY_SNAPSHOT_CACHE_MS, counts);
        return inventorySnapshot;
    }

    private static Set<String> expectedTagPaths(String itemPath) {
        Set<String> paths = new HashSet<>();
        paths.add(itemPath);
        paths.add(pluralizeTagPath(itemPath));
        return paths;
    }

    private static String pluralizeTagPath(String itemPath) {
        if (itemPath.endsWith("s")) return itemPath + "es";
        if (itemPath.endsWith("y") && itemPath.length() > 1 && !isVowel(itemPath.charAt(itemPath.length() - 2))) {
            return itemPath.substring(0, itemPath.length() - 1) + "ies";
        }
        return itemPath + "s";
    }

    private static boolean isVowel(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'a', 'e', 'i', 'o', 'u' -> true;
            default -> false;
        };
    }
}
