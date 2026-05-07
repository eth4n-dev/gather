package com.gather.client.screen;

import com.gather.client.GatherList;
import com.gather.client.GatherState;
import com.gather.client.GatherUi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class GatherTransferScreen extends Screen {

    private static final int ROW_H     = 18;
    private static final int WORLD_H   = 22;
    private static final int WORLD_GAP = 5;
    private static final int BTN_W     = 82;
    private static final int CONTENT_TOP = 44;
    private static final float MENU_WIDTH_RATIO = 0.60f;
    private static final int MODE_WORLDS = 0;
    private static final int MODE_EXPORT = 1;
    private static final int MODE_JSON_IMPORT = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Screen parent;

    private record WorldEntry(String worldId, String displayName, boolean isSp, List<GatherList> lists) {}
    private record JsonEntry(Path path, String name, GatherList list) {}

    private final List<WorldEntry> worlds      = new ArrayList<>();
    private final List<JsonEntry> jsonImports  = new ArrayList<>();
    private final Set<String>      importedKeys = new HashSet<>(); // "worldId:li"
    private int scrollY      = 0;
    private int totalContentH = 0;
    private int contentBottom;
    private int mode = MODE_WORLDS;
    private String statusLine = "";
    private long statusExpiresAtMs = 0L;

    public GatherTransferScreen(Screen parent) {
        super(Text.literal("Imports/Exports"));
        this.parent = parent;
        for (String wid : GatherState.listOtherWorldIds()) {
            List<GatherList> lists = GatherState.loadListsFromWorld(wid);
            if (lists.isEmpty()) continue;
            boolean isSp = wid.startsWith("sp_");
            String raw = (isSp || wid.startsWith("mp_")) ? wid.substring(3) : wid;
            worlds.add(new WorldEntry(wid, raw.replace("_", " "), isSp, lists));
        }
        refreshJsonImports();
    }

    @Override
    protected void init() {
        contentBottom = height - 44;
        recalcContentHeight();
        scrollY = 0;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        clearExpiredStatus();
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFCCDDFF);
        String subtitle = switch (mode) {
            case MODE_EXPORT -> "Choose a current-world list to export as JSON";
            case MODE_JSON_IMPORT -> "Copy a Gather list JSON into the exchange folder, then import it here";
            default -> "Import goal lists from other worlds into this world";
        };
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(subtitle), width / 2, 22, 0xFF445566);

        int menuX = menuLeft();
        int menuW = menuWidth();
        int menuR = menuX + menuW;
        ctx.enableScissor(menuX, CONTENT_TOP, menuR, contentBottom);
        int y = CONTENT_TOP - scrollY;
        if (mode == MODE_EXPORT) {
            renderExportRows(ctx, mx, my, y);
        } else if (mode == MODE_JSON_IMPORT) {
            renderJsonImportRows(ctx, mx, my, y);
        } else {
            renderWorldImportRows(ctx, mx, my, y);
        }
        ctx.disableScissor();

        int visH = contentBottom - CONTENT_TOP;
        if (totalContentH > visH) {
            int thumbH = Math.max(14, visH * visH / totalContentH);
            int thumbY = CONTENT_TOP + (int)((long)(visH - thumbH) * scrollY / (totalContentH - visH));
            ctx.fill(menuR - 3, CONTENT_TOP, menuR - 1, contentBottom, 0x22FFFFFF);
            ctx.fill(menuR - 3, thumbY, menuR - 1, thumbY + thumbH, 0x88AACCFF);
        }

        if (GatherState.get().getListCount() >= 10 && statusLine.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("List cap reached (10 max) - delete a list first"),
                    width / 2, contentBottom + 6, 0xFFFF6666);

        if (!statusLine.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(statusLine), width / 2, contentBottom + 6, 0xFF88CCFF);
        }

        renderBottomButtons(ctx, mx, my);

        super.render(ctx, mx, my, delta);
    }

    private void renderWorldImportRows(DrawContext ctx, int mx, int my, int y) {
        if (worlds.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("No other worlds with saved lists found."),
                    width / 2, height / 2, 0xFF778899);
            return;
        }
        for (WorldEntry world : worlds) {
            renderWorldRow(ctx, mx, my, world, y);
            y += WORLD_H;
            for (int li = 0; li < world.lists().size(); li++) {
                renderListRow(ctx, mx, my, world, li, y);
                y += ROW_H;
            }
            y += WORLD_GAP;
        }
    }

    private void renderExportRows(DrawContext ctx, int mx, int my, int y) {
        List<GatherList> lists = GatherState.get().getLists();
        for (int li = 0; li < lists.size(); li++) {
            renderCurrentListRow(ctx, mx, my, lists.get(li), li, y);
            y += ROW_H;
        }
    }

    private void renderJsonImportRows(DrawContext ctx, int mx, int my, int y) {
        if (jsonImports.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("No JSON exports found. Copy one into the exchange folder."),
                    width / 2, height / 2, 0xFF778899);
            return;
        }
        for (int i = 0; i < jsonImports.size(); i++) {
            renderJsonRow(ctx, mx, my, jsonImports.get(i), i, y);
            y += ROW_H;
        }
    }

    private void renderWorldRow(DrawContext ctx, int mx, int my, WorldEntry world, int y) {
        if (y + WORLD_H < CONTENT_TOP || y > contentBottom) return;
        int menuX = menuLeft();
        int menuR = menuRight();
        String tag   = world.isSp() ? "§bSP§r" : "§6MP§r";
        String label = tag + "  " + capitalize(world.displayName());
        ctx.fill(menuX, y + 1, menuR, y + WORLD_H - 1, 0x33334466);
        ctx.fill(menuX, y + 1, menuX + 1, y + WORLD_H - 1, 0xFF4466BB);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), menuX + 10, y + 6, 0xFFCCDDFF);

        // Import All button
        boolean atCap = GatherState.get().getListCount() >= 10;
        int bx = menuR - BTN_W - 6;
        boolean hov = !atCap && mx >= bx && mx <= bx + BTN_W && my >= y + 3 && my <= y + WORLD_H - 3;
        ctx.fill(bx, y + 3, bx + BTN_W, y + WORLD_H - 3, atCap ? 0xFF1A1A2A : (hov ? 0xFF334433 : 0xFF1A2A1A));
        ctx.fill(bx, y + 3, bx + BTN_W, y + 4, atCap ? 0xFF333344 : 0xFF449944);
        String importAllLabel = "Import All";
        ctx.drawTextWithShadow(textRenderer, Text.literal(importAllLabel),
                bx + (BTN_W - textRenderer.getWidth(importAllLabel)) / 2, y + 7,
                atCap ? 0xFF555566 : 0xFFAAFFAA);
    }

    private void renderListRow(DrawContext ctx, int mx, int my, WorldEntry world, int li, int y) {
        if (y + ROW_H < CONTENT_TOP || y > contentBottom) return;
        GatherList list = world.lists().get(li);
        int roots = (int) list.nodes.stream().filter(n -> n.depth == 0).count();
        String key    = world.worldId() + ":" + li;
        boolean done  = importedKeys.contains(key);
        boolean atCap = GatherState.get().getListCount() >= 10 && !done;

        // Row background
        int menuX = menuLeft();
        int menuR = menuRight();
        int bx   = menuR - BTN_W - 6;
        ctx.fill(menuX + 10, y + 1, bx - 8, y + ROW_H - 1, done ? 0x22004400 : 0x22001133);
        String nameText = list.name + "  §8(" + roots + " goal" + (roots == 1 ? "" : "s") + ")";
        int maxNameW = Math.max(40, bx - (menuX + 22));
        ctx.drawTextWithShadow(textRenderer, Text.literal(truncate(nameText, maxNameW)), menuX + 12, y + 5,
                done ? 0xFF88FF88 : 0xFFCCCCCC);
        if (done)
            ctx.drawTextWithShadow(textRenderer, Text.literal("✔"), bx - 18, y + 5, 0xFF44FF88);

        // Import button
        boolean hov = !atCap && mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2;
        ctx.fill(bx, y + 2, bx + BTN_W, y + ROW_H - 2,
                atCap ? 0xFF1A1A2A : (done ? 0xFF112211 : (hov ? 0xFF224433 : 0xFF113322)));
        ctx.fill(bx, y + 2, bx + BTN_W, y + 3,
                atCap ? 0xFF333344 : (done ? 0xFF337733 : 0xFF337755));
        String btnLabel = done ? "Import again" : "Import";
        ctx.drawTextWithShadow(textRenderer, Text.literal(btnLabel),
                bx + (BTN_W - textRenderer.getWidth(btnLabel)) / 2, y + 6,
                atCap ? 0xFF555566 : (done ? 0xFF88CC88 : 0xFFAAFFCC));
    }

    private void renderCurrentListRow(DrawContext ctx, int mx, int my, GatherList list, int li, int y) {
        if (y + ROW_H < CONTENT_TOP || y > contentBottom) return;
        int roots = (int) list.nodes.stream().filter(n -> n.depth == 0).count();
        int menuX = menuLeft();
        int menuR = menuRight();
        int bx = menuR - BTN_W - 6;
        ctx.fill(menuX + 10, y + 1, bx - 8, y + ROW_H - 1, 0x22001133);
        String nameText = list.name + "  §8(" + roots + " goal" + (roots == 1 ? "" : "s") + ")";
        ctx.drawTextWithShadow(textRenderer, Text.literal(truncate(nameText, Math.max(40, bx - (menuX + 22)))),
                menuX + 12, y + 5, 0xFFCCCCCC);
        boolean hov = mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2;
        ctx.fill(bx, y + 2, bx + BTN_W, y + ROW_H - 2, hov ? 0xFF224433 : 0xFF113322);
        ctx.fill(bx, y + 2, bx + BTN_W, y + 3, 0xFF337755);
        String btnLabel = "Export";
        ctx.drawTextWithShadow(textRenderer, Text.literal(btnLabel),
                bx + (BTN_W - textRenderer.getWidth(btnLabel)) / 2, y + 6, 0xFFAAFFCC);
    }

    private void renderJsonRow(DrawContext ctx, int mx, int my, JsonEntry entry, int index, int y) {
        if (y + ROW_H < CONTENT_TOP || y > contentBottom) return;
        int menuX = menuLeft();
        int menuR = menuRight();
        int bx = menuR - BTN_W - 6;
        ctx.fill(menuX + 10, y + 1, bx - 8, y + ROW_H - 1, 0x22001133);
        ctx.drawTextWithShadow(textRenderer, Text.literal(truncate(entry.name(), Math.max(40, bx - (menuX + 22)))),
                menuX + 12, y + 5, 0xFFCCCCCC);
        boolean atCap = GatherState.get().getListCount() >= 10;
        boolean hov = !atCap && mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2;
        ctx.fill(bx, y + 2, bx + BTN_W, y + ROW_H - 2, atCap ? 0xFF1A1A2A : (hov ? 0xFF224433 : 0xFF113322));
        ctx.fill(bx, y + 2, bx + BTN_W, y + 3, atCap ? 0xFF333344 : 0xFF337755);
        String btnLabel = "Import";
        ctx.drawTextWithShadow(textRenderer, Text.literal(btnLabel),
                bx + (BTN_W - textRenderer.getWidth(btnLabel)) / 2, y + 6,
                atCap ? 0xFF555566 : 0xFFAAFFCC);
    }

    private void renderBottomButtons(DrawContext ctx, int mx, int my) {
        int y = height - 26;
        int w = 84;
        int gap = 4;
        int total = w * 4 + gap * 3;
        int x = width / 2 - total / 2;
        String firstLabel = mode == MODE_EXPORT ? "World Imports" : "Export List";
        drawBottomButton(ctx, mx, my, x, y, w, firstLabel, mode == MODE_EXPORT);
        drawBottomButton(ctx, mx, my, x + (w + gap), y, w, "Open Folder", false);
        drawBottomButton(ctx, mx, my, x + (w + gap) * 2, y, w, "Import JSON", mode == MODE_JSON_IMPORT);
        drawBottomButton(ctx, mx, my, x + (w + gap) * 3, y, w, "< Back", false);
    }

    private void drawBottomButton(DrawContext ctx, int mx, int my, int x, int y, int w, String label, boolean active) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + 18;
        ctx.fill(x, y, x + w, y + 18, active ? 0xFF334466 : (hov ? 0xFF334455 : 0xFF24344F));
        ctx.fill(x, y, x + w, y + 1, 0xFF667799);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + w / 2, y + 5, 0xFFCCDDFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        int mx = (int) click.x();
        int my = (int) click.y();

        if (handleBottomButtonClick(mx, my)) return true;

        if (my < CONTENT_TOP || my > contentBottom) return false;
        if (mode == MODE_EXPORT) return handleExportClick(mx, my);
        if (mode == MODE_JSON_IMPORT) return handleJsonImportClick(mx, my);
        return handleWorldImportClick(mx, my);
    }

    private boolean handleBottomButtonClick(int mx, int my) {
        int y = height - 26;
        int w = 84;
        int gap = 4;
        int total = w * 4 + gap * 3;
        int x = width / 2 - total / 2;
        if (my < y || my > y + 18) return false;
        for (int i = 0; i < 4; i++) {
            int bx = x + (w + gap) * i;
            if (mx < bx || mx > bx + w) continue;
            GatherUi.playClickSound();
            if (i == 0) {
                mode = mode == MODE_EXPORT ? MODE_WORLDS : MODE_EXPORT;
                clearStatus();
                scrollY = 0;
                recalcContentHeight();
            } else if (i == 1) {
                openExchangeTerminal();
            } else if (i == 2) {
                mode = MODE_JSON_IMPORT;
                refreshJsonImports();
                scrollY = 0;
                recalcContentHeight();
            } else {
                close();
            }
            return true;
        }
        return false;
    }

    private boolean handleWorldImportClick(int mx, int my) {
        int y = CONTENT_TOP - scrollY;
        int menuR = menuRight();
        for (WorldEntry world : worlds) {
            // World header - Import All button
            int bx = menuR - BTN_W - 6;
            if (mx >= bx && mx <= bx + BTN_W && my >= y + 3 && my <= y + WORLD_H - 3) {
                if (GatherState.get().getListCount() < 10) {
                    GatherUi.playClickSound();
                    for (int li = 0; li < world.lists().size(); li++) {
                        if (GatherState.get().getListCount() >= 10) break;
                        String key = world.worldId() + ":" + li;
                        if (!importedKeys.contains(key)) {
                            GatherState.get().copyListFromWorld(world.worldId(), li);
                            importedKeys.add(key);
                        }
                    }
                }
                return true;
            }
            y += WORLD_H;

            // List rows - Import button
            for (int li = 0; li < world.lists().size(); li++) {
                if (mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2) {
                    String key   = world.worldId() + ":" + li;
                    boolean done = importedKeys.contains(key);
                    if (!done && GatherState.get().getListCount() >= 10) return true;
                    GatherUi.playClickSound();
                    GatherState.get().copyListFromWorld(world.worldId(), li);
                    importedKeys.add(key);
                    return true;
                }
                y += ROW_H;
            }
            y += WORLD_GAP;
        }
        return true;
    }

    private boolean handleExportClick(int mx, int my) {
        int y = CONTENT_TOP - scrollY;
        int bx = menuRight() - BTN_W - 6;
        List<GatherList> lists = GatherState.get().getLists();
        for (int li = 0; li < lists.size(); li++) {
            if (mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2) {
                GatherUi.playClickSound();
                exportList(li);
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    private boolean handleJsonImportClick(int mx, int my) {
        int y = CONTENT_TOP - scrollY;
        int bx = menuRight() - BTN_W - 6;
        for (int i = 0; i < jsonImports.size(); i++) {
            if (mx >= bx && mx <= bx + BTN_W && my >= y + 2 && my <= y + ROW_H - 2) {
                if (GatherState.get().getListCount() >= 10) return true;
                GatherUi.playClickSound();
                importJson(i);
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int maxScroll = Math.max(0, totalContentH - (contentBottom - CONTENT_TOP));
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vy * 10)));
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private void recalcContentHeight() {
        if (mode == MODE_EXPORT) {
            totalContentH = GatherState.get().getListCount() * ROW_H;
            return;
        }
        if (mode == MODE_JSON_IMPORT) {
            totalContentH = jsonImports.size() * ROW_H;
            return;
        }
        int total = 0;
        for (WorldEntry world : worlds) {
            total += WORLD_H + world.lists().size() * ROW_H + WORLD_GAP;
        }
        totalContentH = total;
    }

    private void refreshJsonImports() {
        jsonImports.clear();
        Path dir = exchangeDir();
        try {
            Files.createDirectories(dir);
            try (var paths = Files.list(dir)) {
                paths
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .forEach(path -> {
                            try (Reader reader = Files.newBufferedReader(path)) {
                                GatherList list = GSON.fromJson(reader, GatherList.class);
                                if (list != null && list.name != null && list.nodes != null) {
                                    jsonImports.add(new JsonEntry(path, path.getFileName().toString(), list));
                                }
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception e) {
            setStatus("Could not read exchange folder");
        }
    }

    private Path exchangeDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("gather").resolve("list_exchange");
    }

    private void exportList(int listIndex) {
        List<GatherList> lists = GatherState.get().getLists();
        if (listIndex < 0 || listIndex >= lists.size()) return;
        GatherList list = lists.get(listIndex);
        try {
            Path dir = exchangeDir();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(EXPORT_STAMP);
            Path file = dir.resolve(sanitizeFileName(list.name) + "_" + stamp + ".json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(list, writer);
            }
            setStatus("Exported " + file.getFileName());
            refreshJsonImports();
        } catch (Exception e) {
            setStatus("Export failed");
        }
    }

    private void importJson(int index) {
        if (index < 0 || index >= jsonImports.size()) return;
        JsonEntry entry = jsonImports.get(index);
        GatherList imported = GatherState.get().importList(entry.list());
        setStatus(imported != null ? "Imported " + imported.name : "Import failed");
        recalcContentHeight();
    }

    private void openExchangeTerminal() {
        try {
            Path dir = exchangeDir();
            Files.createDirectories(dir);
            String path = dir.toAbsolutePath().toString();
            String[][] commands = {
                    {"x-terminal-emulator", "--working-directory", path},
                    {"gnome-terminal", "--working-directory", path},
                    {"konsole", "--workdir", path},
                    {"xfce4-terminal", "--working-directory", path},
                    {"kitty", "--directory", path},
                    {"alacritty", "--working-directory", path}
            };
            for (String[] command : commands) {
                try {
                    new ProcessBuilder(command).start();
                    setStatus("Opened " + path);
                    return;
                } catch (Exception ignored) {
                }
            }
            clearStatus();
        } catch (Exception e) {
            clearStatus();
        }
    }

    private void setStatus(String message) {
        statusLine = message;
        statusExpiresAtMs = System.currentTimeMillis() + 3000L;
    }

    private void clearStatus() {
        statusLine = "";
        statusExpiresAtMs = 0L;
    }

    private void clearExpiredStatus() {
        if (!statusLine.isEmpty() && statusExpiresAtMs > 0L && System.currentTimeMillis() >= statusExpiresAtMs) {
            clearStatus();
        }
    }

    private static String sanitizeFileName(String raw) {
        String value = raw == null || raw.isBlank() ? "gather_list" : raw;
        value = value.replaceAll("[^a-zA-Z0-9_.-]+", "_");
        value = value.replaceAll("_+", "_");
        value = value.replaceAll("^_+|_+$", "");
        return value.isEmpty() ? "gather_list" : value;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int menuWidth() {
        return Math.max(BTN_W + 140, Math.round(width * MENU_WIDTH_RATIO));
    }

    private int menuLeft() {
        return (width - menuWidth()) / 2;
    }

    private int menuRight() {
        return menuLeft() + menuWidth();
    }

    private String truncate(String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) return text;
        String value = text;
        while (value.length() > 1 && textRenderer.getWidth(value + "..") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "..";
    }
}
