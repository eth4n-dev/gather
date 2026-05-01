package com.gather.client;

import com.gather.GatherMod;
import com.gather.client.mixin.HandledScreenAccessor;
import com.gather.network.AutoCraftPayload;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
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
    private static final Identifier DARK_MODE_MARKER = Identifier.of(GatherMod.MOD_ID, "dark_mode_enabled.txt");

    private record CraftEntry(String itemId, List<Integer> indices) {}
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
            panelScroll = 0;
            panelOpen = false;
            panelAnim = 0.0F;

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                hoveredLines = null;
                craftCache = buildQuickCraftList();
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
        int alpha = 0xFF;
        Skin skin = skin();

        renderPanel(ctx, panelX, panelY, PANEL_W, PANEL_H, alpha, skin);
        ctx.drawText(MinecraftClient.getInstance().textRenderer,
                Text.literal("Crafting Goals"), panelX + PANEL_PAD, panelY + 6, skin.titleColor, false);
        renderCloseButton(ctx, mx, my, panelX + PANEL_W - PANEL_PAD - CLOSE_SIZE, panelY + 5);

        int rowsTop = panelY + 24;
        int rowsVisible = Math.max(1, (PANEL_H - 30) / ROW_H);
        int maxScroll = Math.max(0, craftCache.size() - rowsVisible);
        panelScroll = Math.max(0, Math.min(panelScroll, maxScroll));

        if (craftCache.isEmpty()) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer,
                    Text.literal("No crafting-table items ready"), panelX + PANEL_PAD, rowsTop + 8, skin.secondaryTextColor, false);
            return;
        }

        List<ListNode> nodes = GatherState.get().getNodes();
        MinecraftClient client = MinecraftClient.getInstance();
        for (int row = 0; row < rowsVisible && row + panelScroll < craftCache.size(); row++) {
            CraftEntry ce = craftCache.get(row + panelScroll);
            Item item = Registries.ITEM.get(Identifier.of(ce.itemId()));
            if (item == null) continue;

            int rowY = rowsTop + row * ROW_H;
            boolean rowHov = mx >= panelX + 2 && mx <= panelX + PANEL_W - 2 && my >= rowY && my <= rowY + ROW_H - 2;
            renderSlotRow(ctx, panelX + 5, rowY, PANEL_W - 13, ROW_H - 2, rowHov, skin);
            int itemSlotX = panelX + 9;
            int itemSlotY = rowY + 4;
            renderItemSlot(ctx, itemSlotX, itemSlotY, skin);
            ctx.drawItem(item.getDefaultStack(), itemSlotX + 1, itemSlotY + 1);
            ctx.drawText(client.textRenderer, item.getName(), panelX + 33, rowY + 4, skin.textColor, false);

            int stillNeed = 0;
            int maxCraft = 0;
            for (int idx : ce.indices()) {
                if (idx >= nodes.size()) continue;
                stillNeed += Math.max(0, nodes.get(idx).needed - effectiveHave(nodes.get(idx)));
                maxCraft  += computeMaxCraftableChained(idx, nodes.get(idx), nodes);
            }
            ctx.drawText(client.textRenderer,
                    Text.literal("need " + stillNeed + "  max " + maxCraft),
                    panelX + 33, rowY + 15, skin.secondaryTextColor, false);

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
            ctx.fill(barX, barY, barX + 3, barY + barH, skin.scrollTrackColor);
            ctx.fill(barX, thumbY, barX + 3, thumbY + thumbH, skin.scrollThumbColor);
            ctx.fill(barX + 1, thumbY + 1, barX + 3, thumbY + thumbH, skin.scrollThumbShadowColor);
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
        renderIconButton(ctx, x, y, hovered || panelOpen);
        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal(panelOpen ? "Close crafting goals" : "Open crafting goals"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderCloseButton(DrawContext ctx, int mx, int my, int x, int y) {
        boolean hovered = inside(mx, my, x, y, CLOSE_SIZE, CLOSE_SIZE);
        Skin skin = skin();
        ctx.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, hovered ? skin.closeHoverColor : skin.closeColor);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, Text.literal("x"), x + 2, y, skin.textColor, false);
        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal("Close"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderIconButton(DrawContext ctx, int x, int y, boolean active) {
        int fillTop = active ? 0xFF4F8FD8 : 0xFF2F5F9C;
        int fillBottom = active ? 0xFF244E86 : 0xFF183A64;
        ctx.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, 0xFF07111F);
        ctx.fill(x + 1, y + 1, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, fillBottom);
        ctx.fill(x + 2, y + 2, x + BUTTON_SIZE - 2, y + 8, fillTop);
        ctx.fill(x + 2, y + 8, x + BUTTON_SIZE - 2, y + BUTTON_SIZE - 2, fillBottom);
        ctx.fill(x + 2, y + 2, x + BUTTON_SIZE - 2, y + 3, active ? 0xFFC7E8FF : 0xFF7FB2EA);
        ctx.fill(x + BUTTON_SIZE - 2, y + 2, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, 0xFF07111F);
        ctx.fill(x + 2, y + BUTTON_SIZE - 2, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, 0xFF07111F);

        int bundle = active ? 0xFFFFF1C7 : 0xFFE4C986;
        int bundleDark = active ? 0xFF9A6A2D : 0xFF6E4A25;
        int string = active ? 0xFFFFFFFF : 0xFFD8E8FF;
        ctx.fill(x + 5, y + 5, x + 11, y + 6, bundleDark);
        ctx.fill(x + 4, y + 6, x + 12, y + 10, bundle);
        ctx.fill(x + 5, y + 10, x + 11, y + 12, bundle);
        ctx.fill(x + 5, y + 11, x + 11, y + 13, bundleDark);
        ctx.fill(x + 4, y + 8, x + 5, y + 11, bundleDark);
        ctx.fill(x + 11, y + 8, x + 12, y + 11, bundleDark);
        ctx.fill(x + 6, y + 4, x + 10, y + 5, string);
        ctx.fill(x + 7, y + 3, x + 9, y + 4, string);
    }

    private static void renderPanel(DrawContext ctx, int x, int y, int w, int h, int alpha, Skin skin) {
        ctx.fill(x + 3, y + 3, x + w + 3, y + h + 3, skin.shadowColor);
        ctx.fill(x, y, x + w, y + h, skin.panelColor);
        ctx.fill(x, y, x + w, y + 1, skin.lightEdgeColor);
        ctx.fill(x, y, x + 1, y + h, skin.lightEdgeColor);
        ctx.fill(x + w - 1, y, x + w, y + h, skin.darkEdgeColor);
        ctx.fill(x, y + h - 1, x + w, y + h, skin.darkEdgeColor);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, skin.innerEdgeColor);
        ctx.fill(x + 1, y + 1, x + 2, y + h - 1, skin.innerEdgeColor);
        ctx.fill(x + 4, y + 20, x + w - 4, y + 21, skin.dividerColor);
    }

    private static void renderSlotRow(DrawContext ctx, int x, int y, int w, int h, boolean hovered, Skin skin) {
        ctx.fill(x, y, x + w, y + h, skin.rowDarkEdgeColor);
        ctx.fill(x + 2, y + 2, x + w - 1, y + h - 1, hovered ? skin.rowHoverColor : skin.rowColor);
        ctx.fill(x, y, x + w, y + 1, skin.rowDarkEdgeColor);
        ctx.fill(x, y, x + 1, y + h, skin.rowDarkEdgeColor);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, skin.rowDarkEdgeColor);
        ctx.fill(x + 1, y + 1, x + 2, y + h - 1, skin.rowDarkEdgeColor);
        ctx.fill(x + w - 2, y + 1, x + w, y + h, skin.rowLightEdgeColor);
        ctx.fill(x + 1, y + h - 2, x + w, y + h, skin.rowLightEdgeColor);
    }

    private static void renderItemSlot(DrawContext ctx, int x, int y, Skin skin) {
        ctx.fill(x, y, x + 18, y + 18, skin.itemSlotColor);
        ctx.fill(x, y, x + 18, y + 1, skin.itemSlotDarkEdgeColor);
        ctx.fill(x, y, x + 1, y + 18, skin.itemSlotDarkEdgeColor);
        ctx.fill(x + 17, y, x + 18, y + 18, skin.itemSlotLightEdgeColor);
        ctx.fill(x, y + 17, x + 18, y + 18, skin.itemSlotLightEdgeColor);
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

    private static boolean darkMode() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getResourceManager().getResource(DARK_MODE_MARKER).isPresent();
    }

    private static Skin skin() {
        return darkMode() ? Skin.DARK : Skin.LIGHT;
    }

    private record Skin(int panelColor, int lightEdgeColor, int darkEdgeColor, int innerEdgeColor, int dividerColor,
                        int shadowColor,
                        int rowColor, int rowHoverColor, int rowDarkEdgeColor, int rowLightEdgeColor,
                        int itemSlotColor, int itemSlotDarkEdgeColor, int itemSlotLightEdgeColor,
                        int textColor, int titleColor, int secondaryTextColor, int closeColor, int closeHoverColor,
                        int scrollTrackColor, int scrollThumbColor, int scrollThumbShadowColor,
                        int buttonFrameColor, int buttonHoverFrameColor) {
        private static final Skin LIGHT = new Skin(
                0xEE0D1826, 0xFF334455, 0xFF07111F, 0xFF1D3045, 0xFF334455,
                0x66000000,
                0x33223344, 0x88334466, 0xFF101C2C, 0xFF3A5570,
                0xFF162335, 0xFF07111F, 0xFF3A5570,
                0xFFCCDDFF, 0xFFCCDDFF, 0xFF8DA1B8, 0xFF17283B, 0xFF253C56,
                0x22445566, 0xFF445566, 0xFF223344,
                0xFF000000, 0xFFFFFFFF);
        private static final Skin DARK = new Skin(
                0xF00A101A, 0xFF26384C, 0xFF03070C, 0xFF142236, 0xFF26384C,
                0x77000000,
                0x44203042, 0x99507091, 0xFF0A101A, 0xFF445B76,
                0xFF101A28, 0xFF03070C, 0xFF445B76,
                0xFFE1ECFF, 0xFFFFFFFF, 0xFF9EB3CA, 0xFF111A27, 0xFF253C56,
                0x33445566, 0xFF5D7188, 0xFF2D3F55,
                0xFF000000, 0xFFFFFFFF);
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
        ordered.sort((a, b) -> Integer.compare(nodes.get(b).depth, nodes.get(a).depth));

        // Merge duplicate itemIds into one CraftEntry (preserves deepest-first order)
        Map<String, CraftEntry> byItem = new LinkedHashMap<>();
        for (int j : ordered) {
            String id = nodes.get(j).itemId;
            byItem.computeIfAbsent(id, k -> new CraftEntry(k, new ArrayList<>())).indices().add(j);
        }
        return new ArrayList<>(byItem.values());
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
            Item childItem = Registries.ITEM.get(Identifier.of(child.itemId));
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
                Item childItem = Registries.ITEM.get(Identifier.of(child.itemId));
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
        Item it = Registries.ITEM.get(Identifier.of(itemId));
        if (it == null) return 0;
        int inv = countInventoryByType(it).values().stream().mapToInt(Integer::intValue).sum();
        return inv + (GatherSettings.get().countChests ? GatherState.get().getTrackedChestCountMatching(itemId) : 0);
    }

    private static Map<Item, Integer> countInventoryByType(Item neededItem) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return Map.of();
        Set<TagKey<Item>> tags = neededItem.getRegistryEntry()
                .streamTags().collect(Collectors.toSet());
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item inv = stack.getItem();
            if (inv == neededItem
                    || (!tags.isEmpty() && inv.getRegistryEntry().streamTags().anyMatch(tags::contains))) {
                result.merge(inv, stack.getCount(), Integer::sum);
            }
        }
        return result;
    }
}
