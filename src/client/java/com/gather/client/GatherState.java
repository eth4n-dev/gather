package com.gather.client;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GatherState {

    static final String[] WOOD_PREFIXES = {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
        "mangrove", "cherry", "bamboo", "crimson", "warped"
    };

    static boolean isSameWoodFamily(String idA, String idB) {
        String a = idA.contains(":") ? idA.substring(idA.indexOf(':') + 1) : idA;
        String b = idB.contains(":") ? idB.substring(idB.indexOf(':') + 1) : idB;
        for (String prefix : WOOD_PREFIXES) {
            if (a.startsWith(prefix + "_")) {
                String suffix = a.substring(prefix.length());
                for (String p : WOOD_PREFIXES) {
                    if (b.equals(p + suffix)) return true;
                }
                return false;
            }
        }
        return false;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path GATHER_DIR = GatherSettings.GATHER_DIR;
    // Global fallback file — used when no world is loaded
    private static final Path LISTS_FILE        = GATHER_DIR.resolve("lists.json");
    // Legacy single-list paths — used only for migration (old pre-multi-list format)
    private static final Path LEGACY_FILE       = FabricLoader.getInstance().getConfigDir().resolve("gather_list.json");
    private static final Path LEGACY_GOALS_FILE = FabricLoader.getInstance().getConfigDir().resolve("gather_goals.json");

    // Bulk migrate all gather_*.json files from config root to gather/ subdir on first launch
    public static void migrateAllToSubdir() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File[] old = configDir.toFile().listFiles((d, n) -> n.startsWith("gather_") && n.endsWith(".json"));
        if (old == null) return;
        for (File f : old) {
            String newName = f.getName().substring("gather_".length());
            Path dest = GATHER_DIR.resolve(newName);
            if (!Files.exists(dest)) {
                try { Files.move(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) {}
            } else {
                f.delete(); // already migrated, stale duplicate
            }
        }
    }

    private static final Type LISTS_TYPE    = new TypeToken<ArrayList<GatherList>>(){}.getType();
    private static final Type LEGACY_TYPE   = new TypeToken<ArrayList<ListNode>>(){}.getType();
    private static final Type STRING_SET_TYPE = new TypeToken<LinkedHashSet<String>>(){}.getType();

    private static GatherState instance;
    private static String currentWorldId = null;
    private static boolean serverXrayAllowed = false;
    private static final long SAVE_DEBOUNCE_MS = 500L;
    private static boolean savePending = false;
    private static long saveDueAtMs = 0L;

    public static boolean isServerXrayAllowed() { return serverXrayAllowed; }
    public static void setServerXrayAllowed(boolean v) { serverXrayAllowed = v; }

    private static final int MAX_LISTS = 10;

    // ─── WORLD MANAGEMENT ────────────────────────────────────────────────────

    public static void loadForWorld(net.minecraft.client.Minecraft minecraft) {
        if (instance != null) instance.flushSaveNow();
        currentWorldId = deriveWorldId(minecraft);
        instance = load();
        loadHiddenBaseMaterials(instance);
        loadTrackedChests(instance);
        loadTrackedChestContents(instance);
        loadManualChests(instance);
        loadManualChestContents(instance);
        instance.rebuildTrackedChestCounts();
        instance.rebuildManualChestCounts();
    }

    public static void unload() {
        if (instance != null) instance.flushSaveNow();
        instance = null;
        savePending = false;
        currentWorldId = null;
        serverXrayAllowed = false;
    }

    public static void flushPendingSaveIfDue() {
        if (instance == null || !savePending) return;
        if (System.currentTimeMillis() >= saveDueAtMs) instance.flushSaveNow();
    }

    public static String getCurrentWorldId() { return currentWorldId; }

    public static String deriveWorldId(net.minecraft.client.Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            // Use world folder name — unique per world (MC appends "(2)", "(3)" etc for duplicates).
            // getLevelName() returns the display name, which is NOT unique across worlds.
            java.nio.file.Path root = minecraft.getSingleplayerServer()
                    .getServerDirectory()
                    .normalize();
            java.nio.file.Path folder = root.getFileName();
            String name = (folder != null ? folder : root).toString();
            return "sp_" + sanitizeId(name);
        } else if (minecraft.getCurrentServer() != null) {
            return "mp_" + sanitizeId(minecraft.getCurrentServer().ip);
        }
        return "default";
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    private static Path listsFileForWorld(String worldId) {
        return GATHER_DIR.resolve("lists_" + worldId + ".json");
    }

    private static Path activeFile() {
        return currentWorldId != null ? listsFileForWorld(currentWorldId) : LISTS_FILE;
    }

    // Returns IDs of all worlds that have saved list files, excluding the current world.
    public static List<String> listOtherWorldIds() {
        File[] files = GATHER_DIR.toFile().listFiles((d, n) ->
            n.startsWith("lists_") && n.endsWith(".json"));
        if (files == null) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (File f : files) {
            String fn = f.getName();
            String id = fn.substring("lists_".length(), fn.length() - ".json".length());
            if (!id.equals(currentWorldId) && worldIdStillExists(id)) ids.add(id);
        }
        return ids;
    }

    private static boolean worldIdStillExists(String worldId) {
        if (!worldId.startsWith("sp_")) return true;
        java.io.File savesDir = FabricLoader.getInstance().getGameDir().resolve("saves").toFile();
        java.io.File[] worlds = savesDir.listFiles(java.io.File::isDirectory);
        if (worlds == null) return false;
        String target = worldId.substring(3);
        for (java.io.File world : worlds) {
            if (sanitizeId(world.getName()).equals(target)) return true;
        }
        return false;
    }

    // Load lists from an arbitrary world file (for transfer UI).
    public static List<GatherList> loadListsFromWorld(String worldId) {
        Path file = listsFileForWorld(worldId);
        if (!file.toFile().exists()) return new ArrayList<>();
        try (Reader r = new FileReader(file.toFile())) {
            List<GatherList> loaded = GSON.fromJson(r, LISTS_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    // Copy one list from another world into this world's state.
    public GatherList copyListFromWorld(String worldId, int listIndex) {
        List<GatherList> source = loadListsFromWorld(worldId);
        if (listIndex < 0 || listIndex >= source.size()) return null;
        if (lists.size() >= MAX_LISTS) return null;
        return importList(source.get(listIndex));
    }

    public GatherList importList(GatherList src) {
        if (src == null || lists.size() >= MAX_LISTS) return null;
        GatherList copy = new GatherList(src.name);
        copy.nodes = src.nodes.stream().map(ListNode::copy).collect(Collectors.toCollection(ArrayList::new));
        copy.savedGoals = src.savedGoals != null
            ? src.savedGoals.stream().map(ListNode::copy).collect(Collectors.toCollection(ArrayList::new))
            : new ArrayList<>();
        lists.add(copy);
        save();
        return copy;
    }

    private List<GatherList> lists            = new ArrayList<>();
    private int activeListIndex               = 0;   // which list the Add tab targets
    private int lastAddedListIndex            = 0;   // default for the picker overlay
    private Map<String, Integer> chestCounts  = new HashMap<>();
    private Map<String, Map<String, Integer>> breakdownCache        = new HashMap<>();
    private Map<String, Boolean>              inventoryCraftableCache = new HashMap<>();
    private Set<String> hiddenBaseMaterials = new LinkedHashSet<>();

    // Tracked chests (specific positions the user pinned by right-clicking)
    private transient Set<Long> trackedChests = new LinkedHashSet<>();
    private transient boolean chestScanMode = false;
    private transient Map<Long, Map<String, Integer>> trackedChestContents = new HashMap<>();
    private transient Map<String, Integer> trackedChestCounts = new HashMap<>();
    // Manually tagged chests — separate from auto scan, always counted
    private transient Set<Long> manualChests = new LinkedHashSet<>();
    private transient Map<Long, Map<String, Integer>> manualChestContents = new HashMap<>();
    private transient Map<String, Integer> manualChestCounts = new HashMap<>();
    private transient Map<Long, Map<String, Integer>> collectorChestContents = new HashMap<>();
    private transient Map<String, Integer> collectorChestCounts = new HashMap<>();
    private transient Set<String> effectivelyNeededIds = new HashSet<>();
    private transient Map<Item, Boolean> neededItemCache = new HashMap<>();
    // Placed collector shulker positions → item list (server-authoritative)
    private transient java.util.concurrent.ConcurrentHashMap<Long, java.util.List<String>> collectorPositions = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── SINGLETON ───────────────────────────────────────────────────────────

    public static GatherState get() {
        if (instance == null) instance = load();
        return instance;
    }

    // ─── LIST MANAGEMENT ─────────────────────────────────────────────────────

    public List<GatherList> getLists()               { return lists; }
    public int              getListCount()            { return lists.size(); }
    public GatherList       getList(int i)            { return lists.get(i); }

    /** Leaf (non-broken) item IDs under a specific root item in one list. Used for craft hints. */
    public List<String> getLeafIdsForRoot(int listIndex, String rootItemId) {
        if (listIndex < 0 || listIndex >= lists.size()) return List.of();
        List<ListNode> nodes = lists.get(listIndex).nodes;
        List<String> result = new ArrayList<>();
        boolean inRoot = false;
        for (ListNode node : nodes) {
            if (node.depth == 0) {
                if (node.itemId.equals(rootItemId)) { inRoot = true; continue; }
                if (inRoot) break;
            }
            if (inRoot && !node.broken) result.add(node.itemId);
        }
        return result;
    }

    /** All nodes across every list — used by WorldHighlightRenderer for block scanning. */
    public List<ListNode> getNodes() {
        List<ListNode> all = new ArrayList<>();
        for (GatherList list : lists) all.addAll(list.nodes);
        return all;
    }

    /** Total node count without allocating a list. Use instead of getNodes().size() in hot paths. */
    public int getTotalNodeCount() {
        int count = 0;
        for (GatherList list : lists) count += list.nodes.size();
        return count;
    }

    public List<ListNode> getNodes(int listIndex) {
        if (listIndex < 0 || listIndex >= lists.size()) return List.of();
        return lists.get(listIndex).nodes;
    }

    public int  getActiveListIndex()    { return activeListIndex; }
    public void setActiveListIndex(int i) {
        activeListIndex = Math.max(0, Math.min(i, lists.size() - 1));
    }

    public int  getLastAddedListIndex()        { return Math.min(lastAddedListIndex, lists.size()-1); }
    public void setLastAddedListIndex(int i)   { lastAddedListIndex = Math.max(0, Math.min(i, lists.size()-1)); }

    /** Returns null if at the 10-list cap. */
    public GatherList addList(String name) {
        if (lists.size() >= MAX_LISTS) return null;
        GatherList list = new GatherList(name);
        lists.add(list);
        save();
        return list;
    }

    /** Remove list; always keeps at least one. Returns false if refused. */
    public boolean removeList(int listIndex) {
        if (lists.size() <= 1) return false;
        lists.remove(listIndex);
        if (activeListIndex >= lists.size()) activeListIndex = lists.size() - 1;
        save();
        return true;
    }

    public void renameList(int listIndex, String name) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        lists.get(listIndex).name = name.isBlank() ? "List" : name;
        save();
    }

    public void toggleListHudHidden(int listIndex) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        lists.get(listIndex).hudHidden = !lists.get(listIndex).hudHidden;
        save();
        GatherHud.markDirty();
    }

    // ─── ITEM OPERATIONS ─────────────────────────────────────────────────────

    public void addItem(int listIndex, String itemId, int count, int baseline) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        GatherList list = lists.get(listIndex);
        lastAddedListIndex = listIndex;
        for (ListNode n : list.nodes) {
            if (n.depth == 0 && n.itemId.equals(itemId)) {
                n.needed += count;
                save(); syncGoals(list); return;
            }
        }
        ListNode node = new ListNode(itemId, count, 0);
        node.baseline = baseline;
        list.nodes.add(node);
        save(); syncGoals(list);
    }

    public void removeNode(int listIndex, int index) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        GatherList list = lists.get(listIndex);
        removeNodeNoSave(list.nodes, index);
        save(); syncGoals(list);
    }

    private void removeNodeNoSave(List<ListNode> nodes, int index) {
        if (index < 0 || index >= nodes.size()) return;
        int baseDepth = nodes.get(index).depth;
        int end = index + 1;
        while (end < nodes.size() && nodes.get(end).depth > baseDepth) end++;
        nodes.subList(index, end).clear();
    }

    public void setCount(int listIndex, int index, int count) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (index < 0 || index >= nodes.size()) return;
        if (count <= 0) { removeNode(listIndex, index); return; }
        nodes.get(index).needed = count;
        save(); syncGoals(lists.get(listIndex));
    }

    public void insertBreakdown(int listIndex, int index,
                                Map<String, Integer> ingredients, boolean inventoryCraftable) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (index < 0 || index >= nodes.size()) return;
        ListNode parent = nodes.get(index);

        int end = index + 1;
        while (end < nodes.size() && nodes.get(end).depth > parent.depth) end++;
        nodes.subList(index + 1, end).clear();

        if (ingredients == null || ingredients.isEmpty()
                || (ingredients.size() == 1 && ingredients.containsKey(parent.itemId))) {
            parent.broken = false;
            parent.inventoryCraftable = false;
            save(); syncGoals(lists.get(listIndex));
            return;
        }

        int childDepth = parent.depth + 1;
        parent.broken = true;
        parent.inventoryCraftable = inventoryCraftable;
        int insertAt = index + 1;
        for (Map.Entry<String, Integer> e : ingredients.entrySet()) {
            nodes.add(insertAt++, new ListNode(e.getKey(), e.getValue(), childDepth));
        }
        save(); syncGoals(lists.get(listIndex));
    }

    /** Move a root node (and its breakdown subtree) from one list to another. */
    public void moveNodeToList(int fromList, int fromIndex, int toList) {
        if (fromList == toList) return;
        if (fromList < 0 || fromList >= lists.size()) return;
        if (toList   < 0 || toList   >= lists.size()) return;
        List<ListNode> fromNodes = lists.get(fromList).nodes;
        if (fromIndex < 0 || fromIndex >= fromNodes.size()) return;
        ListNode node = fromNodes.get(fromIndex);
        if (node.depth != 0) return;

        int end = fromIndex + 1;
        while (end < fromNodes.size() && fromNodes.get(end).depth > 0) end++;
        List<ListNode> subtree = new ArrayList<>(fromNodes.subList(fromIndex, end));
        fromNodes.subList(fromIndex, end).clear();
        syncGoals(lists.get(fromList));

        List<ListNode> toNodes = lists.get(toList).nodes;
        for (ListNode existing : toNodes) {
            if (existing.depth == 0 && existing.itemId.equals(node.itemId)) {
                existing.needed += node.needed;
                save(); syncGoals(lists.get(toList));
                return;
            }
        }
        toNodes.addAll(subtree);
        save(); syncGoals(lists.get(toList));
    }

    public void setCollapsed(int listIndex, int index, boolean collapsed) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (index < 0 || index >= nodes.size()) return;
        nodes.get(index).collapsed = collapsed;
        save();
    }

    // ─── AUTO-SYNC ───────────────────────────────────────────────────────────

    public boolean autoSync(Function<String, Integer> countFn) {
        boolean changed = false;
        for (GatherList list : lists) changed |= autoSyncList(list, countFn);
        if (changed) save();
        return changed;
    }

    private boolean autoSyncList(GatherList list, Function<String, Integer> countFn) {
        if (!GatherSettings.get().autoRemoveCompleted) return false;
        boolean changed = false;
        List<ListNode> nodes      = list.nodes;
        List<ListNode> savedGoals = list.savedGoals;

        for (int i = nodes.size() - 1; i >= 0; i--) {
            ListNode n = nodes.get(i);
            if (n.depth != 0) continue;
            if (Math.max(0, countFn.apply(n.itemId) - n.baseline) >= n.needed) {
                String completedId = n.itemId;
                removeNodeNoSave(nodes, i);
                for (int si = savedGoals.size() - 1; si >= 0; si--) {
                    if (savedGoals.get(si).depth == 0 && savedGoals.get(si).itemId.equals(completedId)) {
                        int end = si + 1;
                        while (end < savedGoals.size() && savedGoals.get(end).depth > 0) end++;
                        savedGoals.subList(si, end).clear();
                        break;
                    }
                }
                changed = true;
            }
        }

        int gi = 0;
        while (gi < savedGoals.size()) {
            ListNode goal = savedGoals.get(gi);
            if (goal.depth != 0) { gi++; continue; }
            int goalEnd = gi + 1;
            while (goalEnd < savedGoals.size() && savedGoals.get(goalEnd).depth > 0) goalEnd++;

            boolean inNodes = false;
            for (ListNode n : nodes) {
                if (n.depth == 0 && n.itemId.equals(goal.itemId)) { inNodes = true; break; }
            }
            int have = Math.max(0, countFn.apply(goal.itemId) - goal.baseline);
            if (!inNodes && have < goal.needed) {
                for (int k = gi; k < goalEnd; k++) nodes.add(savedGoals.get(k).copy());
                changed = true;
            }
            gi = goalEnd;
        }
        return changed;
    }

    // ─── QUERIES ─────────────────────────────────────────────────────────────

    public boolean isNeeded(Item item) {
        Boolean cached = neededItemCache.get(item);
        if (cached != null) return cached;
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (isBaseMaterialHidden(id)) return false;
        if (effectivelyNeededIds.contains(id)) {
            neededItemCache.put(item, true);
            return true;
        }
        for (String needId : effectivelyNeededIds) {
            if (isSameWoodFamily(needId, id)) {
                neededItemCache.put(item, true);
                return true;
            }
        }
        neededItemCache.put(item, false);
        return false;
    }

    public void updateEffectivelyNeeded(Function<String, Integer> totalCounter) {
        Set<String> result = new HashSet<>();
        for (GatherList list : lists) {
            for (ListNode n : list.nodes) {
                if (n.needed <= 0 || isBaseMaterialHidden(n.itemId)) continue;
                if (totalCounter.apply(n.itemId) < n.needed) result.add(n.itemId);
            }
        }
        effectivelyNeededIds = result;
        neededItemCache.clear();
    }

    /** Leaf (non-broken) nodes for one list — raw needed, no inventory adjustment. Used by menu summary. */
    public Map<String, Integer> getBaseMaterials(int listIndex) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (listIndex < 0 || listIndex >= lists.size()) return result;
        for (ListNode n : lists.get(listIndex).nodes) {
            if (!n.broken) result.merge(n.itemId, n.needed, Integer::sum);
        }
        return result;
    }

    /**
     * Like getBaseMaterials but subtracts inventory from each leaf via inventoryCounter,
     * and propagates parent satisfaction downward so already-covered leaves are omitted.
     * Used by the HUD so it only shows what still needs gathering.
     */
    public Map<String, Integer> getEffectiveBaseMaterials(int listIndex,
                                                           java.util.function.Function<String, Integer> inventoryCounter) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (listIndex < 0 || listIndex >= lists.size()) return result;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        NeededState neededState = computeNeededState(nodes, inventoryCounter);
        for (int ni = 0; ni < nodes.size(); ni++) {
            if (!nodes.get(ni).broken && neededState.effectiveNeeded[ni] > 0) {
                result.merge(nodes.get(ni).itemId, neededState.effectiveNeeded[ni], Integer::sum);
            }
        }
        return result;
    }

    /**
     * HUD display: stays on unresolved leaf materials until the entire list's base-material
     * requirements are covered. Only then does it promote broken (intermediate) nodes whose
     * direct children are all available now, so shared base materials do not disappear early.
     */
    public Map<String, Integer> getActionableItems(int listIndex,
                                                    java.util.function.Function<String, Integer> totalCounter) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (listIndex < 0 || listIndex >= lists.size()) return result;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (nodes.isEmpty()) return result;

        NeededState neededState = computeNeededState(nodes, totalCounter);
        int[] rawNeeded = neededState.rawNeeded;
        int[] effNeeded = neededState.effectiveNeeded;

        if (hasUnresolvedLeaves(nodes, effNeeded)) {
            // Keep showing leaf nodes while any base material is still missing anywhere in the list.
            // Use rawNeeded as the stable target so the denominator does not shrink with inventory changes.
            for (int ni = 0; ni < nodes.size(); ni++) {
                if (effNeeded[ni] > 0 && !nodes.get(ni).broken) {
                    if (isBaseMaterialHidden(nodes.get(ni).itemId)) continue;
                    result.merge(nodes.get(ni).itemId, rawNeeded[ni], Integer::sum);
                }
            }
            return result;
        }

        // All leaf requirements are satisfied, so promote broken nodes whose immediate children are
        // available now. Map value = rawNeeded (stable target), not effNeeded.
        for (int ni = 0; ni < nodes.size(); ni++) {
            if (effNeeded[ni] == 0) continue;
            ListNode node = nodes.get(ni);
            if (!node.broken) continue;
            boolean childrenReady = true;
            for (int j = ni + 1; j < nodes.size(); j++) {
                ListNode child = nodes.get(j);
                if (child.depth <= node.depth) break;
                if (child.depth != node.depth + 1) continue;
                int childNeeded = node.needed > 0
                    ? (int) Math.ceil((double) child.needed * effNeeded[ni] / node.needed)
                    : 0;
                if (totalCounter.apply(child.itemId) < childNeeded) {
                    childrenReady = false;
                    break;
                }
            }
            if (childrenReady) {
                if (isBaseMaterialHidden(node.itemId)) continue;
                result.merge(node.itemId, rawNeeded[ni], Integer::sum);
            }
        }
        return result;
    }

    public record RootInfo(String itemId, int needed, boolean ready, float leafProgress, int craftableNow, int effectiveHave) {}

    /**
     * Per-root progress for the HUD goal section.
     * craftableNow = how many can be built right now from inventory; -1 for unbroken roots.
     */
    public List<RootInfo> getRootsWithProgress(int listIndex,
                                                java.util.function.Function<String, Integer> counter) {
        List<RootInfo> result = new ArrayList<>();
        if (listIndex < 0 || listIndex >= lists.size()) return result;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (nodes.isEmpty()) return result;

        NeededState ns = computeNeededState(nodes, counter);
        int[] raw = ns.rawNeeded();
        int[] eff = ns.effectiveNeeded();

        for (int ni = 0; ni < nodes.size(); ni++) {
            ListNode root = nodes.get(ni);
            if (root.depth != 0) continue;
            if (isBaseMaterialHidden(root.itemId)) continue;

            if (!root.broken) {
                int have = counter.apply(root.itemId);
                int effHave = Math.max(0, have - root.baseline);
                float progress = raw[ni] > 0 ? Math.min(1f, (float) effHave / raw[ni]) : 1f;
                result.add(new RootInfo(root.itemId, root.needed, eff[ni] == 0, progress, -1, effHave));
            } else {
                int leafRawTotal = 0, leafHaveTotal = 0;
                boolean allLeavesCovered = true, hasLeaf = false;
                int craftableNow = Integer.MAX_VALUE;
                boolean hasLeafForCraftable = false;

                for (int j = ni + 1; j < nodes.size(); j++) {
                    ListNode child = nodes.get(j);
                    if (child.depth == 0) break;
                    if (!child.broken) {
                        hasLeaf = true;
                        leafRawTotal += raw[j];
                        leafHaveTotal += Math.min(raw[j], counter.apply(child.itemId));
                        if (eff[j] > 0) allLeavesCovered = false;

                        hasLeafForCraftable = true;
                        int have = counter.apply(child.itemId);
                        if (root.needed > 0 && child.needed > 0) {
                            craftableNow = Math.min(craftableNow, (have * root.needed) / child.needed);
                        }
                    }
                }

                float progress = leafRawTotal > 0 ? (float) leafHaveTotal / leafRawTotal : 1f;
                boolean ready = hasLeaf ? allLeavesCovered : eff[ni] == 0;
                int finalCraftable = hasLeafForCraftable ? (craftableNow == Integer.MAX_VALUE ? 0 : craftableNow) : 0;
                int effHave = Math.max(0, counter.apply(root.itemId) - root.baseline);
                result.add(new RootInfo(root.itemId, root.needed, ready, progress, finalCraftable, effHave));
            }
        }
        return result;
    }

    /**
     * Scaled rawNeeded for depth>0 leaf nodes in one list (accounts for parent progress).
     * Used to build the aggregated shared base-materials section in the HUD.
     */
    public Map<String, Integer> getScaledIngredientLeaves(int listIndex,
                                                           java.util.function.Function<String, Integer> counter) {
        return getScaledIngredientLeaves(listIndex, counter, false);
    }

    public Map<String, Integer> getScaledIngredientLeaves(int listIndex,
                                                           java.util.function.Function<String, Integer> counter,
                                                           boolean includeHidden) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (listIndex < 0 || listIndex >= lists.size()) return result;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        NeededState ns = computeNeededState(nodes, counter);
        for (int ni = 0; ni < nodes.size(); ni++) {
            if (nodes.get(ni).depth > 0 && !nodes.get(ni).broken && ns.rawNeeded()[ni] > 0) {
                if (!includeHidden && isBaseMaterialHidden(nodes.get(ni).itemId)) continue;
                result.merge(nodes.get(ni).itemId, ns.rawNeeded()[ni], Integer::sum);
            }
        }
        return result;
    }

    public boolean isBaseMaterialHidden(String itemId) {
        return hiddenBaseMaterials.contains(itemId);
    }

    public void toggleBaseMaterialHidden(String itemId) {
        if (!hiddenBaseMaterials.remove(itemId)) hiddenBaseMaterials.add(itemId);
        neededItemCache.clear();
        saveHiddenBaseMaterials();
    }

    public void toggleSubtreeHidden(int listIndex, int nodeIndex) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) return;
        int rootDepth = nodes.get(nodeIndex).depth;
        boolean hide = !isBaseMaterialHidden(nodes.get(nodeIndex).itemId);
        for (int i = nodeIndex; i < nodes.size(); i++) {
            ListNode node = nodes.get(i);
            if (i > nodeIndex && node.depth <= rootDepth) break;
            if (hide) hiddenBaseMaterials.add(node.itemId);
            else hiddenBaseMaterials.remove(node.itemId);
        }
        neededItemCache.clear();
        saveHiddenBaseMaterials();
    }

    /**
     * Number of root goals whose own base-material subtree is fully gathered, even if other roots
     * in the same list are still waiting on materials. Used as a small HUD marker.
     */
    public int getReadyRootCount(int listIndex,
                                 java.util.function.Function<String, Integer> totalCounter) {
        if (listIndex < 0 || listIndex >= lists.size()) return 0;
        List<ListNode> nodes = lists.get(listIndex).nodes;
        if (nodes.isEmpty()) return 0;

        NeededState neededState = computeNeededState(nodes, totalCounter);
        int[] effNeeded = neededState.effectiveNeeded;
        int ready = 0;

        for (int ni = 0; ni < nodes.size(); ni++) {
            ListNode root = nodes.get(ni);
            if (root.depth != 0 || !root.broken || effNeeded[ni] == 0) continue;

            boolean hasLeaf = false;
            boolean leavesCovered = true;
            for (int j = ni + 1; j < nodes.size(); j++) {
                ListNode child = nodes.get(j);
                if (child.depth <= root.depth) break;
                if (!child.broken) {
                    hasLeaf = true;
                    if (effNeeded[j] > 0) {
                        leavesCovered = false;
                        break;
                    }
                }
            }

            if (hasLeaf && leavesCovered) ready++;
        }

        return ready;
    }

    private NeededState computeNeededState(List<ListNode> nodes,
                                           java.util.function.Function<String, Integer> counter) {
        int[] rawNeeded = new int[nodes.size()];
        int[] effectiveNeeded = new int[nodes.size()];
        int[] latestIndexByDepth = new int[Math.max(1, maxDepth(nodes) + 1)];
        Arrays.fill(latestIndexByDepth, -1);

        for (int ni = 0; ni < nodes.size(); ni++) {
            ListNode node = nodes.get(ni);
            int have = counter.apply(node.itemId);

            if (node.depth == 0) {
                rawNeeded[ni] = node.needed;
                effectiveNeeded[ni] = Math.max(0, node.needed - Math.max(0, have - node.baseline));
            } else {
                int parentIndex = node.depth - 1 < latestIndexByDepth.length
                        ? latestIndexByDepth[node.depth - 1]
                        : -1;
                if (parentIndex >= 0) {
                    ListNode parent = nodes.get(parentIndex);
                    rawNeeded[ni] = parent.needed > 0
                            ? (int) Math.ceil((double) node.needed * effectiveNeeded[parentIndex] / parent.needed)
                            : 0;
                }
                effectiveNeeded[ni] = Math.max(0, rawNeeded[ni] - have);
            }

            latestIndexByDepth[node.depth] = ni;
            for (int depth = node.depth + 1; depth < latestIndexByDepth.length; depth++) {
                latestIndexByDepth[depth] = -1;
            }
        }

        return new NeededState(rawNeeded, effectiveNeeded);
    }

    private int maxDepth(List<ListNode> nodes) {
        int max = 0;
        for (ListNode node : nodes) max = Math.max(max, node.depth);
        return max;
    }

    private boolean hasUnresolvedLeaves(List<ListNode> nodes, int[] effectiveNeeded) {
        for (int ni = 0; ni < nodes.size(); ni++) {
            if (!nodes.get(ni).broken && effectiveNeeded[ni] > 0) return true;
        }
        return false;
    }

    private record NeededState(int[] rawNeeded, int[] effectiveNeeded) {}

    /** All depth-0 items across all lists (for WorldHighlight block scanning). */
    public Map<String, Integer> getNeeded() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (GatherList list : lists) {
            for (ListNode n : list.nodes) {
                if (isBaseMaterialHidden(n.itemId)) continue;
                if (n.depth == 0) result.merge(n.itemId, n.needed, Integer::sum);
            }
        }
        return result;
    }

    /** Whether any depth-0 non-hidden node exists. Zero-allocation alternative to !getNeeded().isEmpty(). */
    public boolean hasNeeded() {
        for (GatherList list : lists) {
            for (ListNode n : list.nodes) {
                if (n.depth == 0 && !isBaseMaterialHidden(n.itemId)) return true;
            }
        }
        return false;
    }

    public List<String> getChestScanTargets(java.util.function.Function<String, Integer> inventoryCounter) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int li = 0; li < lists.size(); li++) {
            getActionableItems(li, inventoryCounter).forEach((id, count) -> result.merge(id, count, Integer::sum));
        }
        return new ArrayList<>(result.keySet());
    }

    /** Total needed count for an item across all lists (for the Add tab indicator). */
    public int getNeededCount(String itemId) {
        int total = 0;
        for (GatherList list : lists) {
            for (ListNode n : list.nodes) {
                if (n.depth == 0 && n.itemId.equals(itemId)) total += n.needed;
            }
        }
        return total;
    }

    public void updateChestCounts(Map<String, Integer> counts) { this.chestCounts = counts; }
    public int  getHaveCount(String itemId) { return countMatchingChestItems(chestCounts, itemId); }

    // ─── TRACKED CHESTS ───────────────────────────────────────────────────────

    public boolean isChestScanMode()          { return chestScanMode; }
    public void    setChestScanMode(boolean v) {
        chestScanMode = v;
        GatherHud.markDirty();
    }

    public java.util.Map<Long, java.util.List<String>> getCollectorPositions() { return collectorPositions; }

    public Map<String, Integer> getCollectorChestContents(long encoded) {
        return collectorChestContents.getOrDefault(encoded, Map.of());
    }

    public boolean hasCollectorChestContents(long encoded) {
        return collectorChestContents.containsKey(encoded);
    }

    public void setCollectorPositions(java.util.List<com.gather.network.PlacedCollectorPositionsPayload.Entry> entries) {
        collectorPositions.clear();
        for (var e : entries) collectorPositions.put(e.pos(), e.items());
        collectorChestContents.keySet().retainAll(collectorPositions.keySet());
        rebuildCollectorChestCounts();
    }

    public Set<Long> getTrackedChests() { return trackedChests; }

    public Set<Long> getTrackedChestsWithoutContents() {
        Set<Long> result = new HashSet<>(trackedChests);
        result.removeAll(trackedChestContents.keySet());
        return result;
    }

    public void toggleTrackedChest(BlockPos pos) {
        long key = pos.asLong();
        if (trackedChests.remove(key)) {
            trackedChestContents.remove(key);
        } else {
            trackedChests.add(key);
        }
        rebuildTrackedChestCounts();
        saveTrackedChests();
        saveTrackedChestContents();
    }

    public boolean isTrackedChest(BlockPos pos) { return trackedChests.contains(pos.asLong()); }

    public void removeTrackedChest(long encoded) {
        if (trackedChests.remove(encoded)) {
            trackedChestContents.remove(encoded);
            rebuildTrackedChestCounts();
            cachedFinderChests = null;
        }
    }

    public void addTrackedChests(List<Long> positions) {
        boolean changed = trackedChests.addAll(positions);
        if (changed) {
            saveTrackedChests();
            saveTrackedChestContents();
        }
    }

    public void replaceTrackedChests(List<Long> positions) {
        Set<Long> replacement = new LinkedHashSet<>(positions);
        if (trackedChests.equals(replacement)) return;
        trackedChests.clear();
        trackedChests.addAll(replacement);
        trackedChestContents.keySet().retainAll(trackedChests);
        rebuildTrackedChestCounts();
        saveTrackedChests();
        saveTrackedChestContents();
    }

    public void mergeAutoTrackedChests(List<Long> positions, ClientLevel world, BlockPos center, int radius) {
        Set<Long> found = new LinkedHashSet<>(positions);
        boolean changed = trackedChests.addAll(found);
        Iterator<Long> iterator = trackedChests.iterator();
        while (iterator.hasNext()) {
            long encoded = iterator.next();
            if (found.contains(encoded)) continue;
            BlockPos pos = BlockPos.of(encoded);
            if (!isWithinScanRadius(pos, center, radius)) continue;
            if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            iterator.remove();
            trackedChestContents.remove(encoded);
            changed = true;
        }
        if (changed) {
            rebuildTrackedChestCounts();
            saveTrackedChests();
            saveTrackedChestContents();
        }
    }

    private static boolean isWithinScanRadius(BlockPos pos, BlockPos center, int radius) {
        return Math.abs(pos.getX() - center.getX()) <= radius
                && Math.abs(pos.getY() - center.getY()) <= radius
                && Math.abs(pos.getZ() - center.getZ()) <= radius;
    }

    public void clearTrackedChests() {
        trackedChests.clear();
        trackedChestContents.clear();
        trackedChestCounts.clear();
        chestScanMode = false;
        cachedFinderChests = null;
        saveTrackedChests();
        saveTrackedChestContents();
    }

    public void mergeTrackedChestContents(Map<Long, Map<String, Integer>> updates) {
        boolean autoChanged = false, manualChanged = false;
        for (Map.Entry<Long, Map<String, Integer>> entry : updates.entrySet()) {
            long key = entry.getKey();
            if (collectorPositions.containsKey(key)) {
                collectorChestContents.put(key, new HashMap<>(entry.getValue()));
                continue;
            }
            if (manualChests.contains(key)) {
                manualChestContents.put(key, new HashMap<>(entry.getValue()));
                manualChanged = true;
            }
            if (trackedChests.contains(key)) {
                trackedChestContents.put(key, new HashMap<>(entry.getValue()));
                autoChanged = true;
            }
        }
        if (autoChanged) { rebuildTrackedChestCounts(); saveTrackedChestContents(); }
        if (manualChanged) { rebuildManualChestCounts(); saveManualChestContents(); }
        rebuildCollectorChestCounts();
        if (autoChanged || manualChanged) cachedFinderChests = null;
    }

    public int getTrackedChestCount(String itemId) { return trackedChestCounts.getOrDefault(itemId, 0); }
    public int getManualChestCount(String itemId)  { return manualChestCounts.getOrDefault(itemId, 0); }
    public int getCollectorChestCount(String itemId) { return collectorChestCounts.getOrDefault(itemId, 0); }

    public int getTrackedChestCountMatching(String itemId) {
        return countMatchingChestItems(trackedChestCounts, itemId);
    }

    public int getManualChestCountMatching(String itemId) {
        return countMatchingChestItems(manualChestCounts, itemId);
    }

    public int getCollectorChestCountMatching(String itemId) {
        return countMatchingChestItems(collectorChestCounts, itemId);
    }

    // Registry lookups are stable — never need invalidation.
    private static final Map<String, Item> ITEM_LOOKUP_CACHE = new HashMap<>();
    private static final Map<String, Set<String>> ITEM_TAG_PATH_CACHE = new HashMap<>();
    private static final Map<String, Set<String>> EXPECTED_TAG_PATHS_CACHE = new HashMap<>();

    private static Item cachedItem(String itemId) {
        return ITEM_LOOKUP_CACHE.computeIfAbsent(itemId, id -> BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }

    private static Set<String> cachedTagPaths(String itemId) {
        return ITEM_TAG_PATH_CACHE.computeIfAbsent(itemId, id -> {
            Item item = cachedItem(id);
            if (item == null) return Set.of();
            return item.builtInRegistryHolder().tags()
                    .map(tag -> tag.location().getPath())
                    .collect(Collectors.toSet());
        });
    }

    private static int countMatchingChestItems(Map<String, Integer> counts, String itemId) {
        int total = 0;
        String itemPath = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        Set<String> expectedTagPaths = expectedTagPaths(itemPath);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String foundId = entry.getKey();
            if (foundId.equals(itemId) || isSameWoodFamily(itemId, foundId)) {
                total += entry.getValue();
                continue;
            }
            Set<String> foundTags = cachedTagPaths(foundId);
            if (!foundTags.isEmpty() && !Collections.disjoint(foundTags, expectedTagPaths)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private void rebuildCollectorChestCounts() {
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Integer> contents : collectorChestContents.values()) {
            contents.forEach((id, count) -> totals.merge(id, count, Integer::sum));
        }
        collectorChestCounts = totals;
    }

    private static Set<String> expectedTagPaths(String itemPath) {
        return EXPECTED_TAG_PATHS_CACHE.computeIfAbsent(itemPath, path -> {
            Set<String> paths = new HashSet<>(2);
            paths.add(path);
            paths.add(pluralizeTagPath(path));
            return paths;
        });
    }

    private static String pluralizeTagPath(String itemPath) {
        if (itemPath.endsWith("s")) return itemPath + "es";
        if (itemPath.endsWith("y") && itemPath.length() > 1 && !isVowel(itemPath.charAt(itemPath.length() - 2))) {
            return itemPath.substring(0, itemPath.length() - 1) + "ies";
        }
        return itemPath + "s";
    }

    private static boolean isVowel(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'a', 'e', 'i', 'o', 'u' -> true;
            default -> false;
        };
    }

    // ─── CHEST FINDER ────────────────────────────────────────────────────────

    private transient String chestFinderItemId = null;
    private transient String cachedFinderItemId = null;
    private transient Set<Long> cachedFinderChests = null;

    public String getChestFinderItemId() { return chestFinderItemId; }
    public void   setChestFinderItemId(String id) {
        chestFinderItemId = id;
        cachedFinderChests = null; // finder query changed
        GatherHud.markDirty();
    }

    /** All items merged across every chest (tracked + manual), with summed counts. */
    public Map<String, Integer> getAllChestItems() {
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Integer> contents : trackedChestContents.values())
            contents.forEach((id, count) -> result.merge(id, count, Integer::sum));
        for (Map<String, Integer> contents : manualChestContents.values())
            contents.forEach((id, count) -> result.merge(id, count, Integer::sum));
        return result;
    }

    /** Positions (encoded longs) of every chest whose contents match the given item. Cached per itemId. */
    public Set<Long> getChestsContaining(String itemId) {
        if (itemId.equals(cachedFinderItemId) && cachedFinderChests != null) return cachedFinderChests;
        Set<Long> result = new HashSet<>();
        for (Map.Entry<Long, Map<String, Integer>> e : trackedChestContents.entrySet())
            if (countMatchingChestItems(e.getValue(), itemId) > 0) result.add(e.getKey());
        for (Map.Entry<Long, Map<String, Integer>> e : manualChestContents.entrySet())
            if (countMatchingChestItems(e.getValue(), itemId) > 0) result.add(e.getKey());
        cachedFinderItemId = itemId;
        cachedFinderChests = result;
        return result;
    }

    // ─── MANUAL CHEST TRACKING ───────────────────────────────────────────────

    public Set<Long> getManualChests() { return manualChests; }

    public boolean isManualChest(BlockPos pos) { return manualChests.contains(pos.asLong()); }

    public void removeManualChest(long encoded) {
        if (manualChests.remove(encoded)) {
            manualChestContents.remove(encoded);
            rebuildManualChestCounts();
            cachedFinderChests = null;
            saveManualChests();
            saveManualChestContents();
        }
    }

    public void toggleManualChest(BlockPos pos) {
        long key = pos.asLong();
        if (manualChests.remove(key)) {
            manualChestContents.remove(key);
            cachedFinderChests = null;
        } else {
            manualChests.add(key);
        }
        rebuildManualChestCounts();
        saveManualChests();
        saveManualChestContents();
    }

    public void clearManualChests() {
        manualChests.clear();
        manualChestContents.clear();
        manualChestCounts.clear();
        cachedFinderChests = null;
        saveManualChests();
        saveManualChestContents();
    }

    private void rebuildManualChestCounts() {
        Map<String, Integer> totals = new HashMap<>();
        for (long pos : manualChests) {
            Map<String, Integer> contents = manualChestContents.get(pos);
            if (contents == null) continue;
            for (Map.Entry<String, Integer> e : contents.entrySet())
                totals.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        manualChestCounts = totals;
        GatherHud.markDirty();
    }

    private static Path manualChestsFile() {
        String worldId = currentWorldId != null ? currentWorldId : "default";
        return GATHER_DIR.resolve("manual_chests_" + worldId + ".json");
    }

    private static Path manualChestContentsFile() {
        String worldId = currentWorldId != null ? currentWorldId : "default";
        return GATHER_DIR.resolve("manual_chest_contents_" + worldId + ".json");
    }

    private void saveManualChests() {
        try (Writer w = new FileWriter(manualChestsFile().toFile())) {
            GSON.toJson(new ArrayList<>(manualChests), w);
        } catch (IOException ignored) {}
    }

    private void saveManualChestContents() {
        try (Writer w = new FileWriter(manualChestContentsFile().toFile())) {
            Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
            for (long pos : manualChests) {
                Map<String, Integer> contents = manualChestContents.get(pos);
                if (contents != null && !contents.isEmpty())
                    out.put(Long.toString(pos), new LinkedHashMap<>(contents));
            }
            GSON.toJson(out, w);
        } catch (IOException ignored) {}
    }

    private static void loadManualChests(GatherState state) {
        Path file = manualChestsFile();
        if (!file.toFile().exists()) return;
        try (Reader r = new FileReader(file.toFile())) {
            Type type = new TypeToken<ArrayList<Long>>(){}.getType();
            ArrayList<Long> loaded = GSON.fromJson(r, type);
            if (loaded != null) state.manualChests = new LinkedHashSet<>(loaded);
        } catch (IOException | RuntimeException ignored) {}
    }

    private static void loadManualChestContents(GatherState state) {
        Path file = manualChestContentsFile();
        if (!file.toFile().exists()) return;
        try (Reader r = new FileReader(file.toFile())) {
            Type type = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(r, type);
            if (loaded == null) return;
            for (Map.Entry<String, Map<String, Integer>> entry : loaded.entrySet()) {
                try {
                    long pos = Long.parseLong(entry.getKey());
                    if (!state.manualChests.contains(pos)) continue;
                    if (entry.getValue() != null)
                        state.manualChestContents.put(pos, new HashMap<>(entry.getValue()));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException | RuntimeException ignored) {}
    }

    public int getUnloadedTrackedChestCount(net.minecraft.client.multiplayer.ClientLevel world) {
        int unloaded = 0;
        for (long encoded : trackedChests) {
            BlockPos pos = BlockPos.of(encoded);
            if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) unloaded++;
        }
        return unloaded;
    }

    private void rebuildTrackedChestCounts() {
        Map<String, Integer> totals = new HashMap<>();
        for (long pos : trackedChests) {
            Map<String, Integer> contents = trackedChestContents.get(pos);
            if (contents == null) continue;
            for (Map.Entry<String, Integer> entry : contents.entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        trackedChestCounts = totals;
        GatherHud.markDirty();
    }

    private static Path trackedChestsFile() {
        String worldId = currentWorldId != null ? currentWorldId : "default";
        return GATHER_DIR.resolve("tracked_chests_" + worldId + ".json");
    }

    private static Path trackedChestContentsFile() {
        String worldId = currentWorldId != null ? currentWorldId : "default";
        return GATHER_DIR.resolve("tracked_chest_contents_" + worldId + ".json");
    }

    private void saveTrackedChests() {
        try (Writer w = new FileWriter(trackedChestsFile().toFile())) {
            GSON.toJson(new ArrayList<>(trackedChests), w);
        } catch (IOException ignored) {}
    }

    private void saveTrackedChestContents() {
        try (Writer w = new FileWriter(trackedChestContentsFile().toFile())) {
            Map<String, Map<String, Integer>> serializable = new LinkedHashMap<>();
            for (long pos : trackedChests) {
                Map<String, Integer> contents = trackedChestContents.get(pos);
                if (contents != null && !contents.isEmpty()) {
                    serializable.put(Long.toString(pos), new LinkedHashMap<>(contents));
                }
            }
            GSON.toJson(serializable, w);
        } catch (IOException ignored) {}
    }

    private static void loadTrackedChests(GatherState state) {
        Path file = trackedChestsFile();
        if (!file.toFile().exists()) return;
        try (Reader r = new FileReader(file.toFile())) {
            Type type = new TypeToken<ArrayList<Long>>(){}.getType();
            ArrayList<Long> loaded = GSON.fromJson(r, type);
            if (loaded != null) state.trackedChests = new LinkedHashSet<>(loaded);
        } catch (IOException | RuntimeException ignored) {}
    }

    private static void loadTrackedChestContents(GatherState state) {
        Path file = trackedChestContentsFile();
        if (!file.toFile().exists()) return;
        try (Reader r = new FileReader(file.toFile())) {
            Type type = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(r, type);
            if (loaded == null) return;
            for (Map.Entry<String, Map<String, Integer>> entry : loaded.entrySet()) {
                try {
                    long pos = Long.parseLong(entry.getKey());
                    if (!state.trackedChests.contains(pos)) continue;
                    Map<String, Integer> contents = entry.getValue();
                    if (contents != null) state.trackedChestContents.put(pos, new HashMap<>(contents));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException | RuntimeException ignored) {}
    }

    public void cacheBreakdown(String originItemId, Map<String, Integer> ingredients, boolean inventoryCraftable) {
        breakdownCache.put(originItemId, ingredients);
        inventoryCraftableCache.put(originItemId, inventoryCraftable);
    }
    public Map<String, Integer> getCachedBreakdown(String itemId)       { return breakdownCache.get(itemId); }
    public boolean getCachedInventoryCraftable(String itemId)            { return inventoryCraftableCache.getOrDefault(itemId, false); }
    public boolean hasCachedInventoryCraftable(String itemId)            { return inventoryCraftableCache.containsKey(itemId); }

    // ─── PERSISTENCE ─────────────────────────────────────────────────────────

    public void save() {
        GatherHud.markDirty();
        savePending = true;
        saveDueAtMs = System.currentTimeMillis() + SAVE_DEBOUNCE_MS;
    }

    private void flushSaveNow() {
        GatherHud.markDirty();
        try (Writer w = new FileWriter(activeFile().toFile())) {
            GSON.toJson(lists, w);
            savePending = false;
        } catch (IOException ignored) {}
    }

    private static Path hiddenBaseMaterialsFile() {
        String worldId = currentWorldId != null ? currentWorldId : "default";
        return GATHER_DIR.resolve("hidden_base_materials_" + worldId + ".json");
    }

    private void saveHiddenBaseMaterials() {
        GatherHud.markDirty();
        try (Writer w = new FileWriter(hiddenBaseMaterialsFile().toFile())) {
            GSON.toJson(hiddenBaseMaterials, w);
        } catch (IOException ignored) {}
    }

    private static void loadHiddenBaseMaterials(GatherState state) {
        Path file = hiddenBaseMaterialsFile();
        if (!file.toFile().exists()) return;
        try (Reader r = new FileReader(file.toFile())) {
            LinkedHashSet<String> loaded = GSON.fromJson(r, STRING_SET_TYPE);
            if (loaded != null) state.hiddenBaseMaterials = loaded;
        } catch (IOException | RuntimeException ignored) {}
    }

    private void syncGoals(GatherList list) {
        list.savedGoals = list.nodes.stream().map(ListNode::copy)
                .collect(Collectors.toCollection(ArrayList::new));
        save();
    }

    private static GatherState load() {
        GatherState state = new GatherState();
        Path target = activeFile();

        // Try world-specific (or global fallback) file
        if (target.toFile().exists()) {
            try (Reader r = new FileReader(target.toFile())) {
                List<GatherList> loaded = GSON.fromJson(r, LISTS_TYPE);
                if (loaded != null && !loaded.isEmpty()) {
                    state.lists = loaded;
                    for (GatherList list : state.lists) {
                        if (list.savedGoals == null) list.savedGoals = new ArrayList<>();
                        if (list.savedGoals.isEmpty() && !list.nodes.isEmpty()) {
                            list.savedGoals = list.nodes.stream().map(ListNode::copy)
                                    .collect(Collectors.toCollection(ArrayList::new));
                        }
                    }
                    return state;
                }
            } catch (IOException | RuntimeException ignored) {}
        }

        // Migrate from legacy single-list files (one-time: delete after reading so new worlds start fresh)
        GatherList defaultList = new GatherList("My List");
        if (LEGACY_FILE.toFile().exists()) {
            try (Reader r = new FileReader(LEGACY_FILE.toFile())) {
                List<ListNode> nodes = GSON.fromJson(r, LEGACY_TYPE);
                if (nodes != null) defaultList.nodes = nodes;
            } catch (IOException | RuntimeException ignored) {}
            LEGACY_FILE.toFile().delete();
        }
        if (LEGACY_GOALS_FILE.toFile().exists()) {
            try (Reader r = new FileReader(LEGACY_GOALS_FILE.toFile())) {
                List<ListNode> goals = GSON.fromJson(r, LEGACY_TYPE);
                if (goals != null) defaultList.savedGoals = goals;
            } catch (IOException | RuntimeException ignored) {}
            LEGACY_GOALS_FILE.toFile().delete();
        }
        if (defaultList.savedGoals.isEmpty() && !defaultList.nodes.isEmpty()) {
            defaultList.savedGoals = defaultList.nodes.stream().map(ListNode::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        state.lists.add(defaultList);
        state.save();
        return state;
    }
}
