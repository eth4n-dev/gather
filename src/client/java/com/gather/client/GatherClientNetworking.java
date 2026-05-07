package com.gather.client;

import com.gather.network.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.HashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class GatherClientNetworking {

    // keyed by itemId so concurrent requests for different items don't clobber each other
    private static final Map<String, Consumer<BreakdownResultPayload>> pendingCallbacks = new HashMap<>();

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ChestScanResultPayload.ID, (payload, context) -> {
            if (!GatherSettings.get().enabled) return;
            context.client().execute(() -> GatherState.get().updateChestCounts(payload.itemCounts()));
        });

        ClientPlayNetworking.registerGlobalReceiver(TrackedChestResultPayload.ID, (payload, context) -> {
            if (!GatherSettings.get().enabled) return;
            context.client().execute(() -> GatherState.get().mergeTrackedChestContents(payload.chestItemCounts()));
        });

        ClientPlayNetworking.registerGlobalReceiver(AutoTrackResultPayload.ID, (payload, context) -> {
            if (!GatherSettings.get().enabled) return;
            context.client().execute(() -> {
                GatherState state = GatherState.get();
                if (context.client().world != null && context.client().player != null) {
                    state.mergeAutoTrackedChests(payload.positions(), context.client().world,
                            context.client().player.getBlockPos(), GatherSettings.get().chestScanRadius);
                } else {
                    state.addTrackedChests(payload.positions());
                }
                // Only request contents for chests we haven't fetched yet (Fix 2)
                Set<Long> newChests = state.getTrackedChestsWithoutContents();
                if (!newChests.isEmpty()) requestTrackedChests(newChests);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ChestDirtyPayload.ID, (payload, context) -> {
            if (!GatherSettings.get().enabled) return;
            context.client().execute(() -> {
                GatherState state = GatherState.get();
                long pos = payload.posLong();
                WorldHighlightRenderer.evictChestXrayCache(pos);
                if (state.getTrackedChests().contains(pos) || state.getManualChests().contains(pos)) {
                    requestTrackedChests(new HashSet<>(java.util.List.of(pos)));
                    WorldHighlightRenderer.markChestSetDirty();
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BreakdownResultPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                GatherState.get().cacheBreakdown(payload.originItemId(), payload.ingredients(), payload.inventoryCraftable());
                Consumer<BreakdownResultPayload> cb = pendingCallbacks.remove(payload.originItemId());
                if (cb != null) cb.accept(payload);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(CollectorStatePayload.ID, (payload, context) -> {
            if (!GatherSettings.get().enabled) return;
            context.client().execute(() -> GatherShulkerCollectorOverlay.applyServerState(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(com.gather.network.PlacedCollectorPositionsPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                GatherState state = GatherState.get();
                Set<Long> missingContents = new HashSet<>();
                for (com.gather.network.PlacedCollectorPositionsPayload.Entry entry : payload.entries()) {
                    if (!state.hasCollectorChestContents(entry.pos())) missingContents.add(entry.pos());
                }
                state.setCollectorPositions(payload.entries());
                WorldHighlightRenderer.invalidateCollectorCache();
                requestTrackedChests(missingContents);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(com.gather.network.XrayPermissionPayload.ID, (payload, context) -> {
            context.client().execute(() -> GatherState.setServerXrayAllowed(payload.allowed()));
        });

    }

    public static void addBreakdownCallback(String itemId, Consumer<BreakdownResultPayload> cb) {
        pendingCallbacks.put(itemId, cb);
    }

    public static void requestChestScan() {
        if (!GatherSettings.get().enabled) return;
        int radius = GatherSettings.get().chestScanRadius;
        ClientPlayNetworking.send(new ChestScanRequestPayload(radius));
    }

    public static void requestAutoTrack(int radius, List<String> neededItemIds) {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new AutoTrackRequestPayload(radius, neededItemIds));
    }

    public static void requestTrackedChests(Set<Long> positions) {
        if (!GatherSettings.get().enabled) return;
        if (positions.isEmpty()) return;
        ClientPlayNetworking.send(new TrackedChestQueryPayload(new ArrayList<>(positions)));
    }

    public static void forceMarkNearby() {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new com.gather.network.ForceMarkNearbyPayload());
    }

    public static void requestBreakdown(String itemId, int count, int depth) {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new BreakdownRequestPayload(itemId, count, depth));
    }

    public static void sendAutoCraft(String outputItemId, int outputCount, List<AutoCraftPayload.IngredientEntry> consume) {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new AutoCraftPayload(outputItemId, outputCount, consume));
    }

    public static void configureCollector(boolean enabled, boolean allMode, List<String> selectedItemIds, boolean leaveOne) {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new ToggleCollectorPayload(enabled, allMode, selectedItemIds, leaveOne));
    }

    public static void requestCollectorState() {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new CollectorStateRequestPayload());
    }

    public static void updateCollectorTargets(List<String> neededItemIds) {
        if (!GatherSettings.get().enabled) return;
        ClientPlayNetworking.send(new CollectorTargetsPayload(neededItemIds));
    }
}
