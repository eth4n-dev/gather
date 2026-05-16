package com.gather.client.screen;

import com.gather.client.GatherTheme;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class GatherTutorialScreen extends Screen {

    // Mirrored from GatherMenuScreen
    private static final int MENU_LIST_W  = 300;
    private static final int MENU_PAD     = 6;
    private static final int MENU_BOT_H   = 28;
    private static final int MENU_TOGGLE_W = 84;
    private static final int MENU_TOGGLE_H = 20;

    private static final int CARD_W       = 340;   // world / crafting / shulker steps
    private static final int CARD_W_CTR   = 420;   // centered overlay steps
    private static final int NAV_BTN_W    = 66;
    private static final int NAV_BTN_H    = 16;
    private static final int NAV_SCREEN_X = 10;
    private static final int NAV_SCREEN_Y_PAD = 10;
    private static final int CARD_ANIM_MS = 350;

    // Absolute Y positions traced from renderListTab with Scan All OFF
    private static final int Y_SCAN_LABEL  = 40;
    private static final int Y_OUTLINES    = 148;
    private static final int Y_FINDER      = 166;

    @FunctionalInterface
    private interface HlFn { int[] get(int sw, int sh); }

    private enum BgType  { WORLD, MENU, CHEST_TOOLS, CRAFTING, TRADE, SHULKER }
    private enum CardPos {
        DEFAULT,
        CENTER,        // card middle at screen center
        CENTER_TOP,    // card top edge at screen center line
        CENTER_BOTTOM  // card bottom edge at screen center line
    }

    private record Step(String title, BgType bg, int menuTab, HlFn hl, CardPos pos, String[] lines) {}

    private static Step step(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.DEFAULT, lines);
    }
    private static Step stepC(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.CENTER, lines);
    }
    private static Step stepCT(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.CENTER_TOP, lines);
    }
    private static Step stepCB(String title, BgType bg, int tab, HlFn hl, String... lines) {
        return new Step(title, bg, tab, hl, CardPos.CENTER_BOTTOM, lines);
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
        stepCT("The Gather Menu  [G]", BgType.MENU, 0,
            (sw, sh) -> new int[]{ 0, 0, sw, sh },
            "Press §e§l[G]§r to open the Gather menu at any time.",
            "Manage goals, lists, chest scanning, and settings."),
        stepCB("Three Tabs", BgType.MENU, 0,
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
        stepC("Chest Scanning", BgType.CHEST_TOOLS, 0,
            (sw, sh) -> GatherMenuScreen.isCompactChestTools(sw, sh)
                    ? chestScanningRegion(sw, sh) : leftAbsolute(sw, Y_SCAN_LABEL, (GatherMenuScreen.renderedOutlinesBtnY + 14) - Y_SCAN_LABEL),
            "§a§lScan All§r auto-tracks every chest you open.",
            "§aChest Outlines§r highlights known chests. Set range in its settings."),
        stepC("Item Finder", BgType.CHEST_TOOLS, 0,
            (sw, sh) -> GatherMenuScreen.isCompactChestTools(sw, sh)
                    ? itemFinderRegion(sw, sh) : leftAbsolute(sw, GatherMenuScreen.renderedFinderBtnY, 15, 150),
            "Select a needed item; a §acompass arrow§r appears near your crosshair.",
            "Points to the nearest tracked chest containing that item."),
        step("Crafting Overlay", BgType.CRAFTING, -1,
            (sw, sh) -> craftingPanelRegion(sw, sh),
            "Open a §ecrafting table§r while goals are active.",
            "§a§lCrafting Goals§r panel lists items you can craft right now.",
            "§eLeft-click§r a row to craft needed  ·  §eRight-click§r to craft all possible."),
        step("Trade Calculator", BgType.TRADE, -1,
            (sw, sh) -> tradePanelRegion(sw, sh),
            "Open a §emerchant screen§r to see the trade calculator button.",
            "Pick a trade, enter how many results you want, then add the buy items as goals.",
            "Great for villager books, tools, and bulk emerald trades."),
        stepCT("Collector Shulker", BgType.SHULKER, -1,
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
        { Items.IRON_SWORD,  "Iron Sword",  "ready" },
        { Items.IRON_PICKAXE,"Iron Pickaxe","need 3" },
    };

    // ── State ────────────────────────────────────────────────────────────────

    private int  step        = 0;
    private long stepStartMs = 0;
    private GatherMenuScreen embeddedMenu = null;
    private GatherChestToolsScreen embeddedChestTools = null;

    private float cardFromY, cardToY, cardFromX, cardToX;
    private long  cardAnimStart;
    private int   renderedCardY, renderedCardX, renderedCardW, renderedCardH;

    public GatherTutorialScreen() {
        super(Text.literal("Gather Tutorial"));
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
        int lx = sw / 2 - MENU_LIST_W / 2;
        int cx = lx / 2;
        int w = Math.max(100, lx - 8);
        return new int[]{ Math.max(0, cx - w / 2), y, w, h };
    }

    private static int[] leftAbsolute(int sw, int y, int h, int maxW) {
        int lx = sw / 2 - MENU_LIST_W / 2;
        int cx = lx / 2;
        int w = Math.min(maxW, Math.max(80, lx - 8));
        return new int[]{ Math.max(0, cx - w / 2), y, w, h };
    }

    private static int[] chestToolsPanel(int sw, int sh) {
        int panelW = Math.min(220, sw - 16);
        int panelX = sw / 2 - panelW / 2;
        int panelY = sh < 240 ? 6 : Math.max(28, sh / 2 - 124);
        int buttonH = sh < 240 ? 16 : 20;
        return new int[]{ panelX, panelY, panelW, buttonH };
    }

    private static int[] chestScanningRegion(int sw, int sh) {
        int[] panel = chestToolsPanel(sw, sh);
        boolean compact = sh < 240;
        int rowStep = compact ? 18 : 24;
        int rowStart = panel[1] + (compact ? 16 : 28);
        int h = 3 * rowStep;  // Scan All + Manual Scan + Chest Outlines
        return new int[]{ panel[0] + 10, rowStart, panel[2] - 20, h };
    }

    private static int[] itemFinderRegion(int sw, int sh) {
        int[] panel = chestToolsPanel(sw, sh);
        int rowY = panel[1] + (sh < 240 ? 16 : 28) + 4 * (sh < 240 ? 18 : 24);
        return new int[]{ panel[0] + 10, rowY, panel[2] - 20, panel[3] };
    }

    // Right-panel mode toggle: wide enough to cover the label + button + description text
    private static int[] addModeRegion(int sw, int sh) {
        int lx   = sw / 2 - MENU_LIST_W / 2;
        int rpCx = (lx + MENU_LIST_W + sw) / 2;
        int ty   = sh / 2 - MENU_TOGGLE_H / 2;
        int hlW  = Math.min(210, Math.max(100, sw - (lx + MENU_LIST_W) - 8));
        return new int[]{ rpCx - hlW / 2, ty - 18, hlW, MENU_TOGGLE_H + 18 + 36 };
    }

    // + New List button in lower-left panel
    private static int[] newListRegion(int sw, int sh) {
        int lx = sw / 2 - MENU_LIST_W / 2;
        if (GatherMenuScreen.isCompactChestTools(sw, sh)) {
            int leftW = Math.max(0, lx - 6);
            int btnW = Math.min(96, Math.max(72, leftW - 8));
            int leftCx = Math.max(4, lx / 2);
            int btnX = Math.max(4, leftCx - btnW / 2);
            int ly = MENU_PAD + 22 + 4;
            int listLabelY = ly + 24 + MENU_TOGGLE_H + 14;
            return new int[]{ btnX - 4, listLabelY - 2, btnW + 8, MENU_TOGGLE_H + 16 };
        }
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
        if (panX < 4) panX = bgX + 176 + 8;
        if (panX + panW > sw - 4) panX = sw - panW - 4;
        return new int[]{ panX, bgY + 18, panW, panH - 18 };
    }

    private static int[] tradePanelRegion(int sw, int sh) {
        int bgX  = sw / 2 - 88, bgY  = sh / 2 - 83;
        int panW = 222, panH = 168;
        int panX = bgX - panW - 8;
        if (panX < 4) panX = bgX + 176 + 8;
        if (panX + panW > sw - 4) panX = sw - panW - 4;
        return new int[]{ panX, bgY + (166 - panH) / 2, panW, panH };
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

    private boolean compactTutorial() {
        return width < 400 || height < 220;
    }

    private int cardWidth(Step s) {
        if (compactTutorial()) {
            if (s.bg() == BgType.CHEST_TOOLS && menuIsCompact())
                return Math.max(80, Math.min(width - (NAV_SCREEN_X + NAV_BTN_W * 3 + 12) - 16, 160));
            return Math.max(180, Math.min(300, width - 16));
        }
        if (width < 720) return Math.max(220, Math.min(280, width - 16));
        if (s.pos() == CardPos.CENTER)                          return Math.min(CARD_W_CTR, width - 16);
        if (s.bg() == BgType.MENU && s.menuTab() == 1 && rightColAvail() >= 120) return rightColAvail();
        return Math.min(CARD_W, width - 16);
    }

    private int cardH(Step s, int innerW) {
        int lineCount = 0;
        for (String line : s.lines()) {
            List<OrderedText> wrapped = textRenderer.wrapLines(Text.literal(line), innerW);
            lineCount += Math.max(1, wrapped.size());
        }
        return 24 + lineCount * 11 + 26 + (compactTutorial() ? 12 : 0);
    }

    private int targetCardX(int cw, Step s, int[] hl) {
        if (compactTutorial()) {
            if (s.bg() == BgType.CHEST_TOOLS && menuIsCompact())
                return Math.max(NAV_SCREEN_X + NAV_BTN_W * 3 + 12 + 8, width - cw - 8);
            return Math.max(8, width / 2 - cw / 2);
        }
        if (s.bg() == BgType.CHEST_TOOLS) {
            if (!menuIsCompact()) {
                if (hl != null) {
                    int hlCenter = hl[0] + hl[2] / 2;
                    if (hlCenter < width / 2) return Math.max(8, Math.min(width - cw - 8, hl[0] + hl[2] + 10));
                    return Math.max(8, Math.min(width - cw - 8, hl[0] - cw - 10));
                }
                return Math.max(8, width / 2 - cw / 2);
            }
            int panelW = Math.min(220, width - 16);
            int panelX = width / 2 - panelW / 2;
            int leftX = panelX - cw - 8;
            if (leftX >= 8) return leftX;
            return Math.max(8, Math.min(width - cw - 8, width / 2 - cw / 2));
        }
        if (s.pos() == CardPos.CENTER || s.pos() == CardPos.CENTER_TOP || s.pos() == CardPos.CENTER_BOTTOM) {
            int x = width / 2 - cw / 2;
            // For Add Items tab only: stay left of the right panel so it doesn't overlap item list
            if (s.bg() == BgType.MENU && s.menuTab() == 1) x = Math.min(x, menuRightColX() - 10 - cw);
            return Math.max(8, Math.min(width - cw - 8, x));
        }
        if (hl != null) {
            int gap = 10;
            int hlCenter = hl[0] + hl[2] / 2;
            if (hlCenter < width / 2) return Math.max(8, Math.min(width - cw - 8, hl[0] + hl[2] + gap));
            return Math.max(8, Math.min(width - cw - 8, hl[0] - cw - gap));
        }
        if (s.bg() == BgType.TRADE) {
            int x = width / 2 + 88;
            return Math.max(8, Math.min(width - cw - 8, x));
        }
        if (s.bg() == BgType.MENU && s.menuTab() == 1 && rightColAvail() >= 120) return menuRightColX();
        if (hl == null) return width / 2 - cw / 2;
        int hlCx = hl[0] + hl[2] / 2;
        return Math.max(8, Math.min(width - cw - 8, hlCx - cw / 2));
    }

    private int targetCardY(int ch, Step s, int[] hl) {
        if (compactTutorial()) {
            if (s.bg() == BgType.CHEST_TOOLS)
                return Math.max(8, height - ch - NAV_BTN_H - 16);
            return Math.max(8, Math.min(height - ch - NAV_BTN_H - 16, 8));
        }
        if (s.bg() == BgType.CHEST_TOOLS) {
            if (!menuIsCompact()) {
                if (hl != null) {
                    int below = hl[1] + hl[3] + 10;
                    int above = hl[1] - ch - 10;
                    if (below + ch <= height - 4) return below;
                    if (above >= 4) return above;
                }
                return height / 2 - ch / 2;
            }
            if (hl != null) {
                int below = hl[1] + hl[3] + 10;
                int above = hl[1] - ch - 10;
                if (below + ch <= height - NAV_BTN_H - 16) return below;
                if (above >= 8) return above;
            }
            int panelY = chestToolsPanel(width, height)[1];
            return Math.max(8, Math.min(height - ch - NAV_BTN_H - 16, panelY + 8));
        }
        if (s.pos() == CardPos.CENTER_TOP)    return Math.min(height / 2, height - ch - NAV_BTN_H - 16);
        if (s.pos() == CardPos.CENTER_BOTTOM) return Math.max(8, height / 2 - ch);
        if (s.bg() == BgType.TRADE) return height / 2 - ch / 2;
        if (s.pos() == CardPos.CENTER) return height / 2 - ch / 2;
        if (width < 720 && hl != null) {
            int below = hl[1] + hl[3] + 6;
            int above = hl[1] - ch - 6;
            if (below + ch <= height - NAV_BTN_H - NAV_SCREEN_Y_PAD - 6) return below;
            if (above >= 8) return above;
            return Math.max(8, height - NAV_BTN_H - NAV_SCREEN_Y_PAD - ch - 8);
        }
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
        if (embeddedChestTools == null || embeddedChestTools.width != width || embeddedChestTools.height != height) {
            embeddedChestTools = new GatherChestToolsScreen(embeddedMenu);
            embeddedChestTools.width = width;
            embeddedChestTools.height = height;
            embeddedChestTools.init();
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

    private boolean menuIsCompact() {
        return GatherMenuScreen.isCompactChestTools(width, height);
    }

    private void syncMenuTab() {
        Step s = STEPS[step];
        if (s.bg() == BgType.MENU) embeddedMenu.setTutorialTab(s.menuTab());
        else if (s.bg() == BgType.CHEST_TOOLS && !menuIsCompact()) embeddedMenu.setTutorialTab(0);
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
        client.setScreen(null);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        Step s   = STEPS[step];
        long now = System.currentTimeMillis();

        switch (s.bg()) {
            case MENU     -> { if (embeddedMenu != null) embeddedMenu.render(ctx, -9999, -9999, delta); }
            case CHEST_TOOLS -> {
                if (menuIsCompact()) {
                    if (embeddedChestTools != null) embeddedChestTools.render(ctx, mx, my, delta);
                } else {
                    if (embeddedMenu != null) embeddedMenu.render(ctx, -9999, -9999, delta);
                }
            }
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
        drawNav(ctx, mx, my, navScreenX(), navScreenY());
    }

    // ── Spotlight ────────────────────────────────────────────────────────────

    private void drawSpotlight(DrawContext ctx, int[] hl, long now) {
        int dark = 0xBB000000;
        if (compactTutorial()) {
            GatherTheme.fill(ctx, 0, 0, width, height, 0x99000000);
            return;
        }
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

    private void drawFakeHudContent(DrawContext ctx, int[] hl, int hudStep) {
        int x = hl[0], y = hl[1], w = hl[2];
        GatherTheme.fill(ctx, x, y, x + w, y + hl[3], 0xFF050A15);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF334466);

        Object[][] rows    = hudStep == 0 ? FAKE_GOALS : hudStep == 1 ? FAKE_MATERIALS : FAKE_CRAFT;
        String[]   headers = { "Goals", "Materials", "Craft Ready" };

        ctx.drawTextWithShadow(textRenderer, Text.literal(headers[hudStep]), x + 4, y + 4, 0xFF557799);
        GatherTheme.fill(ctx, x, y + 13, x + w, y + 14, 0x33336699);

        int ry = y + 18;
        for (Object[] row : rows) {
            if (ry + 18 > y + hl[3]) break;
            net.minecraft.item.Item item = (net.minecraft.item.Item) row[0];
            ctx.drawItem(item.getDefaultStack(), x + 2, ry);
            String cnt  = (String) row[2];
            int    cw   = textRenderer.getWidth(cnt);
            String name = textRenderer.trimToWidth((String) row[1], w - 20 - cw - 6);
            ctx.drawTextWithShadow(textRenderer, Text.literal(name), x + 20, ry + 4, 0xFFCCDDEE);
            ctx.drawTextWithShadow(textRenderer, Text.literal(cnt),
                    x + w - cw - 3, ry + 4,
                    hudStep == 2 ? 0xFF33DD99 : 0xFF8899AA);
            ry += 18;
        }
    }

    private static final Identifier CRAFTING_TEX =
        Identifier.of("minecraft", "textures/gui/container/crafting_table.png");

    // ── Crafting mockup ──────────────────────────────────────────────────────

    private void drawCraftingMockup(DrawContext ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        // ── Fake crafting table background ────────────────────────────────────
        // Standard MC crafting table texture: 176×166, centered on screen.
        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, CRAFTING_TEX,
                bgX, bgY, 0, 0, bgW, bgH, 256, 256);

        // ── Crafting Goals panel ──────────────────────────────────────────────
        // Panel is 186×132, placed left of the crafting bg (falls back to right).
        // panY is bgY+18 (aligns with the crafting grid area).
        int panW = 186, panH = 132;
        int panX = bgX - panW - 8;
        if (panX < 4) panX = bgX + 176 + 8;
        if (panX + panW > width - 4) panX = width - panW - 4;
        int panY = bgY + 18;   // ← vertical offset from the bg top; increase to move panel down

        drawCraftingPanel(ctx, panX, panY, panW, panH);
        ctx.drawText(textRenderer, Text.literal("Crafting Goals"), panX + 6, panY + 6, 0xFFCCDDFF, false);
        drawCraftingCloseButton(ctx, panX + panW - 15, panY + 5);

        // ── Crafting rows ─────────────────────────────────────────────────────
        // rows[] holds the fake data: { Item, "Name", "need X  max Y" }.
        // Only the first 3 rows are rendered (the 4th shows the scroll position).
        // Each row is 28px tall; starts at panY+24 (below the header line).
        Object[][] rows = {
            { Items.IRON_INGOT,   "Iron Ingot",   "need 64  max 128" },
            { Items.IRON_SWORD,   "Iron Sword",   "need 2   max 4"   },
            { Items.CRAFTING_TABLE, "Crafting Table", "need 1  max 2" },
            { Items.DIAMOND,      "Diamond",      "need 24  max 24"  },  // not rendered, used for scrollbar ratio
        };
        int rowsTop = panY + 24;  // ← Y of first row; move up/down relative to panY
        int rowY = rowsTop;
        for (int i = 0; i < 3; i++) {
            Object[] row = rows[i];
            drawCraftingSlotRow(ctx, panX + 5, rowY, panW - 13, 26, false);
            net.minecraft.item.Item item = (net.minecraft.item.Item) row[0];
            drawCraftingItemSlot(ctx, panX + 9, rowY + 4);   // icon slot: panX+9, 4px down from row top
            ctx.drawItem(item.getDefaultStack(), panX + 10, rowY + 5);
            ctx.drawText(textRenderer, Text.literal((String)row[1]), panX + 33, rowY + 4, 0xFFCCDDFF, false);   // name X: right of icon
            ctx.drawText(textRenderer, Text.literal((String)row[2]), panX + 33, rowY + 15, 0xFF8DA1B8, false);  // sub-label, 11px below name
            rowY += 28;  // next row Y
        }

        // Scrollbar (visual only, not interactive)
        int barX = panX + panW - 5;  // right edge of panel
        int barY = rowsTop;
        int barH = 3 * 28 - 2;
        int thumbH = Math.max(16, barH * 3 / rows.length);  // thumb height reflects 3/4 visible
        GatherTheme.fill(ctx, barX, barY, barX + 3, barY + barH, 0x22445566);
        GatherTheme.fill(ctx, barX, barY, barX + 3, barY + thumbH, 0xFF445566);
        GatherTheme.fill(ctx, barX + 1, barY + 1, barX + 3, barY + thumbH, 0xFF223344);
    }

    private void drawCraftingPanel(DrawContext ctx, int x, int y, int w, int h) {
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

    private void drawCraftingCloseButton(DrawContext ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 9, y + 9, 0xFF17283B);
        ctx.drawText(textRenderer, Text.literal("x"), x + 2, y, 0xFFCCDDFF, false);
    }

    private static void drawCraftingSlotRow(DrawContext ctx, int x, int y, int w, int h, boolean hovered) {
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 2, y + 2, x + w - 1, y + h - 1, hovered ? 0x88334466 : 0x33223344);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF101C2C);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 1, y + 1, x + w - 1, y + 2, 0xFF101C2C);
        GatherTheme.fill(ctx, x + 1, y + 1, x + 2, y + h - 1, 0xFF101C2C);
        GatherTheme.fill(ctx, x + w - 2, y + 1, x + w, y + h, 0xFF3A5570);
        GatherTheme.fill(ctx, x + 1, y + h - 2, x + w, y + h, 0xFF3A5570);
    }

    private static void drawCraftingItemSlot(DrawContext ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 18, y + 18, 0xFF162335);
        GatherTheme.fill(ctx, x, y, x + 18, y + 1, 0xFF07111F);
        GatherTheme.fill(ctx, x, y, x + 1, y + 18, 0xFF07111F);
        GatherTheme.fill(ctx, x + 17, y, x + 18, y + 18, 0xFF3A5570);
        GatherTheme.fill(ctx, x, y + 17, x + 18, y + 18, 0xFF3A5570);
    }

    private void drawTradeMockup(DrawContext ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        // ── Fake merchant screen background ──────────────────────────────────
        // Standard MC merchant bg: 176×166, centered on screen.
        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        GatherTheme.fill(ctx, bgX, bgY, bgX + bgW, bgY + bgH, 0xFF1A1620);
        GatherTheme.fill(ctx, bgX, bgY, bgX + bgW, bgY + 1, 0xFF5A4C63);
        GatherTheme.fill(ctx, bgX, bgY, bgX + 1, bgY + bgH, 0xFF5A4C63);
        GatherTheme.fill(ctx, bgX + bgW - 1, bgY, bgX + bgW, bgY + bgH, 0xFF0E0C12);
        GatherTheme.fill(ctx, bgX, bgY + bgH - 1, bgX + bgW, bgY + bgH, 0xFF0E0C12);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Merchant"), bgX + 6, bgY + 6, 0xFFCCDDFF);

        // ── Trade Calculator panel ────────────────────────────────────────────
        // tradePanelRegion() positions the panel to the left of the merchant bg
        // (falls back to right side if not enough space). Panel is 222×168.
        // panelX / panelY are the panel's top-left corner — all content below is
        // relative to these.
        int[] panel = tradePanelRegion(width, height);
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];  // always 222
        int panelH = panel[3];  // always 168

        drawTradePanel(ctx, panelX, panelY, panelW, panelH);
        // Header: title at panelX+6, close button anchored to right edge
        ctx.drawTextWithShadow(textRenderer, Text.literal("Trade Calculator"), panelX + 6, panelY + 6, 0xFFCCDDFF);
        drawTradeCloseButton(ctx, panelX + panelW - 15, panelY + 5);

        // ── Trade rows ───────────────────────────────────────────────────────
        // Each row is 26px tall. Row list starts at panelY+24 (below the header line).
        // Row Y spacing: +26 per row (24, 50, 76, 102...).
        // drawTradeRow args: (ctx, x, y, hovered, firstBuyItem, secondBuyItem, sellItem, label)
        //   x = panelX+5 (slight left inset)
        //   pass ItemStack.EMPTY as secondBuyItem for single-ingredient trades
        //   hovered=true shows the row highlighted (simulates selected trade)
        drawTradeRow(ctx, panelX + 5, panelY + 24, true,   // row 1 — selected (highlighted)
                new net.minecraft.item.ItemStack(Items.EMERALD, 24), Items.BOOK.getDefaultStack(), Items.ENCHANTED_BOOK.getDefaultStack(),
                "Mending Book");
        drawTradeRow(ctx, panelX + 5, panelY + 50, false,  // row 2
                new net.minecraft.item.ItemStack(Items.EMERALD, 4), new net.minecraft.item.ItemStack(Items.REDSTONE, 8), Items.COMPASS.getDefaultStack(),
                "Compass");
        drawTradeRow(ctx, panelX + 5, panelY + 76, false,  // row 3
                new net.minecraft.item.ItemStack(Items.EMERALD, 8), net.minecraft.item.ItemStack.EMPTY, Items.GOLDEN_APPLE.getDefaultStack(),
                "Golden Apple");

        // ── Detail section ───────────────────────────────────────────────────
        // detailTop is the Y baseline for the entire detail area below the trade list.
        // All offsets below are relative to detailTop.
        int detailTop = panelY + 110;   // ← move this up/down to shift the whole detail block

        // "Want" label + amount field + item name — all on the same row
        ctx.drawTextWithShadow(textRenderer, Text.literal("Want"), panelX + 6, detailTop + 4, 0xFF8899AA);
        drawTradeAmountField(ctx, panelX + 45, detailTop + 1);  // field X: right after "Want" text
        ctx.drawTextWithShadow(textRenderer, Text.literal("Enchanted Book"), panelX + 104, detailTop + 4, 0xFFCCDDFF); // item name X: right of field (field ends ~panelX+97)

        // "Trades needed" label
        ctx.drawTextWithShadow(textRenderer, Text.literal("Trades needed: 3"), panelX + 6, detailTop + 19, 0xFF8899AA);

        // Cost items row — each icon is 18px wide, gap between them is 22px (matches overlay spacing).
        // drawTradeCost args: (ctx, x, y, itemStack, displayCount)
        // Add more drawTradeCost calls at panelX+6+N*22 for more cost items.
        drawTradeCost(ctx, panelX + 6,  detailTop + 28, new net.minecraft.item.ItemStack(Items.EMERALD, 72), 72);
        drawTradeCost(ctx, panelX + 28, detailTop + 28, new net.minecraft.item.ItemStack(Items.BOOK, 3), 3);

        // "Add Goals" button — right-aligned inside the panel
        int btnW = 76;
        int btnH = 16;
        int btnX = panelX + panelW - 6 - btnW;  // right-aligned with 6px padding
        int btnY = detailTop + 31;               // same row as cost items
        GatherTheme.fill(ctx, btnX, btnY, btnX + btnW, btnY + btnH, 0xFF223355);
        GatherTheme.fill(ctx, btnX, btnY, btnX + btnW, btnY + 1, 0xFF334466);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Add Goals"), btnX + btnW / 2, btnY + 4, 0xFFCCDDFF);
    }

    private void drawTradePanel(DrawContext ctx, int x, int y, int w, int h) {
        GatherTheme.fill(ctx, x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
        GatherTheme.fill(ctx, x, y, x + w, y + h, 0xFF0E1826);
        GatherTheme.fill(ctx, x, y, x + w, y + 1, 0xFF334455);
        GatherTheme.fill(ctx, x, y, x + 1, y + h, 0xFF334455);
        GatherTheme.fill(ctx, x + w - 1, y, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x, y + h - 1, x + w, y + h, 0xFF07111F);
        GatherTheme.fill(ctx, x + 4, y + 20, x + w - 4, y + 21, 0xFF334455);
    }

    private void drawTradeRow(DrawContext ctx, int x, int y, boolean hovered,
                              net.minecraft.item.ItemStack first, net.minecraft.item.ItemStack second,
                              net.minecraft.item.ItemStack sell, String label) {
        GatherTheme.fill(ctx, x, y, x + 212, y + 22, hovered ? 0xFF243A57 : 0xFF152233);
        GatherTheme.fill(ctx, x, y, x + 212, y + 1, 0xFF334455);
        GatherTheme.fill(ctx, x + 2, y + 2, x + 208, y + 20, hovered ? 0x33336699 : 0x22112233);
        drawTradeSlot(ctx, x + 5, y + 2, first);
        int textX = x + 26;
        if (!second.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("+"), textX - 1, y + 7, 0xFF8899AA);
            drawTradeSlot(ctx, textX + 8, y + 2, second);
            textX += 34;
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal("->"), textX - 2, y + 7, 0xFF8899AA);
        drawTradeSlot(ctx, textX + 14, y + 2, sell);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), textX + 42, y + 7, 0xFFCCDDFF);
    }

    private void drawTradeSlot(DrawContext ctx, int x, int y, net.minecraft.item.ItemStack stack) {
        drawSlot(ctx, x, y, 18);
        ctx.drawItem(stack, x + 1, y + 1);
        if (stack.getCount() > 1) {
            ctx.drawStackOverlay(textRenderer, stack, x + 1, y + 1);
        }
    }

    private void drawTradeCostSlot(DrawContext ctx, int x, int y, net.minecraft.item.ItemStack stack) {
        drawTradeSlot(ctx, x, y, stack);
    }

    private void drawTradeCloseButton(DrawContext ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 9, y + 9, 0xFF17283B);
        ctx.drawText(textRenderer, Text.literal("x"), x + 2, y, 0xFFCCDDFF, false);
    }

    private void drawTradeAmountField(DrawContext ctx, int x, int y) {
        GatherTheme.fill(ctx, x, y, x + 52, y + 14, 0xFF162335);
        GatherTheme.fill(ctx, x, y, x + 52, y + 1, 0xFF07111F);
        GatherTheme.fill(ctx, x, y, x + 1, y + 14, 0xFF07111F);
        GatherTheme.fill(ctx, x + 51, y, x + 52, y + 14, 0xFF3A5570);
        GatherTheme.fill(ctx, x, y + 13, x + 52, y + 14, 0xFF3A5570);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("3"), x + 26, y + 3, 0xFFCCDDFF);
    }

    private void drawTradeCost(DrawContext ctx, int x, int y, net.minecraft.item.ItemStack stack, int count) {
        net.minecraft.item.ItemStack iconStack = stack.copy();
        iconStack.setCount(1);
        drawTradeSlot(ctx, x, y, iconStack);
        String label = compactTradeCount(count);
        ctx.drawText(textRenderer, Text.literal(label), x + 18 - textRenderer.getWidth(label), y + 10, 0xFFFFFFFF, true);
    }

    private static String compactTradeCount(int count) {
        if (count < 1000) return Integer.toString(count);
        if (count < 10000) return (count / 1000) + "k";
        return "9k+";
    }

    private static final Identifier SHULKER_TEX =
        Identifier.of("minecraft", "textures/gui/container/shulker_box.png");

    // ── Shulker collector mockup ─────────────────────────────────────────────

    private void drawShulkerMockup(DrawContext ctx) {
        GatherTheme.fill(ctx, 0, 0, width, height, 0x80000000);

        // ── Fake shulker box background ───────────────────────────────────────
        // Standard shulker box texture: 176×166, centered on screen.
        int bgW = 176, bgH = 166;
        int bgX = width / 2 - bgW / 2;
        int bgY = height / 2 - bgH / 2;

        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SHULKER_TEX,
                bgX, bgY, 0, 0, bgW, bgH, 256, 256);

        // ── Collector buttons ─────────────────────────────────────────────────
        // Buttons sit ABOVE the shulker bg (btn0Y = bgY-52, i.e. 52px above the bg top).
        // btnX is right-aligned inside the bg width.
        // Each button is 100×14, spaced 3px apart vertically.
        // buttons[] = { "display label", unused_state_string }
        int BUTTON_W = 100, BUTTON_H = 14;
        int btnX  = bgX + bgW - BUTTON_W - 6;  // right-aligned: 6px from bg right edge
        int btn0Y = bgY - 52;                   // ← move this up/down to shift all buttons

        String[][] buttons = {
            { "Collector: ON",   "on"     },
            { "Mode: All Goals", "active" },
            { "Keep 1 item",     "on"     },
        };

        for (int i = 0; i < 3; i++) {
            int by = btn0Y + i * (BUTTON_H + 3);  // 3px gap between buttons
            GatherTheme.fill(ctx, btnX,            by, btnX + BUTTON_W, by + BUTTON_H, 0xBB004433);
            GatherTheme.fill(ctx, btnX,            by, btnX + BUTTON_W, by + 1,        0xFF33DDAA);
            GatherTheme.fill(ctx, btnX, by + BUTTON_H - 1, btnX + BUTTON_W, by + BUTTON_H, 0x66111122);
            ctx.drawTextWithShadow(textRenderer, Text.literal(buttons[i][0]),
                    btnX + 5, by + 3, 0xFF66FFD6);
        }
    }

    private static void drawSlot(DrawContext ctx, int x, int y, int size) {
        GatherTheme.fill(ctx, x,    y,    x+size,   y+size,   0xFF8B8B8B);
        GatherTheme.fill(ctx, x,    y,    x+size-1, y+1,      0xFF373737);
        GatherTheme.fill(ctx, x,    y,    x+1,      y+size-1, 0xFF373737);
        GatherTheme.fill(ctx, x+1,  y+1,  x+size-1, y+size-1, 0xFF8B8B8B);
    }

    // ── Info card ────────────────────────────────────────────────────────────

    private void drawCard(DrawContext ctx, Step s, int mx, int my,
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

        ctx.drawTextWithShadow(textRenderer,
                Text.literal((step + 1) + " / " + STEPS.length),
                cardX + 8, cardY + 8, 0xFF334466);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(s.title()), cx, cardY + 8, 0xFF88CCFF);
        GatherTheme.fill(ctx, cardX + 10, cardY + 19, cardX + cardW - 10, cardY + 20, 0x33336699);

        List<OrderedText> bodyLines = new java.util.ArrayList<>();
        for (String line : s.lines()) {
            bodyLines.addAll(textRenderer.wrapLines(Text.literal(line), innerW));
        }

        int bodyTop = cardY + 24;
        boolean compact = compactTutorial();
        int bodyBottom = cardY + cardH - (compact ? 20 : 8);
        int bodyTextH = bodyLines.isEmpty() ? 0 : (bodyLines.size() - 1) * 11 + 8;
        int lineY = bodyTop + Math.max(0, (bodyBottom - bodyTop - bodyTextH) / 2);
        for (OrderedText ot : bodyLines) {
            int tw = textRenderer.getWidth(ot);
            ctx.drawTextWithShadow(textRenderer, ot, cx - tw / 2, lineY, 0xFFAABBCC);
            lineY += 11;
        }
        if (compact) {
            Text notice = Text.literal("Best viewed at GUI Scale 5 or lower.");
            ctx.drawCenteredTextWithShadow(textRenderer, notice, cx, cardY + cardH - 14, 0xFFFFCC66);
        }

    }

    private void drawNav(DrawContext ctx, int mx, int my, int navX, int navY) {
        boolean hasPrev = step > 0;
        boolean isLast  = step == STEPS.length - 1;
        int textY = navY + (NAV_BTN_H - 8) / 2;

        if (hasPrev) {
            int bx = navX;
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H, hov ? 0xFF223355 : 0xFF162035);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, 0xFF334466);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("← Prev"),
                    bx + NAV_BTN_W / 2, textY, hov ? 0xFFCCDDFF : 0xFF778899);
        }

        {
            int bx = navNextX();
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            String label = isLast ? "Done" : "Next →";
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H,
                    hov ? (isLast ? 0xFF004D42 : 0xFF223355) : (isLast ? 0xFF003D34 : 0xFF162035));
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, isLast ? 0xFF00CC99 : 0xFF334466);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    bx + NAV_BTN_W / 2, textY,
                    hov ? 0xFFFFFFFF : (isLast ? 0xFF33D6AA : 0xFFCCDDFF));
        }

        if (!isLast) {
            int bx = navSkipX();
            boolean hov = hit(mx, my, bx, navY, NAV_BTN_W, NAV_BTN_H);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + NAV_BTN_H, hov ? 0xFF221133 : 0xFF120A1A);
            GatherTheme.fill(ctx, bx, navY, bx + NAV_BTN_W, navY + 1, 0xFF442255);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Skip All"),
                    bx + NAV_BTN_W / 2, textY, hov ? 0xFFCC88FF : 0xFF664488);
        }
    }

    private int navScreenX() {
        return NAV_SCREEN_X;
    }

    private int navScreenY() {
        return height - NAV_SCREEN_Y_PAD - NAV_BTN_H;
    }

    private int navNextX() {
        return navScreenX() + NAV_BTN_W + 6;
    }

    private int navSkipX() {
        return navNextX() + NAV_BTN_W + 6;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();
        int navY    = navScreenY();
        boolean hasPrev = step > 0;
        boolean isLast  = step == STEPS.length - 1;

        if (hasPrev && hit(mx, my, navScreenX(), navY, NAV_BTN_W, NAV_BTN_H)) {
            goTo(step - 1); return true;
        }
        if (hit(mx, my, navNextX(), navY, NAV_BTN_W, NAV_BTN_H)) {
            goTo(step + 1); return true;
        }
        if (!isLast && hit(mx, my, navSkipX(), navY, NAV_BTN_W, NAV_BTN_H)) {
            finish(); return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
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
    public void close() { finish(); }
}
