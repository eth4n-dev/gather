package com.gather.client;

import com.gather.client.screen.GatherHelpScreen;
import com.gather.client.screen.GatherMenuScreen;
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

    private int autoSyncTimer     = 0;
    private int chestRefreshTimer = 0;
    private boolean pendingHelpScreen = false;

    @Override
    public void onInitializeClient() {
        GatherKeyBindings.register();
        GatherClientNetworking.register();
        GatherHud.register();
        WorldHighlightRenderer.register();
        GatherCraftingOverlay.register();
        GatherShulkerCollectorOverlay.register();

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
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GatherState.unload();
            pendingHelpScreen = false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingHelpScreen && client.player != null && client.currentScreen == null) {
                pendingHelpScreen = false;
                client.setScreen(new GatherHelpScreen(null, true));
            }

            while (GatherKeyBindings.openMenu.wasPressed()) {
                if (client.player == null) return;
                long handle = client.getWindow().getHandle();
                boolean shiftDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                                 || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                if (shiftDown && client.currentScreen == null) {
                    if (!GatherSettings.get().enabled) return;
                    if (GatherSettings.get().countChests) return;
                    // Shift+G: toggle chest scan mode
                    GatherState s = GatherState.get();
                    s.setChestScanMode(!s.isChestScanMode());
                } else {
                    client.setScreen(new GatherMenuScreen());
                }
            }

            if (client.player == null) return;
            if (!GatherSettings.get().enabled) return;

            GatherState state = GatherState.get();
            if (++chestRefreshTimer >= 40) {
                chestRefreshTimer = 0;
                List<String> collectorTargets = state.getChestScanTargets(itemId -> 0);
                GatherClientNetworking.updateCollectorTargets(collectorTargets);
                if (GatherSettings.get().countChests) {
                    List<String> targets = state.getChestScanTargets(itemId -> {
                        Item item = Registries.ITEM.get(Identifier.of(itemId));
                        return item == null ? 0 : GatherHud.countInventoryTagAware(client, item);
                    });
                    GatherClientNetworking.requestAutoTrack(GatherSettings.get().chestScanRadius, targets);
                }
                // Refresh auto chests when Scan All is on; manual chests only count when Scan All is off.
                Set<Long> allChests = new HashSet<>(state.getTrackedChests());
                if (!GatherSettings.get().countChests) {
                    allChests.addAll(state.getManualChests());
                }
                Set<Long> nearbyLoadedChests = filterNearbyLoadedChests(client, allChests);
                if (!nearbyLoadedChests.isEmpty()) {
                    GatherClientNetworking.requestTrackedChests(nearbyLoadedChests);
                }
            }

            if (++autoSyncTimer < 20) return;
            autoSyncTimer = 0;

            java.util.function.Function<String, Integer> totalCounter = itemId -> {
                Item it = Registries.ITEM.get(Identifier.of(itemId));
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

    private static Set<Long> filterNearbyLoadedChests(net.minecraft.client.MinecraftClient client, Set<Long> chests) {
        if (client.player == null || client.world == null || chests.isEmpty()) return Set.of();
        BlockPos playerPos = client.player.getBlockPos();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;
        Set<Long> result = new HashSet<>();
        for (long encoded : chests) {
            BlockPos pos = BlockPos.fromLong(encoded);
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            if (Math.abs(chunkX - playerChunkX) > 1 || Math.abs(chunkZ - playerChunkZ) > 1) continue;
            if (!client.world.isChunkLoaded(chunkX, chunkZ)) continue;
            result.add(encoded);
        }
        return result;
    }
}
