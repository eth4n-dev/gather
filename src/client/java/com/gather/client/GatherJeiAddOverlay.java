package com.gather.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class GatherJeiAddOverlay {
    private static final int PANEL_W = 190;
    private static final int PAD = 8;
    private static final int BUTTON_H = 16;
    private static final int FIELD_W = 54;
    private static final int MAX_WANTED_AMOUNT = 99999;

    private static boolean open = false;
    private static boolean choosingList = false;
    private static String itemId = null;
    private static int count = 1;
    private static int selectedList = 0;
    private static TextFieldWidget amountField = null;

    private GatherJeiAddOverlay() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            amountField = new TextFieldWidget(client.textRenderer, 0, 0, FIELD_W, 14, Text.literal("Amount"));
            amountField.setMaxLength(String.valueOf(MAX_WANTED_AMOUNT).length());
            amountField.setTextPredicate(GatherJeiAddOverlay::isValidWantedAmountInput);
            amountField.setEditableColor(0xFFFFFFFF);
            amountField.setVisible(false);
            Screens.getButtons(screen).add(amountField);

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> render(s, ctx, mx, my, delta));
            ScreenEvents.remove(screen).register(s -> close());

            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (!open) return true;
                handleClick(s, (int) click.x(), (int) click.y(), click.button(), (Click) click);
                return false;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horiz, vert) -> !open);

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) -> {
                if (!open) return true;
                handleKeyPress(s, input);
                return false;
            });
        });
    }

    public static boolean isOpen() { return open; }

    public static boolean blocksMouse(Screen screen, double mx, double my) {
        if (!open || screen == null) return false;
        int[] bounds = panelBounds(screen);
        return inside((int) mx, (int) my, bounds[0], bounds[1], PANEL_W, bounds[2]);
    }

    public static boolean open(String newItemId, int defaultCount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!GatherSettings.get().enabled || client == null || client.currentScreen == null || amountField == null) return false;
        if (newItemId == null || newItemId.isBlank()) return false;
        itemId = newItemId;
        count = 1;
        selectedList = GatherState.get().getLastAddedListIndex();
        choosingList = false;
        open = true;
        amountField.setText("");
        amountField.setVisible(true);
        amountField.setFocused(true);
        client.currentScreen.setFocused(amountField);
        return true;
    }

    private static void render(Screen screen, DrawContext ctx, int mx, int my, float delta) {
        if (!open) {
            syncAmountField(false, 0, 0);
            return;
        }
        ctx.fill(0, 0, screen.width, screen.height, 0x60000000);
        int panelH = panelHeight();
        int panelX = Math.max(4, Math.min(screen.width - PANEL_W - 4, screen.width / 2 - PANEL_W / 2));
        int panelY = Math.max(4, Math.min(screen.height - panelH - 4, screen.height / 2 - panelH / 2));
        GatherTheme.fill(ctx, panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xF20A1320);
        GatherTheme.drawNineSlice(ctx, GatherTheme.CRAFT_PANEL, panelX, panelY, PANEL_W, panelH);

        MinecraftClient client = MinecraftClient.getInstance();
        Item item = item();
        ItemStack stack = item == null ? ItemStack.EMPTY : item.getDefaultStack();
        renderStack(ctx, stack, panelX + PAD, panelY + 20);
        ctx.drawText(client.textRenderer, Text.literal("Add Goal"), panelX + PAD, panelY + 7, GatherTheme.textPrimary(), false);
        ctx.drawText(client.textRenderer, Text.literal(displayName(item)), panelX + 32, panelY + 22, GatherTheme.textSecondary(), false);

        int closeX = panelX + PANEL_W - PAD - 9;
        int closeY = panelY + 6;
        renderClose(ctx, mx, my, closeX, closeY);

        if (choosingList) {
            renderListPicker(ctx, mx, my, panelX, panelY + 44);
            syncAmountField(false, 0, 0);
        } else {
            ctx.drawText(client.textRenderer, Text.literal("Amount"), panelX + PAD, panelY + 48, GatherTheme.textMuted(), false);
            syncAmountField(true, panelX + 52, panelY + 45);
            renderAmountField(ctx, panelX + 52, panelY + 45);
            renderButton(ctx, mx, my, panelX + PANEL_W - PAD - 58, panelY + 44, 58, BUTTON_H, "Add", true, false);
        }
    }

    private static void renderListPicker(DrawContext ctx, int mx, int my, int x, int y) {
        MinecraftClient client = MinecraftClient.getInstance();
        GatherState state = GatherState.get();
        ctx.drawText(client.textRenderer, Text.literal("Choose list"), x + PAD, y, GatherTheme.textMuted(), false);
        int rowY = y + 12;
        for (int i = 0; i < state.getListCount(); i++) {
            int bx = x + PAD;
            int by = rowY + i * 18;
            boolean hov = inside(mx, my, bx, by, PANEL_W - PAD * 2, BUTTON_H);
            renderButton(ctx, mx, my, bx, by, PANEL_W - PAD * 2, BUTTON_H, state.getList(i).name, true, i == selectedList);
            if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
        }
    }

    private static void handleClick(Screen screen, int mx, int my, int button, Click click) {
        if (button != 0) return;
        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1];
        int panelH = bounds[2];
        if (!inside(mx, my, panelX, panelY, PANEL_W, panelH)) {
            close();
            return;
        }
        if (inside(mx, my, panelX + PANEL_W - PAD - 9, panelY + 6, 9, 9)) {
            close();
            return;
        }
        if (!choosingList) {
            if (amountField != null && inside(mx, my, amountField.getX(), amountField.getY(), amountField.getWidth(), amountField.getHeight())) {
                amountField.mouseClicked(click, false);
                screen.setFocused(amountField);
                screen.setDragging(true);
                return;
            }
            if (inside(mx, my, panelX + PANEL_W - PAD - 58, panelY + 44, 58, BUTTON_H)) {
                confirmAmount(screen);
            }
            return;
        }
        int startY = panelY + 56;
        for (int i = 0; i < GatherState.get().getListCount(); i++) {
            int by = startY + i * 18;
            if (inside(mx, my, panelX + PAD, by, PANEL_W - PAD * 2, BUTTON_H)) {
                addToList(i);
                close();
                return;
            }
        }
    }

    private static void handleKeyPress(Screen screen, KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return;
        }
        if (!choosingList) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                confirmAmount(screen);
                return;
            }
            if (amountField != null) amountField.keyPressed(input);
            return;
        }
        int n = GatherState.get().getListCount();
        if (n <= 0) return;
        if (key == GLFW.GLFW_KEY_UP) selectedList = (selectedList - 1 + n) % n;
        else if (key == GLFW.GLFW_KEY_DOWN) selectedList = (selectedList + 1) % n;
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            addToList(selectedList);
            close();
        }
    }

    private static void confirmAmount(Screen screen) {
        count = wantedAmount();
        if (count <= 0) return;
        if (screen.getFocused() == amountField) screen.setFocused(null);
        if (amountField != null) amountField.setFocused(false);
        if (GatherState.get().getListCount() <= 1) {
            addToList(0);
            close();
        } else {
            choosingList = true;
            selectedList = GatherState.get().getLastAddedListIndex();
        }
    }

    private static void addToList(int listIndex) {
        if (itemId == null || count <= 0) return;
        GatherState state = GatherState.get();
        int baseline = GatherSettings.get().countExistingOnAdd ? 0 : countForId(itemId);
        state.addItem(listIndex, itemId, count, baseline);
        GatherSettings.get().addRecentItem(itemId, count);
        GatherSettings.get().save();
        GatherHud.markDirty();
        GatherHud.showToast("Added " + displayName(item()) + (count > 1 ? " x" + count : ""));
        GatherUi.playGoalAddedSound();
    }

    private static void close() {
        open = false;
        choosingList = false;
        itemId = null;
        syncAmountField(false, 0, 0);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen != null && client.currentScreen.getFocused() == amountField) {
            client.currentScreen.setFocused(null);
        }
    }

    private static int panelHeight() {
        if (!choosingList) return 76;
        return 62 + Math.max(1, GatherState.get().getListCount()) * 18;
    }

    private static int[] panelBounds(Screen screen) {
        int panelH = panelHeight();
        int panelX = Math.max(4, Math.min(screen.width - PANEL_W - 4, screen.width / 2 - PANEL_W / 2));
        int panelY = Math.max(4, Math.min(screen.height - panelH - 4, screen.height / 2 - panelH / 2));
        return new int[]{panelX, panelY, panelH};
    }

    private static void syncAmountField(boolean visible, int x, int y) {
        if (amountField == null) return;
        amountField.setVisible(visible);
        if (!visible) {
            amountField.setFocused(false);
            return;
        }
        amountField.setX(x);
        amountField.setY(y);
    }

    private static void renderAmountField(DrawContext ctx, int fx, int fy) {
        ctx.fill(fx - 1, fy - 1, fx + FIELD_W + 1, fy + 15, 0xFF334466);
        ctx.fill(fx, fy, fx + FIELD_W, fy + 14, 0xFF0D1822);
        String val = amountField != null ? amountField.getText() : "";
        MinecraftClient client = MinecraftClient.getInstance();
        if (val.isEmpty()) {
            ctx.drawText(client.textRenderer, Text.literal("1"), fx + 3, fy + 3, 0xFF667799, false);
        } else {
            ctx.drawText(client.textRenderer, Text.literal(val), fx + 3, fy + 3, 0xFFFFFFFF, false);
        }
        boolean cursorOn = amountField != null && amountField.isFocused() && ((System.currentTimeMillis() / 500) % 2) == 0;
        if (cursorOn) {
            int cx = fx + 3 + (val.isEmpty() ? 0 : client.textRenderer.getWidth(val));
            if (cx < fx + FIELD_W - 3) ctx.fill(cx, fy + 2, cx + 1, fy + 12, 0xFFFFFFFF);
        }
    }

    private static void renderButton(DrawContext ctx, int mx, int my, int x, int y, int w, int h, String label, boolean enabled, boolean active) {
        boolean hov = enabled && inside(mx, my, x, y, w, h);
        GatherTheme.drawNineSlice(ctx, !enabled ? GatherTheme.MENU_BUTTON_DISABLED : active ? GatherTheme.MENU_BUTTON_ACTIVE : hov ? GatherTheme.MENU_BUTTON_HOVER : GatherTheme.MENU_BUTTON, x, y, w, h);
        MinecraftClient client = MinecraftClient.getInstance();
        String clipped = client.textRenderer.trimToWidth(label, w - 8);
        ctx.drawText(client.textRenderer, Text.literal(clipped), x + (w - client.textRenderer.getWidth(clipped)) / 2, y + 4, enabled ? GatherTheme.textButton() : GatherTheme.textDisabled(), false);
        if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
    }

    private static void renderClose(DrawContext ctx, int mx, int my, int x, int y) {
        boolean hov = inside(mx, my, x, y, 9, 9);
        GatherTheme.draw(ctx, hov ? GatherTheme.CRAFT_CLOSE_HOVER : GatherTheme.CRAFT_CLOSE, x, y);
        if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
    }

    private static void renderStack(DrawContext ctx, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        GatherTheme.draw(ctx, GatherTheme.CRAFT_ITEM_SLOT, x, y);
        ctx.drawItem(stack, x + 1, y + 1);
    }

    private static int wantedAmount() {
        if (amountField == null) return 1;
        String text = amountField.getText().trim();
        if (text.isEmpty()) return 1;
        try {
            return Math.min(MAX_WANTED_AMOUNT, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static boolean isValidWantedAmountInput(String value) {
        if (value.isEmpty()) return true;
        if (!value.chars().allMatch(Character::isDigit)) return false;
        try {
            return Integer.parseInt(value) <= MAX_WANTED_AMOUNT;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int countForId(String id) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return 0;
        Item item = Registries.ITEM.get(Identifier.of(id));
        if (item == null) return 0;
        int total = GatherHud.countInventoryTagAware(client, item);
        return total + (GatherSettings.get().countChests
                ? GatherState.get().getTrackedChestCountMatching(id)
                : GatherState.get().getManualChestCountMatching(id));
    }

    private static Item item() {
        if (itemId == null) return null;
        return Registries.ITEM.get(Identifier.of(itemId));
    }

    private static String displayName(Item item) {
        if (item == null) return itemId == null ? "" : itemId;
        return item.getName().getString();
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return w > 0 && h > 0 && mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
