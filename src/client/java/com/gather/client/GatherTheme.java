package com.gather.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

public final class GatherTheme {
    private static final String ROOT = "textures/gui/theme/";

    public static final Texture PIXEL = tex("pixel", 1, 1);
    public static final NineSlice CRAFT_PANEL = nine("craft_panel", 32, 32, 6);
    public static final NineSlice CRAFT_ROW = nine("craft_row", 16, 16, 4);
    public static final NineSlice CRAFT_ROW_HOVER = nine("craft_row_hover", 16, 16, 4);
    public static final Texture CRAFT_DIVIDER = tex("craft_divider", 8, 1);
    public static final Texture CRAFT_ITEM_SLOT = tex("craft_item_slot", 18, 18);
    public static final Texture CRAFT_CLOSE = tex("craft_close", 9, 9);
    public static final Texture CRAFT_CLOSE_HOVER = tex("craft_close_hover", 9, 9);
    public static final Texture CRAFT_TOGGLE = tex("craft_toggle", 16, 16);
    public static final Texture CRAFT_TOGGLE_HOVER = tex("craft_toggle_hover", 16, 16);
    public static final Texture CRAFT_TOGGLE_ACTIVE = tex("craft_toggle_active", 16, 16);
    public static final Texture CRAFT_TOGGLE_ACTIVE_HOVER = tex("craft_toggle_active_hover", 16, 16);
    public static final Texture SCROLL_TRACK = tex("scroll_track", 3, 8);
    public static final Texture SCROLL_THUMB = tex("scroll_thumb", 3, 8);

