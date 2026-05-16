package com.gather.client.screen;

import com.gather.client.*;
import com.gather.network.AutoCraftPayload;
import com.gather.client.GatherClientNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

public class GatherMenuScreen extends Screen {

    // ─── LAYOUT ──────────────────────────────────────────────────────────────
    private static final int ENTRY_H   = 28;
    private static final int LIST_W    = 300;
    private static final int PAD       = 6;
    private static final int ICON      = 16;
    private static final int BOTTOM_H  = 28;
    private static final int SUMMARY_H = 72;
    private static final long MENU_ANIM_MS = 320L;
    private static final long MENU_ZOOM_OPEN_MS = 180L;
    private static final long MENU_ZOOM_CLOSE_MS = 145L;
    private static final float MENU_CONTENT_REVEAL = 0.74f;
    private static final long COUNT_CACHE_MS = 250L;
    private static final int MAX_WANTED_AMOUNT = 99999;
    private boolean suppressBottomBar = false;

    private static final int TAB_LIST   = 0;
    private static final int TAB_ADD    = 1;
    private static final int TAB_RECENT = 2;

    // ─── VIS ROW ─────────────────────────────────────────────────────────────
    private sealed interface VisRow permits VisRow.Header, VisRow.Node, VisRow.Empty {
        record Header(int listIndex)                  implements VisRow {}
        record Node  (int listIndex, int nodeIndex)   implements VisRow {}
        record Empty (int listIndex)                  implements VisRow {}
    }

    // ─── STATE ───────────────────────────────────────────────────────────────
    private int activeTab  = TAB_LIST;
    private int listScroll   = 0;
    private int baseScroll   = 0;
    private int recentScroll = 0;

    // Editing
    private int editingList  = -1;
    private int editingIndex = -1;
    private int renamingList = -1;
    private TextFieldWidget editField;
    private TextFieldWidget renameField;

    // Tooltip
    private List<Text> hoveredTooltipLines = null;
    private int tooltipX, tooltipY;

    // Add tab
    private List<Item>       allItems;
    private List<Item>       filteredItems;
    private TextFieldWidget  addSearch;
    private TextFieldWidget  addAmountField;
    private String           addFocusedItemId = null;
    private int              addScroll        = 0;
    private int              addModeToggleX, addModeToggleY; // set during renderAddTab
    private int              favoriteStarX, favoriteStarY, favoriteStarSize;
    private int              newListBtnX,    newListBtnY,    newListBtnW;    // set during renderListTab
    private int              chestModeBtnX,  chestModeBtnY, chestModeBtnW; // set during renderListTab
    private int              clearChestsBtnX, clearChestsBtnY, clearChestsBtnW; // set during renderListTab
    private int              clearManualBtnX, clearManualBtnY, clearManualBtnW; // set during renderListTab
    private int              autoTrackBtnX,  autoTrackBtnY,  autoTrackBtnW;     // set during renderListTab
    private int              outlinesBtnX,   outlinesBtnY,   outlinesBtnW;      // set during renderListTab
    private int              finderBtnX,     finderBtnY,     finderBtnW;        // set during renderListTab
    private boolean          finderBtnEnabled;
    public static int renderedOutlinesBtnY = 0;
    public static int renderedFinderBtnY   = 0;
    private int              rescanBtnX,     rescanBtnY,     rescanBtnW;        // set during renderListTab
    private int              chestToolsBtnX, chestToolsBtnY, chestToolsBtnW;
    private int              rescanFeedbackTicks = 0;
    private int              enableGatherBtnX, enableGatherBtnY, enableGatherBtnW, enableGatherBtnH;

    // Pending add (multi-list picker)
    private String pendingAddItemId     = null;
    private int    pendingAddCount      = 0;
    private int    pendingPickerSelected = 0;   // highlighted list index in picker

    // Drag-and-drop
    private int     potentialDragList = -1;
    private int     potentialDragNode = -1;
    private boolean isDragging        = false;
    private int     dragGhostX, dragGhostY;
    private long openedAtMs;
    private boolean closing = false;
    private long closingAtMs = 0L;
    private long countCacheExpiresAtMs = 0L;
    private final Map<String, Integer> countCache = new HashMap<>();
    private final Map<Item, Map<Item, Integer>> typeCountCache = new IdentityHashMap<>();

    // ─── INIT ────────────────────────────────────────────────────────────────

    public GatherMenuScreen() {
        this(false);
    }

    private final boolean isTutorial;

    GatherMenuScreen(boolean tutorial) {
        super(Text.literal("Gather"));
        this.isTutorial = tutorial;
        this.openedAtMs = tutorial
                ? System.currentTimeMillis() - 2000L
                : System.currentTimeMillis();
        if (!tutorial && GatherSettings.get().menuSpinAnimation) GatherUi.playMenuOpenSound();
    }

    void setTutorialTab(int tab) {
        this.activeTab = tab;
    }

    void initForTutorial(int w, int h) {
        this.width = w;
        this.height = h;
        this.init();
    }

    @Override
    protected void init() {
        int lx = width / 2 - LIST_W / 2;
        int ly = PAD + 22 + 4;

        addSearch = new TextFieldWidget(textRenderer, lx, ly, LIST_W - 4, 16, Text.literal(""));
        addSearch.setPlaceholder(Text.literal("Search items..."));
        addSearch.setChangedListener(q -> { addScroll = 0; filterItems(q); });
        addSelectableChild(addSearch);

        addAmountField = new TextFieldWidget(textRenderer, 0, 0, 52, 14, Text.literal(""));
        addAmountField.setMaxLength(String.valueOf(MAX_WANTED_AMOUNT).length());
        addAmountField.setTextPredicate(s -> isValidWantedAmountInput(s));
        addAmountField.setVisible(false);
        addSelectableChild(addAmountField);

        editField = new TextFieldWidget(textRenderer, 0, 0, 60, 14, Text.literal(""));
        editField.setMaxLength(String.valueOf(MAX_WANTED_AMOUNT).length());
        editField.setTextPredicate(s -> isValidWantedAmountInput(s));
        editField.setVisible(false);
        addSelectableChild(editField);

        renameField = new TextFieldWidget(textRenderer, 0, 0, 120, 14, Text.literal(""));
        renameField.setMaxLength(32);
        renameField.setVisible(false);
        addSelectableChild(renameField);

        allItems = Registries.ITEM.stream()
                .filter(i -> !Registries.ITEM.getId(i).toString().equals("minecraft:air"))
                .sorted(Comparator.comparing(i -> Registries.ITEM.getId(i).toString()))
                .collect(Collectors.toList());
        filterItems("");
    }

    private void filterItems(String q) {
        String lq = q.toLowerCase();
        filteredItems = allItems.stream()
                .filter(i -> q.isBlank()
                        || Registries.ITEM.getId(i).toString().contains(lq)
                        || i.getName().getString().toLowerCase().contains(lq))
                .sorted((a, b) -> {
                    GatherSettings settings = GatherSettings.get();
                    String aid = Registries.ITEM.getId(a).toString();
                    String bid = Registries.ITEM.getId(b).toString();
                    boolean af = settings.isFavoriteItem(aid);
                    boolean bf = settings.isFavoriteItem(bid);
                    if (af != bf) return af ? -1 : 1;
                    return aid.compareTo(bid);
                })
                .collect(Collectors.toList());
    }

