package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherSettings;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.Items;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class GatherTutorialScreen extends Screen {

    // Mirrored from GatherMenuScreen
    private static final int MENU_LIST_W  = 340;
    private static final int MENU_PAD     = 6;
    private static final int MENU_BOT_H   = 28;
    private static final int MENU_TOGGLE_W = 84;
    private static final int MENU_TOGGLE_H = 20;

    private static final int CARD_W       = 340;   // world / crafting / shulker steps
    private static final int CARD_W_CTR   = 420;   // centered overlay steps
    private static final int NAV_BTN_W    = 66;
    private static final int NAV_BTN_H    = 16;
    private static final int CARD_ANIM_MS = 350;

    // Absolute Y positions traced from renderListTab with Scan All OFF
    private static final int Y_SCAN_LABEL  = 40;
    private static final int Y_OUTLINES    = 114;
    private static final int Y_FINDER      = 132;

    @FunctionalInterface
    private interface HlFn { int[] get(int sw, int sh); }

    private enum BgType  { WORLD, MENU, CRAFTING, TRADE, SHULKER }
    private enum CardPos { DEFAULT, CENTER }

    private record Step(String title, BgType bg, int menuTab, HlFn hl, CardPos pos, String[] lines) {}

    private static Step step(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.DEFAULT, lines);
    }
    private static Step stepC(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.CENTER, lines);
    }

    // §e = yellow  §a = green  §6 = gold  §b = aqua  §7 = gray  §l = bold  §o = italic  §r = reset
    private static final Step[] STEPS = {
        step("Welcome to Gather!", BgType.WORLD, -1, null,
            "Gather tracks items you need: goals, materials, chests, crafting.",
            "Use §e← →§r or the buttons below to navigate."),
        step("Goals Column", BgType.WORLD, -1,
            (sw, sh) -> hudMod(sw, 0),
            "Your active §6§lgoals§r appear in the top-left HUD.",
            "Each row shows the item and how many more you still need."),
        step("Base Materials", BgType.WORLD, -1,
            (sw, sh) -> hudMod(sw, 1),
            "Goals auto-break into §6§lbase materials§r you actually collect.",
            "Shared ingredients across goals are merged into one row."),
        step("Craft Ready", BgType.WORLD, -1,
            (sw, sh) -> hudMod(sw, 2),
            "Items you can §a§lcraft right now§r appear in this column.",
            "Shulker contents in your inventory count toward crafting."),
        stepC("The Gather Menu  [G]", BgType.MENU, 0,
            (sw, sh) -> new int[]{ 0, 0, sw, sh },
            "Press §e§l[G]§r to open the Gather menu at any time.",
            "Manage goals, lists, chest scanning, and settings."),
        stepC("Three Tabs", BgType.MENU, 0,
            (sw, sh) -> tabsOnly(sw),
            "§eMy Lists§r: active goals.   §eAdd Items§r: search & add goals.",
            "§eRecent§r: quickly re-add past goals for this world."),
        step("Adding Items", BgType.MENU, 1,
            (sw, sh) -> addWithTab(sw, sh),
            "Search by name or ID, set a quantity, and click to add a goal.",
            "§e★ Favorite§r items sort to the top of every search."),
        stepC("Count Mode", BgType.MENU, 1,
            (sw, sh) -> addModeRegion(sw, sh),
            "§b+More§r mode: adds N more to gather, ignores what you already have.",
            "§bTotal§r mode: sets a full target; existing items count toward it.",
            "Toggle with the §e+More / Total§r button in the right panel."),
        stepC("Goal Lists", BgType.MENU, 0,
            (sw, sh) -> newListRegion(sw, sh),
            "Press §e+ New List§r to create a named list for any project.",
            "§7Right-click§r a header to hide/show in HUD.  §7Drag§r items to reorder."),
        stepC("Chest Scanning", BgType.MENU, 0,
            (sw, sh) -> leftAbsolute(sw, Y_SCAN_LABEL, (Y_OUTLINES + 14) - Y_SCAN_LABEL),
            "§a§lScan All§r auto-tracks every chest you open.",
            "§aChest Outlines§r highlights known chests. Set range in its settings."),
        stepC("Item Finder", BgType.MENU, 0,
            (sw, sh) -> leftAbsolute(sw, Y_FINDER, 14),
            "Select a needed item; a §acompass arrow§r appears near your crosshair.",
            "Points to the nearest tracked chest containing that item."),
        step("Crafting Overlay", BgType.CRAFTING, -1,
            (sw, sh) -> craftingPanelRegion(sw, sh),
            "Open a §ecrafting table§r while goals are active.",
            "§a§lCrafting Goals§r panel lists items you can craft right now.",
            "§eClick§r a row to craft one  ·  §eRight-click§r to craft all possible."),
        step("Trade Calculator", BgType.TRADE, -1,
            (sw, sh) -> tradePanelRegion(sw, sh),
            "Open a §emerchant screen§r to see the trade calculator button.",
            "Pick a trade, enter how many results you want, then add the buy items as goals.",
            "Great for villager books, tools, and bulk emerald trades."),
        step("Collector Shulker", BgType.SHULKER, -1,
            (sw, sh) -> shulkerButtonRegion(sw, sh),
            "§a§lCollector§r shulker auto-pulls needed items as you play.",
            "§bAll Goals§r: pulls everything needed.  §bCertain§r: pick specific items.",
            "§bKeep 1§r: leaves 1 of each item in inventory instead of moving all."),
        stepC("You're all set!", BgType.WORLD, -1, null,
            "Add goals, start collecting, and let Gather track the rest.",
            "Find this tour again via §eSettings → Tutorial§r."),
    };

    // ── Fake HUD example data ────────────────────────────────────────────────

    private static final Object[][] FAKE_GOALS = {
        { Items.OAK_LOG,    "Oak Log",    "×32" },
        { Items.DIAMOND,    "Diamond",    "×8"  },
        { Items.IRON_INGOT, "Iron Ingot", "×16" },
    };
    private static final Object[][] FAKE_MATERIALS = {
        { Items.OAK_LOG,    "Oak Log",     "12/32" },
        { Items.COBBLESTONE,"Cobblestone", "48/64" },
        { Items.RAW_IRON,   "Raw Iron",    "0/16"  },
    };
    private static final Object[][] FAKE_CRAFT = {
        { Items.STICK,      "Stick",      "ready" },
        { Items.OAK_PLANKS, "Oak Planks", "ready" },
    };

    // ── State ────────────────────────────────────────────────────────────────

    private int  step        = 0;
    private long stepStartMs = 0;
    private GatherMenuScreen embeddedMenu = null;

    private float cardFromY, cardToY, cardFromX, cardToX;
    private long  cardAnimStart;
    private int   renderedCardY, renderedCardX, renderedCardW, renderedCardH;

    public GatherTutorialScreen() {
        super(Component.literal("Gather Tutorial"));
    }

    // ── Highlight helpers ────────────────────────────────────────────────────

    private static int[] hudMod(int sw, int which) {
        GatherSettings s = GatherSettings.get();
        int x, y, w;
        switch (which) {
            case 0  -> { x = rx(sw, s.layoutGoalsX,      s.layoutGoalsW);      y = s.layoutGoalsY;      w = s.layoutGoalsW; }
            case 1  -> { x = rx(sw, s.layoutMaterialsX,  s.layoutMaterialsW);  y = s.layoutMaterialsY;  w = s.layoutMaterialsW; }
            default -> { x = rx(sw, s.layoutCraftHintsX, s.layoutCraftHintsW); y = s.layoutCraftHintsY; w = s.layoutCraftHintsW; }
        }
        return new int[]{ x, y, w, 100 };
    }

    private static int rx(int sw, int rawX, int w) { return rawX < 0 ? sw + rawX : rawX; }

    private static int[] tabsOnly(int sw) {
        int tabW = 90, gap = 2;
        int t0x = sw / 2 - tabW - gap - tabW / 2;
        return new int[]{ t0x, MENU_PAD, tabW * 3 + gap * 2, 20 };
    }

    private static int[] addWithTab(int sw, int sh) {
        int lx   = sw / 2 - MENU_LIST_W / 2;
        int tabW = 90;
        int t1x  = sw / 2 - tabW / 2;
        int x1   = Math.min(lx, t1x);
        int x2   = Math.max(lx + MENU_LIST_W, t1x + tabW);
        int y1   = MENU_PAD;
        int y2   = (MENU_PAD + 22 + 4) + Math.min(sh / 2, 200);
        return new int[]{ x1, y1, x2 - x1, y2 - y1 };
    }

    private static int[] leftAbsolute(int sw, int y, int h) {
        int cx = (sw / 2 - MENU_LIST_W / 2) / 2;
        return new int[]{ cx - 75, y, 150, h };
    }

    // Right-panel mode toggle: wide enough to cover the label + button + description text
    private static int[] addModeRegion(int sw, int sh) {
        int lx   = sw / 2 - MENU_LIST_W / 2;
        int rpCx = (lx + MENU_LIST_W + sw) / 2;
        int ty   = sh / 2 - MENU_TOGGLE_H / 2;
        int hlW  = 210;  // wide enough for the longest desc line
        return new int[]{ rpCx - hlW / 2, ty - 18, hlW, MENU_TOGGLE_H + 18 + 36 };
    }

    // + New List button in lower-left panel
    private static int[] newListRegion(int sw, int sh) {
        int lx     = sw / 2 - MENU_LIST_W / 2;
        int leftCx = lx / 2;
        int panelBot = sh - MENU_BOT_H;
        int panelMid = (MENU_PAD + 22 + 4 + panelBot) / 2;
        int listSectionCY = (panelMid + panelBot) / 2;
        int btnY = listSectionCY - MENU_TOGGLE_H / 2;
        int btnX = leftCx - MENU_TOGGLE_W / 2;
        return new int[]{ btnX - 8, btnY - 18, MENU_TOGGLE_W + 16, MENU_TOGGLE_H + 18 + 8 };
    }

    private static int[] craftingPanelRegion(int sw, int sh) {
        int bgX  = sw / 2 - 88, bgY  = sh / 2 - 83;
        int panW = 186, panH = 132;
        int panX = bgX - panW - 8;
        if (panX < 4) panX = bgX + 4;
        return new int[]{ panX, bgY + 18, panW, panH - 18 };
    }

    private static int[] tradePanelRegion(int sw, int sh) {
        int bgX  = sw / 2 - 88, bgY  = sh / 2 - 83;
        int panW = 222, panH = 168;
        int panX = bgX - panW - 8;
        if (panX < 4) panX = bgX + 4;
        return new int[]{ panX, bgY + 2, panW, panH };
    }

    private static int[] shulkerButtonRegion(int sw, int sh) {
        int bgX = sw / 2 - 88, bgY = sh / 2 - 83;
        int BUTTON_W = 100, BUTTON_H = 14;
        int btnX  = bgX + 176 - BUTTON_W - 6;
        int btn0Y = bgY - 52;
        return new int[]{ btnX - 4, btn0Y - 4, BUTTON_W + 8, 3 * BUTTON_H + 2 * 3 + 8 };
    }

    // ── Card layout helpers ───────────────────────────────────────────────────

    private int menuRightColX() { return width / 2 + MENU_LIST_W / 2 + 10; }
    private int rightColAvail() { return width - menuRightColX() - 4; }

    private int cardWidth(Step s) {
        if (s.pos() == CardPos.CENTER)                          return Math.min(CARD_W_CTR, width - 16);
        if (s.bg() == BgType.MENU && s.menuTab() == 1 && rightColAvail() >= 120) return rightColAvail();
        return Math.min(CARD_W, width - 16);
    }

    private int cardH(Step s, int innerW) {
        int lineCount = 0;
        for (String line : s.lines()) {
            List<FormattedCharSequence> wrapped = font.split(Component.literal(line), innerW);
            lineCount += Math.max(1, wrapped.size());
        }
        return 24 + lineCount * 11 + 26;
    }

    private int targetCardX(int cw, Step s, int[] hl) {
        if (s.bg() == BgType.TRADE) {
            int x = hl != null ? hl[0] + hl[2] + 8 : width / 2 + 88;
            return Math.max(8, Math.min(width - cw - 8, x));
        }
        if (s.pos() == CardPos.CENTER) {
            int x = width / 2 - cw / 2;
            if (s.bg() == BgType.MENU) x = Math.min(x, menuRightColX() - 10 - cw);
            return Math.max(8, Math.min(width - cw - 8, x));
        }
        if (s.bg() == BgType.MENU && s.menuTab() == 1 && rightColAvail() >= 120) return menuRightColX();
        if (hl == null) return width / 2 - cw / 2;
        int hlCx = hl[0] + hl[2] / 2;
        return Math.max(8, Math.min(width - cw - 8, hlCx - cw / 2));
    }

    private int targetCardY(int ch, Step s, int[] hl) {
        if (s.bg() == BgType.TRADE) return height / 2 - ch / 2;
        if (s.pos() == CardPos.CENTER) return height / 2 - ch / 2;
        if (s.bg() == BgType.MENU && s.menuTab() == 1 && rightColAvail() >= 120) {
            if (hl != null) {
                int hlCy = hl[1] + hl[3] / 2;
                return Math.max(8, Math.min(height - ch - 8, hlCy - ch / 2));
            }
            return height / 2 - ch / 2;
        }
        if (hl == null) return step == 0 ? height / 2 - ch / 2 : height - ch - 6;
        int below = hl[1] + hl[3] + 10;
        int above = hl[1] - ch - 10;
        if (below + ch <= height - 4) return below;
        if (above >= 4) return above;
        return height - ch - 6;
    }

    private float ease(long elapsed) {
        float t = Math.min(1f, elapsed / (float) CARD_ANIM_MS);
        return t < 0.5f ? 2f * t * t : -1f + (4f - 2f * t) * t;
    }

    private float animatedCardY() {
        return cardFromY + (cardToY - cardFromY) * ease(System.currentTimeMillis() - cardAnimStart);
    }

    private float animatedCardX() {
        return cardFromX + (cardToX - cardFromX) * ease(System.currentTimeMillis() - cardAnimStart);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        stepStartMs = System.currentTimeMillis();
        if (embeddedMenu == null || embeddedMenu.width != width || embeddedMenu.height != height) {
            embeddedMenu = new GatherMenuScreen(true);
            embeddedMenu.initForTutorial(width, height);
        }
        syncMenuTab();
        Step s   = STEPS[step];
        int cw   = cardWidth(s);
        int ch   = cardH(s, cw - 16);
        int[] hl = s.hl() != null ? s.hl().get(width, height) : null;
        cardFromY = cardToY = targetCardY(ch, s, hl);
        cardFromX = cardToX = targetCardX(cw, s, hl);
        cardAnimStart = System.currentTimeMillis() - CARD_ANIM_MS;
    }

    private void syncMenuTab() {
        Step s = STEPS[step];
        if (s.bg() == BgType.MENU) embeddedMenu.setTutorialTab(s.menuTab());
    }

    private void goTo(int next) {
        if (next < 0) return;
        if (next >= STEPS.length) { finish(); return; }
        float curY = animatedCardY(), curX = animatedCardX();
        step = next;
        stepStartMs = System.currentTimeMillis();
        syncMenuTab();
        Step s   = STEPS[step];
        int cw   = cardWidth(s);
        int ch   = cardH(s, cw - 16);
        int[] hl = s.hl() != null ? s.hl().get(width, height) : null;
        cardFromY = curY;  cardFromX = curX;
        cardToY   = targetCardY(ch, s, hl);
        cardToX   = targetCardX(cw, s, hl);
        cardAnimStart = System.currentTimeMillis();
    }

    private void finish() {
        GatherSettings.get().hasShownWelcome = true;
        GatherSettings.get().save();
        minecraft.setScreen(null);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        Step s   = STEPS[step];
        long now = System.currentTimeMillis();

        switch (s.bg()) {
            case MENU     -> { if (embeddedMenu != null) embeddedMenu.extractRenderState(ctx, -9999, -9999, delta); }
            case CRAFTING -> drawCraftingMockup(ctx);
            case TRADE    -> drawTradeMockup(ctx);
            case SHULKER  -> drawShulkerMockup(ctx);
            default       -> {}
        }

        int[] hl = s.hl() != null ? s.hl().get(width, height) : null;
        drawSpotlight(ctx, hl, now);

        if (s.bg() == BgType.WORLD && hl != null && step >= 1 && step <= 3) {
            drawFakeHudContent(ctx, hl, step - 1);
        }

        int cw = cardWidth(s);
        int ch = cardH(s, cw - 16);
        renderedCardW = cw;
        renderedCardH = ch;
        renderedCardY = (int) animatedCardY();
        renderedCardX = (int) animatedCardX();
        drawCard(ctx, s, mx, my, renderedCardX, renderedCardY, cw, ch);
    }

    // ── Spotlight ────────────────────────────────────────────────────────────

    private void drawSpotlight(GuiGraphicsExtractor ctx, int[] hl, long now) {
        int dark = 0xBB000000;
        if (hl == null) { GatherTheme.fill(ctx, 0, 0, width, height, dark); return; }

        int pad = 5;
        int x1 = Math.max(0, hl[0] - pad), y1 = Math.max(0, hl[1] - pad);
        int x2 = Math.min(width,  hl[0] + hl[2] + pad);
        int y2 = Math.min(height, hl[1] + hl[3] + pad);

        GatherTheme.fill(ctx, 0,  0,     width, y1,     dark);
        GatherTheme.fill(ctx, 0,  y2,    width, height, dark);
        GatherTheme.fill(ctx, 0,  y1,    x1,   y2,     dark);
        GatherTheme.fill(ctx, x2, y1,    width, y2,     dark);

        float pulse = (float)(Math.sin((now % 2000) / 2000.0 * Math.PI * 2) * 0.5 + 0.5);
        int ga = (int)(0x99 + 0x44 * pulse);
        int gc = (ga << 24) | 0x4499FF;
        GatherTheme.fill(ctx, x1,   y1,   x2,   y1+2,  gc);
        GatherTheme.fill(ctx, x1,   y2-2, x2,   y2,    gc);
        GatherTheme.fill(ctx, x1,   y1,   x1+2, y2,    gc);
        GatherTheme.fill(ctx, x2-2, y1,   x2,   y2,    gc);
    }

    // ── Fake HUD content ─────────────────────────────────────────────────────

    private void drawFakeHudContent(GuiGraphicsExtractor ctx, int[] hl, int hudStep) {
        int x = hl[0], y = hl[1], w = hl[2];
        GatherTheme.fill(ctx, x, y, x + w, y + hl[3], 0xFF050A15);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF334466);

        Object[][] rows    = hudStep == 0 ? FAKE_GOALS : hudStep == 1 ? FAKE_MATERIALS : FAKE_CRAFT;
        String[]   headers = { "Goals", "Materials", "Craft Ready" };

        ctx.text(font, Component.literal(headers[hudStep]), x + 4, y + 4, 0xFF557799);
        GatherTheme.fill(ctx, x, y + 13, x + w, y + 14, 0x33336699);

        int ry = y + 18;
        for (Object[] row : rows) {
            if (ry + 18 > y + hl[3]) break;
            net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) row[0];
            ctx.item(item.getDefaultInstance(), x + 2, ry);
            String cnt  = (String) row[2];
            int    cw   = font.width(cnt);
            String name = font.plainSubstrByWidth((String) row[1], w - 20 - cw - 6);
            ctx.text(font, Component.literal(name), x + 20, ry + 4, 0xFFCCDDEE);
            ctx.text(font, Component.literal(cnt),
                    x + w - cw - 3, ry + 4,
                    hudStep == 2 ? 0xFF33DD99 : 0xFF8899AA);
            ry += 18;
        }
    }

    private static final Identifier CRAFTING_TEX =
        Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/crafting_table.png");

    // ── Crafting mockup ──────────────────────────────────────────────────────

    private void drawCraftingMockup(GuiGraphicsExtractor ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        ctx.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEX,
                bgX, bgY, 0, 0, bgW, bgH, 256, 256);

        int panW = 186, panH = 132;
        int panX = bgX - panW - 8;
        if (panX < 4) panX = bgX + 4;
        int panY = bgY + 18;

        drawCraftingPanel(ctx, panX, panY, panW, panH);
        ctx.text(font, Component.literal("Crafting Goals"), panX + 6, panY + 6, 0xFFCCDDFF, false);
        drawCraftingCloseButton(ctx, panX + panW - 15, panY + 5);

        Object[][] rows = {
            { Items.OAK_PLANKS, "Oak Planks", "need 179  max 300" },
            { Items.STICK, "Stick", "need 75  max 292" },
            { Items.OAK_SLAB, "Oak Slab", "need 280  max 301" },
            { Items.CRAFTING_TABLE, "Crafting Table", "need 1  max 1" },
        };
        int rowsTop = panY + 24;
        int rowY = rowsTop;
        for (int i = 0; i < 3; i++) {
            Object[] row = rows[i];
            drawCraftingSlotRow(ctx, panX + 5, rowY, panW - 13, 26, false);
            net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) row[0];
            drawCraftingItemSlot(ctx, panX + 9, rowY + 4);
            ctx.item(item.getDefaultInstance(), panX + 10, rowY + 5);
            ctx.text(font, Component.literal((String)row[1]), panX + 33, rowY + 4, 0xFFCCDDFF, false);
            ctx.text(font, Component.literal((String)row[2]), panX + 33, rowY + 15, 0xFF8DA1B8, false);
            rowY += 28;
        }

        int barX = panX + panW - 5;
        int barY = rowsTop;
        int barH = 3 * 28 - 2;
        int thumbH = Math.max(16, barH * 3 / rows.length);
        GatherTheme.fill(ctx, barX, barY, barX + 3, barY + barH, 0x22445566);
        GatherTheme.fill(ctx, barX, barY, barX + 3, barY + thumbH, 0xFF445566);
        GatherTheme.fill(ctx, barX + 1, barY + 1, barX + 3, barY + thumbH, 0xFF223344);
    }

    private void drawCraftingPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        GatherTheme.fill(ctx, x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0xEE0D1826);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF334455);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0xFF334455);
        GatherTheme.fill(ctx, x + w - 1, y, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x, y + h - 1, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x + 1, y + 1, x + w - 1, y + 2, 0xFF1D3045);
        GatherTheme.fill(ctx, x + 1, y + 1, x + 2, y + h - 1, 0xFF1D3045);
        GatherTheme.fill(ctx, x + 4, y + 20, x + w - 4, y + 21, 0xFF334455);
    }

    private void drawCraftingCloseButton(GuiGraphicsExtractor ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 9, y + 9, 0xFF17283B);
        ctx.text(font, Component.literal("x"), x + 2, y, 0xFFCCDDFF, false);
    }

    private static void drawCraftingSlotRow(GuiGraphicsExtractor ctx, int x, int y, int w, int h, boolean hovered) {
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 2, y + 2, x + w - 1, y + h - 1, hovered ? 0x88334466 : 0x33223344);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF101C2C);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 1, y + 1, x + w - 1, y + 2, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 1, y + 1, x + 2, y + h - 1, 0xFF101C2C);
        GatherTheme.fill(ctx, x + w - 2, y + 1, x + w, y + h, 0xFF3A5570);
        GatherTheme.fill(ctx, x + 1, y + h - 2, x + w, y + h, 0xFF3A5570);
    }

    private static void drawCraftingItemSlot(GuiGraphicsExtractor ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 18, y + 18, 0xFF162335);
        GatherTheme.fill(ctx, x, y, x + 18, y + 1, 0xFF07111F);
        GatherTheme.fill(ctx, x, y, x + 1, y + 18, 0xFF07111F);
        GatherTheme.fill(ctx, x + 17, y, x + 18, y + 18, 0xFF3A5570);
        GatherTheme.fill(ctx, x, y + 17, x + 18, y + 18, 0xFF3A5570);
    }

    private void drawTradeMockup(GuiGraphicsExtractor ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        GatherTheme.fill(ctx, bgX, bgY, bgX + bgW, bgY + bgH, 0xFF1A1620);
        GatherTheme.fill(ctx, bgX, bgY, bgX + bgW, bgY + 1, 0xFF5A4C63);
        GatherTheme.fill(ctx, bgX, bgY, bgX + 1, bgY + bgH, 0xFF5A4C63);
        GatherTheme.fill(ctx, bgX + bgW - 1, bgY, bgX + bgW, bgY + bgH, 0xFF0E0C12);
        GatherTheme.fill(ctx, bgX, bgY + bgH - 1, bgX + bgW, bgY + bgH, 0xFF0E0C12);
        ctx.text(font, Component.literal("Merchant"), bgX + 6, bgY + 6, 0xFFCCDDFF);

        int[] panel = tradePanelRegion(width, height);
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        drawTradePanel(ctx, panelX, panelY, panelW, panelH);
        ctx.text(font, Component.literal("Trade Calculator"), panelX + 6, panelY + 6, 0xFFCCDDFF);
        drawTradeCloseButton(ctx, panelX + panelW - 15, panelY + 5);

        drawTradeRow(ctx, panelX + 5, panelY + 24, true,
                Items.EMERALD.getDefaultInstance(), Items.BOOK.getDefaultInstance(), Items.ENCHANTED_BOOK.getDefaultInstance(),
                "Mending Book");
        drawTradeRow(ctx, panelX + 5, panelY + 50, false,
                Items.EMERALD.getDefaultInstance(), Items.REDSTONE.getDefaultInstance(), Items.COMPASS.getDefaultInstance(),
                "Compass");
        drawTradeRow(ctx, panelX + 5, panelY + 76, false,
                Items.EMERALD.getDefaultInstance(), Items.ROTTEN_FLESH.getDefaultInstance(), Items.GOLDEN_APPLE.getDefaultInstance(),
                "Golden Apple");
        int detailTop = panelY + 110;
        ctx.text(font, Component.literal("Want"), panelX + 6, detailTop + 4, 0xFF8899AA);
        drawTradeAmountField(ctx, panelX + 45, detailTop + 1);
        ctx.text(font, Component.literal("64"), panelX + 104, detailTop + 4, 0xFFCCDDFF);
        ctx.text(font, Component.literal("Trades 4  receive 64"), panelX + 6, detailTop + 19, 0xFF8899AA);
        drawTradeCostSlot(ctx, panelX + 6, detailTop + 30, new net.minecraft.world.item.ItemStack(Items.EMERALD, 4));
        drawTradeCostSlot(ctx, panelX + 42, detailTop + 30, new net.minecraft.world.item.ItemStack(Items.BOOK, 4));

        int btnW = 76;
        int btnH = 16;
        int btnX = panelX + panelW - 6 - btnW;
        int btnY = detailTop + 31;
        GatherTheme.fill(ctx, btnX, btnY, btnX + btnW, btnY + btnH, 0xFF223355);
        GatherTheme.fill(ctx, btnX, btnY, btnX + btnW, btnY + 1, 0xFF334466);
        ctx.centeredText(font, Component.literal("Add Goals"), btnX + btnW / 2, btnY + 4, 0xFFCCDDFF);
    }

    private void drawTradePanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        GatherTheme.fill(ctx, x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0xFF0E1826);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF334455);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0xFF334455);
        GatherTheme.fill(ctx, x + w - 1, y, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x, y + h - 1, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x + 4, y + 20, x + w - 4, y + 21, 0xFF334455);
    }

    private void drawTradeRow(GuiGraphicsExtractor ctx, int x, int y, boolean hovered,
                              net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second,
                              net.minecraft.world.item.ItemStack sell, String label) {
        GatherTheme.fill(ctx, x, y, x + 212, y + 22, hovered ? 0xFF243A57 : 0xFF152233);
        GatherTheme.fill(ctx, x, y, x + 212, y + 1, 0xFF334455);
        GatherTheme.fill(ctx, x + 2, y + 2, x + 208, y + 20, hovered ? 0x33336699 : 0x22112233);
        drawTradeSlot(ctx, x + 5, y + 2, first);
        int textX = x + 26;
        if (!second.isEmpty()) {
            ctx.text(font, Component.literal("+"), textX - 1, y + 7, 0xFF8899AA);
            drawTradeSlot(ctx, textX + 8, y + 2, second);
            textX += 34;
        }
        ctx.text(font, Component.literal("->"), textX - 2, y + 7, 0xFF8899AA);
        drawTradeSlot(ctx, textX + 14, y + 2, sell);
        ctx.text(font, Component.literal(label), textX + 42, y + 7, 0xFFCCDDFF);
    }

    private void drawTradeSlot(GuiGraphicsExtractor ctx, int x, int y, net.minecraft.world.item.ItemStack stack) {
        drawSlot(ctx, x, y, 18);
        ctx.item(stack, x + 1, y + 1);
        if (!stack.isEmpty() && stack.getCount() > 1) {
            ctx.text(font, Component.literal("x" + stack.getCount()), x + 1, y + 10, 0xFFFFFFFF);
        }
    }

    private void drawTradeCostSlot(GuiGraphicsExtractor ctx, int x, int y, net.minecraft.world.item.ItemStack stack) {
        drawTradeSlot(ctx, x, y, stack);
    }

    private void drawTradeCloseButton(GuiGraphicsExtractor ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 9, y + 9, 0xFF17283B);
        ctx.text(font, Component.literal("x"), x + 2, y, 0xFFCCDDFF);
    }

    private void drawTradeAmountField(GuiGraphicsExtractor ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 52, y + 14, 0xFF162335);
        GatherTheme.fill(ctx, x, y, x + 52, y + 1, 0xFF07111F);
        GatherTheme.fill(ctx, x, y, x + 1, y + 14, 0xFF07111F);
        GatherTheme.fill(ctx, x + 51, y, x + 52, y + 14, 0xFF3A5570);
        GatherTheme.fill(ctx, x, y + 13, x + 52, y + 14, 0xFF3A5570);
        ctx.centeredText(font, Component.literal("64"), x + 26, y + 3, 0xFFCCDDFF);
    }

    private static final Identifier SHULKER_TEX =
        Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/shulker_box.png");

    // ── Shulker collector mockup ─────────────────────────────────────────────

    private void drawShulkerMockup(GuiGraphicsExtractor ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        ctx.blit(RenderPipelines.GUI_TEXTURED, SHULKER_TEX,
                bgX, bgY, 0, 0, bgW, bgH, 256, 256);

        int BUTTON_W = 100, BUTTON_H = 14;
        int btnX  = bgX + bgW - BUTTON_W - 6;
        int btn0Y = bgY - 52;

        String[][] buttons = {
            { "Collector: ON",   "on"     },
            { "Mode: All Goals", "active" },
            { "Keep 1 item",     "on"     },
        };

        for (int i = 0; i < 3; i++) {
            int by = btn0Y + i * (BUTTON_H + 3);
            GatherTheme.fill(ctx, btnX,            by, btnX + BUTTON_W, by + BUTTON_H, 0xBB004433);
            GatherTheme.fill(ctx, btnX,            by, btnX + BUTTON_W, by + 1,        0xFF33DDAA);
            GatherTheme.fill(ctx, btnX, by + BUTTON_H - 1, btnX + BUTTON_W, by + BUTTON_H, 0x66111122);
            ctx.text(font, Component.literal(buttons[i][0]),
                    btnX + 5, by + 3, 0xFF66FFD6);
        }
    }

    private static void drawSlot(GuiGraphicsExtractor ctx, int x, int y, int size) {
        GatherTheme.fill(ctx, x,    y,    x+size,   y+size,   0xFF8B8B8B);
        GatherTheme.fill(ctx, x,    y,    x+size-1, y+1,      0xFF373737);
        GatherTheme.fill(ctx, x,    y,    x+1,      y+size-1, 0xFF373737);
        GatherTheme.fill(ctx, x+1,  y+1,  x+size-1, y+size-1, 0xFF8B8B8B);
    }

    // ── Info card ────────────────────────────────────────────────────────────

    private void drawCard(GuiGraphicsExtractor ctx, Step s, int mx, int my,
                          int cardX, int cardY, int cardW, int cardH) {
        int cx     = cardX + cardW / 2;
        int innerW = cardW - 16;

        // Bottom shadow
        GatherTheme.fill(ctx, cardX + 3, cardY + cardH,     cardX + cardW,     cardY + cardH + 1, 0x50000000);
        GatherTheme.fill(ctx, cardX + 4, cardY + cardH + 1, cardX + cardW - 1, cardY + cardH + 2, 0x30000000);
        GatherTheme.fill(ctx, cardX + 5, cardY + cardH + 2, cardX + cardW - 2, cardY + cardH + 3, 0x18000000);

        GatherTheme.fill(ctx, cardX,           cardY,           cardX + cardW, cardY + cardH, 0xFF0A1020);
        GatherTheme.fill(ctx, cardX,           cardY,           cardX + cardW, cardY + 1,     0xFF2255AA);
        GatherTheme.fill(ctx, cardX,           cardY + cardH-1, cardX + cardW, cardY + cardH, 0xFF112244);
        GatherTheme.fill(ctx, cardX,           cardY,           cardX + 1,     cardY + cardH, 0xFF2255AA);
        GatherTheme.fill(ctx, cardX + cardW-1, cardY,           cardX + cardW, cardY + cardH, 0xFF2255AA);

        ctx.text(font,
                Component.literal((step + 1) + " / " + STEPS.length),
                cardX + 8, cardY + 8, 0xFF334466);
        ctx.centeredText(font, Component.literal(s.title()), cx, cardY + 8, 0xFF88CCFF);
        GatherTheme.fill(ctx, cardX + 10, cardY + 19, cardX + cardW - 10, cardY + 20, 0x33336699);

        int lineY = cardY + 24;
        for (String line : s.lines()) {
            List<FormattedCharSequence> wrapped = font.split(Component.literal(line), innerW);
            for (FormattedCharSequence ot : wrapped) {
                int tw = font.width(ot);
                ctx.text(font, ot, cx - tw / 2, lineY, 0xFFAABBCC);
                lineY += 11;
            }
        }

        drawNav(ctx, mx, my, cardX, cardW, cardY + cardH - 22);
    }

    private void drawNav(GuiGraphicsExtractor ctx, int mx, int my, int cardX, int cardW, int navY) {
        int cx      = cardX + cardW / 2;
        boolean hasPrev = step > 0;
        boolean isLast  = step == STEPS.length - 1;

        if (hasPrev) {
            int bx = cardX + 10;
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H, hov ? 0xFF223355 : 0xFF162035);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, 0xFF334466);
            ctx.centeredText(font, Component.literal("← Prev"),
                    bx + NAV_BTN_W / 2, navY + 4, hov ? 0xFFCCDDFF : 0xFF778899);
        }

        {
            int bx = cx - NAV_BTN_W / 2;
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            String label = isLast ? "Done" : "Next →";
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H,
                    hov ? (isLast ? 0xFF004D42 : 0xFF223355) : (isLast ? 0xFF003D34 : 0xFF162035));
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, isLast ? 0xFF00CC99 : 0xFF334466);
            ctx.centeredText(font, Component.literal(label),
                    bx + NAV_BTN_W / 2, navY + 4,
                    hov ? 0xFFFFFFFF : (isLast ? 0xFF33D6AA : 0xFFCCDDFF));
        }

        if (!isLast) {
            int bx = cardX + cardW - NAV_BTN_W - 10;
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H, hov ? 0xFF221133 : 0xFF120A1A);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, 0xFF442255);
            ctx.centeredText(font, Component.literal("Skip All"),
                    bx + NAV_BTN_W / 2, navY + 4, hov ? 0xFFCC88FF : 0xFF664488);
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean focused) {
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();
        int navY    = renderedCardY + renderedCardH - 22;
        boolean hasPrev = step > 0;
        boolean isLast  = step == STEPS.length - 1;

        if (hasPrev && hit(mx, my, renderedCardX + 10, navY, NAV_BTN_W, NAV_BTN_H)) {
            goTo(step - 1); return true;
        }
        if (hit(mx, my, renderedCardX + renderedCardW / 2 - NAV_BTN_W / 2, navY, NAV_BTN_W, NAV_BTN_H)) {
            goTo(step + 1); return true;
        }
        if (!isLast && hit(mx, my, renderedCardX + renderedCardW - NAV_BTN_W - 10, navY, NAV_BTN_W, NAV_BTN_H)) {
            finish(); return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            goTo(step + 1); return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT)   { goTo(step - 1); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) { finish();        return true; }
        return false;
    }

    private static boolean hit(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    @Override
    public void onClose() { finish(); }
}
