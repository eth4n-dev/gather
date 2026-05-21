package com.gather.network;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.stream.Collectors;

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
            "minecraft:smooth_stone",
            // natural wool source — colored wool breaks down to white_wool + dye, not further
            "minecraft:white_wool"
    );

    public static List<BreakdownEntry> breakdown(String itemId, int count, int depth, ServerLevel world) {
        List<BreakdownEntry> result = new ArrayList<>();
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
                                 List<BreakdownEntry> result, Set<String> visiting, ServerLevel world) {
        if (depth <= 0 || visiting.contains(itemId) || isGatherLeaf(itemId)) {
            mergeInto(result, new BreakdownEntry(List.of(itemId), count));
            return;
        }

        RecipeManager rm = world.getServer().getRecipeManager();

        // Check for multiple separate recipes producing the same item (e.g. red_dye from poppy/tulip/beetroot).
        // If each recipe has exactly one pure-gatherable 1:1 ingredient, expose them all as alternatives.
        List<String> multiSourceAlts = findGatherableSourceAlternatives(itemId, rm, world, visiting);
        if (multiSourceAlts != null) {
            mergeInto(result, new BreakdownEntry(multiSourceAlts, count));
            return;
        }

        RecipeHolder<?> recipe = findRecipe(itemId, rm, world);
        if (recipe == null) {
            mergeInto(result, new BreakdownEntry(List.of(itemId), count));
            return;
        }

        int outputCount = getOutputCount(recipe, world);
        int batches = (int) Math.ceil((double) count / outputCount);
        List<BreakdownEntry> ingredients = getIngredients(recipe, visiting, rm, world);

        if (ingredients.isEmpty()) {
            mergeInto(result, new BreakdownEntry(List.of(itemId), count));
            return;
        }

        visiting.add(itemId);
        for (BreakdownEntry ing : ingredients) {
            if (!ing.isSingle()) {
                // Alternatives group — all pure gatherables, add directly without recursing
                mergeInto(result, new BreakdownEntry(ing.itemIds(), ing.count() * batches));
            } else {
                recurse(ing.primary(), ing.count() * batches, depth - 1, result, visiting, world);
            }
        }
        visiting.remove(itemId);
    }

    private static void mergeInto(List<BreakdownEntry> result, BreakdownEntry entry) {
        String key = entryKey(entry);
        for (int i = 0; i < result.size(); i++) {
            if (entryKey(result.get(i)).equals(key)) {
                result.set(i, new BreakdownEntry(result.get(i).itemIds(), result.get(i).count() + entry.count()));
                return;
            }
        }
        result.add(entry);
    }

    private static String entryKey(BreakdownEntry e) {
        return e.isSingle() ? e.primary()
                : e.itemIds().stream().sorted().collect(Collectors.joining("\0"));
    }

    private static RecipeHolder<?> findRecipe(String itemId, RecipeManager rm, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> entry : rm.getRecipes()) {
            if (entry.value() instanceof SmithingRecipe) continue;
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

    /**
     * Returns a list of gatherable ingredient IDs when multiple separate 1:1 recipes all produce
     * itemId and every recipe's sole ingredient is a pure gatherable (no crafting recipe of its own).
     * Returns null if the normal single-recipe path should be used instead.
     */
    private static List<String> findGatherableSourceAlternatives(String itemId, RecipeManager rm,
                                                                   ServerLevel world, Set<String> visiting) {
        var context = SlotDisplayContext.fromLevel(world);
        List<String> sources = new ArrayList<>();
        int recipesFound = 0;

        for (RecipeHolder<?> entry : rm.getRecipes()) {
            if (entry.value() instanceof SmithingRecipe) continue;
            List<RecipeDisplay> displays = entry.value().display();
            if (displays.isEmpty()) continue;
            net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
            if (out == null || out.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(out.getItem()).toString().equals(itemId)) continue;
            if (isReversibleStorageRecipe(itemId, entry, rm, world)) continue;
            if (out.getCount() != 1) continue; // skip multi-output recipes (count mismatch with alternatives)

            recipesFound++;
            List<BreakdownEntry> ings = getIngredients(entry, visiting, rm, world);
            if (ings.size() != 1 || !ings.get(0).isSingle()) continue;
            String ingId = ings.get(0).primary();
            if (visiting.contains(ingId)) continue;
            if (!isGatherLeaf(ingId) && hasAnyRecipe(ingId, rm, world)) continue;
            if (!sources.contains(ingId)) sources.add(ingId);
        }

        if (recipesFound <= 1 || sources.size() < 2) return null;
        return sources;
    }

    private static boolean hasAnyRecipe(String itemId, RecipeManager rm, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> entry : rm.getRecipes()) {
            if (entry.value() instanceof SmithingRecipe) continue;
            List<RecipeDisplay> displays = entry.value().display();
            if (displays.isEmpty()) continue;
            net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
            if (out != null && !out.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(out.getItem()).toString().equals(itemId)) return true;
        }
        return false;
    }

    private static int getOutputCount(RecipeHolder<?> entry, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        List<RecipeDisplay> displays = entry.value().display();
        if (displays.isEmpty()) return 1;
        net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
        return (out != null && !out.isEmpty()) ? out.getCount() : 1;
    }

    private static List<BreakdownEntry> getIngredients(RecipeHolder<?> entry, Set<String> visiting,
                                                        RecipeManager rm, ServerLevel world) {
        List<BreakdownEntry> result = new ArrayList<>();
        List<Ingredient> ingredients = entry.value().placementInfo().ingredients();
        for (Ingredient ing : ingredients) {
            List<String> allIds = ing.items()
                    .map(re -> BuiltInRegistries.ITEM.getKey(re.value()).toString())
                    .distinct()
                    .collect(Collectors.toList());

            if (allIds.isEmpty()) continue;

            if (allIds.size() == 1) {
                mergeInto(result, new BreakdownEntry(allIds, 1));
                continue;
            }

            // Prefer explicit leaf items first (e.g. white_wool in the wool tag)
            List<String> leafIds = allIds.stream().filter(SMELTED_METAL_LEAVES::contains).toList();
            if (!leafIds.isEmpty()) {
                mergeInto(result, new BreakdownEntry(List.of(leafIds.get(0)), 1));
                continue;
            }

            // Collect pure gatherables (no crafting recipe, not currently on the DFS path)
            List<String> gatherables = allIds.stream()
                    .filter(id -> !visiting.contains(id))
                    .filter(id -> !hasAnyRecipe(id, rm, world))
                    .collect(Collectors.toList());

            if (gatherables.size() >= 2) {
                // Multiple gatherables — expose as alternatives group
                mergeInto(result, new BreakdownEntry(gatherables, 1));
            } else if (gatherables.size() == 1) {
                mergeInto(result, new BreakdownEntry(gatherables, 1));
            } else {
                // Fallback: prefer non-visiting item to avoid cycles, then first available
                String chosen = allIds.stream()
                        .filter(id -> !visiting.contains(id))
                        .findFirst()
                        .orElse(allIds.get(0));
                mergeInto(result, new BreakdownEntry(List.of(chosen), 1));
            }
        }
        return result;
    }

    private static boolean isGatherLeaf(String itemId) {
        return SMELTED_METAL_LEAVES.contains(itemId);
    }

    private static boolean isReversibleStorageRecipe(String itemId, RecipeHolder<?> entry,
                                                      RecipeManager rm, ServerLevel world) {
        int outputCount = getOutputCount(entry, world);
        if (outputCount <= 1) return false;

        List<BreakdownEntry> ingEntries = getIngredients(entry, Set.of(), rm, world);
        if (ingEntries.size() != 1) return false;

        BreakdownEntry onlyIng = ingEntries.get(0);
        if (onlyIng.count() != 1) return false;

        RecipeHolder<?> reverse = findDirectPackingRecipe(onlyIng.primary(), itemId, outputCount, rm, world);
        return reverse != null;
    }

    private static RecipeHolder<?> findDirectPackingRecipe(String outputItemId, String requiredIngredientId,
                                                            int requiredIngredientCount,
                                                            RecipeManager rm, ServerLevel world) {
        var context = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> entry : rm.getRecipes()) {
            if (entry.value() instanceof SmithingRecipe) continue;
            List<RecipeDisplay> displays = entry.value().display();
            if (displays.isEmpty()) continue;
            net.minecraft.world.item.ItemStack out = displays.get(0).result().resolveForFirstStack(context);
            if (out == null || out.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(out.getItem()).toString().equals(outputItemId)) continue;

            List<BreakdownEntry> ingEntries = getIngredients(entry, Set.of(), rm, world);
            if (ingEntries.size() != 1) continue;
            BreakdownEntry onlyIng = ingEntries.get(0);
            if (onlyIng.primary().equals(requiredIngredientId) && onlyIng.count() == requiredIngredientCount)
                return entry;
        }
        return null;
    }
}
