package com.gather.client;

import com.gather.client.mixin.HandledScreenAccessor;
import com.gather.network.AutoCraftPayload;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.block.ShulkerBoxBlock;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GatherCraftingOverlay {

    private static final int BG_W = 176;
    private static final int BG_H = 166;
    private static final int PANEL_W = 186;
    private static final int PANEL_H = 132;
    private static final int PANEL_PAD = 6;
    private static final int ROW_H = 28;
    private static final int BUTTON_SIZE = 16;
    private static final int CLOSE_SIZE = 9;

    private record CraftEntry(String itemId, ItemStack stack, List<Integer> indices) {}
    private static final Map<String, Item> ITEM_ID_CACHE = new HashMap<>();
    private static final Map<Item, Set<TagKey<Item>>> ITEM_TAG_CACHE = new HashMap<>();
    private static long lastCraftCacheMs = 0;
    private static List<CraftEntry> craftCache = List.of();
    private static int panelScroll = 0;
    private static boolean panelOpen = false;
    private static float panelAnim = 0.0F;
    private static List<Text> hoveredLines = null;
    private static int tooltipX;
    private static int tooltipY;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!GatherSettings.get().enabled) return;
            if (!(screen instanceof CraftingScreen)) return;
            craftCache = buildQuickCraftList();
            lastCraftCacheMs = System.currentTimeMillis();
            panelScroll = 0;
            panelOpen = false;
            panelAnim = 0.0F;

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                hoveredLines = null;
                long now = System.currentTimeMillis();
                if (now - lastCraftCacheMs >= 500) {
                    craftCache = buildQuickCraftList();
                    lastCraftCacheMs = now;
                }
                renderOverlay((CraftingScreen) s, ctx, mx, my);
                if (hoveredLines != null) {
                    ctx.drawTooltip(client.textRenderer, hoveredLines, tooltipX, tooltipY);
                }
            });

            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                int mx = (int) click.x();
                int my = (int) click.y();
                if (!isOverlayClickTarget((CraftingScreen) s, mx, my)) return true;
                handleClick((CraftingScreen) s, mx, my, click.button());
                return false;
            });

            ScreenMouseEvents.afterMouseClick(screen).register((s, click, consumed) -> {
                handleClick((CraftingScreen) s, (int) click.x(), (int) click.y(), click.button());
                return consumed;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
                if (!isPanelTarget((CraftingScreen) s, (int) mouseX, (int) mouseY)) return true;
                handleScroll((CraftingScreen) s, (int) mouseX, (int) mouseY, verticalAmount);
                return false;
            });

            ScreenMouseEvents.afterMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount, consumed) -> {
                handleScroll((CraftingScreen) s, (int) mouseX, (int) mouseY, verticalAmount);
                return consumed;
            });
        });
    }

    private static void renderOverlay(CraftingScreen screen, DrawContext ctx, int mx, int my) {
        if (!GatherSettings.get().enabled) return;
        updateAnimation();
        int[] button = buttonBounds(screen);
        renderToggleButton(ctx, mx, my, button[0], button[1]);

        if (panelAnim <= 0.01F) return;

        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int targetY = bounds[1];
        boolean above = panelOpensAbove(screen);
        int panelY = targetY + Math.round((above ? -1 : 1) * (1.0F - easedPanelAnim()) * 20.0F);

        renderPanel(ctx, panelX, panelY, PANEL_W, PANEL_H);
        ctx.drawText(MinecraftClient.getInstance().textRenderer,
                Text.literal("Crafting Goals"), panelX + PANEL_PAD, panelY + 6, GatherTheme.textPrimary(), false);
        renderCloseButton(ctx, mx, my, panelX + PANEL_W - PANEL_PAD - CLOSE_SIZE, panelY + 5);

        int rowsTop = panelY + 24;
        int rowsVisible = Math.max(1, (PANEL_H - 30) / ROW_H);
        int maxScroll = Math.max(0, craftCache.size() - rowsVisible);
        panelScroll = Math.max(0, Math.min(panelScroll, maxScroll));

        if (craftCache.isEmpty()) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer,
                    Text.literal("No crafting-table items ready"), panelX + PANEL_PAD, rowsTop + 8, GatherTheme.textSecondary(), false);
            return;
        }

        List<ListNode> nodes = GatherState.get().getNodes();
        MinecraftClient client = MinecraftClient.getInstance();
        for (int row = 0; row < rowsVisible && row + panelScroll < craftCache.size(); row++) {
            CraftEntry ce = craftCache.get(row + panelScroll);
            ItemStack stack = ce.stack();
            if (stack.isEmpty()) continue;

            int rowY = rowsTop + row * ROW_H;
            boolean rowHov = mx >= panelX + 2 && mx <= panelX + PANEL_W - 2 && my >= rowY && my <= rowY + ROW_H - 2;
            renderSlotRow(ctx, panelX + 5, rowY, PANEL_W - 13, ROW_H - 2, rowHov);
            int itemSlotX = panelX + 9;
            int itemSlotY = rowY + 4;
            renderItemSlot(ctx, itemSlotX, itemSlotY);
            ctx.drawItem(stack, itemSlotX + 1, itemSlotY + 1);
            ctx.drawText(client.textRenderer, stack.getName(), panelX + 33, rowY + 4, GatherTheme.textPrimary(), false);

            int stillNeed = 0;
            int maxCraft = 0;
            for (int idx : ce.indices()) {
                if (idx >= nodes.size()) continue;
                stillNeed += Math.max(0, nodes.get(idx).needed - effectiveHave(nodes.get(idx)));
                maxCraft  += computeMaxCraftableChained(idx, nodes.get(idx), nodes);
            }
            ctx.drawText(client.textRenderer,
                    Text.literal("need " + stillNeed + "  max " + maxCraft),
                    panelX + 33, rowY + 15, GatherTheme.textSecondary(), false);

            if (rowHov) {
                ctx.setCursor(StandardCursors.POINTING_HAND);
                hoveredLines = List.of(
                        Text.literal("Click to craft"),
                        Text.literal("Left click: craft needed (" + stillNeed + ")"),
                        Text.literal("Right click: craft all possible (" + maxCraft + ")")
                );
                tooltipX = mx;
                tooltipY = my;
            }
        }

        if (maxScroll > 0) {
            int barX = panelX + PANEL_W - 5;
            int barY = rowsTop;
            int barH = rowsVisible * ROW_H - 2;
            int thumbH = Math.max(16, barH * rowsVisible / craftCache.size());
            int thumbY = barY + (int) ((float) panelScroll / maxScroll * (barH - thumbH));
            GatherTheme.drawStretch(ctx, GatherTheme.SCROLL_TRACK, barX, barY, 3, barH);
            GatherTheme.drawStretch(ctx, GatherTheme.SCROLL_THUMB, barX, thumbY, 3, thumbH);
        }
    }

    private static void handleClick(CraftingScreen screen, int mx, int my, int button) {
        if (!GatherSettings.get().enabled) return;
        if (button != 0 && button != 1) return;

        int[] toggle = buttonBounds(screen);
        if (inside(mx, my, toggle[0], toggle[1], BUTTON_SIZE, BUTTON_SIZE)) {
            panelOpen = !panelOpen;
            playClickSound();
            return;
        }

        if (!panelOpen) return;

        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1] + Math.round((panelOpensAbove(screen) ? -1 : 1) * (1.0F - easedPanelAnim()) * 20.0F);
        int closeX = panelX + PANEL_W - PANEL_PAD - CLOSE_SIZE;
        int closeY = panelY + 5;
        if (inside(mx, my, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE)) {
            panelOpen = false;
            playClickSound();
            return;
        }

        int rowsTop = panelY + 24;
        int rowsVisible = Math.max(1, (PANEL_H - 30) / ROW_H);

        if (mx < panelX || mx > panelX + PANEL_W || my < rowsTop || my > panelY + PANEL_H) return;

        int row = (my - rowsTop) / ROW_H;
        if (row < 0 || row >= rowsVisible || row + panelScroll >= craftCache.size()) return;

        List<ListNode> nodes = GatherState.get().getNodes();
        CraftEntry ce = craftCache.get(row + panelScroll);
        for (int idx : ce.indices()) {
            if (idx >= nodes.size()) continue;
            ListNode node = nodes.get(idx);
            if (button == 1) doChainCraftMax(idx, node);
            else doChainCraft(idx, node);
        }
    }

    private static void handleScroll(CraftingScreen screen, int mx, int my, double verticalAmount) {
        if (!panelOpen) return;
        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1] + Math.round((panelOpensAbove(screen) ? -1 : 1) * (1.0F - easedPanelAnim()) * 20.0F);
        if (mx < panelX || mx > panelX + PANEL_W || my < panelY || my > panelY + PANEL_H) return;

        int rowsVisible = Math.max(1, (PANEL_H - 30) / ROW_H);
        int maxScroll = Math.max(0, craftCache.size() - rowsVisible);
        panelScroll = (int) Math.max(0, Math.min(maxScroll, panelScroll - verticalAmount));
    }

    private static int[] panelBounds(CraftingScreen screen) {
        int bgX = screenX(screen);
        int bgY = screenY(screen);
        boolean above = panelOpensAbove(screen);
        int panelX = above ? bgX + 4 : bgX - PANEL_W - 8;
        int panelY = above ? bgY - PANEL_H - 8 : bgY + 18;
        if (panelX < 4) panelX = bgX + 4;
        if (panelX + PANEL_W > screen.width - 4) panelX = screen.width - PANEL_W - 4;
        if (panelY < 4) panelY = bgY + 18;
        return new int[]{panelX, panelY};
    }

    private static int[] buttonBounds(CraftingScreen screen) {
        int bgX = screenX(screen);
        int bgY = screenY(screen);
        return new int[]{bgX + 6, bgY + 5};
    }

    private static void updateAnimation() {
        float target = panelOpen ? 1.0F : 0.0F;
        if (panelAnim < target) {
            panelAnim = Math.min(target, panelAnim + 0.085F);
        } else if (panelAnim > target) {
            panelAnim = Math.max(target, panelAnim - 0.085F);
        }
    }

    private static float easedPanelAnim() {
        return panelAnim * panelAnim * (3.0F - 2.0F * panelAnim);
    }

    private static void renderToggleButton(DrawContext ctx, int mx, int my, int x, int y) {
        boolean hovered = inside(mx, my, x, y, BUTTON_SIZE, BUTTON_SIZE);
        GatherTheme.draw(ctx,
                panelOpen ? (hovered ? GatherTheme.CRAFT_TOGGLE_ACTIVE_HOVER : GatherTheme.CRAFT_TOGGLE_ACTIVE)
                          : hovered ? GatherTheme.CRAFT_TOGGLE_HOVER : GatherTheme.CRAFT_TOGGLE,
                x, y);
        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal(panelOpen ? "Close crafting goals" : "Open crafting goals"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderCloseButton(DrawContext ctx, int mx, int my, int x, int y) {
        boolean hovered = inside(mx, my, x, y, CLOSE_SIZE, CLOSE_SIZE);
        GatherTheme.draw(ctx, hovered ? GatherTheme.CRAFT_CLOSE_HOVER : GatherTheme.CRAFT_CLOSE, x, y);
        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal("Close"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderPanel(DrawContext ctx, int x, int y, int w, int h) {
        GatherTheme.drawNineSlice(ctx, GatherTheme.CRAFT_PANEL, x, y, w + 3, h + 3);
        GatherTheme.drawStretch(ctx, GatherTheme.CRAFT_DIVIDER, x + 4, y + 20, w - 8, 1);
    }

    private static void renderSlotRow(DrawContext ctx, int x, int y, int w, int h, boolean hovered) {
        GatherTheme.drawNineSlice(ctx, hovered ? GatherTheme.CRAFT_ROW_HOVER : GatherTheme.CRAFT_ROW, x, y, w, h);
    }

    private static void renderItemSlot(DrawContext ctx, int x, int y) {
        GatherTheme.draw(ctx, GatherTheme.CRAFT_ITEM_SLOT, x, y);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void playClickSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOverlayClickTarget(CraftingScreen screen, int mx, int my) {
        int[] toggle = buttonBounds(screen);
        return inside(mx, my, toggle[0], toggle[1], BUTTON_SIZE, BUTTON_SIZE) || isPanelTarget(screen, mx, my);
    }

    private static boolean isPanelTarget(CraftingScreen screen, int mx, int my) {
        if (panelAnim <= 0.01F) return false;
        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1] + Math.round((panelOpensAbove(screen) ? -1 : 1) * (1.0F - easedPanelAnim()) * 20.0F);
        return inside(mx, my, panelX, panelY, PANEL_W, PANEL_H);
    }

    private static int screenX(CraftingScreen screen) {
        return ((HandledScreenAccessor) screen).gather$getX();
    }

    private static int screenY(CraftingScreen screen) {
        return ((HandledScreenAccessor) screen).gather$getY();
    }

    private static boolean panelOpensAbove(CraftingScreen screen) {
        return screenX(screen) != (screen.width - ((HandledScreenAccessor) screen).gather$getBackgroundWidth()) / 2;
    }

    private static List<CraftEntry> buildQuickCraftList() {
        List<ListNode> nodes = GatherState.get().getNodes();

        // Compute effective still-needed per node, accounting for current inventory
        int[] effNeeded = new int[nodes.size()];
        for (int ni = 0; ni < nodes.size(); ni++) {
            ListNode node = nodes.get(ni);
            if (node.depth == 0) {
                effNeeded[ni] = Math.max(0, node.needed - effectiveHave(node));
            } else {
                for (int pi = ni - 1; pi >= 0; pi--) {
                    ListNode par = nodes.get(pi);
                    if (par.depth == node.depth - 1) {
                        int rawNeeded = par.needed > 0
                            ? (int) Math.ceil((double) node.needed * effNeeded[pi] / par.needed)
                            : 0;
                        effNeeded[ni] = Math.max(0, rawNeeded - countForId(node.itemId));
                        break;
                    }
                }
            }
        }

        // Collect eligible indices, sort deepest first, then aggregate by itemId
        List<Integer> ordered = new ArrayList<>();
        for (int j = 0; j < nodes.size(); j++) {
            ListNode n = nodes.get(j);
            if (!n.broken || effNeeded[j] == 0) continue;
            if (computeMaxCraftableChained(j, n, nodes) >= 1) ordered.add(j);
        }
        ordered.sort((a, b) -> {
            boolean aGoal = nodes.get(a).depth == 0;
            boolean bGoal = nodes.get(b).depth == 0;
            if (aGoal != bGoal) return aGoal ? -1 : 1;
            int cA = countDirectChildren(a, nodes);
            int cB = countDirectChildren(b, nodes);
            if (cB != cA) return Integer.compare(cB, cA);
            return Integer.compare(nodes.get(a).depth, nodes.get(b).depth);
        });

        // Merge duplicate itemIds into one CraftEntry (more children = higher priority)
        Map<String, CraftEntry> byItem = new LinkedHashMap<>();
        for (int j : ordered) {
            String id = nodes.get(j).itemId;
            byItem.computeIfAbsent(id, k -> {
                Item it = ITEM_ID_CACHE.computeIfAbsent(k, s -> Registries.ITEM.get(Identifier.of(s)));
                ItemStack st = it != null ? it.getDefaultStack() : ItemStack.EMPTY;
                return new CraftEntry(k, st, new ArrayList<>());
            }).indices().add(j);
        }
        return new ArrayList<>(byItem.values());
    }

    private static int countDirectChildren(int ni, List<ListNode> nodes) {
        ListNode parent = nodes.get(ni);
        int count = 0;
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= parent.depth) break;
            if (child.depth == parent.depth + 1) count++;
        }
        return count;
    }

    private static int computeMaxCraftable(int ni, ListNode node) {
        List<ListNode> nodes = GatherState.get().getNodes();
        long max = Long.MAX_VALUE;
        boolean hasChildren = false;
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= node.depth) break;
            if (child.depth != node.depth + 1 || child.needed <= 0) continue;
            hasChildren = true;
            long have = countForId(child.itemId);
            long canMake = have * node.needed / child.needed;
            if (canMake < max) max = canMake;
        }
        return hasChildren && max != Long.MAX_VALUE ? (int) max : 0;
    }

    private static boolean canCraftForNeed(int ni, ListNode node) {
        int stillNeed = Math.max(0, node.needed - effectiveHave(node));
        if (stillNeed == 0) return false;
        return computeMaxCraftable(ni, node) >= 1;
    }

    private static void doCraft(int ni, ListNode node) {
        int stillNeed = Math.max(0, node.needed - effectiveHave(node));
        if (stillNeed == 0) return;
        int maxCraft = computeMaxCraftable(ni, node);
        if (maxCraft <= 0) return;
        int outputCount = Math.min(stillNeed, maxCraft);
        List<ListNode> nodes = GatherState.get().getNodes();
        List<AutoCraftPayload.IngredientEntry> consume = buildIngredients(ni, node, nodes, outputCount);
        GatherClientNetworking.sendAutoCraft(node.itemId, outputCount, consume);
    }

    private static void doCraftMax(int ni, ListNode node) {
        int maxOutput = computeMaxCraftable(ni, node);
        if (maxOutput <= 0) return;
        List<ListNode> nodes = GatherState.get().getNodes();
        List<AutoCraftPayload.IngredientEntry> consume = buildIngredients(ni, node, nodes, maxOutput);
        GatherClientNetworking.sendAutoCraft(node.itemId, maxOutput, consume);
    }

    private static List<AutoCraftPayload.IngredientEntry> buildIngredients(
            int ni, ListNode node, List<ListNode> nodes, int outputCount) {
        List<AutoCraftPayload.IngredientEntry> consume = new ArrayList<>();
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= node.depth) break;
            if (child.depth != node.depth + 1 || child.needed <= 0) continue;
            int ingNeeded = (int) Math.ceil((double) child.needed * outputCount / node.needed);
            Item childItem = ITEM_ID_CACHE.computeIfAbsent(child.itemId, k -> Registries.ITEM.get(Identifier.of(k)));
            List<Map.Entry<Item, Integer>> available = new ArrayList<>(countInventoryByType(childItem).entrySet());
            available.sort((a, b) -> b.getValue() - a.getValue());
            int rem = ingNeeded;
            for (Map.Entry<Item, Integer> e : available) {
                if (rem <= 0) break;
                int use = Math.min(rem, e.getValue());
                consume.add(new AutoCraftPayload.IngredientEntry(
                        Registries.ITEM.getId(e.getKey()).toString(), use));
                rem -= use;
            }
        }
        return consume;
    }

    private static int computeMaxCraftableChained(int ni, ListNode node, List<ListNode> nodes) {
        long max = Long.MAX_VALUE;
        boolean hasChildren = false;
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= node.depth) break;
            if (child.depth != node.depth + 1 || child.needed <= 0) continue;
            hasChildren = true;
            long actualHave = countForId(child.itemId);
            long canMakeChild = child.broken ? computeMaxCraftableChained(j, child, nodes) : 0;
            long effectiveHave = actualHave + canMakeChild;
            long canMake = effectiveHave * node.needed / child.needed;
            if (canMake < max) max = canMake;
        }
        return hasChildren && max != Long.MAX_VALUE ? (int) max : 0;
    }

    private static boolean canChainCraftForNeed(int ni, ListNode node, List<ListNode> nodes) {
        int stillNeed = Math.max(0, node.needed - effectiveHave(node));
        if (stillNeed == 0) return false;
        return computeMaxCraftableChained(ni, node, nodes) >= 1;
    }

    private static void doChainCraft(int ni, ListNode node) {
        List<ListNode> nodes = GatherState.get().getNodes();
        int stillNeed = Math.max(0, node.needed - effectiveHave(node));
        if (stillNeed == 0) return;
        int maxChain = computeMaxCraftableChained(ni, node, nodes);
        if (maxChain <= 0) return;
        doChainCraftInternal(ni, node, nodes, Math.min(stillNeed, maxChain), new HashMap<>());
    }

    private static void doChainCraftMax(int ni, ListNode node) {
        List<ListNode> nodes = GatherState.get().getNodes();
        int maxChain = computeMaxCraftableChained(ni, node, nodes);
        if (maxChain <= 0) return;
        doChainCraftInternal(ni, node, nodes, maxChain, new HashMap<>());
    }

    private static void doChainCraftInternal(int ni, ListNode node, List<ListNode> nodes, int outputCount,
                                             Map<String, Integer> virtualInv) {
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= node.depth) break;
            if (child.depth != node.depth + 1 || child.needed <= 0) continue;
            int ingNeeded = (int) Math.ceil((double) child.needed * outputCount / node.needed);
            int have = countForId(child.itemId) + virtualInv.getOrDefault(child.itemId, 0);
            int toMake = Math.max(0, ingNeeded - have);
            if (toMake > 0 && child.broken) {
                doChainCraftInternal(j, child, nodes, toMake, virtualInv);
                virtualInv.merge(child.itemId, toMake, Integer::sum);
            }
        }
        List<AutoCraftPayload.IngredientEntry> consume = new ArrayList<>();
        for (int j = ni + 1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth <= node.depth) break;
            if (child.depth != node.depth + 1 || child.needed <= 0) continue;
            int ingNeeded = (int) Math.ceil((double) child.needed * outputCount / node.needed);
            int fromVirtual = Math.min(ingNeeded, virtualInv.getOrDefault(child.itemId, 0));
            if (fromVirtual > 0) {
                consume.add(new AutoCraftPayload.IngredientEntry(child.itemId, fromVirtual));
                virtualInv.merge(child.itemId, -fromVirtual, Integer::sum);
                ingNeeded -= fromVirtual;
            }
            if (ingNeeded > 0) {
                Item childItem = ITEM_ID_CACHE.computeIfAbsent(child.itemId, k -> Registries.ITEM.get(Identifier.of(k)));
                List<Map.Entry<Item, Integer>> available = new ArrayList<>(countInventoryByType(childItem).entrySet());
                available.sort((a, b) -> b.getValue() - a.getValue());
                int rem = ingNeeded;
                for (Map.Entry<Item, Integer> e : available) {
                    if (rem <= 0) break;
                    int use = Math.min(rem, e.getValue());
                    consume.add(new AutoCraftPayload.IngredientEntry(
                            Registries.ITEM.getId(e.getKey()).toString(), use));
                    rem -= use;
                }
            }
        }
        GatherClientNetworking.sendAutoCraft(node.itemId, outputCount, consume);
    }

    private static int effectiveHave(ListNode node) {
        int raw = countForId(node.itemId);
        return (node.depth == 0) ? Math.max(0, raw - node.baseline) : raw;
    }

    private static int countForId(String itemId) {
        Item it = ITEM_ID_CACHE.computeIfAbsent(itemId, k -> Registries.ITEM.get(Identifier.of(k)));
        if (it == null) return 0;
        int inv = 0;
        for (int c : countInventoryByType(it).values()) inv += c;
        return inv + (GatherSettings.get().countChests ? GatherState.get().getTrackedChestCountMatching(itemId) : 0);
    }

    private static Map<Item, Integer> countInventoryByType(Item neededItem) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return Map.of();
        Set<TagKey<Item>> tags = ITEM_TAG_CACHE.computeIfAbsent(neededItem,
                k -> k.getRegistryEntry().streamTags().collect(Collectors.toSet()));
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item inv = stack.getItem();
            if (inv == neededItem || (!tags.isEmpty() && !Collections.disjoint(tags,
                    ITEM_TAG_CACHE.computeIfAbsent(inv, k -> k.getRegistryEntry().streamTags().collect(Collectors.toSet()))))) {
                result.merge(inv, stack.getCount(), Integer::sum);
            }
            if (inv instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                var container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) continue;
                for (var inner : container.iterateNonEmpty()) {
                    if (inner.isEmpty()) continue;
                    Item innerItem = inner.getItem();
                    if (innerItem == neededItem || (!tags.isEmpty() && !Collections.disjoint(tags,
                            ITEM_TAG_CACHE.computeIfAbsent(innerItem, k -> k.getRegistryEntry().streamTags().collect(Collectors.toSet()))))) {
                        result.merge(innerItem, inner.getCount(), Integer::sum);
                    }
                }
            }
        }
        return result;
    }
}
