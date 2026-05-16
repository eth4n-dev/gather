package com.gather.client;

import com.gather.client.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class GatherTradeCalculatorOverlay {
    private static final int PANEL_W = 222;
    private static final int PANEL_H = 168;
    private static final int ROW_H = 26;
    private static final int BUTTON_SIZE = 16;
    private static final int CLOSE_SIZE = 9;
    private static final int PAD = 6;
    private static final int MAX_WANTED_AMOUNT = 99999;
    private static final int MERCHANT_BG_H = 166;

    private static boolean panelOpen = false;
    private static float panelAnim = 0.0F;
    private static int selectedTrade = -1;
    private static int scroll = 0;
    private static EditBox amountField = null;
    private static List<Component> hoveredLines = null;
    private static int tooltipX;
    private static int tooltipY;
    private static int statusTicks = 0;
    private static String statusText = "";
    private static boolean clearOnNextClick = false;

    private GatherTradeCalculatorOverlay() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!GatherSettings.get().enabled) return;
            if (!(screen instanceof MerchantScreen)) return;

            panelOpen = false;
            panelAnim = 0.0F;
            selectedTrade = -1;
            scroll = 0;
            statusTicks = 0;
            statusText = "";
            clearOnNextClick = false;

            amountField = new EditBox(client.font, 0, 0, 52, 14, Component.literal("Amount"));
            amountField.setMaxLength(String.valueOf(MAX_WANTED_AMOUNT).length());
            final boolean[] sanitizing = {false};
            amountField.setResponder(val -> {
                if (sanitizing[0]) return;
                String clean = val.replaceAll("[^0-9]", "");
                if (!clean.equals(val)) {
                    sanitizing[0] = true;
                    amountField.setValue(clean);
                    sanitizing[0] = false;
                }
            });
            amountField.setVisible(false);
            MerchantScreen merchantRef = (MerchantScreen) screen;
            Screens.getWidgets(screen).add(amountField);
            Screens.getWidgets(screen).add(new AbstractWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE, Component.empty()) {
                @Override
                protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
                    if (GatherSettings.get().enabled) {
                        int[] b = buttonBounds(merchantRef);
                        renderToggleButton(ctx, mx, my, b[0], b[1]);
                    }
                }
                @Override
                protected void updateWidgetNarration(NarrationElementOutput output) {}
            });

            ScreenEvents.afterExtract(screen).register((s, ctx, mx, my, delta) -> {
                hoveredLines = null;
                renderOverlay((MerchantScreen) s, ctx, mx, my, delta);
                if (hoveredLines != null) {
                    ctx.setComponentTooltipForNextFrame(client.font, hoveredLines, tooltipX, tooltipY);
                }
            });

            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                int mx = (int) click.x();
                int my = (int) click.y();
                if (amountField != null && amountField.isVisible() && inside(mx, my,
                        amountField.getX(), amountField.getY(), amountField.getWidth(), amountField.getHeight())) {
                    if (clearOnNextClick) {
                        amountField.setValue("");
                        clearOnNextClick = false;
                    }
                    amountField.mouseClicked(click, false);
                    ((MerchantScreen) s).setFocused(amountField);
                    ((MerchantScreen) s).setDragging(true);
                    return false;
                }
                if (amountField != null) {
                    amountField.setFocused(false);
                    ((MerchantScreen) s).setFocused(null);
                }
                if (!isOverlayClickTarget((MerchantScreen) s, mx, my)) return true;
                handleClick((MerchantScreen) s, mx, my, click.button());
                return false;
            });

            ScreenMouseEvents.allowMouseDrag(screen).register((s, click, deltaX, deltaY) -> {
                if (amountField != null && amountField.isVisible() && ((MerchantScreen) s).getFocused() == amountField) {
                    amountField.mouseDragged(click, deltaX, deltaY);
                    return false;
                }
                return true;
            });

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
                if (!isPanelTarget((MerchantScreen) s, (int) mouseX, (int) mouseY)) return true;
                handleScroll((MerchantScreen) s, verticalAmount);
                return false;
            });

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) -> {
                if (!panelOpen) return true;
                if (amountField != null && ((MerchantScreen) s).getFocused() == amountField) {
                    int key = input.key();
                    if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                        ((MerchantScreen) s).setFocused(null);
                        amountField.setFocused(false);
                        return false;
                    }
                    amountField.keyPressed(input);
                    return false;
                }
                return handleKeyPress(input);
            });

        });
    }

    private static void renderOverlay(MerchantScreen screen, GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (!GatherSettings.get().enabled) return;
        updateAnimation();

        if (panelAnim <= 0.01F) {
            if (amountField != null && screen.getFocused() == amountField) screen.setFocused(null);
            syncAmountField(false, 0, 0);
            return;
        }

        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1] + Math.round((1.0F - easedPanelAnim()) * 18.0F);
        GatherTheme.fill(ctx, panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xF20A1320);
        GatherTheme.drawNineSlice(ctx, GatherTheme.CRAFT_PANEL, panelX, panelY, PANEL_W, PANEL_H);
        Minecraft client = Minecraft.getInstance();
        ctx.text(client.font, Component.literal("Trade Calculator"),
                panelX + PAD, panelY + 6, GatherTheme.textPrimary(), false);
        renderCloseButton(ctx, mx, my, panelX + PANEL_W - PAD - CLOSE_SIZE, panelY + 5);

        MerchantOffers offers = offers(screen);
        int rowsTop = panelY + 24;
        int detailTop = panelY + 105;
        int rowsVisible = Math.max(1, (detailTop - rowsTop - 4) / ROW_H);
        int maxScroll = Math.max(0, offers.size() - rowsVisible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (offers.isEmpty()) {
            ctx.text(client.font, Component.literal("No trades available"),
                    panelX + PAD, rowsTop + 8, GatherTheme.textSecondary(), false);
            syncAmountField(false, 0, 0);
            return;
        }

        for (int row = 0; row < rowsVisible && row + scroll < offers.size(); row++) {
            int offerIndex = row + scroll;
            MerchantOffer offer = offers.get(offerIndex);
            int rowY = rowsTop + row * ROW_H;
            int rowInnerW = PANEL_W - PAD * 2;
            boolean hovered = inside(mx, my, panelX + PAD, rowY, rowInnerW, ROW_H - 2);
            boolean selected = offerIndex == selectedTrade;
            renderTradeRowBackground(ctx, panelX + PAD, rowY, rowInnerW, ROW_H - 2, selected || hovered);
            renderTradeRow(ctx, offer, panelX + PAD + 4, rowInnerW - 8, rowY + 4, offer.isOutOfStock());
            if (hovered) {
                ctx.requestCursor(CursorTypes.POINTING_HAND);
                hoveredLines = List.of(Component.literal("Select trade output"));
                tooltipX = mx;
                tooltipY = my;
            }
        }

        if (maxScroll > 0) {
            int barX = panelX + PANEL_W - 5;
            int barY = rowsTop;
            int barH = rowsVisible * ROW_H - 2;
            int thumbH = Math.max(16, barH * rowsVisible / offers.size());
            int thumbY = barY + (int) ((float) scroll / maxScroll * (barH - thumbH));
            GatherTheme.drawStretch(ctx, GatherTheme.SCROLL_TRACK, barX, barY, 3, barH);
            GatherTheme.drawStretch(ctx, GatherTheme.SCROLL_THUMB, barX, thumbY, 3, thumbH);
        }

        renderDetails(screen, ctx, mx, my, panelX, detailTop);
        if (statusTicks > 0) statusTicks--;
    }

    private static void renderTradeRow(GuiGraphicsExtractor ctx, MerchantOffer offer, int x, int maxW, int y, boolean disabled) {
        Minecraft client = Minecraft.getInstance();
        ItemStack sell = offer.getResult();
        ItemStack first = offer.getCostA();
        ItemStack second = offer.getCostB();
        int text = disabled ? GatherTheme.textDisabled() : GatherTheme.textPrimary();
        int muted = disabled ? GatherTheme.textDisabled() : GatherTheme.textSecondary();

        int cx = x;
        renderStack(ctx, first, cx, y, true);
        cx += 24;
        if (!second.isEmpty()) {
            ctx.text(client.font, Component.literal("+"), cx, y + 5, muted, false);
            cx += 10;
            renderStack(ctx, second, cx, y, true);
            cx += 24;
        } else {
            cx += 34;
        }
        ctx.text(client.font, Component.literal("→"), cx, y + 5, muted, false);
        cx += 14;
        renderStack(ctx, sell, cx, y, true);
        cx += 20;
        String name = com.gather.client.GatherUi.itemName(sell).getString();
        int nameMaxW = x + maxW - cx - 2;
        if (client.font.width(name) > nameMaxW) {
            name = client.font.plainSubstrByWidth(name, nameMaxW - client.font.width("...")) + "...";
        }
        ctx.text(client.font, Component.literal(name), cx, y + 5, text, false);
    }

    private static void renderDetails(MerchantScreen screen, GuiGraphicsExtractor ctx, int mx, int my, int panelX, int detailTop) {
        Minecraft client = Minecraft.getInstance();
        MerchantOffers offers = offers(screen);
        if (selectedTrade < 0 || selectedTrade >= offers.size()) {
            syncAmountField(false, 0, 0);
            ctx.text(client.font, Component.literal("Select a trade, then enter wanted output."),
                    panelX + PAD, detailTop + 10, GatherTheme.textSecondary(), false);
            return;
        }

        MerchantOffer offer = offers.get(selectedTrade);
        ItemStack sell = offer.getResult();
        int fieldX = panelX + PAD + client.font.width("Want") + 4;
        int fieldY = detailTop + 2;
        syncAmountField(true, fieldX, fieldY);
        amountField.extractWidgetRenderState(ctx, mx, my, 0.0F);
        ctx.text(client.font, Component.literal("Want"), panelX + PAD, detailTop + 5,
                GatherTheme.textSecondary(), false);
        ctx.text(client.font, com.gather.client.GatherUi.itemName(sell), fieldX + 58, detailTop + 5,
                GatherTheme.textPrimary(), false);

        int wanted = wantedAmount();
        Calculation calc = calculate(offer, wanted);
        int lineY = detailTop + 21;
        ctx.text(client.font,
                Component.literal("Trades needed: " + calc.trades()),
                panelX + PAD, lineY, GatherTheme.textSecondary(), false);

        int costY = lineY + 13;
        int costX = panelX + PAD;
        for (Cost cost : calc.costs()) {
            renderCostStack(ctx, cost.stack(), cost.count(), costX, costY - 2);
            costX += 22;
        }

        int btnW = 76;
        int btnH = 16;
        int btnX = panelX + PANEL_W - PAD - btnW;
        int btnY = costY - 2;
        boolean canAdd = wanted > 0 && calc.trades() > 0 && !calc.costs().isEmpty();
        boolean hovered = canAdd && inside(mx, my, btnX, btnY, btnW, btnH);
        GatherTheme.drawNineSlice(ctx,
                canAdd ? hovered ? GatherTheme.MENU_BUTTON_HOVER : GatherTheme.MENU_BUTTON
                        : GatherTheme.MENU_BUTTON_DISABLED,
                btnX, btnY, btnW, btnH);
        ctx.text(client.font, Component.literal("Add Goals"),
                btnX + 9, btnY + 4, canAdd ? GatherTheme.textButton() : GatherTheme.textDisabled(), false);
        if (hovered) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = List.of(Component.literal("Add required buy items to Gather"));
            tooltipX = mx;
            tooltipY = my;
        }

        if (statusTicks > 0 && !statusText.isEmpty()) {
            int msgX = panelX + PAD - 2;
            int msgY = btnY + btnH + 5;
            int msgW = client.font.width(statusText) + 8;
            GatherTheme.drawNineSlice(ctx, GatherTheme.TRADE_STATUS_SHADOW, msgX + 2, msgY + 2, msgW, 11);
            GatherTheme.drawNineSlice(ctx, GatherTheme.TRADE_STATUS_PANEL, msgX, msgY, msgW, 11);
            ctx.text(client.font, Component.literal(statusText),
                    msgX + 4, msgY + 2, GatherTheme.textAccent(), false);
        }
    }

    private static void handleClick(MerchantScreen screen, int mx, int my, int button) {
        if (button != 0) return;
        int[] toggle = buttonBounds(screen);
        if (inside(mx, my, toggle[0], toggle[1], BUTTON_SIZE, BUTTON_SIZE)) {
            panelOpen = !panelOpen;
            playClick();
            return;
        }
        if (!panelOpen) return;

        int[] bounds = panelBounds(screen);
        int panelX = bounds[0];
        int panelY = bounds[1] + Math.round((1.0F - easedPanelAnim()) * 18.0F);
        if (inside(mx, my, panelX + PANEL_W - PAD - CLOSE_SIZE, panelY + 5, CLOSE_SIZE, CLOSE_SIZE)) {
            panelOpen = false;
            if (amountField != null && screen.getFocused() == amountField) screen.setFocused(null);
            syncAmountField(false, 0, 0);
            playClick();
            return;
        }

        MerchantOffers offers = offers(screen);
        int rowsTop = panelY + 24;
        int detailTop = panelY + 105;
        int rowsVisible = Math.max(1, (detailTop - rowsTop - 4) / ROW_H);
        if (inside(mx, my, panelX + PAD, rowsTop, PANEL_W - PAD * 2, rowsVisible * ROW_H)) {
            int row = (my - rowsTop) / ROW_H;
            int offerIndex = row + scroll;
            if (offerIndex >= 0 && offerIndex < offers.size()) {
                selectedTrade = offerIndex;
                amountField.setValue(String.valueOf(Math.max(1, offers.get(offerIndex).getResult().getCount())));
                clearOnNextClick = true;
                statusTicks = 0;
                playClick();
            }
            return;
        }

        if (selectedTrade >= 0 && selectedTrade < offers.size()) {
            int btnW = 76;
            int btnH = 16;
            int btnX = panelX + PANEL_W - PAD - btnW;
            int btnY = detailTop + 32;
            if (inside(mx, my, btnX, btnY, btnW, btnH)) {
                addGoals(offers.get(selectedTrade));
                playClick();
            }
        }
    }

    private static void handleScroll(MerchantScreen screen, double verticalAmount) {
        MerchantOffers offers = offers(screen);
        int rowsVisible = Math.max(1, (105 - 24 - 4) / ROW_H);
        int maxScroll = Math.max(0, offers.size() - rowsVisible);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount)));
    }

    private static boolean handleKeyPress(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            panelOpen = false;
            syncAmountField(false, 0, 0);
            return false;
        }
        return true;
    }


    private static void addGoals(MerchantOffer offer) {
        int wanted = wantedAmount();
        Calculation calc = calculate(offer, wanted);
        if (wanted <= 0 || calc.trades() <= 0 || calc.costs().isEmpty()) return;

        GatherState state = GatherState.get();
        int listIndex = state.getLastAddedListIndex();
        for (Cost cost : calc.costs()) {
            String itemId = BuiltInRegistries.ITEM.getKey(cost.stack().getItem()).toString();
            int baseline = GatherSettings.get().countExistingOnAdd ? 0 : countForId(itemId);
            state.addItem(listIndex, itemId, cost.count(), baseline);
            GatherSettings.get().addRecentItem(itemId, cost.count());
        }
        GatherSettings.get().save();
        GatherHud.markDirty();
        statusText = "Added costs for " + calc.trades() + " trades";
        statusTicks = 150;
    }

    private static Calculation calculate(MerchantOffer offer, int wanted) {
        ItemStack sell = offer.getResult();
        int out = Math.max(1, sell.getCount());
        int trades = wanted <= 0 ? 0 : (wanted + out - 1) / out;
        int received = trades * out;
        List<Cost> costs = new ArrayList<>();
        ItemStack first = offer.getCostA();
        if (!first.isEmpty()) costs.add(new Cost(first, first.getCount() * trades));
        ItemStack second = offer.getCostB();
        if (!second.isEmpty()) costs.add(new Cost(second, second.getCount() * trades));
        return new Calculation(trades, received, Math.max(0, received - Math.max(0, wanted)), costs);
    }

    private static int wantedAmount() {
        if (amountField == null) return 0;
        try {
            return Math.min(MAX_WANTED_AMOUNT, Integer.parseInt(amountField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int countForId(String itemId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return 0;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        int count = GatherHud.countInventoryTagAware(client, item);
        if (GatherSettings.get().countChests) {
            count += GatherState.get().getTrackedChestCountMatching(itemId);
        } else {
            count += GatherState.get().getManualChestCountMatching(itemId);
        }
        return count;
    }

    private static MerchantOffers offers(MerchantScreen screen) {
        MerchantMenu handler = screen.getMenu();
        return handler.getOffers();
    }

    private static void renderToggleButton(GuiGraphicsExtractor ctx, int mx, int my, int x, int y) {
        boolean hovered = inside(mx, my, x, y, BUTTON_SIZE, BUTTON_SIZE);
        renderTradeButtonIcon(ctx, x, y, hovered, panelOpen);
        if (hovered) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = List.of(Component.literal(panelOpen ? "Close trade calculator" : "Open trade calculator"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderCloseButton(GuiGraphicsExtractor ctx, int mx, int my, int x, int y) {
        boolean hovered = inside(mx, my, x, y, CLOSE_SIZE, CLOSE_SIZE);
        GatherTheme.draw(ctx, hovered ? GatherTheme.CRAFT_CLOSE_HOVER : GatherTheme.CRAFT_CLOSE, x, y);
        if (hovered) {
            ctx.requestCursor(CursorTypes.POINTING_HAND);
            hoveredLines = List.of(Component.literal("Close"));
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private static void renderStack(GuiGraphicsExtractor ctx, ItemStack stack, int x, int y) {
        renderStack(ctx, stack, x, y, true);
    }

    private static void renderStack(GuiGraphicsExtractor ctx, ItemStack stack, int x, int y, boolean overlayCount) {
        if (stack.isEmpty()) return;
        GatherTheme.draw(ctx, GatherTheme.CRAFT_ITEM_SLOT, x, y);
        ctx.item(stack, x + 1, y + 1);
        if (overlayCount && stack.getCount() > 1) {
            ctx.itemDecorations(Minecraft.getInstance().font, stack, x + 1, y + 1);
        }
    }

    private static void renderCostStack(GuiGraphicsExtractor ctx, ItemStack stack, int count, int x, int y) {
        if (stack.isEmpty()) return;
        ItemStack iconStack = stack.copy();
        iconStack.setCount(1);
        renderStack(ctx, iconStack, x, y, false);
        Minecraft client = Minecraft.getInstance();
        String label = compactCount(count);
        int tw = client.font.width(label);
        ctx.text(client.font, Component.literal(label), x + 18 - tw, y + 10, 0xFFFFFFFF);
    }

    private static String compactCount(int count) {
        if (count < 1000) return Integer.toString(count);
        if (count < 10000) return (count / 1000) + "k";
        return "9k+";
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

    private static boolean isOverlayClickTarget(MerchantScreen screen, int mx, int my) {
        int[] toggle = buttonBounds(screen);
        return inside(mx, my, toggle[0], toggle[1], BUTTON_SIZE, BUTTON_SIZE) || isPanelTarget(screen, mx, my);
    }

    private static boolean isPanelTarget(MerchantScreen screen, int mx, int my) {
        if (panelAnim <= 0.01F) return false;
        int[] bounds = panelBounds(screen);
        int panelY = bounds[1] + Math.round((1.0F - easedPanelAnim()) * 18.0F);
        return inside(mx, my, bounds[0], panelY, PANEL_W, PANEL_H);
    }

    private static int[] buttonBounds(MerchantScreen screen) {
        int bgX = screenX(screen);
        int bgY = screenY(screen);
        return new int[]{bgX + 110, bgY + 17};
    }

    private static int[] panelBounds(MerchantScreen screen) {
        int bgX = screenX(screen);
        int bgY = screenY(screen);
        int panelX = bgX - PANEL_W - 8;
        if (panelX < 4) panelX = bgX + 176 + 8;
        if (panelX + PANEL_W > screen.width - 4) panelX = screen.width - PANEL_W - 4;
        int panelY = bgY + (MERCHANT_BG_H - PANEL_H) / 2;
        if (panelY + PANEL_H > screen.height - 4) panelY = screen.height - PANEL_H - 4;
        if (panelY < 4) panelY = 4;
        return new int[]{panelX, panelY};
    }

    private static int screenX(MerchantScreen screen) {
        return ((HandledScreenAccessor) screen).gather$getX();
    }

    private static int screenY(MerchantScreen screen) {
        return ((HandledScreenAccessor) screen).gather$getY();
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

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int digitForKey(int key) {
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
        return -1;
    }

    private static void renderTradeRowBackground(GuiGraphicsExtractor ctx, int x, int y, int w, int h, boolean active) {
        GatherTheme.drawNineSlice(ctx, active ? GatherTheme.TRADE_ROW_ACTIVE : GatherTheme.TRADE_ROW, x, y, w, h);
    }

    private static void renderTradeButtonIcon(GuiGraphicsExtractor ctx, int x, int y, boolean hovered, boolean active) {
        GatherTheme.draw(ctx,
                active ? (hovered ? GatherTheme.TRADE_CALCULATOR_TOGGLE_ACTIVE_HOVER : GatherTheme.TRADE_CALCULATOR_TOGGLE_ACTIVE)
                        : hovered ? GatherTheme.TRADE_CALCULATOR_TOGGLE_HOVER
                        : GatherTheme.TRADE_CALCULATOR_TOGGLE,
                x, y);
    }

    private static void playClick() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private record Cost(ItemStack stack, int count) {
    }

    private record Calculation(int trades, int received, int extra, List<Cost> costs) {
    }
}
