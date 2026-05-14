package com.gather.client;

import com.gather.client.screen.GatherHelpScreen;
import com.gather.client.screen.GatherMenuScreen;
import com.gather.client.screen.GatherTutorialScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GatherClientMod implements ClientModInitializer {

    private static final java.util.Map<String, Item> ITEM_LOOKUP_CACHE = new java.util.HashMap<>();

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
        GatherTradeCalculatorOverlay.register();
        GatherShulkerCollectorOverlay.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("gather")
                .then(ClientCommands.literal("help")
                    .executes(ctx -> {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        mc.execute(() -> mc.setScreen(new GatherHelpScreen(null, false)));
                        return 1;
                    }))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GatherState.loadForWorld(client);
            if (GatherSettings.get().enabled && !GatherSettings.get().hasShownWelcome) pendingHelpScreen = true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GatherState.unload();
            GatherHud.reset();
            pendingHelpScreen = false;
            lastCollectorTargets = new java.util.ArrayList<>();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            GatherState.flushPendingSaveIfDue();

            if (pendingHelpScreen && client.player != null && client.screen == null) {
                pendingHelpScreen = false;
                client.setScreen(new GatherTutorialScreen());
            }

            while (GatherKeyBindings.openMenu.consumeClick()) {
                if (client.player == null) return;
                if (client.screen == null) {
                    client.setScreen(new GatherMenuScreen());
                }
            }

            while (GatherKeyBindings.manualScanToggle.consumeClick()) {
                if (client.player == null) return;
                GatherSettings cfg = GatherSettings.get();
                long handle = client.getWindow().handle();
                if (scanModifiersHeld(cfg, handle) && client.screen == null && cfg.enabled && !cfg.countChests) {
                    GatherState s = GatherState.get();
                    s.setChestScanMode(!s.isChestScanMode());
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
                    long ck = chunkKey(client.player.blockPosition());
                    autoTrackFallbackCount++;
                    if (ck != lastAutoTrackChunkKey || autoTrackFallbackCount >= 15) {
                        lastAutoTrackChunkKey = ck;
                        autoTrackFallbackCount = 0;
                        List<String> targets = state.getChestScanTargets(itemId -> {
                            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
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
                        BlockPos bp = BlockPos.of(pos);
                        if (client.level != null && client.level.getChunkSource().hasChunk(bp.getX() >> 4, bp.getZ() >> 4)) {
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
                Item it = ITEM_LOOKUP_CACHE.computeIfAbsent(itemId, k -> BuiltInRegistries.ITEM.getValue(Identifier.parse(k)));
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

    private static long chunkKey(BlockPos p) {
        return ((long)(p.getX() >> 4) << 32) | ((p.getZ() >> 4) & 0xFFFFFFFFL);
    }

}
