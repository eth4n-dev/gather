package com.gather.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GatherSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Canonical gather data subdirectory — all world/settings files live here
    public static final Path GATHER_DIR = initDir(FabricLoader.getInstance().getConfigDir().resolve("gather"));
    private static Path initDir(Path p) {
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    private static final Path FILE = GATHER_DIR.resolve("settings.json");

    private static GatherSettings instance;

    public boolean showHud = true;
    public boolean enabled = true;
    public boolean autoRemoveCompleted = true;
    public boolean goalSoundEnabled = true;
    public boolean menuSpinAnimation = true;
    public String uiTheme = "modern";
    public boolean countChests = false;
    public boolean hasShownWelcome = false;
    public boolean highlightEnabled = true;
    public boolean droppedItemXray = true;
    public boolean blockXray = false;
    public boolean countExistingOnAdd = false; // false = "+more" mode (need X more), true = "total" mode (need X total)
    public boolean chestOutlinesEnabled = true;
    public boolean chestXray = false;
    public int maxBlockHighlights = 100;
    public int highlightScanBudget = 4;
    public int highlightRampSeconds = 4;
    public int chestScanRadius = 64;
    public int breakdownDepth = 3;
    public int highlightRadius = 32;
    public String outlineColor = "rainbow";

    public int layoutGoalsX = 4;
    public int layoutGoalsY = 10;
    public int layoutGoalsW = 84;
    public int layoutMaterialsX = 94;
    public int layoutMaterialsY = 10;
    public int layoutMaterialsW = 84;
    public int layoutCraftHintsX = 184;
    public int layoutCraftHintsY = 10;
    public int layoutCraftHintsW = 84;
    public int layoutManualScanX = -1;
    public int layoutManualScanY = 8;
    public int layoutManualScanW = 210;
    public int layoutScanBadgesX = -1;
    public int layoutScanBadgesY = 4;
    public int layoutScanBadgesW = 96;
    public int layoutFinderX = -1;
    public int layoutFinderY = 23;
    public int layoutFinderW = 130;
    public int layoutToastX = -1;
    public int layoutToastY = 6;

    public int chestFallbackRefreshSeconds = 60;

    public boolean collectorOutlinesEnabled = true;
    public boolean scanToggleShift = false;
    public boolean scanToggleCtrl = false;
    public boolean scanToggleAlt = false;
    public Boolean migratedSeparateManualScanKey = true;

    public List<RecentEntry> recentItems = new ArrayList<>();
    public Map<String, List<RecentEntry>> recentItemsByWorld = new HashMap<>();
    public List<String> favoriteItems = new ArrayList<>();

    public static class RecentEntry {
        public String itemId;
        public int count;
        public RecentEntry() {}
        public RecentEntry(String itemId, int count) { this.itemId = itemId; this.count = count; }
    }

    public void addRecentItem(String itemId, int count) {
        List<RecentEntry> recent = getRecentItems();
        recent.removeIf(e -> e != null && itemId.equals(e.itemId));
        recent.add(0, new RecentEntry(itemId, count));
        if (recent.size() > 10) recent.subList(10, recent.size()).clear();
    }

    public List<RecentEntry> getRecentItems() {
        if (recentItemsByWorld == null) recentItemsByWorld = new HashMap<>();
        return recentItemsByWorld.computeIfAbsent(currentRecentWorldId(), key -> new ArrayList<>());
    }

    public boolean isFavoriteItem(String itemId) {
        return favoriteItems != null && favoriteItems.contains(itemId);
    }

    public void toggleFavoriteItem(String itemId) {
        if (favoriteItems == null) favoriteItems = new ArrayList<>();
        if (favoriteItems.remove(itemId)) return;
        favoriteItems.add(itemId);
    }

    private static String currentRecentWorldId() {
        String worldId = GatherState.getCurrentWorldId();
        return worldId != null ? worldId : "default";
    }

    public static GatherSettings get() {
        if (instance == null) instance = load();
        return instance;
    }

    public void save() {
        try (Writer w = new FileWriter(FILE.toFile())) {
            GSON.toJson(this, w);
        } catch (IOException ignored) {}
    }

    public void resetHudLayout() {
        layoutGoalsX = 4;
        layoutGoalsY = 10;
        layoutGoalsW = 84;
        layoutMaterialsX = 94;
        layoutMaterialsY = 10;
        layoutMaterialsW = 84;
        layoutCraftHintsX = 184;
        layoutCraftHintsY = 10;
        layoutCraftHintsW = 84;
        layoutManualScanX = -1;
        layoutManualScanY = 8;
        layoutManualScanW = 210;
        layoutScanBadgesX = -1;
        layoutScanBadgesY = 4;
        layoutScanBadgesW = 96;
        layoutFinderX = -1;
        layoutFinderY = 23;
        layoutFinderW = 130;
        layoutToastX = -1;
        layoutToastY = 6;
        save();
    }

    private static GatherSettings load() {
        // Migrate old root-level file on first launch
        Path old = FabricLoader.getInstance().getConfigDir().resolve("gather_settings.json");
        if (!Files.exists(FILE) && Files.exists(old)) {
            try { Files.move(old, FILE, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
        }
        if (!FILE.toFile().exists()) return new GatherSettings();
        try {
            String json = java.nio.file.Files.readString(FILE);
            boolean hasSeparateManualMigrationFlag = json.contains("\"migratedSeparateManualScanKey\"");
            GatherSettings s = GSON.fromJson(json, GatherSettings.class);
            if (s != null && s.recentItemsByWorld == null) s.recentItemsByWorld = new HashMap<>();
            if (s != null && s.favoriteItems == null) s.favoriteItems = new ArrayList<>();
            if (s != null && !hasSeparateManualMigrationFlag) {
                s.scanToggleShift = false;
                s.scanToggleCtrl = false;
                s.scanToggleAlt = false;
                s.migratedSeparateManualScanKey = Boolean.TRUE;
                s.save();
            }
            return s != null ? s : new GatherSettings();
        } catch (IOException e) {
            return new GatherSettings();
        }
    }
}
