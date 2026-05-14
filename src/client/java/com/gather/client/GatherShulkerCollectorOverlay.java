package com.gather.client;

import com.gather.client.mixin.HandledScreenAccessor;
import com.gather.mixin.ShulkerBoxScreenHandlerAccessor;
import com.gather.network.CollectorStatePayload;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GatherShulkerCollectorOverlay {

    private static final Identifier SEARCH_ICON_TEX = Identifier.fromNamespaceAndPath("gather", "textures/gui/search_icon.png");
    private static final int BUTTON_W = 100;
    private static final int BUTTON_H = 14;
    private static final int PANEL_W = 178;
    private static final int PANEL_H = 92;
    private static final int ROW_H = 12;
    private static boolean collectorActive = false;
    private static boolean allMode = true;
    private static boolean leaveOne = true;
    private static boolean serverStateLoaded = false;
    private static boolean certainOpen = false;
    private static boolean filterGoalsOnly = true;
    private static int scroll = 0;
    private static EditBox searchField = null;
    private static final Set<String> selectedItems = new LinkedHashSet<>();
    private static final Map<Long, CompoundTag> placedShulkerUiState = new HashMap<>();
    private static List<Component> hoveredLines = null;
    private static int tooltipX, tooltipY;
    private static List<String> allItemsCache = null;
    private static final Map<String, String> LABEL_CACHE = new HashMap<>();
    private static List<String> filteredCache = List.of();
    private static boolean filteredDirty = true;
    private static long filteredMs = 0;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!GatherSettings.get().enabled) return;
            if (!(screen instanceof AbstractContainerScreen<?> handled)) return;
            if (!(handled.getMenu() instanceof ShulkerBoxMenu)) return;
            syncOpenShulkerState(client, handled);
            GatherClientNetworking.requestCollectorState();
            certainOpen = false;
            filterGoalsOnly = true;
            scroll = 0;
            filteredDirty = true;
            searchField = new EditBox(client.font, 0, 0, PANEL_W - 12, 16, Component.literal("Search"));
            searchField.setMaxLength(32);
            searchField.setHint(Component.literal("Search needed items"));
            searchField.setResponder(value -> { scroll = 0; filteredDirty = true; });
            searchField.setVisible(false);
            Screens.getWidgets(screen).add(searchField);

            ScreenEvents.afterExtract(screen).register((s, ctx, mx, my, delta) -> {
                hoveredLines = null;
                syncSearchField((AbstractContainerScreen<?>) s);
                renderOverlay((AbstractContainerScreen<?>) s, ctx, mx, my);
                if (hoveredLines != null) {
                    ctx.setComponentTooltipForNextFrame(client.font, hoveredLines, tooltipX, tooltipY);
                }
            });

            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                int mx = (int) click.x();
                int my = (int) click.y();
                if (handleClick((AbstractContainerScreen<?>) s, mx, my)) {
                    playClick();
                    return false;
                }
                return true;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
                if (!certainOpen) return true;
                int mx = (int) mouseX;
                int my = (int) mouseY;
                int[] p = panelBounds((AbstractContainerScreen<?>) s);
                if (!inside(mx, my, p[0], p[1] + 33, PANEL_W, PANEL_H - 33)) return true;
                List<String> rows = filteredNeededItems();
                int visibleRows = Math.max(1, (PANEL_H - 31) / ROW_H);
                int maxScroll = Math.max(0, rows.size() - visibleRows);
                scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount)));
                return false;
            });

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) -> {
                if (!certainOpen) return true;
                if (searchField != null && searchField.isFocused()) return true;
                return handleKeyPress(input);
            });

            Container initInv = ((ShulkerBoxScreenHandlerAccessor) handled.getMenu()).gather$getInventory();
            final long closePlacedPos = initInv instanceof ShulkerBoxBlockEntity initShulker
                    ? initShulker.getBlockPos().asLong()
                    : -1L;
            ScreenEvents.remove(screen).register(s -> {
                WorldHighlightRenderer.resetCollectorLabelCache();
                if (closePlacedPos != -1L) GatherClientNetworking.requestTrackedChests(java.util.Set.of(closePlacedPos));
            });
        });
    }

    private static void renderOverlay(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx, int mx, int my) {
        if (!GatherSettings.get().enabled) return;
        renderButton(screen, ctx, mx, my, 0, "Collector",
                serverStateLoaded && collectorActive ? 0xFF66FFD6 : 0xFFAAB7C4);
        renderButton(screen, ctx, mx, my, 1, allMode ? "Mode: All Goals" : "Mode: Certain",
                allMode ? 0xFFE6EFF7 : 0xFFFFD37A);
        renderButton(screen, ctx, mx, my, 2, "Keep 1 item",
                serverStateLoaded && leaveOne ? 0xFF66FFD6 : 0xFFAAB7C4);
        if (!allMode) renderArrowButton(screen, ctx, mx, my);
        if (certainOpen) renderPanel(screen, ctx, mx, my);
    }

    private static void renderButton(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx, int mx, int my,
                                     int index, String label, int textColor) {
        int[] b = buttonBounds(screen, index);
        boolean hovered = inside(mx, my, b[0], b[1], BUTTON_W, BUTTON_H);
        boolean active = serverStateLoaded && (index == 0 ? collectorActive : index == 1 ? !allMode : leaveOne);
        GatherTheme.drawNineSlice(ctx, shulkerButtonTexture(index, active, hovered),
                b[0], b[1], BUTTON_W, BUTTON_H);
        int color = GatherTheme.isVanilla()
                ? (active ? 0xFF245C24 : GatherTheme.textButton())
                : textColor;
        drawThemeText(ctx, Component.literal(label), b[0] + 5, b[1] + 3, color);

        if (hovered) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = switch (index) {
                case 0 -> List.of(Component.literal("Toggle Gather collector"),
                        Component.literal("Picked-up matching items move into this shulker."));
                case 1 -> List.of(Component.literal("All Goals: collect everything needed."),
                        Component.literal("Certain: pick specific items (goals or any)"));
                default -> List.of(Component.literal("Keep 1 item"),
                        Component.literal("Checked: keep one matching stack item in inventory."),
                        Component.literal("Unchecked: move every matching item into the shulker."));
            };
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderArrowButton(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx, int mx, int my) {
        int[] b = arrowButtonBounds(screen);
        boolean hovered = inside(mx, my, b[0], b[1], 16, BUTTON_H);
        GatherTheme.drawNineSlice(ctx, shulkerButtonTexture(1, certainOpen, hovered),
                b[0], b[1], 16, BUTTON_H);
        drawSearchIcon(ctx, b[0] + 2, b[1] + 2);
        if (hovered) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = List.of(Component.literal(certainOpen ? "Collapse item picker" : "Open item picker"));
            tooltipX = mx; tooltipY = my;
        }
    }

    private static void renderPanel(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx, int mx, int my) {
        Minecraft client = Minecraft.getInstance();
        int[] p = panelBounds(screen);
        GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_PANEL, p[0], p[1], PANEL_W, PANEL_H);

        String selected = selectedItems.isEmpty() ? "None selected" : selectedItems.size() + " selected";
        drawThemeText(ctx, Component.literal(selected), p[0] + 6, p[1] + 6, GatherTheme.textPrimary());

        // Filter toggle: Goals / All
        int toggleX = p[0] + PANEL_W - 54;
        int toggleY = p[1] + 3;
        boolean toggleHov = inside(mx, my, toggleX, toggleY, 48, 14);
        GatherTheme.drawNineSlice(ctx, shulkerButtonTexture(1, !filterGoalsOnly, toggleHov),
                toggleX, toggleY, 48, 14);
        drawThemeText(ctx, Component.literal(filterGoalsOnly ? "Goals" : "All"), toggleX + 5, toggleY + 3,
                filterGoalsOnly ? GatherTheme.textButton() : (GatherTheme.isVanilla() ? 0xFF245C24 : 0xFF66FFD6));
        if (toggleHov) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = List.of(Component.literal(filterGoalsOnly ? "Showing goal items only" : "Showing all items"));
            tooltipX = mx; tooltipY = my;
        }

        if (searchField != null) searchField.extractWidgetRenderState(ctx, mx, my, 0.0F);

        List<String> rows = filteredNeededItems();
        int startY = p[1] + 42;
        int visibleRows = Math.max(1, (PANEL_H - 44) / ROW_H);
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), scroll));
        for (int i = 0; i < visibleRows && i + scroll < rows.size(); i++) {
            String id = rows.get(i + scroll);
            int y = startY + i * ROW_H;
            boolean hovered = inside(mx, my, p[0] + 4, y - 1, PANEL_W - 8, ROW_H);
            boolean selectedRow = selectedItems.contains(id);
            int rowColor = selectedRow ? 0x6633AA88 : (hovered ? 0x442E4054 : 0x00000000);
            if (rowColor != 0) GatherTheme.drawNineSlice(ctx,
                    selectedRow ? GatherTheme.MENU_ROW_COMPLETE : GatherTheme.MENU_ROW_HOVER,
                    p[0] + 4, y - 1, PANEL_W - 8, ROW_H);
            String label = itemLabel(id);
            if (client.font.width(label) > PANEL_W - 28) {
                label = client.font.plainSubstrByWidth(label, PANEL_W - 36) + "...";
            }
            drawThemeText(ctx, Component.literal((selectedRow ? "* " : "  ") + label),
                    p[0] + 7, y,
                    selectedRow ? (GatherTheme.isVanilla() ? 0xFF245C24 : 0xFF66FFD6) : GatherTheme.textPrimary());
            if (hovered) {
                ctx.requestCursor(CursorTypes.POINTING_HAND);
                hoveredLines = List.of(
                        Component.literal(itemLabel(id)),
                        Component.literal(selectedRow ? "Click to stop collecting this item." : "Click to collect this item."),
                        Component.literal("ID: " + id));
                tooltipX = mx;
                tooltipY = my;
            }
        }

        if (rows.isEmpty()) {
            String emptyMsg = filterGoalsOnly ? "No goal items" : "No items found";
            drawThemeText(ctx, Component.literal(emptyMsg), p[0] + 7, startY, GatherTheme.textMuted());
        }
    }

    private static boolean handleClick(AbstractContainerScreen<?> screen, int mx, int my) {
        if (!GatherSettings.get().enabled) return false;
        if (!serverStateLoaded) return true;
        int[] main = buttonBounds(screen, 0);
        if (inside(mx, my, main[0], main[1], BUTTON_W, BUTTON_H)) {
            collectorActive = !collectorActive;
            sendConfig();
            return true;
        }

        int[] mode = buttonBounds(screen, 1);
        if (inside(mx, my, mode[0], mode[1], BUTTON_W, BUTTON_H)) {
            allMode = !allMode;
            certainOpen = !allMode;
            scroll = 0;
            sendConfig();
            return true;
        }

        int[] leaveOneBtn = buttonBounds(screen, 2);
        if (inside(mx, my, leaveOneBtn[0], leaveOneBtn[1], BUTTON_W, BUTTON_H)) {
            leaveOne = !leaveOne;
            sendConfig();
            return true;
        }

        if (!allMode) {
            int[] arrow = arrowButtonBounds(screen);
            if (inside(mx, my, arrow[0], arrow[1], 16, BUTTON_H)) {
                certainOpen = !certainOpen;
                return true;
            }
        }

        if (!certainOpen) return false;
        int[] p = panelBounds(screen);
        if (!inside(mx, my, p[0], p[1], PANEL_W, PANEL_H)) {
            if (searchField != null) searchField.setFocused(false);
            return false;
        }
        // Filter toggle click
        int toggleX = p[0] + PANEL_W - 54;
        int toggleY = p[1] + 3;
        if (inside(mx, my, toggleX, toggleY, 48, 14)) {
            filterGoalsOnly = !filterGoalsOnly;
            scroll = 0;
            filteredDirty = true;
            if (searchField != null) {
                searchField.setHint(Component.literal(filterGoalsOnly ? "Search goals..." : "Search all items..."));
            }
            return true;
        }

        if (inside(mx, my, p[0] + 6, p[1] + 22, PANEL_W - 12, 16)) return false;

        List<String> rows = filteredNeededItems();
        int row = (my - (p[1] + 42)) / ROW_H;
        int visibleRows = Math.max(1, (PANEL_H - 44) / ROW_H);
        if (row >= 0 && row < visibleRows && row + scroll < rows.size()) {
            String id = rows.get(row + scroll);
            if (!selectedItems.remove(id)) selectedItems.add(id);
            sendConfig();
            return true;
        }
        return true;
    }

    private static boolean handleKeyPress(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
            certainOpen = false;
            if (searchField != null) {
                searchField.setFocused(false);
                searchField.setVisible(false);
            }
            return false;
        }
        return true;
    }

    private static List<String> filteredNeededItems() {
        long now = System.currentTimeMillis();
        if (filteredDirty || now - filteredMs > 1000) {
            filteredCache = buildFilteredNeededItems();
            filteredDirty = false;
            filteredMs = now;
        }
        return filteredCache;
    }

    private static List<String> buildFilteredNeededItems() {
        String q = searchField == null ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        List<String> source = filterGoalsOnly ? currentNeededItems() : getAllItems();
        List<String> result = new ArrayList<>();
        for (String id : source) {
            String label = itemLabel(id).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q) || label.contains(q)) {
                result.add(id);
            }
        }
        return result;
    }

    private static List<String> getAllItems() {
        if (allItemsCache == null) {
            allItemsCache = BuiltInRegistries.ITEM.stream()
                    .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                    .sorted((a, b) -> itemLabel(a).compareToIgnoreCase(itemLabel(b)))
                    .toList();
        }
        return allItemsCache;
    }

    private static List<String> currentNeededItems() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return List.of();
        return GatherState.get().getChestScanTargets(itemId -> 0);
    }

    private static String itemLabel(String id) {
        return LABEL_CACHE.computeIfAbsent(id, k -> {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(k));
            return item != null ? com.gather.client.GatherUi.itemName(item).getString() : k;
        });
    }

    private static void sendConfig() {
        cacheOpenPlacedShulkerState();
        GatherClientNetworking.updateCollectorTargets(currentNeededItems());
        GatherClientNetworking.configureCollector(collectorActive, allMode, new ArrayList<>(selectedItems), leaveOne);
    }

    private static GatherTheme.NineSlice shulkerButtonTexture(int index, boolean active, boolean hovered) {
        if (active && index == 1) {
            return hovered ? GatherTheme.SHULKER_BUTTON_ORANGE_HOVER : GatherTheme.SHULKER_BUTTON_ORANGE;
        }
        if (active) {
            return hovered ? GatherTheme.SHULKER_BUTTON_ACTIVE_HOVER : GatherTheme.SHULKER_BUTTON_ACTIVE;
        }
        return hovered ? GatherTheme.SHULKER_BUTTON_HOVER : GatherTheme.SHULKER_BUTTON;
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static int[] buttonBounds(AbstractContainerScreen<?> screen, int index) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int x = accessor.gather$getX() + accessor.gather$getBackgroundWidth() - BUTTON_W - 6;
        int y = accessor.gather$getY() - 52 + index * (BUTTON_H + 3);
        return new int[]{x, y};
    }

    private static int[] arrowButtonBounds(AbstractContainerScreen<?> screen) {
        int[] mode = buttonBounds(screen, 1);
        return new int[]{mode[0] - 18, mode[1]};
    }

    private static void drawSearchIcon(GuiGraphicsExtractor ctx, int x, int y) {
        ctx.blit(RenderPipelines.GUI_TEXTURED, SEARCH_ICON_TEX, x, y, 0.0f, 0.0f, 13, 13, 13, 13);
    }

    private static void drawThemeText(GuiGraphicsExtractor ctx, Component text, int x, int y, int color) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        if (GatherTheme.isVanilla()) ctx.text(client.font, text, x, y, color, false);
        else ctx.text(client.font, text, x, y, color);
    }

    private static int[] panelBounds(AbstractContainerScreen<?> screen) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int x = accessor.gather$getX() + accessor.gather$getBackgroundWidth() - PANEL_W;
        int y = Math.max(6, accessor.gather$getY() - PANEL_H - 39);
        return new int[]{x, y};
    }

    private static void syncSearchField(AbstractContainerScreen<?> screen) {
        if (searchField == null) return;
        int[] p = panelBounds(screen);
        searchField.setX(p[0] + 6);
        searchField.setY(p[1] + 22);
        searchField.setWidth(PANEL_W - 12);
        searchField.setVisible(certainOpen);
        if (!certainOpen) searchField.setFocused(false);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void syncOpenShulkerState(Minecraft client, AbstractContainerScreen<?> screen) {
        collectorActive = false;
        allMode = true;
        leaveOne = false;
        serverStateLoaded = false;
        selectedItems.clear();
        if (client.player == null || !(screen.getMenu() instanceof ShulkerBoxMenu handler)) return;

        Container inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        CompoundTag data = null;
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            CustomData cd = shulker.components().get(DataComponents.CUSTOM_DATA);
            if (cd != null) data = cd.copyTag();
        } else {
            ItemStack backingStack = findUniqueOpenShulkerStack(client, inventory);
            if (!backingStack.isEmpty()) {
                CustomData cd = backingStack.get(DataComponents.CUSTOM_DATA);
                if (cd != null) data = cd.copyTag();
            }
        }
        if (data == null) return;
        collectorActive = data.getBooleanOr("gather_collector", false);
        allMode = !"certain".equals(data.getStringOr("gather_collector_mode", "all"));
        leaveOne = data.getBooleanOr("gather_collector_leave_one", true);
        selectedItems.addAll(splitLines(data.getStringOr("gather_collector_items", "")));
        serverStateLoaded = data.contains("gather_collector");
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            placedShulkerUiState.put(shulker.getBlockPos().asLong(), data.copy());
        }
    }

    public static void applyServerState(CollectorStatePayload payload) {
        collectorActive = payload.enabled();
        allMode = payload.allMode();
        leaveOne = payload.leaveOne();
        selectedItems.clear();
        selectedItems.addAll(payload.selectedItemIds());
        if (allMode) certainOpen = false;
        scroll = 0;
        serverStateLoaded = true;
        WorldHighlightRenderer.resetCollectorLabelCache();
        cacheOpenPlacedShulkerState();
        refreshOpenPlacedShulkerContents();
        if (searchField != null) {
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof AbstractContainerScreen<?> screen) syncSearchField(screen);
        }
    }

    private static void refreshOpenPlacedShulkerContents() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        if (!(screen.getMenu() instanceof ShulkerBoxMenu handler)) return;
        Container inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        if (!(inventory instanceof ShulkerBoxBlockEntity shulker)) return;
        GatherClientNetworking.requestTrackedChests(java.util.Set.of(shulker.getBlockPos().asLong()));
    }

    private static void cacheOpenPlacedShulkerState() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        if (!(screen.getMenu() instanceof ShulkerBoxMenu handler)) return;
        Container inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        if (!(inventory instanceof ShulkerBoxBlockEntity shulker)) return;
        CompoundTag data = new CompoundTag();
        data.putBoolean("gather_collector", collectorActive);
        data.putString("gather_collector_mode", allMode ? "all" : "certain");
        data.putString("gather_collector_items", String.join("\n", selectedItems));
        data.putBoolean("gather_collector_leave_one", leaveOne);
        placedShulkerUiState.put(shulker.getBlockPos().asLong(), data);
    }

    private static ItemStack findUniqueOpenShulkerStack(Minecraft client, Container openInventory) {
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        ItemStack selected = client.player.getInventory().getItem(selectedSlot);
        if (isShulkerBox(selected)
                && (hasCollectorIdentity(selected) || containerMatchesInventory(selected.get(DataComponents.CONTAINER), openInventory))) {
            return selected;
        }

        ItemStack match = ItemStack.EMPTY;
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            if (i == selectedSlot) continue;
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!isShulkerBox(stack)) continue;
            if (!containerMatchesInventory(stack.get(DataComponents.CONTAINER), openInventory)) continue;
            if (!match.isEmpty()) return ItemStack.EMPTY;
            match = stack;
        }
        return match;
    }

    private static boolean hasCollectorIdentity(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag nbt = customData.copyTag();
        return nbt.getBooleanOr("gather_collector", false)
                || !nbt.getStringOr("gather_collector_id", "").isBlank();
    }

    private static boolean containerMatchesInventory(ItemContainerContents container, Container inventory) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        if (container != null) container.copyInto(contents);
        for (int i = 0; i < Math.min(contents.size(), inventory.getContainerSize()); i++) {
            if (!ItemStack.matches(contents.get(i), inventory.getItem(i))) return false;
        }
        return true;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String id : raw.split("\\n")) {
            if (!id.isBlank()) result.add(id.trim());
        }
        return result;
    }
}
