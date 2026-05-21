package com.gather.client.compat.jei;

import com.gather.GatherMod;
import com.gather.client.GatherJeiAddOverlay;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

@JeiPlugin
public class GatherJeiPlugin implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(GatherMod.MOD_ID, "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    public static boolean addHoveredItemToGoals() {
        IJeiRuntime jeiRuntime = runtime;
        if (jeiRuntime == null) return false;
        Optional<ITypedIngredient<?>> typed = jeiRuntime.getIngredientListOverlay().getIngredientUnderMouse();
        if (typed.isEmpty()) typed = jeiRuntime.getBookmarkOverlay().getIngredientUnderMouse();
        Optional<ItemStack> stack = typed.flatMap(ITypedIngredient::getItemStack);
        if (stack.isEmpty() || stack.get().isEmpty()) return false;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.get().getItem()).toString();
        return GatherJeiAddOverlay.open(itemId, Math.max(1, stack.get().getCount()));
    }
}
