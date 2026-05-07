package com.gather.client.mixin;

import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class UseBlockMixin {

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void gather_interceptChestScan(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<ActionResult> cir) {
        if (hand != Hand.MAIN_HAND) return;
        if (!GatherSettings.get().enabled) return;
        GatherState state = GatherState.get();
        GatherSettings settings = GatherSettings.get();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        BlockPos pos = hitResult.getBlockPos();
        BlockEntity be = mc.world.getBlockEntity(pos);
        if (!(be instanceof Inventory)) {
            watchLikelyContainerPlacement(player, hand, hitResult, state, settings);
            return;
        }

        boolean manualTagClick = !settings.countChests && state.isChestScanMode();
        BlockPos rootPos = canonicalChestPos(mc.world, pos);
        BlockPos partnerPos = doubleChestPartner(mc.world, pos);
        WorldHighlightRenderer.evictChestXrayCache(rootPos.asLong());
        if (partnerPos != null) WorldHighlightRenderer.evictChestXrayCache(partnerPos.asLong());
        if (mc.world.getBlockState(rootPos).getBlock() instanceof ShulkerBoxBlock) {
            WorldHighlightRenderer.watchShulkerAnimation(rootPos.asLong());
        }
        if (player.isSneaking()) {
            watchLikelyContainerPlacement(player, hand, hitResult, state, settings);
        }

        if (!manualTagClick) {
            clearFinderIfOpening(state, rootPos, partnerPos);
            return;
        }

        if (state.isManualChest(rootPos) || (partnerPos != null && state.isManualChest(partnerPos))) {
            state.removeManualChest(rootPos.asLong());
            if (partnerPos != null) state.removeManualChest(partnerPos.asLong());
        } else {
            state.toggleManualChest(rootPos);
            // Fetch contents immediately rather than waiting for the 40-tick timer.
            GatherClientNetworking.requestTrackedChests(java.util.Set.of(rootPos.asLong()));
        }
        cir.setReturnValue(ActionResult.SUCCESS);
    }

    private static void watchLikelyContainerPlacement(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                                       GatherState state, GatherSettings settings) {
        ItemStack held = player.getStackInHand(hand);
        if (!(held.getItem() instanceof BlockItem blockItem)) return;
        if (!(blockItem.getBlock() instanceof ChestBlock || blockItem.getBlock() instanceof ShulkerBoxBlock)) return;

        BlockPos placePos = hitResult.getBlockPos().offset(hitResult.getSide());
        long encoded = placePos.asLong();
        WorldHighlightRenderer.watchNewContainerPlacement(encoded);

        if (settings.countChests) {
            state.addTrackedChests(java.util.List.of(encoded));
            GatherClientNetworking.requestTrackedChests(java.util.Set.of(encoded));
            WorldHighlightRenderer.markChestSetDirty();
        }
    }

    private static void clearFinderIfOpening(GatherState state, BlockPos rootPos, BlockPos partnerPos) {
        String finderItemId = state.getChestFinderItemId();
        if (finderItemId == null) return;
        java.util.Set<Long> matching = state.getChestsContaining(finderItemId);
        if (matching.contains(rootPos.asLong()) || (partnerPos != null && matching.contains(partnerPos.asLong()))) {
            state.setChestFinderItemId(null);
        }
    }

    private static BlockPos canonicalChestPos(ClientWorld world, BlockPos pos) {
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return pos;
        return partner.asLong() < pos.asLong() ? partner : pos;
    }

    private static BlockPos doubleChestPartner(ClientWorld world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) return null;
        ChestType chestType = blockState.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) return null;
        Direction facing = blockState.get(ChestBlock.FACING);
        Direction neighborDir = (chestType == ChestType.LEFT)
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        BlockPos neighborPos = pos.offset(neighborDir);
        return world.getBlockState(neighborPos).getBlock() instanceof ChestBlock ? neighborPos : null;
    }
}
