package com.gather.client;

import com.gather.client.mixin.HandledScreenAccessor;
import com.gather.mixin.ShulkerBoxScreenHandlerAccessor;
import com.gather.network.CollectorStatePayload;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GatherShulkerCollectorOverlay {

    private static final Identifier SEARCH_ICON_TEX = Identifier.of("gather", "textures/gui/search_icon.png");
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
    private static TextFieldWidget searchField = null;
    private static final Set<String> selectedItems = new LinkedHashSet<>();
    private static final Map<Long, NbtCompound> placedShulkerUiState = new HashMap<>();
    private static List<Text> hoveredLines = null;
    private static int tooltipX, tooltipY;
    private static List<String> allItemsCache = null;
    private static final Map<String, String> LABEL_CACHE = new HashMap<>();
    private static List<String> filteredCache = List.of();
    private static boolean filteredDirty = true;
    private static long filteredMs = 0;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!GatherSettings.get().enabled) return;
            if (!(screen instanceof HandledScreen<?> handled)) return;
            if (!(handled.getScreenHandler() instanceof ShulkerBoxScreenHandler)) return;
            syncOpenShulkerState(client, handled);
            GatherClientNetworking.requestCollectorState();
            certainOpen = false;
            filterGoalsOnly = true;
            scroll = 0;
            filteredDirty = true;
            searchField = new TextFieldWidget(client.textRenderer, 0, 0, PANEL_W - 12, 16, Text.literal("Search"));
            searchField.setMaxLength(32);
            searchField.setPlaceholder(Text.literal("Search needed items"));
            searchField.setChangedListener(value -> { scroll = 0; filteredDirty = true; });
            searchField.setVisible(false);
            Screens.getButtons(screen).add(searchField);

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                hoveredLines = null;
                syncSearchField((HandledScreen<?>) s);
                renderOverlay((HandledScreen<?>) s, ctx, mx, my);
                if (hoveredLines != null) {
                    ctx.drawTooltip(client.textRenderer, hoveredLines, tooltipX, tooltipY);
                }
            });

            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                int mx = (int) click.x();
                int my = (int) click.y();
                if (handleClick((HandledScreen<?>) s, mx, my)) {
                    playClick();
                    return false;
                }
                return true;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
                if (!certainOpen) return true;
                int mx = (int) mouseX;
                int my = (int) mouseY;
                int[] p = panelBounds((HandledScreen<?>) s);
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

            Inventory initInv = ((ShulkerBoxScreenHandlerAccessor) handled.getScreenHandler()).gather$getInventory();
            final long closePlacedPos = initInv instanceof ShulkerBoxBlockEntity initShulker
                    ? initShulker.getPos().asLong()
                    : -1L;
            ScreenEvents.remove(screen).register(s -> {
                WorldHighlightRenderer.resetCollectorLabelCache();
                if (closePlacedPos != -1L) GatherClientNetworking.requestTrackedChests(java.util.Set.of(closePlacedPos));
            });
        });
    }

    private static void renderOverlay(HandledScreen<?> screen, DrawContext ctx, int mx, int my) {
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

    private static void renderButton(HandledScreen<?> screen, DrawContext ctx, int mx, int my,
                                     int index, String label, int textColor) {
        int[] b = buttonBounds(screen, index);
        boolean hovered = inside(mx, my, b[0], b[1], BUTTON_W, BUTTON_H);
        boolean active = serverStateLoaded && (index == 0 ? collectorActive : index == 1 ? !allMode : leaveOne);
        int bg = active ? (hovered ? 0xDD005544 : 0xBB004433) : (hovered ? 0xDD223355 : 0xBB111A28);
        int edge = active ? 0xFF33DDAA : 0xFF445566;
        ctx.fill(b[0], b[1], b[0] + BUTTON_W, b[1] + BUTTON_H, bg);
        ctx.fill(b[0], b[1], b[0] + BUTTON_W, b[1] + 1, edge);
        ctx.fill(b[0], b[1] + BUTTON_H - 1, b[0] + BUTTON_W, b[1] + BUTTON_H, 0x66111122);
        ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                Text.literal(label), b[0] + 5, b[1] + 3, textColor);

        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = switch (index) {
                case 0 -> List.of(Text.literal("Toggle Gather collector"),
                        Text.literal("Picked-up matching items move into this shulker."));
                case 1 -> List.of(Text.literal("All Goals: collect everything needed."),
                        Text.literal("Certain: pick specific items (goals or any)"));
                default -> List.of(Text.literal("Keep 1 item"),
                        Text.literal("Checked: keep one matching stack item in inventory."),
                        Text.literal("Unchecked: move every matching item into the shulker."));
            };
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderArrowButton(HandledScreen<?> screen, DrawContext ctx, int mx, int my) {
        int[] b = arrowButtonBounds(screen);
        boolean hovered = inside(mx, my, b[0], b[1], 16, BUTTON_H);
        int bg = certainOpen ? (hovered ? 0xDD005544 : 0xBB004433) : (hovered ? 0xDD223355 : 0xBB111A28);
        int edge = certainOpen ? 0xFF33DDAA : 0xFF445566;
        ctx.fill(b[0], b[1], b[0] + 16, b[1] + BUTTON_H, bg);
        ctx.fill(b[0], b[1], b[0] + 16, b[1] + 1, edge);
        drawSearchIcon(ctx, b[0] + 2, b[1] + 2);
        if (hovered) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal(certainOpen ? "Collapse item picker" : "Open item picker"));
            tooltipX = mx; tooltipY = my;
        }
    }

    private static void renderPanel(HandledScreen<?> screen, DrawContext ctx, int mx, int my) {
        MinecraftClient client = MinecraftClient.getInstance();
        int[] p = panelBounds(screen);
        ctx.fill(p[0], p[1], p[0] + PANEL_W, p[1] + PANEL_H, 0xEA101722);
        ctx.fill(p[0], p[1], p[0] + PANEL_W, p[1] + 1, 0xFF5C6C80);
        ctx.fill(p[0], p[1] + 20, p[0] + PANEL_W, p[1] + 21, 0x665C6C80);

        String selected = selectedItems.isEmpty() ? "None selected" : selectedItems.size() + " selected";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(selected), p[0] + 6, p[1] + 6, 0xFFE6EFF7);

        // Filter toggle: Goals / All
        int toggleX = p[0] + PANEL_W - 54;
        int toggleY = p[1] + 3;
        boolean toggleHov = inside(mx, my, toggleX, toggleY, 48, 14);
        int toggleBg = filterGoalsOnly
                ? (toggleHov ? 0xDD223355 : 0xBB111A28)
                : (toggleHov ? 0xDD005544 : 0xBB004433);
        int toggleEdge = filterGoalsOnly ? 0xFF445566 : 0xFF33DDAA;
        ctx.fill(toggleX + 1, toggleY + 1, toggleX + 49, toggleY + 15, 0x66101822);
        ctx.fill(toggleX, toggleY, toggleX + 48, toggleY + 14, toggleBg);
        ctx.fill(toggleX, toggleY, toggleX + 48, toggleY + 1, toggleEdge);
        ctx.fill(toggleX, toggleY + 13, toggleX + 48, toggleY + 14, filterGoalsOnly ? 0xFF1A2233 : 0xFF006A55);
        ctx.fill(toggleX, toggleY, toggleX + 1, toggleY + 14, filterGoalsOnly ? 0xAA334455 : 0xAA33DDAA);
        ctx.fill(toggleX + 47, toggleY, toggleX + 48, toggleY + 14, filterGoalsOnly ? 0xAA1A2233 : 0xAA006A55);
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal(filterGoalsOnly ? "Goals" : "All"),
                toggleX + 5, toggleY + 3, filterGoalsOnly ? 0xFFAAB7C4 : 0xFF66FFD6);
        if (toggleHov) {
            ctx.setCursor(StandardCursors.POINTING_HAND);
            hoveredLines = List.of(Text.literal(filterGoalsOnly ? "Showing goal items only" : "Showing all items"));
            tooltipX = mx; tooltipY = my;
        }

        if (searchField != null) searchField.renderWidget(ctx, mx, my, 0.0F);

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
            if (rowColor != 0) ctx.fill(p[0] + 4, y - 1, p[0] + PANEL_W - 4, y + ROW_H - 1, rowColor);
            String label = itemLabel(id);
            if (client.textRenderer.getWidth(label) > PANEL_W - 28) {
                label = client.textRenderer.trimToWidth(label, PANEL_W - 36) + "...";
            }
            ctx.drawText(client.textRenderer, Text.literal((selectedRow ? "* " : "  ") + label),
                    p[0] + 7, y, selectedRow ? 0xFF66FFD6 : 0xFFE6EFF7, false);
            if (hovered) {
                ctx.setCursor(StandardCursors.POINTING_HAND);
                hoveredLines = List.of(
                        Text.literal(itemLabel(id)),
                        Text.literal(selectedRow ? "Click to stop collecting this item." : "Click to collect this item."),
                        Text.literal("ID: " + id));
                tooltipX = mx;
                tooltipY = my;
            }
        }

        if (rows.isEmpty()) {
            String emptyMsg = filterGoalsOnly ? "No goal items" : "No items found";
            ctx.drawText(client.textRenderer, Text.literal(emptyMsg), p[0] + 7, startY, 0xFF8996A4, false);
        }
    }

    private static boolean handleClick(HandledScreen<?> screen, int mx, int my) {
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
                searchField.setPlaceholder(Text.literal(filterGoalsOnly ? "Search goals..." : "Search all items..."));
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

    private static boolean handleKeyPress(KeyInput input) {
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
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
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
            allItemsCache = Registries.ITEM.stream()
                    .map(item -> Registries.ITEM.getId(item).toString())
                    .sorted((a, b) -> itemLabel(a).compareToIgnoreCase(itemLabel(b)))
                    .toList();
        }
        return allItemsCache;
    }

    private static List<String> currentNeededItems() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return List.of();
        return GatherState.get().getChestScanTargets(itemId -> 0);
    }

    private static String itemLabel(String id) {
        return LABEL_CACHE.computeIfAbsent(id, k -> {
            Item item = Registries.ITEM.get(Identifier.of(k));
            return item != null ? item.getName().getString() : k;
        });
    }

    private static void sendConfig() {
        cacheOpenPlacedShulkerState();
        GatherClientNetworking.updateCollectorTargets(currentNeededItems());
        GatherClientNetworking.configureCollector(collectorActive, allMode, new ArrayList<>(selectedItems), leaveOne);
    }

    private static void playClick() {
        MinecraftClient.getInstance().getSoundManager().play(
                PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static int[] buttonBounds(HandledScreen<?> screen, int index) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int x = accessor.gather$getX() + accessor.gather$getBackgroundWidth() - BUTTON_W - 6;
        int y = accessor.gather$getY() - 52 + index * (BUTTON_H + 3);
        return new int[]{x, y};
    }

    private static int[] arrowButtonBounds(HandledScreen<?> screen) {
        int[] mode = buttonBounds(screen, 1);
        return new int[]{mode[0] - 18, mode[1]};
    }

    private static void drawSearchIcon(DrawContext ctx, int x, int y) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SEARCH_ICON_TEX, x, y, 0.0f, 0.0f, 13, 13, 13, 13);
    }

    private static int[] panelBounds(HandledScreen<?> screen) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int x = accessor.gather$getX() + accessor.gather$getBackgroundWidth() - PANEL_W;
        int y = Math.max(6, accessor.gather$getY() - PANEL_H - 39);
        return new int[]{x, y};
    }

    private static void syncSearchField(HandledScreen<?> screen) {
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

    private static void syncOpenShulkerState(MinecraftClient client, HandledScreen<?> screen) {
        collectorActive = false;
        allMode = true;
        leaveOne = false;
        serverStateLoaded = false;
        selectedItems.clear();
        if (client.player == null || !(screen.getScreenHandler() instanceof ShulkerBoxScreenHandler handler)) return;

        Inventory inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        NbtCompound data = null;
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            NbtComponent cd = shulker.getComponents().get(DataComponentTypes.CUSTOM_DATA);
            if (cd != null) data = cd.copyNbt();
        } else {
            ItemStack backingStack = findUniqueOpenShulkerStack(client, inventory);
            if (!backingStack.isEmpty()) {
                NbtComponent cd = backingStack.get(DataComponentTypes.CUSTOM_DATA);
                if (cd != null) data = cd.copyNbt();
            }
        }
        if (data == null) return;
        collectorActive = data.getBoolean("gather_collector", false);
        allMode = !"certain".equals(data.getString("gather_collector_mode", "all"));
        leaveOne = data.getBoolean("gather_collector_leave_one", true);
        selectedItems.addAll(splitLines(data.getString("gather_collector_items", "")));
        serverStateLoaded = data.contains("gather_collector");
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            placedShulkerUiState.put(shulker.getPos().asLong(), data.copy());
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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen instanceof HandledScreen<?> screen) syncSearchField(screen);
        }
    }

    private static void refreshOpenPlacedShulkerContents() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        if (!(screen.getScreenHandler() instanceof ShulkerBoxScreenHandler handler)) return;
        Inventory inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        if (!(inventory instanceof ShulkerBoxBlockEntity shulker)) return;
        GatherClientNetworking.requestTrackedChests(java.util.Set.of(shulker.getPos().asLong()));
    }

    private static void cacheOpenPlacedShulkerState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        if (!(screen.getScreenHandler() instanceof ShulkerBoxScreenHandler handler)) return;
        Inventory inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        if (!(inventory instanceof ShulkerBoxBlockEntity shulker)) return;
        NbtCompound data = new NbtCompound();
        data.putBoolean("gather_collector", collectorActive);
        data.putString("gather_collector_mode", allMode ? "all" : "certain");
        data.putString("gather_collector_items", String.join("\n", selectedItems));
        data.putBoolean("gather_collector_leave_one", leaveOne);
        placedShulkerUiState.put(shulker.getPos().asLong(), data);
    }

    private static ItemStack findUniqueOpenShulkerStack(MinecraftClient client, Inventory openInventory) {
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        ItemStack selected = client.player.getInventory().getStack(selectedSlot);
        if (isShulkerBox(selected)
                && (hasCollectorIdentity(selected) || containerMatchesInventory(selected.get(DataComponentTypes.CONTAINER), openInventory))) {
            return selected;
        }

        ItemStack match = ItemStack.EMPTY;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            if (i == selectedSlot) continue;
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!isShulkerBox(stack)) continue;
            if (!containerMatchesInventory(stack.get(DataComponentTypes.CONTAINER), openInventory)) continue;
            if (!match.isEmpty()) return ItemStack.EMPTY;
            match = stack;
        }
        return match;
    }

    private static boolean hasCollectorIdentity(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return false;
        NbtCompound nbt = customData.copyNbt();
        return nbt.getBoolean("gather_collector", false)
                || !nbt.getString("gather_collector_id", "").isBlank();
    }

    private static boolean containerMatchesInventory(ContainerComponent container, Inventory inventory) {
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
        if (container != null) container.copyTo(contents);
        for (int i = 0; i < Math.min(contents.size(), inventory.size()); i++) {
            if (!ItemStack.areEqual(contents.get(i), inventory.getStack(i))) return false;
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
