package com.gather.client.mixin;

import com.gather.client.GatherClientNetworking;
import com.gather.client.GatherSettings;
import com.gather.client.GatherState;
import com.gather.client.WorldHighlightRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class UseBlockMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void gather_interceptChestScan(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        if (!GatherSettings.get().enabled) return;
        GatherState state = GatherState.get();
        GatherSettings settings = GatherSettings.get();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos pos = hitResult.getBlockPos();
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof Container)) {
            watchLikelyContainerPlacement(player, hand, hitResult, state, settings);
            return;
        }

        boolean manualTagClick = !settings.countChests && state.isChestScanMode();
        BlockPos rootPos = canonicalChestPos(mc.level, pos);
        BlockPos partnerPos = doubleChestPartner(mc.level, pos);
        WorldHighlightRenderer.evictChestXrayCache(rootPos.asLong());
        if (partnerPos != null) WorldHighlightRenderer.evictChestXrayCache(partnerPos.asLong());
        if (mc.level.getBlockState(rootPos).getBlock() instanceof ShulkerBoxBlock) {
            WorldHighlightRenderer.watchShulkerAnimation(rootPos.asLong());
        }
        if (player.isShiftKeyDown()) {
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
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    private static void watchLikelyContainerPlacement(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                                       GatherState state, GatherSettings settings) {
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof BlockItem blockItem)) return;
        if (!(blockItem.getBlock() instanceof ChestBlock || blockItem.getBlock() instanceof ShulkerBoxBlock)) return;

        BlockPos placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
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

    private static BlockPos canonicalChestPos(ClientLevel world, BlockPos pos) {
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return pos;
        return partner.asLong() < pos.asLong() ? partner : pos;
    }

    private static BlockPos doubleChestPartner(ClientLevel world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) return null;
        ChestType chestType = blockState.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) return null;
        Direction facing = blockState.getValue(ChestBlock.FACING);
        Direction neighborDir = (chestType == ChestType.LEFT)
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockPos neighborPos = pos.relative(neighborDir);
        return world.getBlockState(neighborPos).getBlock() instanceof ChestBlock ? neighborPos : null;
    }
}