    public static final NineSlice MENU_PANEL = nine("menu_panel", 32, 32, 6);
    public static final NineSlice MENU_HEADER_ROW = nine("menu_header_row", 16, 16, 4);
    public static final NineSlice MENU_ROW = nine("menu_row", 16, 16, 4);
    public static final NineSlice MENU_ROW_HOVER = nine("menu_row_hover", 16, 16, 4);
    public static final NineSlice MENU_ROW_COMPLETE = nine("menu_row_complete", 16, 16, 4);
    public static final NineSlice MENU_ROW_DRAG = nine("menu_row_drag", 16, 16, 4);
    public static final NineSlice MENU_SUMMARY_PANEL = nine("menu_summary_panel", 32, 32, 6);
    public static final NineSlice MENU_MATERIAL_CHIP = nine("menu_material_chip", 16, 16, 4);
    public static final NineSlice MENU_MATERIAL_CHIP_HOVER = nine("menu_material_chip_hover", 16, 16, 4);
    public static final NineSlice MENU_MATERIAL_CHIP_COMPLETE = nine("menu_material_chip_complete", 16, 16, 4);
    public static final NineSlice MENU_MATERIAL_CHIP_DISABLED = nine("menu_material_chip_disabled", 16, 16, 4);
    public static final NineSlice MENU_TAB = nine("menu_tab", 32, 20, 4);
    public static final NineSlice MENU_TAB_HOVER = nine("menu_tab_hover", 32, 20, 4);
    public static final NineSlice MENU_TAB_ACTIVE = nine("menu_tab_active", 32, 20, 4);
    public static final NineSlice MENU_BUTTON = nine("menu_button", 32, 20, 4);
    public static final NineSlice MENU_BUTTON_HOVER = nine("menu_button_hover", 32, 20, 4);
    public static final NineSlice MENU_BUTTON_ACTIVE = nine("menu_button_active", 32, 20, 4);
    public static final NineSlice MENU_BUTTON_DISABLED = nine("menu_button_disabled", 32, 20, 4);
    public static final NineSlice MENU_BUTTON_DANGER = nine("menu_button_danger", 32, 20, 4);
    public static final NineSlice MENU_SIDE_BUTTON = nine("menu_side_button", 32, 16, 4);
    public static final NineSlice MENU_SIDE_BUTTON_HOVER = nine("menu_side_button_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_BUTTON_ACTIVE = nine("menu_side_button_active", 32, 16, 4);
    public static final NineSlice MENU_SIDE_BUTTON_ACTIVE_HOVER = nine("menu_side_button_active_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_SCANS_OFF = nine("menu_side_chest_scans_off", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_SCANS_ON = nine("menu_side_chest_scans_on", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_SCANS_HOVER = nine("menu_side_chest_scans_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_MANUAL_SCAN_OFF = nine("menu_side_manual_scan_off", 32, 16, 4);
    public static final NineSlice MENU_SIDE_MANUAL_SCAN_ON = nine("menu_side_manual_scan_on", 32, 16, 4);
    public static final NineSlice MENU_SIDE_MANUAL_SCAN_HOVER = nine("menu_side_manual_scan_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_OUTLINES_OFF = nine("menu_side_chest_outlines_off", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_OUTLINES_ON = nine("menu_side_chest_outlines_on", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CHEST_OUTLINES_HOVER = nine("menu_side_chest_outlines_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_CLEAR_ALL = nine("menu_side_clear_all", 32, 16, 4);
    public static final NineSlice MENU_SIDE_FIND_ITEM = nine("menu_side_find_item", 32, 16, 4);
    public static final NineSlice MENU_SIDE_FIND_ITEM_HOVER = nine("menu_side_find_item_hover", 32, 16, 4);
    public static final NineSlice MENU_SIDE_RESCAN_NOW = nine("menu_side_rescan_now", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON = nine("shulker_button", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON_HOVER = nine("shulker_button_hover", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON_ACTIVE = nine("shulker_button_active", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON_ACTIVE_HOVER = nine("shulker_button_active_hover", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON_ORANGE = nine("shulker_button_orange", 32, 16, 4);
    public static final NineSlice SHULKER_BUTTON_ORANGE_HOVER = nine("shulker_button_orange_hover", 32, 16, 4);
    public static final Texture MENU_SCROLL_TRACK = tex("menu_scroll_track", 3, 8);
    public static final Texture MENU_SCROLL_THUMB = tex("menu_scroll_thumb", 3, 8);
    public static final Texture MENU_TREE_LINE = tex("menu_tree_line", 1, 8);
    public static final Texture MENU_ITEM_MASK = tex("menu_item_mask", 16, 16);
    public static final NineSlice MENU_DROP_TARGET = nine("menu_drop_target", 16, 16, 4);
    public static final Texture MENU_CHECKBOX_OFF = tex("menu_checkbox_off", 14, 14);
    public static final Texture MENU_CHECKBOX_OFF_HOVER = tex("menu_checkbox_off_hover", 14, 14);
    public static final Texture MENU_CHECKBOX_ON = tex("menu_checkbox_on", 14, 14);
    public static final Texture MENU_CHECKBOX_ON_HOVER = tex("menu_checkbox_on_hover", 14, 14);
    public static final NineSlice HUD_MANUAL_SCAN_PANEL = nine("hud_manual_scan_panel", 16, 16, 1);
    public static final NineSlice HUD_SCAN_ALL_BADGE = nine("hud_scan_all_badge", 8, 8, 1);
    public static final NineSlice HUD_MANUAL_BADGE = nine("hud_manual_badge", 8, 8, 1);
    public static final NineSlice HUD_FINDER_PANEL = nine("hud_finder_panel", 16, 16, 1);
    public static final NineSlice HUD_TOAST_PANEL = nine("hud_toast_panel", 16, 14, 1);
    public static final NineSlice HUD_GOAL_ROW = nine("hud_goal_row", 16, 16, 1);
    public static final NineSlice HUD_GOAL_ROW_READY = nine("hud_goal_row_ready", 16, 16, 1);
    public static final NineSlice HUD_MORE_ROW = nine("hud_more_row", 16, 10, 1);
    public static final NineSlice HUD_MATERIAL_COMPLETE_ROW = nine("hud_material_complete_row", 16, 16, 1);
    public static final NineSlice HUD_MATERIAL_NEEDED_ROW = nine("hud_material_needed_row", 16, 16, 1);
    public static final NineSlice HUD_CRAFT_HINT_ROW = nine("hud_craft_hint_row", 16, 16, 1);
    public static final NineSlice HUD_TINT_ROW = nine("hud_tint_row", 16, 16, 1);
    public static final Texture HUD_MATERIAL_ACCENT = tex("hud_material_accent", 3, 16);
    public static final Texture HUD_ITEM_FADE_MASK = tex("hud_item_fade_mask", 16, 16);
    public static final Texture HUD_PROGRESS_TRACK = tex("hud_progress_track", 1, 1);
    public static final Texture HUD_PROGRESS_TRACK_FADED = tex("hud_progress_track_faded", 1, 1);
    public static final Texture HUD_PROGRESS_BLACK = tex("hud_progress_black", 1, 1);
    public static final Texture HUD_PROGRESS_FILL_COMPLETE = tex("hud_progress_fill_complete", 1, 1);
    public static final Texture HUD_PROGRESS_FILL_PARTIAL = tex("hud_progress_fill_partial", 1, 1);
    public static final Texture HUD_PROGRESS_FILL_LOW = tex("hud_progress_fill_low", 1, 1);
    public static final Texture HUD_PROGRESS_FILL_EMPTY = tex("hud_progress_fill_empty", 1, 1);
    public static final NineSlice TRADE_ROW = nine("trade_row", 16, 16, 1);
    public static final NineSlice TRADE_ROW_ACTIVE = nine("trade_row_active", 16, 16, 1);
    public static final NineSlice TRADE_STATUS_SHADOW = nine("trade_status_shadow", 8, 8, 1);
    public static final NineSlice TRADE_STATUS_PANEL = nine("trade_status_panel", 8, 8, 1);
    public static final Texture TRADE_CALCULATOR_TOGGLE = tex("trade_calculator_toggle", 16, 16);
    public static final Texture TRADE_CALCULATOR_TOGGLE_HOVER = tex("trade_calculator_toggle_hover", 16, 16);
    public static final Texture TRADE_CALCULATOR_TOGGLE_ACTIVE = tex("trade_calculator_toggle_active", 16, 16);
    public static final Texture TRADE_CALCULATOR_TOGGLE_ACTIVE_HOVER = tex("trade_calculator_toggle_active_hover", 16, 16);

    private GatherTheme() {
    }

    public static void draw(DrawContext ctx, Sprite texture, int x, int y) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0.0f, 0.0f,
                texture.width(), texture.height(), texture.width(), texture.height());
    }

    public static void drawTint(DrawContext ctx, Sprite texture, int x, int y, int argb) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0.0f, 0.0f,
                texture.width(), texture.height(), texture.width(), texture.height(),
                texture.width(), texture.height(), argb);
    }

    public static void drawStretch(DrawContext ctx, Sprite texture, int x, int y, int width, int height) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0.0f, 0.0f,
                width, height, texture.width(), texture.height(), texture.width(), texture.height());
    }

    public static void drawStretchTint(DrawContext ctx, Sprite texture, int x, int y, int width, int height, int argb) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0.0f, 0.0f,
                width, height, texture.width(), texture.height(), texture.width(), texture.height(), argb);
    }

