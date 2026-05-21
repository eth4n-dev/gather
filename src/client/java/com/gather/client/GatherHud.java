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
import org.lwjgl.glfw.GLFW;

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

    private static final int ROW_H_GOAL = GatherHudLayout.GOAL_ROW_H; // goal rows (two text lines + progress bar)
    private static final int ROW_H_MAT  = GatherHudLayout.MAT_ROW_H; // material rows
    private static final int ROW_H_HINT = GatherHudLayout.HINT_ROW_H; // craft-hint rows
    private static final int LABEL_H    = GatherHudLayout.LABEL_H;
    private static final int SEP_H      = 5;
    private static final int GOAL_CARD_W = GatherHudLayout.GOAL_CARD_W;
    private static final int MAT_CARD_W = GatherHudLayout.MAT_CARD_W;

    // long[4] = { firstSeenMs, lastActiveMs, rawNeeded, completionStartMs }
    // completionStartMs == 0 → not yet completing.
    // Items removed from aggRaw without completionStartMs being set → broken down, not gathered → drop silently.
    private static final Map<String, long[]> animMats = new LinkedHashMap<>();
    private static final Map<String, InventoryCountEntry> inventoryCountCache = new HashMap<>();
    private static InventorySnapshot inventorySnapshot = null;
    private static HudModel cachedModel = HudModel.empty();
    private static long cachedModelAtMs = 0L;
    private static boolean modelDirty = true;
    private static int goalPage = 0;
    private static int matPage = 0;
    private static int lastGoalPageCount = 1;
    private static int lastMatPageCount = 1;
    private static boolean goalPageLeftDown = false;
    private static boolean goalPageRightDown = false;
    private static boolean matPageLeftDown = false;
    private static boolean matPageRightDown = false;
    private static int lastAppliedLayoutGuiScale = Integer.MIN_VALUE;
    private static int lastAppliedLayoutScreenW = Integer.MIN_VALUE;
    private static int lastAppliedLayoutScreenH = Integer.MIN_VALUE;

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
    private record GoalPage(int listIndex, int startEntry, int endEntry, int part, int parts) {}
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

    private static void resetPages() {
        goalPage = 0;
        matPage = 0;
        lastGoalPageCount = 1;
        lastMatPageCount = 1;
        goalPageLeftDown = false;
        goalPageRightDown = false;
        matPageLeftDown = false;
        matPageRightDown = false;
    }

    public static void handlePageKeys(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null) {
            goalPageLeftDown = false;
            goalPageRightDown = false;
            matPageLeftDown = false;
            matPageRightDown = false;
            return;
        }
        long handle = client.getWindow().getHandle();
        boolean alt = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        boolean ctrl = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean leftArrow = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS;
        boolean rightArrow = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;

        boolean goalLeftDown = alt && leftArrow;
        boolean goalRightDown = alt && rightArrow;
        boolean matLeftDown = ctrl && leftArrow;
        boolean matRightDown = ctrl && rightArrow;

        if (goalLeftDown && !goalPageLeftDown) changeGoalPage(-1);
        if (goalRightDown && !goalPageRightDown) changeGoalPage(1);
        if (matLeftDown && !matPageLeftDown) changeMatPage(-1);
        if (matRightDown && !matPageRightDown) changeMatPage(1);

        goalPageLeftDown = goalLeftDown;
        goalPageRightDown = goalRightDown;
        matPageLeftDown = matLeftDown;
        matPageRightDown = matRightDown;
    }

    private static void changeGoalPage(int delta) {
        if (lastGoalPageCount > 1) goalPage = clamp(goalPage + delta, 0, lastGoalPageCount - 1);
    }

    private static void changeMatPage(int delta) {
        if (lastMatPageCount > 1) matPage = clamp(matPage + delta, 0, lastMatPageCount - 1);
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
        resetPages();
    }

    public static void showToast(String message) {
        if (message == null || message.isBlank()) return;
        if (toastQueue.size() >= MAX_TOASTS) toastQueue.remove(0);
        toastQueue.add(new ToastEntry(message, System.currentTimeMillis()));
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
        int currentGuiScale = currentGuiScale(client);
        if (currentGuiScale != lastAppliedLayoutGuiScale
                || screenW != lastAppliedLayoutScreenW
                || screenH != lastAppliedLayoutScreenH) {
            settings.applyHudLayoutForZoom(currentGuiScale, screenW, screenH);
            lastAppliedLayoutGuiScale = currentGuiScale;
            lastAppliedLayoutScreenW = screenW;
            lastAppliedLayoutScreenH = screenH;
        }
        ensureLayoutBaseSize(settings, screenW, screenH);

        boolean countChests = GatherSettings.get().countChests;
        boolean manualScanOn = !countChests && state.isChestScanMode();
        boolean scanAllOn    = countChests;
        String line1 = "◎ MANUAL SCAN ACTIVE";
        String line2 = "Right-click chests to tag / untag";
        int manualCount = state.getManualChests().size();
        String line3 = manualCount == 0 ? "No chests tagged yet"
                : manualCount + " chest" + (manualCount == 1 ? "" : "s") + " tagged · Contents count toward goals";
        int manualW = Math.max(196, Math.max(client.textRenderer.getWidth(line1),
                Math.max(client.textRenderer.getWidth(line2), client.textRenderer.getWidth(line3))) + 12);
        String finderItemIdForLayout = state.getChestFinderItemId();
        int finderWForLayout = 120;
        if (finderItemIdForLayout != null) {
            Item finderItemForLayout = ITEM_ID_CACHE.computeIfAbsent(finderItemIdForLayout, k -> Registries.ITEM.get(Identifier.of(k)));
            String finderName = finderItemForLayout != null ? finderItemForLayout.getName().getString() : finderItemIdForLayout;
            int finderTextW = Math.max(client.textRenderer.getWidth(client.textRenderer.trimToWidth(finderName, 90)),
                    Math.max(client.textRenderer.getWidth("x99  in 99 chests"), client.textRenderer.getWidth("not in scanned chests")));
            finderWForLayout = Math.max(120, 20 + finderTextW + 12);
        }
        GatherHudLayout.Resolved layout = GatherHudLayout.resolve(settings, screenW, screenH,
                new GatherHudLayout.Metrics(manualW, finderWForLayout, 200));

        int topY = layout.goals().y();
        int maxContentH = Math.max(ROW_H_GOAL, screenH - topY - 12);
        List<GoalPage> goalPages = buildGoalPages(goalEntries, maxContentH);
        int goalPageCount = goalPages.isEmpty() ? 1 : goalPages.size();
        if (goalPageCount > 1) {
            maxContentH = Math.max(ROW_H_GOAL, maxContentH - 12);
            goalPages = buildGoalPages(goalEntries, maxContentH);
            goalPageCount = goalPages.isEmpty() ? 1 : goalPages.size();
        }
        lastGoalPageCount = goalPageCount;
        goalPage = clamp(goalPage, 0, goalPageCount - 1);

        int matTopY = layout.materials().y();
        int matStartX = layout.materials().x();
        int matRowStartY = matTopY + LABEL_H;
        int matContentH = Math.max(ROW_H_MAT, screenH - matTopY - 12 - LABEL_H);
        int matRowsPerPage = Math.max(1, matContentH / ROW_H_MAT);
        int matPageCount = matEntries.isEmpty() ? 1 : (int) Math.ceil((double) matEntries.size() / matRowsPerPage);
        if (matPageCount > 1) {
            matContentH = Math.max(ROW_H_MAT, matContentH - 12);
            matRowsPerPage = Math.max(1, matContentH / ROW_H_MAT);
            matPageCount = matEntries.isEmpty() ? 1 : (int) Math.ceil((double) matEntries.size() / matRowsPerPage);
        }
        lastMatPageCount = matPageCount;
        matPage = clamp(matPage, 0, matPageCount - 1);

        int hintColW = GatherHudLayout.HINT_COL_W;
        int hintTopY = layout.craftHints().y();
        int hintStartX = layout.craftHints().x();
        int hintRowStartY = hintTopY + LABEL_H;
        int hintContentH = Math.max(ROW_H_HINT, screenH - hintTopY - 12 - LABEL_H);
        int hintRowsPerCol = Math.max(1, hintContentH / ROW_H_HINT);
        int hintCols = hints.isEmpty() ? 0 : (int) Math.ceil((double) hints.size() / hintRowsPerCol);
        int maxHintCols = Math.max(1, (screenW - hintStartX - 4) / hintColW);
        if (hintCols > maxHintCols) {
            hintCols = maxHintCols;
            hintRowsPerCol = (int) Math.ceil((double) hints.size() / hintCols);
        }

        int sw = screenW;

        if (manualScanOn) {
            // Centre-top banner while manual mode is active
            int bw = layout.manualScan().w();
            int bh = layout.manualScan().h();
            int bx = layout.manualScan().x();
            int by = layout.manualScan().y();
            int textCenterX = bx + bw / 2;
            GatherTheme.drawNineSlice(context, GatherTheme.HUD_MANUAL_SCAN_PANEL, bx, by, bw, bh);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line1), textCenterX, by + 4, 0xFFFFCC44);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line2), textCenterX, by + 15, 0xFFCCBB88);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line3), textCenterX, by + 26, manualCount == 0 ? 0xFF776655 : 0xFFFFDD99);
        }

        // Small top-right badge for persistent scan modes
        {
            int badgeX = settings.layoutScanBadgesX < 0 ? sw - 4 : layout.scanBadges().x() + layout.scanBadges().w();
            int badgeY = layout.scanBadges().y();
            if (scanAllOn) {
                int autoCount = state.getTrackedChests().size();
                String badge = "• SCAN ALL" + (autoCount > 0 ? " (" + autoCount + ")" : "");
                int bw = client.textRenderer.getWidth(badge) + 8;
                badgeX -= bw;
                GatherTheme.drawNineSlice(context, GatherTheme.HUD_SCAN_ALL_BADGE, badgeX, badgeY, bw, 11);
                context.drawTextWithShadow(client.textRenderer, Text.literal(badge), badgeX + 4, badgeY + 2, 0xFF33D6AA);
                badgeY += 13;
                badgeX = sw - 4;
            }
            if (!scanAllOn && !state.getManualChests().isEmpty()) {
                int mc = state.getManualChests().size();
                String badge = "• MANUAL (" + mc + ")";
                int bw = client.textRenderer.getWidth(badge) + 8;
                badgeX -= bw;
                GatherTheme.drawNineSlice(context, GatherTheme.HUD_MANUAL_BADGE, badgeX, badgeY, bw, 11);
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
                    distStr = Math.max(0, dist - 1) + "m";
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
                int panelW = Math.max(layout.finder().w(), iconW + textW + 12);
                int panelX = settings.layoutFinderX < 0 ? sw - 4 - panelW : clampToScreenX(layout.finder().x(), panelW, screenW);
                int panelY = layout.finder().y();
                int panelH = layout.finder().h();

                GatherTheme.drawNineSlice(context, GatherTheme.HUD_FINDER_PANEL, panelX, panelY, panelW, panelH);

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
            int toastCenterX = layout.toast().x() + layout.toast().w() / 2;
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
                int py = layout.toast().y() + (toastQueue.size() - 1 - ti) * 18;
                GatherTheme.drawNineSliceTint(context, GatherTheme.HUD_TOAST_PANEL, px, py, pw, 14, (a << 24) | 0xFFFFFF);
                context.drawTextWithShadow(client.textRenderer, Text.literal(msg), px + 9, py + 3, (a << 24) | 0xAAFFCC);
            }
        }

        if (goalEntries.isEmpty() && matEntries.isEmpty() && hints.isEmpty()) return;

        int x = layout.goals().x();
        int y = topY;

        // === RENDER GOAL ROWS (paged by list) ===
        if (!goalEntries.isEmpty() && !goalPages.isEmpty()) {
            GoalPage page = goalPages.get(goalPage);
            String title = lists.get(page.listIndex()).name;
            if (page.parts() > 1) title += " " + page.part() + "/" + page.parts();
            title = trimToWidth(client, title, GOAL_CARD_W - 4);
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal("§7" + title), x + 2, y + 2, 0xFF778899);
            y += LABEL_H;

            for (int gi = page.startEntry(); gi < page.endEntry(); gi++) {
            GoalEntry entry = goalEntries.get(gi);
            if (entry.header()) continue;
            if (y > topY && y + ROW_H_GOAL > topY + maxContentH) break;
            GatherState.RootInfo root = entry.root();
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) { y += ROW_H_GOAL; continue; }

            int have      = root.effectiveHave();
            int needed    = root.needed();
            boolean ready = root.ready();

            GatherTheme.drawNineSlice(context, ready ? GatherTheme.HUD_GOAL_ROW_READY : GatherTheme.HUD_GOAL_ROW,
                    x, y, GOAL_CARD_W, ROW_H_GOAL - 2);
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
                    Text.literal(haveBadge), x + GOAL_CARD_W - 2 - haveBadgeW, y + 2, haveBadgeCol);

            int nameMaxW = GOAL_CARD_W - 2 - 19 - haveBadgeW - 3;
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
                        Text.literal(craftLabel), x + GOAL_CARD_W - 2 - craftW, y + 11, craftCol);
            }

            float prog = root.leafProgress();
            int lineW = (int)(GOAL_CARD_W * prog);
            GatherTheme.drawStretch(context, GatherTheme.HUD_PROGRESS_TRACK, x, y + ROW_H_GOAL - 2, GOAL_CARD_W, 1);
            if (lineW > 0)
                GatherTheme.drawStretch(context, progressFillTexture(prog), x, y + ROW_H_GOAL - 2, lineW, 1);

            y += ROW_H_GOAL;
            }
        }
        // === RENDER BASE MATERIAL ROWS (paged) ===
        if (!matEntries.isEmpty()) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal("§7base materials"), matStartX + 2, matTopY + 2, 0xFF778899);
        }
        int matStart = matPage * matRowsPerPage;
        int matEnd = Math.min(matEntries.size(), matStart + matRowsPerPage);
        for (int mi = matStart; mi < matEnd; mi++) {
            int row = mi - matStart;
            MatEntry entry = matEntries.get(mi);
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) continue;

            int have = entry.have();
            int need = entry.rawNeeded();
            boolean completing = entry.completionStartMs() != 0;
            float alpha = materialAlpha(entry, now);
            int a    = Math.max(0, Math.min(255, (int)(alpha * 255)));
            int rx   = matStartX + materialXShift(entry, now);
            int ry   = matRowStartY + row * ROW_H_MAT;

            GatherTheme.drawStretchTint(context, GatherTheme.HUD_MATERIAL_ACCENT,
                    rx + 77, ry, 3, ROW_H_MAT - 2, (a << 24) | 0xFFFFFF);

            if (completing) {
                float pulse = (float)(0.5 + 0.5 * Math.sin(now * 0.020));
                int bgG = (int)(0x33 + pulse * 0x55);
                GatherTheme.drawNineSliceTint(context, GatherTheme.HUD_TINT_ROW,
                        rx, ry, 80, ROW_H_MAT - 2, (a << 24) | (bgG << 8));
                context.drawItem(stack, rx + 1, ry);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(need + "/" + need), rx + 19, ry + 4, (a << 24) | 0x55FF55);
                GatherTheme.drawStretchTint(context, GatherTheme.HUD_PROGRESS_BLACK,
                        rx, ry + ROW_H_MAT - 2, 80, 1, (a / 4 << 24) | 0xFFFFFF);
                GatherTheme.drawStretchTint(context, GatherTheme.HUD_PROGRESS_FILL_COMPLETE,
                        rx, ry + ROW_H_MAT - 2, 80, 1, (a << 24) | 0xFFFFFF);
            } else {
                float progress = need > 0 ? Math.min(1f, (float) have / need) : 1f;
                int bgBase  = have >= need ? 0x00AA44 : 0x220033;
                int bgAlpha = (int)(alpha * 0x88);
                GatherTheme.drawNineSliceTint(context, GatherTheme.HUD_TINT_ROW,
                        rx, ry, 80, ROW_H_MAT - 2, (bgAlpha << 24) | bgBase);
                context.drawItem(stack, rx + 1, ry);
                if (alpha < 1f) {
                    int maskA = (int)((1f - alpha) * 230);
                    GatherTheme.drawTint(context, GatherTheme.HUD_ITEM_FADE_MASK, rx + 1, ry, maskA << 24);
                }
                int rawCol = have >= need ? 0x88FF88 : (have > 0 ? 0xFFFF55 : 0xFF6666);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(have + "/" + need), rx + 19, ry + 4, (a << 24) | (rawCol & 0xFFFFFF));
                int lineW      = (int)(80 * progress);
                int lineAlpha  = (int)(alpha * 0xFF);
                GatherTheme.drawStretchTint(context, GatherTheme.HUD_PROGRESS_BLACK,
                        rx, ry + ROW_H_MAT - 2, 80, 1, (lineAlpha / 4 << 24) | 0xFFFFFF);
                if (lineW > 0)
                    GatherTheme.drawStretchTint(context, progressFillTexture(progress),
                            rx, ry + ROW_H_MAT - 2, lineW, 1, (lineAlpha << 24) | 0xFFFFFF);
            }
        }
        if (goalPageCount > 1) {
            drawColumnPageHint(context, client, layout.goals().x(), topY + maxContentH, GOAL_CARD_W, "Alt+←/→  Goals " + (goalPage + 1) + "/" + goalPageCount, screenH);
        }
        if (matPageCount > 1) {
            drawColumnPageHint(context, client, matStartX, matRowStartY + matRowsPerPage * ROW_H_MAT, MAT_CARD_W, "Ctrl+←/→  Materials " + (matPage + 1) + "/" + matPageCount, screenH);
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
                GatherTheme.drawNineSliceTint(context, GatherTheme.HUD_TINT_ROW,
                        x, y, maxRowW, ROW_H_HINT - 2, (0xAA << 24) | (bgG << 8));

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

    private static GatherTheme.Texture progressFillTexture(float progress) {
        if (progress >= 1f) return GatherTheme.HUD_PROGRESS_FILL_COMPLETE;
        if (progress > 0.5f) return GatherTheme.HUD_PROGRESS_FILL_PARTIAL;
        if (progress > 0f) return GatherTheme.HUD_PROGRESS_FILL_LOW;
        return GatherTheme.HUD_PROGRESS_FILL_EMPTY;
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
        Map<String, Integer> totalCountCache = new HashMap<>();
        java.util.function.Function<String, Integer> totalCounter = id -> totalCountCache.computeIfAbsent(id, key -> {
            Item it = ITEM_ID_CACHE.computeIfAbsent(key, k -> Registries.ITEM.get(Identifier.of(k)));
            int inInv = it == null ? 0 : countInventoryTagAware(client, it);
            int inChests = countChests ? state.getTrackedChestCountMatching(key) : 0;
            int inManual = countChests ? 0 : state.getManualChestCountMatching(key);
            int inCollectors = state.getCollectorChestCountMatching(key);
            return inInv + inChests + inManual + inCollectors;
        });

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

    private static List<GoalPage> buildGoalPages(List<GoalEntry> entries, int pageH) {
        if (entries.isEmpty()) return List.of();
        List<GoalPage> pages = new ArrayList<>();
        int rowsPerPage = Math.max(1, (pageH - LABEL_H) / ROW_H_GOAL);
        for (int i = 0; i < entries.size(); i++) {
            GoalEntry header = entries.get(i);
            if (!header.header()) continue;
            int listIndex = header.listIndex();
            int start = i + 1;
            int end = start;
            while (end < entries.size() && !entries.get(end).header()) end++;
            int rowCount = end - start;
            if (rowCount <= 0) continue;
            int parts = Math.max(1, (int) Math.ceil(rowCount / (double) rowsPerPage));
            for (int part = 0; part < parts; part++) {
                int pageStart = start + part * rowsPerPage;
                int pageEnd = Math.min(end, pageStart + rowsPerPage);
                pages.add(new GoalPage(listIndex, pageStart, pageEnd, part + 1, parts));
            }
        }
        return pages;
    }

    private static void drawColumnPageHint(DrawContext context, MinecraftClient client, int columnX, int columnBottomY, int columnW, String label, int screenH) {
        int tw = client.textRenderer.getWidth(label);
        int x = Math.max(0, columnX + (columnW - tw - 12) / 2);
        int y = Math.min(Math.max(0, screenH - 32), columnBottomY + 1);
        GatherTheme.drawNineSlice(context, GatherTheme.HUD_MORE_ROW, x, y, tw + 12, 10);
        context.drawTextWithShadow(client.textRenderer, Text.literal(label), x + 6, y + 1, 0xFFFFCC66);
    }

    private static int currentGuiScale(MinecraftClient client) {
        Integer value = client.options.getGuiScale().getValue();
        return value == null ? 0 : value;
    }

    private static String trimToWidth(MinecraftClient client, String text, int maxW) {
        if (client.textRenderer.getWidth(text) <= maxW) return text;
        int ellipsisW = client.textRenderer.getWidth("...");
        return client.textRenderer.trimToWidth(text, Math.max(1, maxW - ellipsisW)) + "...";
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

    private static void ensureLayoutBaseSize(GatherSettings settings, int screenW, int screenH) {
        if (settings.layoutBaseScreenW > 0 && settings.layoutBaseScreenH > 0) return;
        settings.layoutBaseScreenW = screenW;
        settings.layoutBaseScreenH = screenH;
        settings.save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampToScreenX(int x, int width, int screenW) {
        if (x < 0) return x;
        return clamp(x, 0, Math.max(0, screenW - width - 4));
    }

    private static boolean isVowel(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'a', 'e', 'i', 'o', 'u' -> true;
            default -> false;
        };
    }
}
