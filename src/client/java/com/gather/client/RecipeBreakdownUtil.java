package com.gather.client;

import com.gather.network.BreakdownRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-side facade — delegates to server since RecipeManager is server-only in 1.21.11.
 * Results arrive via BreakdownResultPayload and are stored in GatherState.breakdownCache.
 */
public class RecipeBreakdownUtil {

    public static void requestBreakdown(String itemId, int count, int depth) {
        ClientPlayNetworking.send(new BreakdownRequestPayload(itemId, count, depth));
    }
}
