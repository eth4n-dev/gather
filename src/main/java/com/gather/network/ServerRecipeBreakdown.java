package com.gather.network;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class ServerRecipeBreakdown {

    private static final Set<String> SMELTED_METAL_LEAVES = Set.of(
            // smelted from ore — stop here, outline system maps back to ore blocks
            "minecraft:iron_ingot",
            "minecraft:gold_ingot",
            "minecraft:copper_ingot",
            "minecraft:netherite_scrap",
            // direct ore drops — smelting recipes exist but mine → drop is the real source
            "minecraft:redstone",
            "minecraft:lapis_lazuli",
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:coal",
            "minecraft:quartz",
            "minecraft:glowstone_dust",
            // cooked/reversible stone products should stay as gatherable leaves
            "minecraft:stone",
            "minecraft:smooth_stone"
    );

    public static Map<String, Integer> breakdown(String itemId, int count, int depth, ServerLevel world) {
        Map<String, Integer> result = new LinkedHashMap<>();
        recurse(itemId, count, depth, result, new HashSet<>(), world);
        return result;
    }

    public static boolean isInventoryCraftable(String itemId, ServerLevel world) {
        RecipeManager rm = world.getServer().getRecipeManager();
        RecipeHolder<?> recipe = findRecipe(itemId, rm, world);
        if (recipe == null) return false;
        Recipe<?> r = recipe.value();
        if (r instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
        }
        return r.placementInfo().ingredients().size() <= 4;
    }

    private static void recurse(String itemId, int count, int depth,
                                 Map<String, Integer> result, Set<String> visiting, ServerLevel world) {
        if (depth <= 0 || visiting.contains(itemId) || isGatherLeaf(itemId)) {
            result.merge(itemId, count, Integer::sum);
            return;
        }

        RecipeManager rm = world.getServer().getRecipeManager();
        RecipeHolder<?> recipe = findRecipe(itemId, rm, world);
        if (recipe == null) {
            result.merge(itemId, count, Integer::sum);
            return;
        }

        int outputCount = getOutputCount(recipe, world);
        int batches = (int) Math.ceil((double) count / outputCount);
        Map<String, Integer> ingredients = getIngredients(recipe);

        if (ingredients.isEmpty()) {
            result.merge(itemId, count, Integer::sum);
            return;
        }

        visiting.add(itemId);
        for (Map.Entry<String, Integer> ing : ingredients.entrySet()) {
            recurse(ing.getKey(), ing.getValue() * batches, depth - 1, result, visiting, world);
        }
        visiting.remove(itemId);
    }

    private static RecipeHolder<?> findRecipe(String itemId, RecipeManager rm, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> entry : rm.getRecipes()) {
            List<RecipeDisplay> displays = entry.value().display();
            if (displays.isEmpty()) continue;
            net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
            if (out != null && !out.isEmpty()) {
                String outId = BuiltInRegistries.ITEM.getKey(out.getItem()).toString();
                if (!outId.equals(itemId)) continue;
                if (!isReversibleStorageRecipe(itemId, entry, rm, world)) return entry;
            }
        }
        return null;
    }

    private static int getOutputCount(RecipeHolder<?> entry, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        List<RecipeDisplay> displays = entry.value().display();
        if (displays.isEmpty()) return 1;
        net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
        return (out != null && !out.isEmpty()) ? out.getCount() : 1;
    }

    private static Map<String, Integer> getIngredients(RecipeHolder<?> entry) {
        Map<String, Integer> map = new LinkedHashMap<>();
        List<Ingredient> ingredients = entry.value().placementInfo().ingredients();
        for (Ingredient ing : ingredients) {
            Optional<Holder<Item>> first = ing.items().findFirst();
            first.ifPresent(re -> {
                String id = BuiltInRegistries.ITEM.getKey(re.value()).toString();
                map.merge(id, 1, Integer::sum);
            });
        }
        return map;
    }

    private static boolean isGatherLeaf(String itemId) {
        return SMELTED_METAL_LEAVES.contains(itemId);
    }

    private static boolean isReversibleStorageRecipe(String itemId, RecipeHolder<?> entry, RecipeManager rm, ServerLevel world) {
        int outputCount = getOutputCount(entry, world);
        if (outputCount <= 1) return false;

        Map<String, Integer> ingredients = getIngredients(entry);
        if (ingredients.size() != 1) return false;

        Map.Entry<String, Integer> onlyIngredient = ingredients.entrySet().iterator().next();
        if (onlyIngredient.getValue() != 1) return false;

        RecipeHolder<?> reverse = findDirectPackingRecipe(onlyIngredient.getKey(), itemId, outputCount, rm, world);
        return reverse != null;
    }

    private static RecipeHolder<?> findDirectPackingRecipe(
            String outputItemId,
            String requiredIngredientId,
            int requiredIngredientCount,
            RecipeManager rm,
            ServerLevel world
    ) {
        var context = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> entry : rm.getRecipes()) {
            List<RecipeDisplay> displays = entry.value().display();
            if (displays.isEmpty()) continue;
            net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
            if (out == null || out.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(out.getItem()).toString().equals(outputItemId)) continue;

            Map<String, Integer> ingredients = getIngredients(entry);
            if (ingredients.size() != 1) continue;
            Integer count = ingredients.get(requiredIngredientId);
            if (count != null && count == requiredIngredientCount) return entry;
        }
        return null;
    }
}
