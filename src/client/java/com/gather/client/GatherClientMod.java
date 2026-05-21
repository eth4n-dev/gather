package com.gather.client;

import com.gather.client.screen.GatherHelpScreen;
import com.gather.client.screen.GatherMenuScreen;
import com.gather.client.screen.GatherTutorialScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.lwjgl.glfw.GLFW;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GatherClientMod implements ClientModInitializer {

    private static final java.util.Map<String, Item> ITEM_LOOKUP_CACHE = new java.util.HashMap<>();

    // Update checker
    private static final String UPDATE_CHECK_URL = "https://obl1v1on.xyz/gather/version.json";
    private static final String MC_CHANNEL = "1.21.11";
    public static volatile String updateAvailableVersion = null;
    public static volatile long   updateNotifStartMs     = 0L;

    private int autoSyncTimer     = 0;
    private int chestRefreshTimer = 0;
    private int fallbackRefreshIndex = 0;
    private boolean pendingHelpScreen = false;
    private long lastAutoTrackChunkKey = Long.MIN_VALUE;
    private int autoTrackFallbackCount = 0;
    private List<String> lastCollectorTargets = new java.util.ArrayList<>();

    @Override
    public void onInitializeClient() {
        GatherState.migrateAllToSubdir();
        GatherKeyBindings.register();
        GatherClientNetworking.register();
        GatherHud.register();
        WorldHighlightRenderer.register();
        GatherCraftingOverlay.register();
        GatherShulkerCollectorOverlay.register();
        GatherTradeCalculatorOverlay.register();
        GatherJeiAddOverlay.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("gather")
                .then(ClientCommandManager.literal("help")
                    .executes(ctx -> {
                        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                        mc.send(() -> mc.setScreen(new GatherHelpScreen(null, false)));
                        return 1;
                    }))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GatherState.loadForWorld(client);
            if (GatherSettings.get().enabled && !GatherSettings.get().hasShownWelcome) pendingHelpScreen = true;
            checkForUpdate();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GatherState.unload();
            GatherHud.reset();
            pendingHelpScreen = false;
            lastCollectorTargets = new java.util.ArrayList<>();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            GatherState.flushPendingSaveIfDue();
            GatherHud.handlePageKeys(client);

            if (pendingHelpScreen && client.player != null && client.currentScreen == null) {
                pendingHelpScreen = false;
                client.setScreen(new GatherTutorialScreen());
            }

            boolean manualScanHandled = false;
            while (GatherKeyBindings.manualScanToggle.wasPressed()) {
                if (client.player == null) return;
                GatherSettings cfg = GatherSettings.get();
                long handle = client.getWindow().getHandle();
                if (!exactMenuManualConflict(cfg) && scanModifiersHeld(cfg, handle) && client.currentScreen == null && cfg.enabled && !cfg.countChests) {
                    GatherState s = GatherState.get();
                    s.setChestScanMode(!s.isChestScanMode());
                    manualScanHandled = true;
                }
            }

            while (GatherKeyBindings.openMenu.wasPressed()) {
                if (client.player == null) return;
                if (manualScanHandled) continue;
                if (client.currentScreen == null) {
                    client.setScreen(new GatherMenuScreen());
                }
            }

            if (client.player == null) return;
            if (!GatherSettings.get().enabled) return;

            GatherState state = GatherState.get();
            if (++chestRefreshTimer >= 40) {
                chestRefreshTimer = 0;
                List<String> collectorTargets = state.getChestScanTargets(itemId -> 0);
                if (!collectorTargets.equals(lastCollectorTargets)) {
                    lastCollectorTargets = new java.util.ArrayList<>(collectorTargets);
                    GatherClientNetworking.updateCollectorTargets(collectorTargets);
                }
                if (GatherSettings.get().countChests) {
                    long ck = chunkKey(client.player.getBlockPos());
                    autoTrackFallbackCount++;
                    if (ck != lastAutoTrackChunkKey || autoTrackFallbackCount >= 15) {
                        lastAutoTrackChunkKey = ck;
                        autoTrackFallbackCount = 0;
                        List<String> targets = state.getChestScanTargets(itemId -> {
                            Item item = Registries.ITEM.get(Identifier.of(itemId));
                            return item == null ? 0 : GatherHud.countInventoryTagAware(client, item);
                        });
                        GatherClientNetworking.requestAutoTrack(GatherSettings.get().chestScanRadius, targets);
                    }
                }
                // Staggered lazy fallback: spread chest refreshes evenly over chestFallbackRefreshSeconds
                List<Long> allChestList = new java.util.ArrayList<>(state.getTrackedChests());
                if (!GatherSettings.get().countChests) allChestList.addAll(state.getManualChests());
                if (!allChestList.isEmpty()) {
                    int total = allChestList.size();
                    if (fallbackRefreshIndex >= total) fallbackRefreshIndex = 0;
                    int refreshSec = GatherSettings.get().chestFallbackRefreshSeconds;
                    int cyclesPerFullRefresh = Math.max(1, refreshSec / 2);
                    int batchSize = Math.max(1, (total + cyclesPerFullRefresh - 1) / cyclesPerFullRefresh);
                    Set<Long> batch = new HashSet<>();
                    for (int i = 0; i < batchSize; i++) {
                        long pos = allChestList.get(fallbackRefreshIndex);
                        fallbackRefreshIndex = (fallbackRefreshIndex + 1) % total;
                        BlockPos bp = BlockPos.fromLong(pos);
                        if (client.world != null && client.world.isChunkLoaded(bp.getX() >> 4, bp.getZ() >> 4)) {
                            batch.add(pos);
                        }
                    }
                    if (!batch.isEmpty()) GatherClientNetworking.requestTrackedChests(batch);
                } else {
                    fallbackRefreshIndex = 0;
                }
            }

            if (++autoSyncTimer < 20) return;
            autoSyncTimer = 0;

            java.util.function.Function<String, Integer> totalCounter = itemId -> {
                Item it = ITEM_LOOKUP_CACHE.computeIfAbsent(itemId, k -> Registries.ITEM.get(Identifier.of(k)));
                int count = it == null ? 0 : GatherHud.countInventoryTagAware(client, it);
                if (GatherSettings.get().countChests) {
                    count += GatherState.get().getTrackedChestCountMatching(itemId);
                } else {
                    count += GatherState.get().getManualChestCountMatching(itemId);
                }
                return count;
            };
            GatherState.get().autoSync(totalCounter);
            GatherState.get().updateEffectivelyNeeded(totalCounter);
            GatherHud.markDirty();
        });
    }

    public static boolean scanModifiersHeld(GatherSettings cfg, long handle) {
        if (cfg.scanToggleShift && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) != GLFW.GLFW_PRESS
                && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) != GLFW.GLFW_PRESS) return false;
        if (cfg.scanToggleCtrl && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) != GLFW.GLFW_PRESS
                && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) != GLFW.GLFW_PRESS) return false;
        if (cfg.scanToggleAlt && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) != GLFW.GLFW_PRESS
                && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) != GLFW.GLFW_PRESS) return false;
        return true;
    }

    private static boolean exactMenuManualConflict(GatherSettings cfg) {
        if (cfg.scanToggleShift || cfg.scanToggleCtrl || cfg.scanToggleAlt) return false;
        return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.openMenu)
                .equals(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(GatherKeyBindings.manualScanToggle));
    }

    private static long chunkKey(BlockPos p) {
        return ((long)(p.getX() >> 4) << 32) | ((p.getZ() >> 4) & 0xFFFFFFFFL);
    }

    private static void checkForUpdate() {
        if (!GatherSettings.get().updateNotifications || GatherSettings.get().suppressUpdateNotif) return;
        Thread t = new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                        java.net.URI.create(UPDATE_CHECK_URL).toURL().openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestProperty("User-Agent", "Gather/Minecraft");
                if (con.getResponseCode() != 200) return;
                com.google.gson.JsonObject obj;
                try (java.io.InputStreamReader r = new java.io.InputStreamReader(con.getInputStream())) {
                    obj = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
                }
                com.google.gson.JsonObject promos = obj.getAsJsonObject("promos");
                if (promos == null) return;
                com.google.gson.JsonElement latestEl = promos.get(MC_CHANNEL + "-latest");
                if (latestEl == null) return;
                String latest = latestEl.getAsString();
                String current = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("gather")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("0.0.0");
                if (isNewer(latest, comparableModVersion(current))) {
                    updateAvailableVersion = latest;
                    updateNotifStartMs = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }, "gather-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isNewer(String a, String b) {
        int[] pa = parseVer(a), pb = parseVer(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    public static String currentModVersion() {
        String current = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("gather")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
        return comparableModVersion(current);
    }

    private static String comparableModVersion(String version) {
        int plus = version.indexOf('+');
        if (plus >= 0 && plus + 1 < version.length()) {
            return version.substring(plus + 1);
        }
        return version;
    }

    private static int[] parseVer(String v) {
        String[] parts = v.split("[.\\-]");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return nums;
    }

}
