package com.gather.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;

public class GatherSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("gather_settings.json");

    private static GatherSettings instance;

    public boolean showHud = true;
    public boolean enabled = true;
    public boolean countChests = false;
    public boolean hasShownWelcome = false;
    public boolean highlightEnabled = true;
    public boolean droppedItemXray = true;
    public boolean blockXray = false;
    public boolean countExistingOnAdd = false; // false = "+more" mode (need X more), true = "total" mode (need X total)
    public boolean chestOutlinesEnabled = true;
    public int maxBlockHighlights = 32;
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
    public int layoutFinderW = 112;

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
        layoutFinderW = 112;
        save();
    }

    private static GatherSettings load() {
        if (!FILE.toFile().exists()) return new GatherSettings();
        try (Reader r = new FileReader(FILE.toFile())) {
            GatherSettings s = GSON.fromJson(r, GatherSettings.class);
            return s != null ? s : new GatherSettings();
        } catch (IOException e) {
            return new GatherSettings();
        }
    }
}