    // ─── RENDER ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long now = System.currentTimeMillis();
        if (closing && now - closingAtMs >= menuAnimationDurationMs()) {
            if (client != null) client.setScreen(null);
            return;
        }
        float anim = animationProgress(now);
        int bgAlpha = closing ? Math.round(0xCC * closingAlpha(anim)) : Math.round(0xCC * anim);
        GatherTheme.fill(ctx, 0, 0, width, height, (bgAlpha << 24) | 0x00111122);

        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        float eased = easeOutCubic(anim);
        float scale = menuScale(now, anim, eased);
        matrices.translate(width / 2.0f, height / 2.0f);
        if (GatherSettings.get().menuSpinAnimation) {
            float spin = (1.0f - eased) * -720.0f;
            matrices.rotate((float) Math.toRadians(spin));
        }
        matrices.scale(scale, scale);
        matrices.translate(-width / 2.0f, -height / 2.0f);
        boolean full = shouldRenderFullContent(anim);
        suppressBottomBar = !full && GatherSettings.get().menuSpinAnimation;
        renderContents(ctx, mx, my, delta);
        suppressBottomBar = false;
        matrices.popMatrix();
    }

    private boolean shouldRenderFullContent(float anim) {
        return !GatherSettings.get().menuSpinAnimation || anim >= MENU_CONTENT_REVEAL;
    }

    private boolean menuAnimationBlockingInput() {
        if (!GatherSettings.get().menuSpinAnimation) return false;
        return !shouldRenderFullContent(animationProgress(System.currentTimeMillis()));
    }

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private void renderContents(DrawContext ctx, int mx, int my, float delta) {
        if (!GatherSettings.get().enabled) {
            renderDisabledMenu(ctx, mx, my);
            return;
        }
        refreshCountCachesIfStale();
        hoveredTooltipLines = null;
        drawTabs(ctx, mx, my);

        int lx = width / 2 - LIST_W / 2;
        int ly = PAD + 22 + 4;
        addSearch.setVisible(activeTab == TAB_ADD);

        if      (activeTab == TAB_LIST)   renderListTab(ctx, mx, my, lx, ly);
        else if (activeTab == TAB_RECENT) renderRecentTab(ctx, mx, my, lx, ly);
        else                              renderAddTab(ctx, mx, my, lx, ly);

        if (!suppressBottomBar) drawBottomBar(ctx, mx, my);
        super.render(ctx, mx, my, delta);

        // Drag ghost
        if (isDragging && potentialDragList >= 0 && potentialDragNode >= 0) {
            List<ListNode> nodes = GatherState.get().getNodes(potentialDragList);
            if (potentialDragNode < nodes.size()) {
                ListNode n = nodes.get(potentialDragNode);
                Item item = Registries.ITEM.get(Identifier.of(n.itemId));
                int gx = dragGhostX - ICON / 2, gy = dragGhostY - ENTRY_H / 2;
                drawMenuRow(ctx, gx, gy, LIST_W / 2, ENTRY_H - 2, true, false, false);
                ctx.drawItem(item.getDefaultStack(), gx + 2, gy + (ENTRY_H-2-ICON)/2);
                ctx.drawTextWithShadow(textRenderer, item.getName(), gx+ICON+6, gy+10, 0xBBCCDDFF);
            }
        }

        // Pending-add picker overlay
        if (pendingAddItemId != null) renderAddPicker(ctx, mx, my);

        if (hoveredTooltipLines != null)
            ctx.drawTooltip(textRenderer, hoveredTooltipLines, tooltipX, tooltipY);
    }

    private float animationProgress(long now) {
        long start = closing ? closingAtMs : openedAtMs;
        float t = Math.max(0f, Math.min(1f, (now - start) / (float) menuAnimationDurationMs()));
        return closing ? 1f - easeOutCubic(t) : t;
    }

    private long menuAnimationDurationMs() {
        if (GatherSettings.get().menuSpinAnimation) return MENU_ANIM_MS;
        return closing ? MENU_ZOOM_CLOSE_MS : MENU_ZOOM_OPEN_MS;
    }

    private float rawAnimationProgress(long now) {
        long start = closing ? closingAtMs : openedAtMs;
        return Math.max(0f, Math.min(1f, (now - start) / (float) menuAnimationDurationMs()));
    }

    private float menuScale(long now, float anim, float eased) {
        if (GatherSettings.get().menuSpinAnimation) {
            return 0.015f + 0.985f * eased;
        }

        float t = rawAnimationProgress(now);
        if (closing) {
            float out = easeOutCubic(t);
            return 1.0f - 0.92f * out;
        }

        float pop = easeOutBackLight(t);
        return 0.46f + 0.54f * pop;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float p = t - 1.0f;
        return 1.0f + c3 * p * p * p + c1 * p * p;
    }

    private static float easeOutBackLight(float t) {
        float c1 = 0.82f;
        float c3 = c1 + 1.0f;
        float p = t - 1.0f;
        return 1.0f + c3 * p * p * p + c1 * p * p;
    }

    private static float easeOutCubic(float t) {
        float p = 1.0f - t;
        return 1.0f - p * p * p;
    }

    private static float closingAlpha(float anim) {
        return anim * anim;
    }

    private void renderDisabledMenu(DrawContext ctx, int mx, int my) {
        int panelW = 172;
        int panelH = 58;
        int panelX = width / 2 - panelW / 2;
        int panelY = height / 2 - panelH / 2;
        GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_PANEL, panelX, panelY, panelW, panelH);

        enableGatherBtnW = 126;
        enableGatherBtnH = 22;
        enableGatherBtnX = width / 2 - enableGatherBtnW / 2;
        enableGatherBtnY = panelY + (panelH - enableGatherBtnH) / 2;
        boolean hov = mx >= enableGatherBtnX && mx <= enableGatherBtnX + enableGatherBtnW
                && my >= enableGatherBtnY && my <= enableGatherBtnY + enableGatherBtnH;
        drawButton(ctx, enableGatherBtnX, enableGatherBtnY, enableGatherBtnW, enableGatherBtnH,
                "Enable Gather", mx, my, hov);
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        int tabW = 90, tabH = 20, ty = PAD, gap = 2, cx = width / 2;
        // 3 tabs centered: total = 3*tabW + 2*gap, start = cx - half
        int t0x = cx - tabW - gap - tabW / 2;
        int t1x = cx - tabW / 2;
        int t2x = cx + tabW / 2 + gap;
        for (int t = 0; t < 3; t++) {
            int tx = t == 0 ? t0x : t == 1 ? t1x : t2x;
            boolean hov = mx>=tx && mx<=tx+tabW && my>=ty && my<=ty+tabH;
            boolean active = activeTab == t;
            GatherTheme.drawNineSlice(ctx, active ? GatherTheme.MENU_TAB_ACTIVE
                    : hov ? GatherTheme.MENU_TAB_HOVER : GatherTheme.MENU_TAB, tx, ty, tabW, tabH);
            String label = t == 0 ? "My Lists" : t == 1 ? "Add Items" : "Recent";
            drawCenteredThemedText(ctx, Text.literal(label), tx+tabW/2, ty+6, GatherTheme.textButton());
            if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
        }
    }

    // ─── LIST TAB ────────────────────────────────────────────────────────────

    private void renderListTab(DrawContext ctx, int mx, int my, int lx, int ly) {
        GatherState state = GatherState.get();

        // === LEFT PANEL ===
        if (suppressBottomBar) {
            clearSideControlHitboxes();
        } else if (useCompactChestTools(lx)) {
            renderCompactChestTools(ctx, mx, my, lx, ly);
        } else {
        int leftCx = lx / 2;
        int panelTop = ly;
        int panelBot = height - BOTTOM_H;
        int panelMid = (panelTop + panelBot) / 2;
        int sideMaxW = Math.max(36, lx - 10);

        // --- Upper half: Chest Scan section ---
        GatherState gstate = state;
        boolean scanMode = gstate.isChestScanMode();
        boolean countCached = GatherSettings.get().countChests;
        int trackedCount = gstate.getTrackedChests().size();

        int scanY = panelTop + 8;

        // Section header
        String chestSectionLabel = sideMaxW < textRenderer.getWidth("CHEST SCANNING") ? "CHESTS" : "CHEST SCANNING";
        drawThemedText(ctx, Text.literal(chestSectionLabel),
                leftCx - textRenderer.getWidth(chestSectionLabel) / 2, scanY, GatherTheme.textMuted());
        scanY += 14;

        // Scan All Chests button (primary, always on top)
        String atBase = sideMaxW < textRenderer.getWidth("Scan All Chests: OFF") + 12 ? "Scan All" : "Scan All Chests";
        String atLabel = atBase + ": " + (countCached ? "ON" : "OFF");
        autoTrackBtnW = Math.min(sideMaxW, Math.max(SIDE_TOGGLE_W, textRenderer.getWidth("Scan All: OFF") + 12));
        autoTrackBtnX = leftCx - autoTrackBtnW / 2;
        autoTrackBtnY = scanY;
        boolean atHov = mx >= autoTrackBtnX && mx <= autoTrackBtnX + autoTrackBtnW
                     && my >= autoTrackBtnY && my <= autoTrackBtnY + 13;
        drawSideButtonFrame(ctx, autoTrackBtnX, autoTrackBtnY, autoTrackBtnW, 13,
                (atHov && !countCached) ? GatherTheme.MENU_SIDE_CHEST_SCANS_HOVER
                                        : (countCached ? GatherTheme.MENU_SIDE_CHEST_SCANS_ON : GatherTheme.MENU_SIDE_CHEST_SCANS_OFF));
        int atText = countCached ? (atHov ? 0xFF66FFD9 : 0xFF33D6AA) : (atHov ? 0xFF00FFDD : 0xFF009988);
        drawCentered(ctx, atLabel, autoTrackBtnX, autoTrackBtnY, autoTrackBtnW, 13, atText);
        if (atHov) ctx.setCursor(StandardCursors.POINTING_HAND);
        scanY += 17;

        if (countCached) {
            // Tracked count + Clear button
            if (trackedCount > 0) {
                String countLabel = trackedCount + " chest" + (trackedCount == 1 ? "" : "s") + " scanned";
                drawThemedText(ctx, Text.literal(countLabel),
                        leftCx - textRenderer.getWidth(countLabel) / 2, scanY, 0xFF00DDAA);
                scanY += 11;

                String clearLabel = "Clear All";
                clearChestsBtnW = textRenderer.getWidth(clearLabel) + 8;
                clearChestsBtnX = leftCx - clearChestsBtnW / 2;
                clearChestsBtnY = scanY;
                boolean clHov = mx >= clearChestsBtnX && mx <= clearChestsBtnX + clearChestsBtnW
                             && my >= clearChestsBtnY && my <= clearChestsBtnY + 10;
                drawSideButtonFrame(ctx, clearChestsBtnX, clearChestsBtnY, clearChestsBtnW, 10,
                        GatherTheme.MENU_SIDE_CLEAR_ALL);
                drawThemedText(ctx, Text.literal(clearLabel), clearChestsBtnX + 4, clearChestsBtnY + 1,
                        clHov ? 0xFFFF6666 : 0xFFAA4444);
                if (clHov) ctx.setCursor(StandardCursors.POINTING_HAND);
                scanY += 14;
            } else {
                clearChestsBtnW = 0;
            }

            // Rescan button
            if (rescanFeedbackTicks > 0) rescanFeedbackTicks--;
            String rescanLabel = rescanFeedbackTicks > 0 ? "Rescanning..." : "Rescan Now";
            rescanBtnW = textRenderer.getWidth(rescanLabel) + 8;
            rescanBtnX = leftCx - rescanBtnW / 2;
            rescanBtnY = scanY;
            boolean rsHov = rescanFeedbackTicks == 0 && mx >= rescanBtnX && mx <= rescanBtnX + rescanBtnW
                         && my >= rescanBtnY && my <= rescanBtnY + 10;
            drawSideButtonFrame(ctx, rescanBtnX, rescanBtnY, rescanBtnW, 10,
                    GatherTheme.MENU_SIDE_RESCAN_NOW);
            drawThemedText(ctx, Text.literal(rescanLabel), rescanBtnX + 4, rescanBtnY + 1,
                    rescanFeedbackTicks > 0 ? GatherTheme.textDisabled() : (rsHov ? 0xFF44AAFF : 0xFF2266AA));
            if (rsHov) ctx.setCursor(StandardCursors.POINTING_HAND);
            scanY += 13;

            int unloadedTracked = client != null && client.world != null
                    ? gstate.getUnloadedTrackedChestCount(client.world) : 0;
            drawCenteredClamped(ctx, "Scans nearby loaded chests.", leftCx, scanY, sideMaxW, GatherTheme.textMuted());
            scanY += 10;
            drawCenteredClamped(ctx, "Only opened chests are tracked.", leftCx, scanY, sideMaxW, GatherTheme.textMuted());
            scanY += 10;
            if (unloadedTracked > 0) {
                String oob = unloadedTracked + " chest" + (unloadedTracked == 1 ? "" : "s") + " out of range.";
                drawThemedText(ctx, Text.literal(oob),
                        leftCx - textRenderer.getWidth(oob) / 2, scanY, 0xFFFF9944);
                scanY += 10;
                drawCenteredClamped(ctx, "Saved contents shown when nearby.", leftCx, scanY, sideMaxW, GatherTheme.textMuted());
                scanY += 12;
            } else {
                drawCenteredClamped(ctx, "Saved after unload.", leftCx, scanY, sideMaxW, GatherTheme.textMuted());
                scanY += 12;
            }

            // Manual button hidden when Scan All is ON
            chestModeBtnW = 0;
        } else {
            clearChestsBtnW = 0;
            rescanBtnW = 0;
            drawCenteredClamped(ctx, "Only inventory counts now.", leftCx, scanY, sideMaxW, GatherTheme.textMuted());
            scanY += 18;

            // Manual Scan button (only shown when Scan All is OFF)
            String cmBase = sideMaxW < textRenderer.getWidth("Manual Scan: OFF") + 12 ? "Manual" : "Manual Scan";
            String cmLabel = cmBase + ": " + (scanMode ? "ON" : "OFF");
            chestModeBtnW = Math.min(sideMaxW, Math.max(SIDE_TOGGLE_W, textRenderer.getWidth("Manual: OFF") + 12));
            chestModeBtnX = leftCx - chestModeBtnW / 2;
            chestModeBtnY = scanY;
            boolean cmHov = mx >= chestModeBtnX && mx <= chestModeBtnX + chestModeBtnW
                         && my >= chestModeBtnY && my <= chestModeBtnY + 13;
            drawSideButtonFrame(ctx, chestModeBtnX, chestModeBtnY, chestModeBtnW, 13,
                    (cmHov && !scanMode) ? GatherTheme.MENU_SIDE_MANUAL_SCAN_HOVER
                                         : (scanMode ? GatherTheme.MENU_SIDE_MANUAL_SCAN_ON : GatherTheme.MENU_SIDE_MANUAL_SCAN_OFF));
            int cmTextCol = scanMode ? (cmHov ? 0xFFFFEE88 : 0xFFFFCC44) : (cmHov ? 0xFFAABBCC : GatherTheme.textSecondary());
            drawCentered(ctx, cmLabel, chestModeBtnX, chestModeBtnY, chestModeBtnW, 13, cmTextCol);
            if (cmHov) ctx.setCursor(StandardCursors.POINTING_HAND);
            scanY += 17;

            if (scanMode) {
                drawCenteredClamped(ctx, "Right-click chests to tag / untag.", leftCx, scanY, sideMaxW, 0xFFBB9955);
                scanY += 11;
            }

            // Manual chest count + clear
            int manualCount = gstate.getManualChests().size();
            if (manualCount > 0) {
                String mcLabel = manualCount + " chest" + (manualCount == 1 ? "" : "s") + " tagged";
                drawThemedText(ctx, Text.literal(mcLabel),
                        leftCx - textRenderer.getWidth(mcLabel) / 2, scanY, 0xFFFFAA44);
                scanY += 11;

                String clLabel = "Clear Tagged";
                clearManualBtnW = textRenderer.getWidth(clLabel) + 8;
                clearManualBtnX = leftCx - clearManualBtnW / 2;
                clearManualBtnY = scanY;
                boolean clHov = mx >= clearManualBtnX && mx <= clearManualBtnX + clearManualBtnW
                             && my >= clearManualBtnY && my <= clearManualBtnY + 10;
                drawSideButtonFrame(ctx, clearManualBtnX, clearManualBtnY, clearManualBtnW, 10,
                        GatherTheme.MENU_SIDE_CLEAR_ALL);
                drawThemedText(ctx, Text.literal(clLabel), clearManualBtnX + 4, clearManualBtnY + 1,
                        clHov ? 0xFFFF6666 : 0xFFAA4444);
                if (clHov) ctx.setCursor(StandardCursors.POINTING_HAND);
                scanY += 14;
            } else {
                clearManualBtnW = 0;
                scanY += 4;
            }
        }

        // --- Chest Outlines + Find Item buttons (inline in scan section) ---
        {
            final int BTN_H = 13;
            scanY += 4;
            boolean outOn = GatherSettings.get().chestOutlinesEnabled;
            String outBase = sideMaxW < textRenderer.getWidth("Chest Outlines: OFF") + 14 ? "Outlines" : "Chest Outlines";
            String outLabel = outBase + ": " + (outOn ? "ON" : "OFF");
            outlinesBtnW = Math.min(sideMaxW, Math.max(SIDE_TOGGLE_W, textRenderer.getWidth("Outlines: OFF") + 14));
            outlinesBtnX = leftCx - outlinesBtnW / 2;
            outlinesBtnY = scanY;
            renderedOutlinesBtnY = outlinesBtnY;
            boolean outHov = mx >= outlinesBtnX && mx <= outlinesBtnX + outlinesBtnW
                          && my >= outlinesBtnY && my <= outlinesBtnY + BTN_H;
            drawSideButtonFrame(ctx, outlinesBtnX, outlinesBtnY, outlinesBtnW, BTN_H,
                    (outHov && !outOn) ? GatherTheme.MENU_SIDE_CHEST_OUTLINES_HOVER
                                       : (outOn ? GatherTheme.MENU_SIDE_CHEST_OUTLINES_ON : GatherTheme.MENU_SIDE_CHEST_OUTLINES_OFF));
            drawThemedText(ctx, Text.literal(outLabel),
                    outlinesBtnX + (outlinesBtnW - textRenderer.getWidth(outLabel)) / 2,
                    outlinesBtnY + (BTN_H - 8) / 2 + 1,
                    outOn ? (outHov ? 0xFFBBEEFF : 0xFF88DDFF) : (outHov ? 0xFF8899AA : GatherTheme.textMuted()));
            if (outHov) ctx.setCursor(StandardCursors.POINTING_HAND);
            scanY += BTN_H + 4;

            boolean fActive = GatherState.get().getChestFinderItemId() != null;
            int searchableChestCount = countCached ? trackedCount : gstate.getManualChests().size();
            finderBtnEnabled = searchableChestCount > 0;
            String findLabel = fActive ? "Find: active" : "Find Item...";
            finderBtnW = Math.max(SIDE_SMALL_W - 12, textRenderer.getWidth("Find Item...") + 2);
            finderBtnX = leftCx - finderBtnW / 2;
            finderBtnY = scanY;
            renderedFinderBtnY = finderBtnY;
            boolean fHov = finderBtnEnabled
                    && mx >= finderBtnX && mx <= finderBtnX + finderBtnW
                    && my >= finderBtnY && my <= finderBtnY + FINDER_H;
            if (finderBtnEnabled) drawSideButtonFrame(ctx, finderBtnX, finderBtnY, finderBtnW, FINDER_H,
                    (fHov && !fActive) ? GatherTheme.MENU_SIDE_FIND_ITEM_HOVER : GatherTheme.MENU_SIDE_FIND_ITEM);
            else drawMenuButtonFrame(ctx, finderBtnX, finderBtnY, finderBtnW, FINDER_H, false, false, true, false);
            drawCentered(ctx, findLabel, finderBtnX, finderBtnY, finderBtnW, FINDER_H,
                    !finderBtnEnabled ? GatherTheme.textDisabled()
                            : (fActive ? (fHov ? 0xFFFF9999 : 0xFFFF6666)
                                       : (fHov ? 0xFF99AACC : 0xFF6F86A8)));
            if (fHov) ctx.setCursor(StandardCursors.POINTING_HAND);
        }

        // --- Lower half: My Lists section ---
        int listSectionCY = panelMid + (panelBot - panelMid) / 2;
        boolean atCap = state.getListCount() >= 10;
        newListBtnX = leftCx - TOGGLE_W / 2;
        newListBtnY = listSectionCY - TOGGLE_H / 2;
        newListBtnW = TOGGLE_W;
        String listLabel = "My Lists (" + state.getListCount() + "/10)";
        drawThemedText(ctx, Text.literal(listLabel),
                leftCx - textRenderer.getWidth(listLabel) / 2, newListBtnY - 14, GatherTheme.textSecondary());
        if (atCap) {
            drawMenuButtonFrame(ctx, newListBtnX, newListBtnY, TOGGLE_W, TOGGLE_H, false, false, true, false);
            String btnLabel = "+ New List";
            drawThemedText(ctx, Text.literal(btnLabel),
                    leftCx - textRenderer.getWidth(btnLabel) / 2, newListBtnY + 6, GatherTheme.textDisabled());
        } else {
            boolean nlHov = mx >= newListBtnX && mx <= newListBtnX + TOGGLE_W
                         && my >= newListBtnY && my <= newListBtnY + TOGGLE_H;
            drawMenuButtonFrame(ctx, newListBtnX, newListBtnY, TOGGLE_W, TOGGLE_H, nlHov, false, false, false);
            String btnLabel = "+ New List";
            drawThemedText(ctx, Text.literal(btnLabel),
                    leftCx - textRenderer.getWidth(btnLabel) / 2, newListBtnY + 6, GatherTheme.textButton());
            if (nlHov) ctx.setCursor(StandardCursors.POINTING_HAND);
        }
        }

        int summaryY = height - BOTTOM_H - SUMMARY_H - 4;
        int visCount = (summaryY - ly) / ENTRY_H;

        List<VisRow> rows = buildVisibleRows(state);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - visCount)));

        for (int i = 0; i < visCount && (i+listScroll) < rows.size(); i++) {
            VisRow row = rows.get(i + listScroll);
            int y = ly + i * ENTRY_H;
            switch (row) {
                case VisRow.Header h -> renderHeaderRow(ctx, mx, my, lx, y, h.listIndex());
                case VisRow.Node   n -> {
                    List<ListNode> nodes = state.getNodes(n.listIndex());
                    renderNodeRow(ctx, mx, my, lx, y, n.listIndex(), n.nodeIndex(), nodes.get(n.nodeIndex()), state);
                }
                case VisRow.Empty  e -> {
                    drawThemedText(ctx, Text.literal("  Empty - add items in Add Items tab"),
                            lx + 8, y + 10, GatherTheme.textMuted());
                }
            }
        }

        // Scrollbar
        if (rows.size() > visCount && visCount > 0) {
            int barH   = summaryY - ly;
            int thumbH = Math.max(16, barH * visCount / rows.size());
            int thumbY = ly + (int)((float)listScroll / Math.max(1, rows.size()-visCount) * (barH-thumbH));
            GatherTheme.drawStretch(ctx, GatherTheme.MENU_SCROLL_TRACK, lx + LIST_W + 2, ly, 3, barH);
            GatherTheme.drawStretch(ctx, GatherTheme.MENU_SCROLL_THUMB, lx + LIST_W + 2, thumbY, 3, thumbH);
        }

        // Drop target highlight when dragging
        if (isDragging) {
            for (int i = 0; i < visCount && (i+listScroll) < rows.size(); i++) {
                if (!(rows.get(i+listScroll) instanceof VisRow.Header h)) continue;
                if (h.listIndex() == potentialDragList) continue;
                int y = ly + i * ENTRY_H;
                if (my >= y && my <= y + ENTRY_H - 2 && mx >= lx && mx <= lx+LIST_W)
                    GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_DROP_TARGET, lx, y, LIST_W, ENTRY_H - 2);
            }
        }

        renderSummary(ctx, mx, my, lx, summaryY);

        if (editingList >= 0 && editingIndex >= 0) {
            int ep = indexInRows(rows, editingList, editingIndex) - listScroll;
            if (ep >= 0 && ep < visCount) editField.render(ctx, mx, my, 0);
        }
        if (renamingList >= 0) {
            int rp = indexOfHeader(rows, renamingList) - listScroll;
            if (rp >= 0 && rp < visCount) renameField.render(ctx, mx, my, 0);
        }
    }

    private void clearSideControlHitboxes() {
        newListBtnX = newListBtnY = -1000;
        newListBtnW = 0;
        chestToolsBtnW = 0;
        chestModeBtnW = 0;
        clearChestsBtnW = 0;
        clearManualBtnW = 0;
        autoTrackBtnW = 0;
        outlinesBtnW = 0;
        finderBtnW = 0;
        rescanBtnW = 0;
        finderBtnEnabled = false;
    }

    private boolean useCompactChestTools(int lx) {
        return isCompactChestTools(width, height);
    }

    public static boolean isCompactChestTools(int screenW, int screenH) {
        return screenW / 2 - LIST_W / 2 < 118 || screenH < 260;
    }

    private void renderCompactChestTools(DrawContext ctx, int mx, int my, int lx, int ly) {
        clearSideControlHitboxes();
        int leftW = Math.max(0, lx - 6);
        int btnW = Math.min(96, Math.max(72, leftW - 8));
        if (btnW <= 0) return;
        int leftCx = Math.max(4, lx / 2);
        chestToolsBtnW = btnW;
        chestToolsBtnX = Math.max(4, leftCx - btnW / 2);
        chestToolsBtnY = ly + 24;
        boolean hov = mx >= chestToolsBtnX && mx <= chestToolsBtnX + chestToolsBtnW
                && my >= chestToolsBtnY && my <= chestToolsBtnY + TOGGLE_H;
        drawThemedText(ctx, Text.literal("CHESTS"),
                leftCx - textRenderer.getWidth("CHESTS") / 2, ly + 8, GatherTheme.textMuted());
        drawMenuButtonFrame(ctx, chestToolsBtnX, chestToolsBtnY, chestToolsBtnW, TOGGLE_H, hov, false, false, false);
        drawThemedText(ctx, Text.literal("Tools"),
                chestToolsBtnX + (chestToolsBtnW - textRenderer.getWidth("Tools")) / 2,
                chestToolsBtnY + 6, GatherTheme.textButton());
        if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);

        // Goal Lists section below chest button
        int listY = chestToolsBtnY + TOGGLE_H + 14;
        drawThemedText(ctx, Text.literal("LISTS"),
                leftCx - textRenderer.getWidth("LISTS") / 2, listY, GatherTheme.textMuted());
        listY += 12;
        boolean atCap = GatherState.get().getListCount() >= 10;
        newListBtnW = btnW;
        newListBtnX = Math.max(4, leftCx - btnW / 2);
        newListBtnY = listY;
        boolean nlHov = !atCap && mx >= newListBtnX && mx <= newListBtnX + newListBtnW
                && my >= newListBtnY && my <= newListBtnY + TOGGLE_H;
        drawMenuButtonFrame(ctx, newListBtnX, newListBtnY, newListBtnW, TOGGLE_H, nlHov, false, atCap, false);
        drawThemedText(ctx, Text.literal("+ New List"),
                newListBtnX + (newListBtnW - textRenderer.getWidth("+ New List")) / 2,
                newListBtnY + 6, atCap ? GatherTheme.textDisabled() : GatherTheme.textButton());
        if (nlHov) ctx.setCursor(StandardCursors.POINTING_HAND);
    }

    private void renderHeaderRow(DrawContext ctx, int mx, int my, int lx, int y, int listIndex) {
        GatherState state = GatherState.get();
        GatherList list = state.getList(listIndex);
        boolean hov = mx >= lx && mx <= lx+LIST_W && my >= y && my <= y+ENTRY_H-2;

        GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_HEADER_ROW, lx, y, LIST_W, ENTRY_H - 1);

        if (renamingList == listIndex) {
            renameField.setX(lx + 4); renameField.setY(y + 6); renameField.setWidth(160);
        } else {
            String nameLabel = list.name;
            int nameColor = list.hudHidden ? GatherTheme.textMuted() : 0xFF88FFFF;
            drawThemedText(ctx, Text.literal(nameLabel), lx+6, y+10, nameColor);
        }

        int bx = lx + LIST_W - 2;
        int delW = 36, renW = 44, gap = 3;
        int delX = bx - delW, renX = delX - gap - renW;
        int btnY = y + (ENTRY_H - 2 - 14) / 2;

        if (state.getListCount() > 1)
            drawButton(ctx, delX, btnY, delW, 14, "Del", mx, my,
                    hov && mx>=delX && mx<=bx && my>=btnY && my<=btnY+14);
        drawButton(ctx, renX, btnY, renW, 14, renamingList==listIndex ? "Done" : "Rename", mx, my,
                hov && mx>=renX && mx<=renX+renW && my>=btnY && my<=btnY+14);

        // Tooltip: right-click to toggle HUD visibility
        boolean overButtons = mx>=renX && mx<=bx;
        if (hov && !overButtons) {
            String tip = list.hudHidden ? "Right-click to show in HUD" : "Right-click to hide from HUD";
            hoveredTooltipLines = List.of(Text.literal(tip));
            tooltipX = mx; tooltipY = my;
        }
    }

    private void renderNodeRow(DrawContext ctx, int mx, int my, int lx, int y,
                                int listIndex, int ni, ListNode node, GatherState state) {
        int indent   = node.depth * 14;
        int toggleW  = 10;
        Item item    = Registries.ITEM.get(Identifier.of(node.itemId));
        List<ListNode> nodes = state.getNodes(listIndex);

        Map<Item, Integer> byType = item == null ? Map.of() : countInventoryByType(item); // for multi-type display
        int totalHave = countForId(node.itemId);
        int have      = (node.depth == 0) ? Math.max(0, totalHave - node.baseline) : totalHave;

        int displayNeeded = computeDisplayNeeded(listIndex, ni, node, nodes);

        boolean rowHov = mx>=lx && mx<=lx+LIST_W && my>=y && my<=y+ENTRY_H-2;
        // Dim row if it's the drag source
        boolean isDragSrc = isDragging && listIndex==potentialDragList && ni==potentialDragNode;
        drawMenuRow(ctx, lx, y, LIST_W, ENTRY_H - 2, rowHov, have >= displayNeeded, isDragSrc);

        for (int d = 1; d <= node.depth; d++) {
            GatherTheme.drawStretch(ctx, GatherTheme.MENU_TREE_LINE,
                    lx + d * 14 - 3, y + 1, 1, (ENTRY_H - 2) / 2);
        }

        if (node.broken) {
            String arrow = node.collapsed ? "▶" : "▼";
            ctx.drawTextWithShadow(textRenderer, Text.literal(arrow),
                    lx+indent+2, y+(ENTRY_H-2-8)/2+1, 0xFF8899BB);
        }

        ctx.drawItem(item.getDefaultStack(), lx+indent+toggleW+2, y+(ENTRY_H-2-ICON)/2);
        drawThemedText(ctx, item.getName(), lx+indent+toggleW+ICON+6, y+5, GatherTheme.textPrimary());

        boolean multiType = byType.size()>1 || (byType.size()==1 && !byType.containsKey(item));
        if (multiType && !byType.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var te : byType.entrySet()) {
                if (sb.length()>0) sb.append(", ");
                sb.append(shortName(te.getKey())).append("×").append(te.getValue());
            }
            ctx.drawTextWithShadow(textRenderer, Text.literal(sb.toString()),
                    lx+indent+toggleW+ICON+7, y+16, 0xFF5A7088);
        }

        int bx   = lx + LIST_W - 2;
        int remW = 44, editW = 28, brkW = 32, chkW = 14, gap = 3;
        boolean hidden = GatherState.get().isBaseMaterialHidden(node.itemId);

        int remX, chkX, editX, brkX;
        if (node.depth == 0) {
            // Goal nodes: keep Remove, add checkbox to its left
            remX  = bx - remW;
            chkX  = remX - gap - chkW;
            editX = chkX - gap - editW;
            brkX  = editX - gap - brkW;
            drawButton(ctx, remX, y+3, remW, 14, "Remove", mx, my,
                    rowHov && mx>=remX && mx<=bx && my>=y+3 && my<=y+17);
        } else {
            // Sub-items: replace Remove with small checkbox
            chkX  = bx - chkW;
            editX = chkX - gap - editW;
            brkX  = editX - gap - brkW;
            remX  = chkX; // unused for Remove but used for bounds below
        }

        drawNodeCheckbox(ctx, chkX, y+3, chkW, 14, !hidden,
                rowHov && mx>=chkX && mx<=chkX+chkW && my>=y+3 && my<=y+17);

        if (editingList==listIndex && editingIndex==ni) {
            editField.setX(editX-2); editField.setY(y+4); editField.setWidth(editW+6);
        } else {
            drawButton(ctx, editX, y+3, editW, 14, "Edit", mx, my,
                    rowHov && mx>=editX && mx<=editX+editW && my>=y+3 && my<=y+17);
        }

        int leftBtn = editX;
        if (node.broken) {
            leftBtn = brkX;
            boolean craftHov = rowHov && mx>=brkX && mx<=brkX+brkW && my>=y+3 && my<=y+17;
            if (effectiveHave(listIndex, node) >= node.needed) {
                drawDisabledButton(ctx, brkX, y+3, brkW, 14, "Craft");
                if (craftHov) { hoveredTooltipLines=List.of(Text.literal("Already have enough")); tooltipX=mx; tooltipY=my; }
            } else if (!node.inventoryCraftable) {
                drawDisabledButton(ctx, brkX, y+3, brkW, 14, "Craft");
                if (craftHov) { hoveredTooltipLines=List.of(Text.literal("Requires crafting table")); tooltipX=mx; tooltipY=my; }
            } else if (computeMaxCraftableChained(listIndex, ni, node, nodes) >= 1) {
                drawButton(ctx, brkX, y+3, brkW, 14, "Craft", mx, my, craftHov);
            } else {
                drawDisabledButton(ctx, brkX, y+3, brkW, 14, "Craft");
                if (craftHov) { hoveredTooltipLines=List.of(Text.literal("Not enough materials")); tooltipX=mx; tooltipY=my; }
            }
        }

        String counter = have+"/"+displayNeeded;
        int cc   = have>=displayNeeded ? 0xFF66FF88 : (have>0 ? 0xFFFFDD44 : 0xFFFF5555);
        int ctrX = leftBtn-gap-textRenderer.getWidth(counter);
        int nameEnd = lx+indent+toggleW+ICON+8+textRenderer.getWidth(item.getName().getString());
        if (ctrX > nameEnd+4)
            drawThemedText(ctx, Text.literal(counter), ctrX, y+5, cc);
    }

    private void renderSummary(DrawContext ctx, int mx, int my, int lx, int y) {
        GatherState state = GatherState.get();
        Map<String, Integer> base = getBaseMaterialSummary();

        GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_SUMMARY_PANEL, lx, y, LIST_W, SUMMARY_H);
        drawThemedText(ctx, Text.literal("Base materials:"), lx+4, y+4, GatherTheme.textSecondary());

        if (base.isEmpty()) { drawThemedText(ctx, Text.literal("-"), lx+4, y+18, GatherTheme.textMuted()); return; }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(base.entrySet());
        int tagW = (LIST_W - 12) / 2;
        int rowsVisible = Math.max(1, (SUMMARY_H - 18) / 18);
        int entriesVisible = rowsVisible * 2;
        int maxScroll = Math.max(0, entries.size() - entriesVisible);
        baseScroll = Math.max(0, Math.min(baseScroll, maxScroll));

        int tx = lx + 4;
        int ty = y + 16;
        int col = 0;
        for (int i = baseScroll; i < entries.size() && i < baseScroll + entriesVisible; i++) {
            var e = entries.get(i);
            Item item = Registries.ITEM.get(Identifier.of(e.getKey()));
            boolean off = state.isBaseMaterialHidden(e.getKey());
            boolean hov = mx >= tx && mx <= tx + tagW && my >= ty && my <= ty + 16;
            int have = countForId(e.getKey());
            boolean complete = have >= e.getValue();
            drawMaterialChip(ctx, tx, ty, tagW, 16, hov, complete, off);
            ctx.drawItem(item.getDefaultStack(), tx+1, ty);
            if (off) GatherTheme.draw(ctx, GatherTheme.MENU_ITEM_MASK, tx + 1, ty);
            if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
            String raw = off
                    ? item.getName().getString() + " OFF"
                    : complete
                            ? item.getName().getString()
                            : item.getName().getString()+" "+have+"/"+e.getValue();
            int maxW = tagW-20-4; String label = raw;
            while (textRenderer.getWidth(label)>maxW && label.length()>1) label=label.substring(0,label.length()-1);
            if (!label.equals(raw)) label = label.substring(0,label.length()-1)+"…";
            drawThemedText(ctx, Text.literal(label), tx+20, ty+4,
                    off ? GatherTheme.textMuted() : complete ? 0xFFDDFFDD : GatherTheme.textPrimary());
            col++; if (col%2==0){tx=lx+4; ty+=18;} else {tx+=tagW+4;}
        }

        if (entries.size() > entriesVisible) {
            int barX = lx + LIST_W - 4;
            int barY = y + 16;
            int barH = rowsVisible * 18 - 2;
            int thumbH = Math.max(10, barH * entriesVisible / entries.size());
            int thumbY = barY + (int)((float)baseScroll / Math.max(1, maxScroll) * (barH - thumbH));
            GatherTheme.drawStretch(ctx, GatherTheme.MENU_SCROLL_TRACK, barX, barY, 2, barH);
            GatherTheme.drawStretch(ctx, GatherTheme.MENU_SCROLL_THUMB, barX, thumbY, 2, thumbH);
        }
    }

    private Map<String, Integer> getBaseMaterialSummary() {
        GatherState state = GatherState.get();
        Map<String, Integer> base = new LinkedHashMap<>();
        boolean countChests = GatherSettings.get().countChests;
        java.util.function.Function<String, Integer> totalCounter = id -> {
            return countForId(id);
        };
        for (int li = 0; li < state.getListCount(); li++) {
            state.getScaledIngredientLeaves(li, totalCounter, true).forEach((k, v) -> base.merge(k, v, Integer::sum));
        }
        return base;
    }

    // ─── ADD TAB ─────────────────────────────────────────────────────────────

    private static final int TOGGLE_W = 84;
    private static final int TOGGLE_H = 20;

    // ─── RECENT TAB ──────────────────────────────────────────────────────────

    private void renderRecentTab(DrawContext ctx, int mx, int my, int lx, int ly) {
        List<GatherSettings.RecentEntry> recent = GatherSettings.get().getRecentItems();
        if (recent.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("No recent items yet."), width/2, ly + 20, 0xFF556677);
            return;
        }
        int contentBot = height - BOTTOM_H - 4;
        int visCount   = (contentBot - ly) / ENTRY_H;
        int maxScroll  = Math.max(0, recent.size() - visCount);
        recentScroll   = Math.max(0, Math.min(recentScroll, maxScroll));

        for (int i = 0; i < visCount && (i + recentScroll) < recent.size(); i++) {
            GatherSettings.RecentEntry entry = recent.get(i + recentScroll);
            int y = ly + i * ENTRY_H;
            boolean hov = mx >= lx && mx <= lx+LIST_W && my >= y && my <= y+ENTRY_H-2;
            drawMenuRow(ctx, lx, y, LIST_W, ENTRY_H - 1, hov, false, false);

            Item item = Registries.ITEM.get(Identifier.of(entry.itemId));
            if (item != null) ctx.drawItem(item.getDefaultStack(), lx+4, y+(ENTRY_H-2-16)/2);
            String name = item != null ? item.getName().getString() : entry.itemId;
            drawThemedText(ctx, Text.literal(name), lx+24, y+5, GatherTheme.textPrimary());
            drawThemedText(ctx, Text.literal("×" + entry.count + "  click to re-add"), lx+24, y+15, GatherTheme.textMuted());
        }
    }

    private boolean handleRecentClick(int mx, int my, int lx, int ly) {
        List<GatherSettings.RecentEntry> recent = GatherSettings.get().getRecentItems();
        int contentBot = height - BOTTOM_H - 4;
        int visCount   = (contentBot - ly) / ENTRY_H;
        for (int i = 0; i < visCount && (i + recentScroll) < recent.size(); i++) {
            int y = ly + i * ENTRY_H;
            if (my >= y && my <= y+ENTRY_H-2 && mx >= lx && mx <= lx+LIST_W) {
                GatherSettings.RecentEntry entry = recent.get(i + recentScroll);
                GatherUi.playClickSound();
                pendingAddItemId = entry.itemId;
                pendingAddCount  = entry.count;
                pendingPickerSelected = -1;
                if (GatherState.get().getListCount() == 1) executeAdd(0);
                return true;
            }
        }
        return false;
    }

    // ─── ADD TAB ─────────────────────────────────────────────────────────────

    private void renderModeTogglePanel(DrawContext ctx, int mx, int my) {
        int lx = width / 2 - LIST_W / 2;
        boolean modeTotal = GatherSettings.get().countExistingOnAdd;
        int rightPanelCx = (lx + LIST_W + width) / 2;
        addModeToggleX   = rightPanelCx - TOGGLE_W / 2;
        addModeToggleY   = height / 2 - TOGGLE_H / 2;

        int rightAvail = Math.max(40, width - (lx + LIST_W) - 8);
        String topLabel = rightAvail < 120 ? "Add as:" : "Add goal as:";
        drawThemedText(ctx, Text.literal(topLabel),
                rightPanelCx - textRenderer.getWidth(topLabel) / 2, addModeToggleY - 14, GatherTheme.textSecondary());

        boolean tHov = mx >= addModeToggleX && mx <= addModeToggleX + TOGGLE_W
                    && my >= addModeToggleY && my <= addModeToggleY + TOGGLE_H;
        drawMenuButtonFrame(ctx, addModeToggleX, addModeToggleY, TOGGLE_W, TOGGLE_H, tHov, modeTotal, false, false);
        if (tHov) ctx.setCursor(StandardCursors.POINTING_HAND);

        String modeLabel = modeTotal ? "TOTAL" : "+MORE";
        int mlW = textRenderer.getWidth(modeLabel);
        drawThemedText(ctx, Text.literal(modeLabel),
                rightPanelCx - mlW / 2, addModeToggleY + 6, GatherTheme.textButton());

        String desc1 = modeTotal ? "Set total target." : "Add N more.";
        String desc2 = modeTotal ? "Counts existing." : "Ignores existing.";
        int descY = addModeToggleY + TOGGLE_H + 8;
        drawCenteredClamped(ctx, desc1, rightPanelCx, descY, rightAvail, GatherTheme.textMuted());
        drawCenteredClamped(ctx, desc2, rightPanelCx, descY + 12, rightAvail, GatherTheme.textMuted());
    }

    private void renderAddTab(DrawContext ctx, int mx, int my, int lx, int ly) {
        GatherState state = GatherState.get();
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("Click item · type amount · press ↵ to add"),
                lx, ly+2, 0xFF445566);

        renderModeTogglePanel(ctx, mx, my);

        int searchY  = ly+12;
        addSearch.setX(lx); addSearch.setY(searchY);
        addSearch.render(ctx, mx, my, 0);

        int itemsTop = searchY+20;
        int visCount = (height-itemsTop-BOTTOM_H-4)/ENTRY_H;
        int fieldW   = 52, fieldX = lx+LIST_W-2-fieldW;
        favoriteStarSize = 12;
        favoriteStarX = fieldX - favoriteStarSize - 5;
        favoriteStarY = -1000;

        for (int i = 0; i < visCount && (i+addScroll) < filteredItems.size(); i++) {
            Item   item = filteredItems.get(i+addScroll);
            int    y    = itemsTop+i*ENTRY_H;
            String id   = Registries.ITEM.getId(item).toString();
            int already = state.getNeededCount(id);

            boolean hov = mx>=lx && mx<=lx+LIST_W && my>=y && my<=y+ENTRY_H-2;
            drawMenuRow(ctx, lx, y, LIST_W, ENTRY_H - 2, hov, false, false);
            ctx.drawItem(item.getDefaultStack(), lx+2, y+(ENTRY_H-2-ICON)/2);
            int nameX = lx + ICON + 6;
            String itemName = textRenderer.trimToWidth(item.getName().getString(), Math.max(20, favoriteStarX - nameX - 8));
            drawThemedText(ctx, Text.literal(itemName), nameX, y+10, GatherTheme.textPrimary());
            if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);

            if (already > 0) {
                String cs = "×"+already;
                ctx.drawTextWithShadow(textRenderer, Text.literal(cs),
                        favoriteStarX - 4 - textRenderer.getWidth(cs), y+10, 0xFF66FF88);
            }

            int rowStarY = addRowStarY(y);
            boolean fav = GatherSettings.get().isFavoriteItem(id);
            boolean starHov = mx >= favoriteStarX && mx <= favoriteStarX + favoriteStarSize
                    && my >= rowStarY && my <= rowStarY + favoriteStarSize;
            ctx.drawTextWithShadow(textRenderer, Text.literal(fav ? "★" : "☆"),
                    favoriteStarX + 1, rowStarY + 1,
                    fav ? 0xFFFFDD55 : (starHov ? 0xFFFFEE88 : 0xFF667788));
            if (starHov) ctx.setCursor(StandardCursors.POINTING_HAND);

            if (id.equals(addFocusedItemId)) {
                addAmountField.setX(fieldX); addAmountField.setY(y+7);
                addAmountField.setWidth(fieldW); addAmountField.setVisible(true);
                favoriteStarY = rowStarY;
                ctx.drawTextWithShadow(textRenderer, Text.literal("↵"), fieldX+fieldW+3, y+10, 0xFF556644);
                if (GatherSettings.get().countExistingOnAdd) {
                    int have = countForId(id);
                    if (have > 0) {
                        String hs = "have:" + have;
                        ctx.drawTextWithShadow(textRenderer, Text.literal(hs),
                                lx+ICON+6, y+18, 0xFF55AAFF);
                    }
                }
            } else {
                GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_BUTTON_DISABLED, fieldX, y + 6, fieldW, 14);
                drawCentered(ctx, fieldW < 50 ? "amt" : "amt...", fieldX, y + 6, fieldW, 14, GatherTheme.textMuted());
            }
        }
        if (addFocusedItemId != null) addAmountField.render(ctx, mx, my, 0);
    }

    // ─── LIST PICKER OVERLAY (multi-list add) ────────────────────────────────
    // Layout: up to 5 entries per column, max 2 columns (cap = 10 lists).

    private static final int PICKER_BTN_W  = 160;
    private static final int PICKER_BTN_H  = 24;
    private static final int PICKER_ROW_GAP = 5;
    private static final int PICKER_COL_GAP = 10;
    private static final int PICKER_ROWS    = 5;   // max rows before new column

    private void renderAddPicker(DrawContext ctx, int mx, int my) {
        GatherState state  = GatherState.get();
        int n              = state.getListCount();
        int cols           = (int)Math.ceil((double)n / PICKER_ROWS);
        int rows           = Math.min(n, PICKER_ROWS);

        int btnAreaW = cols * PICKER_BTN_W + (cols-1) * PICKER_COL_GAP;
        int btnAreaH = rows * (PICKER_BTN_H + PICKER_ROW_GAP) - PICKER_ROW_GAP;

        // Header: item label + title
        int padH = 14, padV = 12;
        int headerH = 24;   // two text lines
        int footerH = 14;   // hint line
        int boxW = btnAreaW + padH * 2;
        int boxH = headerH + 10 + btnAreaH + 8 + footerH + padV * 2;

        int ox = width  / 2 - boxW / 2;
        int oy = height / 2 - boxH / 2;

        // Box background + top border
        GatherTheme.drawNineSlice(ctx, GatherTheme.MENU_PANEL, ox, oy, boxW, boxH);

        // Item name
        String itemId = pendingAddItemId == null ? "" : pendingAddItemId;
        Item mc = itemId.isEmpty() ? null : Registries.ITEM.get(Identifier.of(itemId));
        String itemLabel = (mc != null ? mc.getName().getString() : itemId) + " ×" + pendingAddCount;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7" + itemLabel),
                ox + boxW/2, oy + padV, 0xFF778899);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Add to which list?"),
                ox + boxW/2, oy + padV + 11, 0xFFAABBCC);

        // Buttons
        int btnStartX = ox + padH;
        int btnStartY = oy + padV + headerH + 10;

        for (int li = 0; li < n; li++) {
            int col = li / PICKER_ROWS;
            int row = li % PICKER_ROWS;
            int bx  = btnStartX + col * (PICKER_BTN_W + PICKER_COL_GAP);
            int by  = btnStartY + row * (PICKER_BTN_H + PICKER_ROW_GAP);

            boolean sel = li == pendingPickerSelected;
            boolean hov = mx>=bx && mx<=bx+PICKER_BTN_W && my>=by && my<=by+PICKER_BTN_H;

            // Background
            drawMenuButtonFrame(ctx, bx, by, PICKER_BTN_W, PICKER_BTN_H, hov, sel, false, false);
            if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);

            // Selected marker: ▶ on left
            if (sel) ctx.drawTextWithShadow(textRenderer, Text.literal("▶"), bx+4, by+(PICKER_BTN_H-8)/2, 0xFF88AADD);
            String name = state.getList(li).name;
            ctx.drawTextWithShadow(textRenderer, Text.literal(name),
                    bx + (sel ? 16 : 8), by + (PICKER_BTN_H-8)/2, sel ? 0xFFCCDDFF : 0xFF778899);
        }

        // Hint
        int hintY = btnStartY + btnAreaH + 8;
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§8↑↓ select  ↵ confirm  Esc cancel"),
                ox + boxW/2, hintY, 0xFF334455);
    }

    // ─── BOTTOM BAR ──────────────────────────────────────────────────────────

    private void drawBottomBar(DrawContext ctx, int mx, int my) {
        int barY = height - BOTTOM_H;
        GatherTheme.fill(ctx, 0, barY, width, height, 0xAA0A0A1A);

        int cx = width/2, btnW = 60, layoutW = 70, gap = 4;
        int totalW = btnW+gap+layoutW+gap+btnW;
        int hudX = cx-totalW/2, layoutX = hudX+btnW+gap, setX = layoutX+layoutW+gap;
        boolean hudOn = GatherSettings.get().showHud;
        drawButton(ctx, hudX, barY+4, btnW, 18, hudOn?"HUD: ON":"HUD: OFF", mx, my, mx>=hudX && mx<=hudX+btnW && my>=barY+4 && my<=barY+22);
        drawButton(ctx, layoutX, barY+4, layoutW, 18, "Layout", mx, my, mx>=layoutX && mx<=layoutX+layoutW && my>=barY+4 && my<=barY+22);
        drawButton(ctx, setX, barY+4, btnW, 18, "Settings", mx, my, mx>=setX && mx<=setX+btnW && my>=barY+4 && my<=barY+22);
    }

    // ─── MOUSE ───────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        if (menuAnimationBlockingInput()) return true;
        int mx = (int)click.x(), my = (int)click.y();
        int btn = click.button();

        if (!GatherSettings.get().enabled) {
            if (btn == 0
                    && mx >= enableGatherBtnX && mx <= enableGatherBtnX + enableGatherBtnW
                    && my >= enableGatherBtnY && my <= enableGatherBtnY + enableGatherBtnH) {
                GatherUi.playClickSound();
                GatherSettings settings = GatherSettings.get();
                settings.enabled = true;
                settings.save();
                GatherHud.markDirty();
                WorldHighlightRenderer.invalidateCache();
            }
            return true;
        }

        // Pending-add picker takes priority
        if (pendingAddItemId != null) {
            GatherState state = GatherState.get();
            int n    = state.getListCount();
            int cols = (int)Math.ceil((double)n / PICKER_ROWS);
            int btnAreaW = cols*PICKER_BTN_W + (cols-1)*PICKER_COL_GAP;
            int rows     = Math.min(n, PICKER_ROWS);
            int btnAreaH = rows*(PICKER_BTN_H+PICKER_ROW_GAP) - PICKER_ROW_GAP;
            int boxW = btnAreaW + 14*2;
            int boxH = 24 + 10 + btnAreaH + 8 + 14 + 12*2;
            int ox = width/2 - boxW/2, oy = height/2 - boxH/2;
            int btnStartX = ox+14, btnStartY = oy+12+24+10;
            for (int li = 0; li < n; li++) {
                int col = li/PICKER_ROWS, row = li%PICKER_ROWS;
                int bx  = btnStartX + col*(PICKER_BTN_W+PICKER_COL_GAP);
                int by  = btnStartY + row*(PICKER_BTN_H+PICKER_ROW_GAP);
                if (mx>=bx && mx<=bx+PICKER_BTN_W && my>=by && my<=by+PICKER_BTN_H) {
                    pendingPickerSelected = li;
                    GatherUi.playClickSound();
                    executeAdd(li); return true;
                }
            }
            return true; // absorb all clicks while picker is open
        }

        int tabW = 90, tabH = 20, ty = PAD, gap = 2, cx = width/2;
        int t0x = cx - tabW - gap - tabW/2, t1x = cx - tabW/2, t2x = cx + tabW/2 + gap;
        if (my>=ty && my<=ty+tabH) {
            if (mx>=t0x && mx<=t0x+tabW) { GatherUi.playClickSound(); switchTab(TAB_LIST);   return true; }
            if (mx>=t1x && mx<=t1x+tabW) { GatherUi.playClickSound(); switchTab(TAB_ADD);    return true; }
            if (mx>=t2x && mx<=t2x+tabW) { GatherUi.playClickSound(); switchTab(TAB_RECENT); return true; }
        }

        if (super.mouseClicked(click, focused)) return true;

        int lx = width/2-LIST_W/2, barY = height-BOTTOM_H;
        if (my >= barY) {
            int btnW = 60, layoutW = 70, bGap = 4;
            int totalW = btnW+bGap+layoutW+bGap+btnW;
            int hudX = cx-totalW/2, layoutX = hudX+btnW+bGap, setX = layoutX+layoutW+bGap;
            if (mx>=hudX && mx<=hudX+btnW) { GatherUi.playClickSound(); GatherSettings.get().showHud = !GatherSettings.get().showHud; GatherSettings.get().save(); return true; }
            if (mx>=layoutX && mx<=layoutX+layoutW) { GatherUi.playClickSound(); client.setScreen(new GatherLayoutEditorScreen(this)); return true; }
            if (mx>=setX && mx<=setX+btnW) { GatherUi.playClickSound(); client.setScreen(new GatherSettingsScreen(this)); return true; }
        }

        if (activeTab == TAB_LIST && chestToolsBtnW > 0
                && my >= chestToolsBtnY && my <= chestToolsBtnY + TOGGLE_H
                && mx >= chestToolsBtnX && mx <= chestToolsBtnX + chestToolsBtnW) {
            GatherUi.playClickSound();
            client.setScreen(new GatherChestToolsScreen(this));
            return true;
        }

        // + New List button (list tab, left panel)
        if (activeTab == TAB_LIST
                && my >= newListBtnY && my <= newListBtnY + TOGGLE_H
                && mx >= newListBtnX && mx <= newListBtnX + newListBtnW
                && GatherState.get().getListCount() < 10) {
            GatherUi.playClickSound();
            GatherState.get().addList("List " + (GatherState.get().getListCount() + 1));
            return true;
        }

        // Chest Mode toggle (list tab, left panel; only visible when Scan All is OFF)
        if (activeTab == TAB_LIST && chestModeBtnW > 0
                && my >= chestModeBtnY && my <= chestModeBtnY + 13
                && mx >= chestModeBtnX && mx <= chestModeBtnX + chestModeBtnW) {
            GatherUi.playClickSound();
            GatherState state = GatherState.get();
            state.setChestScanMode(!state.isChestScanMode());
            return true;
        }

        // Clear manually tagged chests
        if (activeTab == TAB_LIST && clearManualBtnW > 0
                && my >= clearManualBtnY && my <= clearManualBtnY + 10
                && mx >= clearManualBtnX && mx <= clearManualBtnX + clearManualBtnW) {
            GatherUi.playClickSound();
            GatherState.get().clearManualChests();
            return true;
        }

        // Clear tracked chests button (list tab, left panel)
        if (activeTab == TAB_LIST && clearChestsBtnW > 0
                && my >= clearChestsBtnY && my <= clearChestsBtnY + 10
                && mx >= clearChestsBtnX && mx <= clearChestsBtnX + clearChestsBtnW) {
            GatherUi.playClickSound();
            GatherState.get().clearTrackedChests();
            return true;
        }

        // Rescan Now button
        if (activeTab == TAB_LIST && rescanBtnW > 0 && rescanFeedbackTicks == 0
                && my >= rescanBtnY && my <= rescanBtnY + 10
                && mx >= rescanBtnX && mx <= rescanBtnX + rescanBtnW) {
            GatherUi.playClickSound();
            rescanFeedbackTicks = 50;
            GatherState state = GatherState.get();
            List<String> targets = state.getChestScanTargets(id -> {
                Item item = Registries.ITEM.get(Identifier.of(id));
                return item == null ? 0 : GatherHud.countInventoryTagAware(client, item);
            });
            GatherClientNetworking.requestAutoTrack(GatherSettings.get().chestScanRadius, targets);
            Set<Long> all = new HashSet<>(state.getTrackedChests());
            all.addAll(state.getManualChests());
            if (!all.isEmpty()) GatherClientNetworking.requestTrackedChests(all);
            return true;
        }

        // Chest cache toggle (list tab, left panel)
        if (activeTab == TAB_LIST && autoTrackBtnW > 0
                && my >= autoTrackBtnY && my <= autoTrackBtnY + 13
                && mx >= autoTrackBtnX && mx <= autoTrackBtnX + autoTrackBtnW) {
            GatherUi.playClickSound();
            GatherSettings settings = GatherSettings.get();
            settings.countChests = !settings.countChests;
            if (settings.countChests) {
                GatherState.get().setChestScanMode(false);
            }
            settings.save();
            if (settings.countChests) {
                GatherState state = GatherState.get();
                List<String> targets = state.getChestScanTargets(id -> {
                    Item item = Registries.ITEM.get(Identifier.of(id));
                    return item == null ? 0 : GatherHud.countInventoryTagAware(client, item);
                });
                GatherClientNetworking.requestAutoTrack(settings.chestScanRadius, targets);
                if (!state.getTrackedChests().isEmpty()) {
                    GatherClientNetworking.requestTrackedChests(state.getTrackedChests());
                }
            }
            return true;
        }

        // Chest Outlines toggle
        if (activeTab == TAB_LIST && outlinesBtnW > 0
                && my >= outlinesBtnY && my <= outlinesBtnY + 14
                && mx >= outlinesBtnX && mx <= outlinesBtnX + outlinesBtnW) {
            GatherUi.playClickSound();
            GatherSettings settings = GatherSettings.get();
            settings.chestOutlinesEnabled = !settings.chestOutlinesEnabled;
            settings.save();
            return true;
        }

        // Find Item in Chest
        if (activeTab == TAB_LIST && finderBtnW > 0 && finderBtnEnabled
                && my >= finderBtnY && my <= finderBtnY + 14
                && mx >= finderBtnX && mx <= finderBtnX + finderBtnW) {
            GatherUi.playClickSound();
            client.setScreen(new GatherChestFinderScreen(this));
            return true;
        }

        if (activeTab == TAB_LIST) {
            String baseMaterial = baseMaterialAt(mx, my, lx, height-BOTTOM_H-SUMMARY_H-4);
            if (baseMaterial != null) {
                GatherUi.playClickSound();
                GatherState.get().toggleBaseMaterialHidden(baseMaterial);
                WorldHighlightRenderer.invalidateCache();
                return true;
            }
        }

        // Mode toggle (right panel, Add tab only)
        if (activeTab == TAB_ADD
                && my >= addModeToggleY && my <= addModeToggleY + TOGGLE_H
                && mx >= addModeToggleX && mx <= addModeToggleX + TOGGLE_W) {
            GatherUi.playClickSound();
            GatherSettings.get().countExistingOnAdd = !GatherSettings.get().countExistingOnAdd;
            GatherSettings.get().save();
            return true;
        }

        if      (activeTab == TAB_LIST)   return handleListClick(mx, my, lx, PAD+22+4, btn);
        else if (activeTab == TAB_RECENT) return handleRecentClick(mx, my, lx, PAD+22+4);
        else                              return handleAddClick(mx, my, lx, PAD+22+4);
    }

    private boolean handleListClick(int mx, int my, int lx, int ly, int btn) {
        GatherState state = GatherState.get();
        int summaryY = height-BOTTOM_H-SUMMARY_H-4;
        int visCount = (summaryY-ly)/ENTRY_H;
        List<VisRow> rows = buildVisibleRows(state);

        for (int i = 0; i < visCount && (i+listScroll) < rows.size(); i++) {
            VisRow row = rows.get(i+listScroll);
            int y = ly+i*ENTRY_H;
            if (my < y || my > y+ENTRY_H-2 || mx < lx || mx > lx+LIST_W) continue;
            if (row instanceof VisRow.Header h) return handleHeaderClick(mx, my, lx, y, h.listIndex(), btn);
            if (row instanceof VisRow.Node   n) return handleNodeClick(mx, my, lx, y, n.listIndex(), n.nodeIndex(), state, btn);
        }
        return false;
    }

    private String baseMaterialAt(int mx, int my, int lx, int summaryY) {
        if (mx < lx || mx > lx + LIST_W || my < summaryY + 16 || my > summaryY + SUMMARY_H) return null;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(getBaseMaterialSummary().entrySet());
        if (entries.isEmpty()) return null;
        int tagW = (LIST_W - 12) / 2;
        int rowsVisible = Math.max(1, (SUMMARY_H - 18) / 18);
        int entriesVisible = rowsVisible * 2;
        int maxScroll = Math.max(0, entries.size() - entriesVisible);
        baseScroll = Math.max(0, Math.min(baseScroll, maxScroll));
        int tx = lx + 4;
        int ty = summaryY + 16;
        int col = 0;
        for (int i = baseScroll; i < entries.size() && i < baseScroll + entriesVisible; i++) {
            if (mx >= tx && mx <= tx + tagW && my >= ty && my <= ty + 16) return entries.get(i).getKey();
            col++;
            if (col % 2 == 0) {
                tx = lx + 4;
                ty += 18;
            } else {
                tx += tagW + 4;
            }
        }
        return null;
    }

    private boolean handleHeaderClick(int mx, int my, int lx, int y, int listIndex, int btn) {
        GatherState state = GatherState.get();
        int bx = lx+LIST_W-2, delW = 36, renW = 44, gap = 3;
        int delX = bx-delW, renX = delX-gap-renW;
        int btnY = y + (ENTRY_H - 2 - 14) / 2;

        if (state.getListCount() > 1 && mx>=delX && mx<=bx) {
            GatherUi.playClickSound();
            commitRename();
            state.removeList(listIndex);
            if (editingList==listIndex){editingList=-1;editingIndex=-1;editField.setVisible(false);}
            return true;
        }
        if (mx>=renX && mx<=renX+renW) {
            GatherUi.playClickSound();
            if (renamingList==listIndex) { commitRename(); }
            else {
                commitRename();
                renamingList = listIndex;
                renameField.setText(state.getList(listIndex).name);
                renameField.setVisible(true); setFocused(renameField);
            }
            return true;
        }
        if (btn == 1 && !(mx>=renX && mx<=bx)) {
            GatherUi.playClickSound();
            state.toggleListHudHidden(listIndex);
            return true;
        }
        return false;
    }

    private boolean handleNodeClick(int mx, int my, int lx, int y,
                                     int listIndex, int ni, GatherState state, int btn) {
        List<ListNode> nodes = state.getNodes(listIndex);
        ListNode node = nodes.get(ni);
        int indent = node.depth*14, toggleW = 10;

        // Collapse toggle
        if (node.broken && mx>=lx+indent && mx<=lx+indent+toggleW+2) {
            GatherUi.playClickSound();
            state.setCollapsed(listIndex, ni, !node.collapsed); return true;
        }

        int bx   = lx+LIST_W-2;
        int remW = 44, editW = 28, brkW = 32, chkW = 14, gap = 3;

        int remX, chkX, editX, brkX;
        if (node.depth == 0) {
            remX  = bx - remW;
            chkX  = remX - gap - chkW;
            editX = chkX - gap - editW;
            brkX  = editX - gap - brkW;
            if (mx>=remX && mx<=bx) {
                GatherUi.playClickSound();
                commitEdit(); state.removeNode(listIndex, ni);
                editingList=-1; editingIndex=-1; editField.setVisible(false); return true;
            }
        } else {
            chkX  = bx - chkW;
            editX = chkX - gap - editW;
            brkX  = editX - gap - brkW;
            remX  = chkX;
        }

        if (mx>=chkX && mx<=chkX+chkW) {
            GatherUi.playClickSound();
            state.toggleSubtreeHidden(listIndex, ni);
            WorldHighlightRenderer.invalidateCache();
            return true;
        }
        if (mx>=editX && mx<=editX+editW) {
            GatherUi.playClickSound();
            if (editingList==listIndex && editingIndex==ni) { commitEdit(); }
            else {
                commitEdit(); editingList=listIndex; editingIndex=ni;
                editField.setText(String.valueOf(node.needed)); editField.setVisible(true); setFocused(editField);
            }
            return true;
        }
        if (node.broken && node.inventoryCraftable && computeMaxCraftableChained(listIndex, ni, node, nodes)>=1
                && mx>=brkX && mx<=brkX+brkW) {
            GatherUi.playClickSound();
            doChainCraft(listIndex, ni, node, state); return true;
        }

        // Left-click on root node non-button area: start drag potential
        if (btn == 0 && node.depth == 0) {
            potentialDragList = listIndex;
            potentialDragNode = ni;
            return true;
        }
        return false;
    }

    private boolean handleAddClick(int mx, int my, int lx, int ly) {
        int searchY  = ly+12;
        int itemsTop = searchY+20;
        int fieldW   = 52, fieldX = lx+LIST_W-2-fieldW;
        int visCount = (height-itemsTop-BOTTOM_H-4)/ENTRY_H;

        for (int i = 0; i < visCount && (i+addScroll) < filteredItems.size(); i++) {
            Item item = filteredItems.get(i+addScroll);
            int  y    = itemsTop+i*ENTRY_H;
            if (my<y||my>y+ENTRY_H-2||mx<lx||mx>lx+LIST_W) continue;
            String id = Registries.ITEM.getId(item).toString();
            int rowStarY = addRowStarY(y);
            if (mx >= favoriteStarX && mx <= favoriteStarX + favoriteStarSize
                    && my >= rowStarY && my <= rowStarY + favoriteStarSize) {
                GatherUi.playClickSound();
                GatherSettings.get().toggleFavoriteItem(id);
                GatherSettings.get().save();
                filterItems(addSearch.getText());
                return true;
            }
            if (!id.equals(addFocusedItemId)) {
                GatherUi.playClickSound();
                commitAddAmount();
                addFocusedItemId=id; addAmountField.setText(""); addAmountField.setVisible(true);
                setFocused(addAmountField);
            }
            return true;
        }
        if (addFocusedItemId != null) commitAddAmount();
        return false;
    }

    private int addRowStarY(int rowY) {
        return rowY + (ENTRY_H - 2 - favoriteStarSize) / 2 + 1;
    }

    // ─── DRAG & DROP ─────────────────────────────────────────────────────────

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (menuAnimationBlockingInput()) return true;
        if (click.button() == 0 && potentialDragList >= 0) {
            isDragging  = true;
            dragGhostX  = (int)click.x();
            dragGhostY  = (int)click.y();
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (menuAnimationBlockingInput()) return true;
        if (click.button() == 0) {
            if (isDragging && potentialDragList >= 0) {
                int mx = (int)click.x(), my = (int)click.y();
                int lx = width/2-LIST_W/2, ly = PAD+22+4;
                int summaryY = height-BOTTOM_H-SUMMARY_H-4;
                int visCount = (summaryY-ly)/ENTRY_H;
                List<VisRow> rows = buildVisibleRows(GatherState.get());
                for (int i = 0; i < visCount && (i+listScroll) < rows.size(); i++) {
                    if (!(rows.get(i+listScroll) instanceof VisRow.Header h)) continue;
                    if (h.listIndex() == potentialDragList) continue;
                    int y = ly+i*ENTRY_H;
                    if (my>=y && my<=y+ENTRY_H-2 && mx>=lx && mx<=lx+LIST_W) {
                        GatherState.get().moveNodeToList(potentialDragList, potentialDragNode, h.listIndex());
                        break;
                    }
                }
            }
            isDragging = false; potentialDragList = -1; potentialDragNode = -1;
            return true;
        }
        return super.mouseReleased(click);
    }

    // ─── SCROLL ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (menuAnimationBlockingInput()) return true;
        int ly = PAD+22+4;
        if (activeTab == TAB_LIST) {
            commitEdit();
            int summaryY = height-BOTTOM_H-SUMMARY_H-4;
            int lx = width/2-LIST_W/2;
            if (mx >= lx && mx <= lx + LIST_W && my >= summaryY && my <= summaryY + SUMMARY_H) {
                int max = maxBaseScroll();
                baseScroll = (int)Math.max(0, Math.min(max, baseScroll-v));
                return true;
            }
            int visCount = (summaryY-ly)/ENTRY_H;
            int max = Math.max(0, buildVisibleRows(GatherState.get()).size()-visCount);
            listScroll = (int)Math.max(0, Math.min(max, listScroll-v));
        } else if (activeTab == TAB_RECENT) {
            int visCount = (height-ly-BOTTOM_H-4)/ENTRY_H;
            int max = Math.max(0, GatherSettings.get().getRecentItems().size()-visCount);
            recentScroll = (int)Math.max(0, Math.min(max, recentScroll-v));
        } else {
            addFocusedItemId = null; addAmountField.setVisible(false);
            int itemsTop = ly+12+20;
            int visCount = (height-itemsTop-BOTTOM_H-4)/ENTRY_H;
            int max = Math.max(0, filteredItems.size()-visCount);
            addScroll = (int)Math.max(0, Math.min(max, addScroll-v));
        }
        return true;
    }

    private int maxBaseScroll() {
        GatherState state = GatherState.get();
        Map<String, Integer> base = getBaseMaterialSummary();
        int rowsVisible = Math.max(1, (SUMMARY_H - 18) / 18);
        return Math.max(0, base.size() - rowsVisible * 2);
    }

    // ─── KEYS ────────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();

        boolean anyFieldActive = (addSearch      != null && addSearch.isActive()      && getFocused() == addSearch)
                              || (addAmountField != null && addAmountField.isActive() && getFocused() == addAmountField)
                              || (editField      != null && editField.isActive()      && getFocused() == editField)
                              || (renameField    != null && renameField.isActive()    && getFocused() == renameField);
        if (!anyFieldActive && (isGatherMenuKey(input) || key==GLFW.GLFW_KEY_E)) {
            commitEdit(); commitAddAmount(); commitRename(); close(); return true;
        }

        // Picker key handling: takes full priority
        if (pendingAddItemId != null) {
            int n = GatherState.get().getListCount();
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                pendingAddItemId = null; pendingAddCount = 0; return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                executeAdd(pendingPickerSelected); return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                pendingPickerSelected = (pendingPickerSelected - 1 + n) % n; return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                pendingPickerSelected = (pendingPickerSelected + 1) % n; return true;
            }
            if (key == GLFW.GLFW_KEY_LEFT) {
                pendingPickerSelected = Math.max(0, pendingPickerSelected - PICKER_ROWS); return true;
            }
            if (key == GLFW.GLFW_KEY_RIGHT) {
                pendingPickerSelected = Math.min(n-1, pendingPickerSelected + PICKER_ROWS); return true;
            }
            return true; // absorb everything else
        }

        boolean searchFocused = addSearch   != null && addSearch.isActive()   && getFocused() == addSearch;
        boolean renameFocused = renameField != null && renameField.isActive() && getFocused() == renameField;

        if (!searchFocused && !renameFocused && key==GLFW.GLFW_KEY_LEFT)  { switchTab(TAB_LIST); return true; }
        if (!searchFocused && !renameFocused && key==GLFW.GLFW_KEY_RIGHT) { switchTab(TAB_ADD);  return true; }

        if (renamingList>=0 && (key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)) { commitRename(); return true; }
        if (renamingList>=0 && key==GLFW.GLFW_KEY_ESCAPE) { renamingList=-1; renameField.setVisible(false); return true; }

        if (editingIndex>=0 && (key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)) { commitEdit(); return true; }
        if (editingIndex>=0 && key==GLFW.GLFW_KEY_ESCAPE) { editingList=-1; editingIndex=-1; editField.setVisible(false); return true; }

        if (addFocusedItemId!=null && (key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)) { commitAddAmount(); return true; }
        if (addFocusedItemId!=null && key==GLFW.GLFW_KEY_ESCAPE) { addFocusedItemId=null; addAmountField.setVisible(false); return true; }
        return super.keyPressed(input);
    }

    private boolean isGatherMenuKey(KeyInput input) {
        InputUtil.Key gatherKey = KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.openMenu);
        return gatherKey.getCode() == input.key();
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private void commitEdit() {
        if (editingList<0||editingIndex<0) return;
        List<ListNode> nodes = GatherState.get().getNodes(editingList);
        if (editingIndex < nodes.size()) {
            ListNode edited = nodes.get(editingIndex);
            try {
                GatherState.get().setCount(editingList, editingIndex,
                        Math.min(MAX_WANTED_AMOUNT, Integer.parseInt(editField.getText().trim())));
                if (edited.depth==0 && editingIndex<GatherState.get().getNodes(editingList).size())
                    refreshRootBreakdown(editingList, edited.itemId);
            } catch (NumberFormatException ignored) {}
        }
        editingList=-1; editingIndex=-1; editField.setVisible(false);
    }

    private void commitRename() {
        if (renamingList<0) return;
        GatherState.get().renameList(renamingList, renameField.getText().trim());
        renamingList=-1; renameField.setVisible(false);
    }

    private void commitAddAmount() {
        if (addFocusedItemId==null) return;
        String itemId = addFocusedItemId;
        int n;
        try { n = Integer.parseInt(addAmountField.getText().trim()); }
        catch (NumberFormatException ignored) { n = 0; }

        addFocusedItemId=null; addAmountField.setText(""); addAmountField.setVisible(false);

        if (n <= 0) return;
        int count = Math.min(n, MAX_WANTED_AMOUNT);

        pendingAddItemId     = itemId;
        pendingAddCount      = count;
        pendingPickerSelected = GatherState.get().getLastAddedListIndex();
        if (GatherState.get().getListCount() == 1) {
            executeAdd(0); // clears pending state internally
        }
        // else: picker overlay stays open until user clicks a list or presses Enter
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

    private void executeAdd(int listIndex) {
        if (pendingAddItemId == null || pendingAddCount <= 0) return;
        int baseline = GatherSettings.get().countExistingOnAdd ? 0 : countForId(pendingAddItemId);
        invalidateCountCaches();
        GatherState.get().addItem(listIndex, pendingAddItemId, pendingAddCount, baseline);
        refreshRootBreakdown(listIndex, pendingAddItemId);
        GatherSettings.get().addRecentItem(pendingAddItemId, pendingAddCount);
        GatherSettings.get().save();
        pendingAddItemId = null; pendingAddCount = 0;
    }

    private void switchTab(int tab) {
        commitEdit();
        if (tab == TAB_ADD) {
            activeTab=TAB_ADD; addSearch.setVisible(true);
            addFocusedItemId=null; addAmountField.setVisible(false); setFocused(addSearch);
        } else if (tab == TAB_RECENT) {
            commitAddAmount(); addSearch.setVisible(false); activeTab=TAB_RECENT; setFocused(null);
        } else {
            commitAddAmount(); addSearch.setVisible(false); activeTab=TAB_LIST; setFocused(null);
        }
    }

    private void refreshRootBreakdown(int listIndex, String itemId) {
        List<ListNode> nodes = GatherState.get().getNodes(listIndex);
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).depth==0 && nodes.get(i).itemId.equals(itemId)) {
                expandNodeAtIndex(listIndex, i, ()->{});  return;
            }
        }
    }

    private void expandNodeAtIndex(int listIndex, int nodeIndex, Runnable onDone) {
        List<ListNode> nodes = GatherState.get().getNodes(listIndex);
        if (nodeIndex<0||nodeIndex>=nodes.size()){onDone.run();return;}
        ListNode node = nodes.get(nodeIndex);
        GatherClientNetworking.addBreakdownCallback(node.itemId, payload -> {
            applyBreakdown(listIndex, nodeIndex, payload.ingredients(), payload.inventoryCraftable());
            List<ListNode> cur = GatherState.get().getNodes(listIndex);
            if (nodeIndex>=cur.size()||!cur.get(nodeIndex).broken){onDone.run();return;}
            expandChildrenSequentially(listIndex, nodeIndex, nodeIndex+1, onDone);
        });
        GatherClientNetworking.requestBreakdown(node.itemId, node.needed, 1);
    }

    private void expandChildrenSequentially(int listIndex, int parentIndex, int childIndex, Runnable onDone) {
        List<ListNode> nodes = GatherState.get().getNodes(listIndex);
        if (parentIndex<0||parentIndex>=nodes.size()){onDone.run();return;}
        int pd = nodes.get(parentIndex).depth;
        if (childIndex>=nodes.size()||nodes.get(childIndex).depth<=pd){onDone.run();return;}
        expandNodeAtIndex(listIndex, childIndex,
                ()->expandChildrenSequentially(listIndex, parentIndex, findSubtreeEnd(listIndex, childIndex), onDone));
    }

    private int findSubtreeEnd(int listIndex, int index) {
        List<ListNode> nodes = GatherState.get().getNodes(listIndex);
        if (index<0||index>=nodes.size()) return index;
        int depth = nodes.get(index).depth, end = index+1;
        while (end<nodes.size()&&nodes.get(end).depth>depth) end++;
        return end;
    }

    private void applyBreakdown(int listIndex, int index, Map<String,Integer> ingredients, boolean craftable) {
        if (index < GatherState.get().getNodes(listIndex).size())
            GatherState.get().insertBreakdown(listIndex, index, ingredients, craftable);
    }

    // ─── VIS ROWS ────────────────────────────────────────────────────────────

    private List<VisRow> buildVisibleRows(GatherState state) {
        List<VisRow> rows = new ArrayList<>();
        for (int li = 0; li < state.getListCount(); li++) {
            rows.add(new VisRow.Header(li));
            List<ListNode> nodes = state.getNodes(li);
            Deque<Integer> collapseStack = new ArrayDeque<>();
            int nodeCount = 0;
            for (int ni = 0; ni < nodes.size(); ni++) {
                ListNode node = nodes.get(ni);
                while (!collapseStack.isEmpty() && node.depth <= collapseStack.peek()) collapseStack.pop();
                if (!collapseStack.isEmpty()) continue;
                if (node.depth>0 && computeDisplayNeeded(li, ni, node, nodes)<=0) continue;
                rows.add(new VisRow.Node(li, ni));
                nodeCount++;
                if (node.broken && node.collapsed) collapseStack.push(node.depth);
            }
            if (nodeCount == 0) rows.add(new VisRow.Empty(li));
        }
        return rows;
    }

    private int indexInRows(List<VisRow> rows, int listIndex, int nodeIndex) {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i) instanceof VisRow.Node n && n.listIndex()==listIndex && n.nodeIndex()==nodeIndex) return i;
        return -1;
    }

    private int indexOfHeader(List<VisRow> rows, int listIndex) {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i) instanceof VisRow.Header h && h.listIndex()==listIndex) return i;
        return -1;
    }

    // ─── CRAFT HELPERS ───────────────────────────────────────────────────────

    private int effectiveHave(int listIndex, ListNode node) {
        int raw = countForId(node.itemId);
        return (node.depth==0) ? Math.max(0, raw-node.baseline) : raw;
    }

    private int countForId(String itemId) {
        if (client==null||client.player==null) return 0;
        return countCache.computeIfAbsent(itemId, id -> {
            Item it = Registries.ITEM.get(Identifier.of(id));
            if (it == null) return 0;
            int inv = GatherHud.countInventoryTagAware(client, it); // includes shulkers in inventory
            return inv + (GatherSettings.get().countChests
                    ? GatherState.get().getTrackedChestCountMatching(id)
                    : GatherState.get().getManualChestCountMatching(id));
        });
    }

    private Map<Item, Integer> countInventoryByType(Item neededItem) {
        if (client==null||client.player==null) return Map.of();
        Map<Item, Integer> cached = typeCountCache.get(neededItem);
        if (cached != null) return cached;
        String itemPath = net.minecraft.registry.Registries.ITEM.getId(neededItem).getPath();
        String typeWord = itemPath.contains("_")
                ? itemPath.substring(itemPath.lastIndexOf('_') + 1)
                : itemPath;
        Set<TagKey<Item>> tags = neededItem.getRegistryEntry().streamTags()
                .filter(tag -> tag.id().getPath().contains(typeWord))
                .collect(Collectors.toSet());
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item inv = stack.getItem();
            if (inv==neededItem || (!tags.isEmpty() && inv.getRegistryEntry().streamTags().anyMatch(tags::contains)))
                result.merge(inv, stack.getCount(), Integer::sum);
        }
        typeCountCache.put(neededItem, result);
        return result;
    }

    private void refreshCountCachesIfStale() {
        long now = System.currentTimeMillis();
        if (now < countCacheExpiresAtMs) return;
        invalidateCountCaches();
        countCacheExpiresAtMs = now + COUNT_CACHE_MS;
    }

    private void invalidateCountCaches() {
        countCache.clear();
        typeCountCache.clear();
    }

    // Recursive: propagates effective need through entire ancestor chain.
    // Uses parentDisplay (not parent.needed) for pStill so a satisfied grandparent
    // correctly zeroes out all descendants (e.g. Stick satisfied → Planks need=0 → Log need=0).
    private int computeDisplayNeeded(int listIndex, int ni, ListNode node, List<ListNode> nodes) {
        if (node.depth == 0) return node.needed;
        for (int pi = ni - 1; pi >= 0; pi--) {
            ListNode parent = nodes.get(pi);
            if (parent.depth == node.depth - 1) {
                int parentDisplay = computeDisplayNeeded(listIndex, pi, parent, nodes);
                if (parentDisplay == 0) return 0;
                int pHave  = effectiveHave(listIndex, parent);
                int pStill = Math.max(0, parentDisplay - pHave);
                if (parent.needed > 0)
                    return (int)Math.ceil((double)node.needed * pStill / parent.needed);
                break;
            }
        }
        return node.needed;
    }

    private int computeMaxCraftableChained(int listIndex, int ni, ListNode node, List<ListNode> nodes) {
        long max = Long.MAX_VALUE; boolean hasChildren = false;
        for (int j = ni+1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth<=node.depth) break;
            if (child.depth!=node.depth+1||child.needed<=0) continue;
            hasChildren = true;
            long ah = countForId(child.itemId);
            long mc = child.broken ? computeMaxCraftableChained(listIndex, j, child, nodes) : 0;
            long cm = (ah+mc)*node.needed/child.needed;
            if (cm < max) max = cm;
        }
        return hasChildren && max!=Long.MAX_VALUE ? (int)max : 0;
    }

    private void doChainCraft(int listIndex, int ni, ListNode node, GatherState state) {
        List<ListNode> nodes = state.getNodes(listIndex);
        int stillNeed = Math.max(0, node.needed-effectiveHave(listIndex, node));
        if (stillNeed==0) return;
        int maxChain = computeMaxCraftableChained(listIndex, ni, node, nodes);
        if (maxChain<=0) return;
        doChainCraftInternal(listIndex, ni, node, nodes, Math.min(stillNeed, maxChain), new HashMap<>());
    }

    private void doChainCraftInternal(int listIndex, int ni, ListNode node, List<ListNode> nodes,
                                       int outputCount, Map<String,Integer> virtualInv) {
        for (int j = ni+1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth<=node.depth) break;
            if (child.depth!=node.depth+1||child.needed<=0) continue;
            int ingNeeded = (int)Math.ceil((double)child.needed*outputCount/node.needed);
            int have = countForId(child.itemId)+virtualInv.getOrDefault(child.itemId, 0);
            int toMake = Math.max(0, ingNeeded-have);
            if (toMake>0 && child.broken) {
                doChainCraftInternal(listIndex, j, child, nodes, toMake, virtualInv);
                virtualInv.merge(child.itemId, toMake, Integer::sum);
            }
        }
        List<AutoCraftPayload.IngredientEntry> consume = new ArrayList<>();
        for (int j = ni+1; j < nodes.size(); j++) {
            ListNode child = nodes.get(j);
            if (child.depth<=node.depth) break;
            if (child.depth!=node.depth+1||child.needed<=0) continue;
            int ingNeeded = (int)Math.ceil((double)child.needed*outputCount/node.needed);
            int fromVirtual = Math.min(ingNeeded, virtualInv.getOrDefault(child.itemId, 0));
            if (fromVirtual>0) {
                consume.add(new AutoCraftPayload.IngredientEntry(child.itemId, fromVirtual));
                virtualInv.merge(child.itemId, -fromVirtual, Integer::sum);
                ingNeeded -= fromVirtual;
            }
            if (ingNeeded>0) {
                Item ci = Registries.ITEM.get(Identifier.of(child.itemId));
                List<Map.Entry<Item,Integer>> avail = new ArrayList<>(countInventoryByType(ci).entrySet());
                avail.sort((a,b)->b.getValue()-a.getValue());
                int rem = ingNeeded;
                for (var e : avail) {
                    if (rem<=0) break;
                    int use = Math.min(rem, e.getValue());
                    consume.add(new AutoCraftPayload.IngredientEntry(Registries.ITEM.getId(e.getKey()).toString(), use));
                    rem -= use;
                }
            }
        }
        GatherClientNetworking.sendAutoCraft(node.itemId, outputCount, consume);
    }

    // ─── DRAW UTILS ──────────────────────────────────────────────────────────

    private static String shortName(Item item) {
        String name = item.getName().getString();
        int sp = name.indexOf(' ');
        return sp>0 ? name.substring(0,sp) : name;
    }

    private void drawButton(DrawContext ctx, int bx, int by, int bw, int bh,
                             String label, int mx, int my, boolean hov) {
        drawMenuButtonFrame(ctx, bx, by, bw, bh, hov, false, false, false);
        drawThemedText(ctx, Text.literal(label),
                bx+(bw-textRenderer.getWidth(label))/2, by+(bh-8)/2, GatherTheme.textButton());
        if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
    }

    private void drawDisabledButton(DrawContext ctx, int bx, int by, int bw, int bh, String label) {
        drawMenuButtonFrame(ctx, bx, by, bw, bh, false, false, true, false);
        drawThemedText(ctx, Text.literal(label),
                bx+(bw-textRenderer.getWidth(label))/2, by+(bh-8)/2, GatherTheme.textDisabled());
    }

    private void drawNodeCheckbox(DrawContext ctx, int bx, int by, int bw, int bh,
                                   boolean checked, boolean hov) {
        GatherTheme.drawStretch(ctx,
                checked ? (hov ? GatherTheme.MENU_CHECKBOX_ON_HOVER : GatherTheme.MENU_CHECKBOX_ON)
                        : (hov ? GatherTheme.MENU_CHECKBOX_OFF_HOVER : GatherTheme.MENU_CHECKBOX_OFF),
                bx, by, bw, bh);
        if (hov) ctx.setCursor(StandardCursors.POINTING_HAND);
    }

    private static final int SIDE_TOGGLE_W = 112;
    private static final int SIDE_SMALL_W  = 86;
    private static final int FINDER_H      = 15;

    private static void drawMenuButtonFrame(DrawContext ctx, int x, int y, int w, int h,
                                            boolean hover, boolean active, boolean disabled, boolean danger) {
        GatherTheme.drawNineSlice(ctx,
                disabled ? GatherTheme.MENU_BUTTON_DISABLED
                        : danger ? GatherTheme.MENU_BUTTON_DANGER
                        : active ? GatherTheme.MENU_BUTTON_ACTIVE
                        : hover ? GatherTheme.MENU_BUTTON_HOVER
                        : GatherTheme.MENU_BUTTON,
                x, y, w, h);
    }

    private static void drawSideButtonFrame(DrawContext ctx, int x, int y, int w, int h,
                                            GatherTheme.NineSlice texture) {
        GatherTheme.drawNineSlice(ctx, texture, x, y, w, h);
    }

    private static void drawMenuRow(DrawContext ctx, int x, int y, int w, int h,
                                    boolean hover, boolean complete, boolean dragSource) {
        GatherTheme.drawNineSlice(ctx,
                dragSource ? GatherTheme.MENU_ROW_DRAG
                        : hover ? GatherTheme.MENU_ROW_HOVER
                        : complete ? GatherTheme.MENU_ROW_COMPLETE
                        : GatherTheme.MENU_ROW,
                x, y, w, h);
    }

    private static void drawMaterialChip(DrawContext ctx, int x, int y, int w, int h,
                                         boolean hover, boolean complete, boolean disabled) {
        GatherTheme.drawNineSlice(ctx,
                disabled ? GatherTheme.MENU_MATERIAL_CHIP_DISABLED
                        : complete ? GatherTheme.MENU_MATERIAL_CHIP_COMPLETE
                        : hover ? GatherTheme.MENU_MATERIAL_CHIP_HOVER
                        : GatherTheme.MENU_MATERIAL_CHIP,
                x, y, w, h);
    }

    private void drawThemedText(DrawContext ctx, Text text, int x, int y, int color) {
        ctx.drawText(textRenderer, text, x, y, color, true);
    }

    private void drawCenteredThemedText(DrawContext ctx, Text text, int x, int y, int color) {
        ctx.drawCenteredTextWithShadow(textRenderer, text, x, y, color);
    }

    private void drawCentered(DrawContext ctx, String label, int x, int y, int w, int h, int color) {
        String fit = textRenderer.trimToWidth(label, Math.max(1, w - 4));
        drawThemedText(ctx, Text.literal(fit),
                x + (w - textRenderer.getWidth(fit)) / 2, y + (h - 8) / 2 + 1, color);
    }

    private void drawCenteredClamped(DrawContext ctx, String label, int cx, int y, int maxW, int color) {
        String fit = textRenderer.trimToWidth(label, Math.max(1, maxW));
        drawThemedText(ctx, Text.literal(fit), cx - textRenderer.getWidth(fit) / 2, y, color);
    }

    @Override
    public void close() {
        if (closing) return;
        closing = true;
        closingAtMs = System.currentTimeMillis();
        if (GatherSettings.get().menuSpinAnimation) GatherUi.playMenuCloseSound();
    }

    @Override
    public boolean shouldPause() { return false; }
}
