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
    private static final int MAX_RECENT_ITEMS = 25;
    private static final int HUD_LAYOUT_PROFILE_VERSION = 6;


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
    public boolean updateNotifications = true;
    public boolean suppressUpdateNotif = false;
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

    public int layoutGoalsX = 8;
    public int layoutGoalsY = 21;
    public int layoutGoalsW = 76;
    public int layoutMaterialsX = 92;
    public int layoutMaterialsY = 21;
    public int layoutMaterialsW = 80;
    public int layoutCraftHintsX = 180;
    public int layoutCraftHintsY = 21;
    public int layoutCraftHintsW = 80;
    public int layoutManualScanX = -1;
    public int layoutManualScanY = 58;
    public int layoutManualScanW = 196;
    public int layoutScanBadgesX = -1;
    public int layoutScanBadgesY = 8;
    public int layoutScanBadgesW = 88;
    public int layoutFinderX = -1;
    public int layoutFinderY = 44;
    public int layoutFinderW = 120;
    public int layoutToastX = -1;
    public int layoutToastY = 36;
    public int layoutBaseScreenW = 0;
    public int layoutBaseScreenH = 0;
    public int layoutEditorZoom = 0;
    public int hudLayoutProfileVersion = HUD_LAYOUT_PROFILE_VERSION;
    public Map<String, HudLayoutProfile> hudLayoutsByZoom = new HashMap<>();

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

    public static class HudLayoutProfile {
        public int goalsX;
        public int goalsY;
        public int goalsW;
        public int materialsX;
        public int materialsY;
        public int materialsW;
        public int craftHintsX;
        public int craftHintsY;
        public int craftHintsW;
        public int manualScanX;
        public int manualScanY;
        public int manualScanW;
        public int scanBadgesX;
        public int scanBadgesY;
        public int scanBadgesW;
        public int finderX;
        public int finderY;
        public int finderW;
        public int toastX;
        public int toastY;
        public int baseScreenW;
        public int baseScreenH;
    }

    public void addRecentItem(String itemId, int count) {
        List<RecentEntry> recent = getRecentItems();
        recent.removeIf(e -> e != null && itemId.equals(e.itemId));
        recent.add(0, new RecentEntry(itemId, count));
        if (recent.size() > MAX_RECENT_ITEMS) recent.subList(MAX_RECENT_ITEMS, recent.size()).clear();
    }

    public void removeRecentItem(String itemId) {
        if (itemId == null) return;
        List<RecentEntry> recent = getRecentItems();
        if (recent.removeIf(e -> e != null && itemId.equals(e.itemId))) save();
    }

    public List<RecentEntry> getRecentItems() {
        if (recentItemsByWorld == null) recentItemsByWorld = new HashMap<>();
        List<RecentEntry> recent = recentItemsByWorld.computeIfAbsent(currentRecentWorldId(), key -> new ArrayList<>());
        if (recent.size() > MAX_RECENT_ITEMS) recent.subList(MAX_RECENT_ITEMS, recent.size()).clear();
        return recent;
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
        applyProfile(presetProfile(layoutEditorZoom, 0, 0));
        if (hudLayoutsByZoom != null) hudLayoutsByZoom.remove(zoomKey(layoutEditorZoom));
        save();
    }

    public void applyHudLayoutForZoom(int guiScale, int screenW, int screenH) {
        HudLayoutProfile p = profileForZoom(guiScale);
        if (p == null) p = presetProfile(guiScale, screenW, screenH);
        applyProfile(p);
    }

    public void saveHudLayoutForZoom(int guiScale) {
        if (hudLayoutsByZoom == null) hudLayoutsByZoom = new HashMap<>();
        hudLayoutsByZoom.put(zoomKey(guiScale), captureProfile());
    }

    public void applyPresetForZoom(int guiScale, int screenW, int screenH) {
        HudLayoutProfile p = presetProfile(guiScale, screenW, screenH);
        applyProfile(p);
        if (hudLayoutsByZoom == null) hudLayoutsByZoom = new HashMap<>();
        hudLayoutsByZoom.put(zoomKey(guiScale), captureProfile());
        save();
    }

    public HudLayoutProfile profileForZoom(int guiScale) {
        if (hudLayoutsByZoom == null) hudLayoutsByZoom = new HashMap<>();
        return hudLayoutsByZoom.get(zoomKey(guiScale));
    }

    public static String zoomKey(int guiScale) {
        return "gui_" + Math.max(0, guiScale);
    }

    private HudLayoutProfile captureProfile() {
        HudLayoutProfile p = new HudLayoutProfile();
        p.goalsX = layoutGoalsX;
        p.goalsY = layoutGoalsY;
        p.goalsW = layoutGoalsW;
        p.materialsX = layoutMaterialsX;
        p.materialsY = layoutMaterialsY;
        p.materialsW = layoutMaterialsW;
        p.craftHintsX = layoutCraftHintsX;
        p.craftHintsY = layoutCraftHintsY;
        p.craftHintsW = layoutCraftHintsW;
        p.manualScanX = layoutManualScanX;
        p.manualScanY = layoutManualScanY;
        p.manualScanW = layoutManualScanW;
        p.scanBadgesX = layoutScanBadgesX;
        p.scanBadgesY = layoutScanBadgesY;
        p.scanBadgesW = layoutScanBadgesW;
        p.finderX = layoutFinderX;
        p.finderY = layoutFinderY;
        p.finderW = layoutFinderW;
        p.toastX = layoutToastX;
        p.toastY = layoutToastY;
        p.baseScreenW = layoutBaseScreenW;
        p.baseScreenH = layoutBaseScreenH;
        return p;
    }

    private void applyProfile(HudLayoutProfile p) {
        layoutGoalsX = p.goalsX;
        layoutGoalsY = p.goalsY;
        layoutGoalsW = p.goalsW;
        layoutMaterialsX = p.materialsX;
        layoutMaterialsY = p.materialsY;
        layoutMaterialsW = p.materialsW;
        layoutCraftHintsX = p.craftHintsX;
        layoutCraftHintsY = p.craftHintsY;
        layoutCraftHintsW = p.craftHintsW;
        layoutManualScanX = p.manualScanX;
        layoutManualScanY = p.manualScanY;
        layoutManualScanW = p.manualScanW;
        layoutScanBadgesX = p.scanBadgesX;
        layoutScanBadgesY = p.scanBadgesY;
        layoutScanBadgesW = p.scanBadgesW;
        layoutFinderX = p.finderX;
        layoutFinderY = p.finderY;
        layoutFinderW = p.finderW;
        layoutToastX = p.toastX;
        layoutToastY = p.toastY;
        layoutBaseScreenW = p.baseScreenW;
        layoutBaseScreenH = p.baseScreenH;
    }

    private static HudLayoutProfile presetProfile(int guiScale, int screenW, int screenH) {
        HudLayoutProfile p = new HudLayoutProfile();
        int sw = screenW > 0 ? screenW : 427;
        int sh = screenH > 0 ? screenH : 240;
        boolean compact = sw < 270;
        int xNudge = Math.max(0, Math.round(sw / 100.0f));
        int leftX = 4 + xNudge;
        int materialsX = compact ? leftX : leftX + 84;
        int materialsY = compact ? Math.min(104, Math.max(52, sh - 136)) : 21;
        int hintsX = compact ? leftX : leftX + 172;
        int hintsY = compact ? Math.min(172, Math.max(88, sh - 68)) : 21;
        boolean midNarrow = sw < 760;
        p.goalsX = leftX;
        p.goalsY = 21;
        p.goalsW = 76;
        p.materialsX = materialsX;
        p.materialsY = materialsY;
        p.materialsW = 80;
        p.craftHintsX = hintsX;
        p.craftHintsY = hintsY;
        p.craftHintsW = 80;
        p.manualScanX = midNarrow ? Math.min(Math.max(4, sw - 200), hintsX + 112) : -1;
        p.manualScanY = compact ? Math.min(96, Math.max(58, sh - 74)) : (midNarrow ? 70 : 58);
        p.manualScanW = 196;
        p.scanBadgesX = -1;
        p.scanBadgesY = 8;
        p.scanBadgesW = 88;
        p.finderX = -1;
        p.finderY = compact ? Math.min(72, Math.max(44, sh - 118)) : (midNarrow ? Math.max(44, sh - 50) : 44);
        p.finderW = 120;
        p.toastX = midNarrow ? Math.min(Math.max(4, sw - 204), hintsX + 110) : -1;
        p.toastY = midNarrow ? 47 : 36;
        p.baseScreenW = screenW;
        p.baseScreenH = screenH;
        return p;
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
            if (s != null && s.hudLayoutsByZoom == null) s.hudLayoutsByZoom = new HashMap<>();
            if (s != null && s.hudLayoutProfileVersion < HUD_LAYOUT_PROFILE_VERSION) {
                s.hudLayoutsByZoom.clear();
                s.applyProfile(presetProfile(0, 0, 0));
                s.layoutEditorZoom = 0;
                s.hudLayoutProfileVersion = HUD_LAYOUT_PROFILE_VERSION;
                s.save();
            }
            if (s != null && normalizeHudWidths(s)) s.save();
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

    private static boolean normalizeHudWidths(GatherSettings s) {
        boolean changed = false;
        if (s.layoutGoalsW != 76) { s.layoutGoalsW = 76; changed = true; }
        if (s.layoutMaterialsW != 80) { s.layoutMaterialsW = 80; changed = true; }
        if (s.layoutCraftHintsW != 80) { s.layoutCraftHintsW = 80; changed = true; }
        if (s.layoutManualScanW != 196) { s.layoutManualScanW = 196; changed = true; }
        if (s.layoutScanBadgesW != 88) { s.layoutScanBadgesW = 88; changed = true; }
        if (s.layoutFinderW != 120) { s.layoutFinderW = 120; changed = true; }
        return changed;
    }
}