    public static void fill(DrawContext ctx, int x1, int y1, int x2, int y2, int argb) {
        if (x2 <= x1 || y2 <= y1) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, PIXEL.id(), x1, y1, 0.0f, 0.0f,
                x2 - x1, y2 - y1, 1, 1, 1, 1, argb);
    }

    public static void drawNineSlice(DrawContext ctx, NineSlice texture, int x, int y, int width, int height) {
        drawNineSliceTint(ctx, texture, x, y, width, height, 0xFFFFFFFF);
    }

    public static void drawNineSliceTint(DrawContext ctx, NineSlice texture, int x, int y, int width, int height, int argb) {
        int c = texture.corner();
        int texW = texture.width();
        int texH = texture.height();
        if (width <= c * 2 || height <= c * 2) {
            drawStretchTint(ctx, texture, x, y, width, height, argb);
            return;
        }

        drawRegionTint(ctx, texture, x, y, 0, 0, c, c, c, c, argb);
        drawRegionTint(ctx, texture, x + width - c, y, texW - c, 0, c, c, c, c, argb);
        drawRegionTint(ctx, texture, x, y + height - c, 0, texH - c, c, c, c, c, argb);
        drawRegionTint(ctx, texture, x + width - c, y + height - c, texW - c, texH - c, c, c, c, c, argb);

        drawRegionTint(ctx, texture, x + c, y, c, 0, width - c * 2, c, texW - c * 2, c, argb);
        drawRegionTint(ctx, texture, x + c, y + height - c, c, texH - c, width - c * 2, c, texW - c * 2, c, argb);
        drawRegionTint(ctx, texture, x, y + c, 0, c, c, height - c * 2, c, texH - c * 2, argb);
        drawRegionTint(ctx, texture, x + width - c, y + c, texW - c, c, c, height - c * 2, c, texH - c * 2, argb);
        drawRegionTint(ctx, texture, x + c, y + c, c, c, width - c * 2, height - c * 2, texW - c * 2, texH - c * 2, argb);
    }

    private static void drawRegionTint(DrawContext ctx, Sprite texture, int x, int y, int u, int v,
                                       int width, int height, int regionWidth, int regionHeight, int argb) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, (float) u, (float) v,
                width, height, regionWidth, regionHeight, texture.width(), texture.height(), argb);
    }

    public static String currentTheme() {
        return "modern";
    }

    public static String currentThemeLabel() {
        return "Modern";
    }

    public static boolean isVanilla() {
        return false;
    }

    public static int textPrimary() {
        return isVanilla() ? 0xFF202020 : 0xFFCCDDFF;
    }

    public static int textSecondary() {
        return isVanilla() ? 0xFF404040 : 0xFF8899AA;
    }

    public static int textMuted() {
        return isVanilla() ? 0xFF555555 : 0xFF445566;
    }

    public static int textAccent() {
        return isVanilla() ? 0xFF303030 : 0xFF33D6AA;
    }

    public static int textButton() {
        return isVanilla() ? 0xFF202020 : 0xFFCCDDFF;
    }

    public static int textDisabled() {
        return isVanilla() ? 0xFF555555 : 0xFF445566;
    }

    public static void cycleTheme() {
        // Vanilla theme coming soon — no-op for now
    }

    private static Texture tex(String name, int width, int height) {
        return new Texture(name, width, height);
    }

    private static NineSlice nine(String name, int width, int height, int corner) {
        return new NineSlice(name, width, height, corner);
    }

    private static Identifier idFor(String name) {
        return Identifier.of("gather", ROOT + currentTheme() + "/" + name + ".png");
    }

    public interface Sprite {
        Identifier id();
        int width();
        int height();
    }

    public record Texture(String name, int width, int height) implements Sprite {
        @Override
        public Identifier id() {
            return idFor(name);
        }
    }

    public record NineSlice(String name, int width, int height, int corner) implements Sprite {
        @Override
        public Identifier id() {
            return idFor(name);
        }
    }
}
