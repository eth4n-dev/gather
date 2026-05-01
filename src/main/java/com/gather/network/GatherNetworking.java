package com.gather.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gather.mixin.ShulkerBoxScreenHandlerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.ItemEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

public class GatherNetworking {

    private static final Map<UUID, Set<String>> COLLECTOR_TARGETS = new HashMap<>();
    private static final Map<UUID, CollectorPlacement> PENDING_COLLECTOR_PLACEMENTS = new HashMap<>();
    private static final Map<Long, CollectorPlacement> PLACED_COLLECTOR_CONFIGS = new HashMap<>();
    private static final Map<UUID, PendingBreak> PENDING_COLLECTOR_BREAKS = new HashMap<>();
    private static int collectorTick = 0;

    private record CollectorPlacement(BlockPos pos, boolean allMode, List<String> items, boolean leaveOne) {}
    private record PendingBreak(BlockPos pos, boolean allMode, List<String> items, boolean leaveOne, int ticksLeft) {}
    private static final String COLLECTOR_ENABLED_KEY = "gather_collector";
    private static final String COLLECTOR_MODE_KEY = "gather_collector_mode";
    private static final String COLLECTOR_ITEMS_KEY = "gather_collector_items";
    private static final String COLLECTOR_ID_KEY = "gather_collector_id";
    private static final String COLLECTOR_LEAVE_ONE_KEY = "gather_collector_leave_one";
    private static final String COLLECTOR_MODE_ALL = "all";
    private static final String COLLECTOR_MODE_CERTAIN = "certain";
    private static final String[] WOOD_PREFIXES = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "bamboo", "crimson", "warped"
    };

    public static void registerServerSide() {
        PayloadTypeRegistry.playC2S().register(ForceMarkNearbyPayload.ID, ForceMarkNearbyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ToggleCollectorPayload.ID, ToggleCollectorPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CollectorStateRequestPayload.ID, CollectorStateRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CollectorTargetsPayload.ID, CollectorTargetsPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChestScanRequestPayload.ID, ChestScanRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AutoCraftPayload.ID, AutoCraftPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BreakdownRequestPayload.ID, BreakdownRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TrackedChestQueryPayload.ID, TrackedChestQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AutoTrackRequestPayload.ID, AutoTrackRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChestScanResultPayload.ID, ChestScanResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CollectorStatePayload.ID, CollectorStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BreakdownResultPayload.ID, BreakdownResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TrackedChestResultPayload.ID, TrackedChestResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AutoTrackResultPayload.ID, AutoTrackResultPayload.CODEC);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Apply collector config to shulkers that were just placed this tick
            if (!PENDING_COLLECTOR_PLACEMENTS.isEmpty()) {
                var it = PENDING_COLLECTOR_PLACEMENTS.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    it.remove();
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
                    if (p == null) continue;
                    BlockEntity be = ((ServerWorld) p.getEntityWorld()).getBlockEntity(entry.getValue().pos());
                    if (be instanceof ShulkerBoxBlockEntity shulker) {
                        writeCollectorToBlockEntity(shulker, true, entry.getValue().allMode(), entry.getValue().items(), entry.getValue().leaveOne());
                    }
                }
            }

            // Retry writing collector config to dropped shulker item entities
            if (!PENDING_COLLECTOR_BREAKS.isEmpty()) {
                var brit = PENDING_COLLECTOR_BREAKS.entrySet().iterator();
                while (brit.hasNext()) {
                    var entry = brit.next();
                    PendingBreak pb = entry.getValue();
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
                    if (p == null || !(p.getEntityWorld() instanceof ServerWorld sw)) {
                        if (pb.ticksLeft() <= 0) brit.remove();
                        else entry.setValue(new PendingBreak(pb.pos(), pb.allMode(), pb.items(), pb.leaveOne(), pb.ticksLeft() - 1));
                        continue;
                    }
                    net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(pb.pos()).expand(2.0);
                    List<ItemEntity> hits = sw.getEntitiesByClass(ItemEntity.class, box, ie -> isShulkerBox(ie.getStack()));
                    if (!hits.isEmpty()) {
                        for (ItemEntity ie : hits) {
                            ItemStack copy = ie.getStack().copy();
                            writeCollectorToStack(copy, true, pb.allMode(), pb.items(), pb.leaveOne());
                            ie.setStack(copy);
                        }
                        brit.remove();
                    } else if (pb.ticksLeft() <= 0) {
                        brit.remove();
                    } else {
                        entry.setValue(new PendingBreak(pb.pos(), pb.allMode(), pb.items(), pb.leaveOne(), pb.ticksLeft() - 1));
                    }
                }
            }

            if (++collectorTick % 10 != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Set<String> targets = COLLECTOR_TARGETS.getOrDefault(player.getUuid(), Set.of());
                if (!targets.isEmpty()) collectIntoCarriedCollectors(player, targets);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !(world instanceof ServerWorld serverWorld)) {
                return ActionResult.PASS;
            }
            BlockEntity be = serverWorld.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof Inventory) {
                OpenedContainers.mark(serverWorld, canonicalContainerPos(serverWorld, hitResult.getBlockPos()));
                BlockPos partner = doubleChestPartner(serverWorld, hitResult.getBlockPos());
                if (partner != null) OpenedContainers.mark(serverWorld, partner);
            }
            // If player is placing a collector-enabled shulker, schedule config write for next tick
            ItemStack held = player.getStackInHand(hand);
            if (isShulkerBox(held) && isCollectorEnabled(held) && be == null) {
                BlockPos placementPos = hitResult.getBlockPos().offset(hitResult.getSide());
                PENDING_COLLECTOR_PLACEMENTS.put(player.getUuid(),
                        new CollectorPlacement(placementPos, isCollectorAllMode(held), readCollectorItems(held), isCollectorLeaveOne(held)));
            }
            return ActionResult.PASS;
        });

        // After break: get config from Phase A cache (most reliable) or blockEntity fallback, schedule item write
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) return;
            // Primary: Phase A has already successfully read CUSTOM_DATA from this pos
            CollectorPlacement cached = PLACED_COLLECTOR_CONFIGS.remove(pos.asLong());
            CollectorPlacement config = cached;
            // Fallback: read directly from block entity object (still in memory even if removed from world)
            if (config == null && blockEntity instanceof ShulkerBoxBlockEntity shulker) {
                NbtComponent cd = shulker.getComponents().get(DataComponentTypes.CUSTOM_DATA);
                if (cd != null) {
                    NbtCompound nbt = cd.copyNbt();
                    if (nbt.getBoolean(COLLECTOR_ENABLED_KEY, false)) {
                        boolean am = !COLLECTOR_MODE_CERTAIN.equals(nbt.getString(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
                        boolean lo = nbt.getBoolean(COLLECTOR_LEAVE_ONE_KEY, true);
                        config = new CollectorPlacement(pos, am, splitLines(nbt.getString(COLLECTOR_ITEMS_KEY, "")), lo);
                    }
                }
            }
            if (config == null) return;
            PENDING_COLLECTOR_BREAKS.put(player.getUuid(),
                    new PendingBreak(pos, config.allMode(), config.items(), config.leaveOne(), 5));
        });

        ServerPlayNetworking.registerGlobalReceiver(ChestScanRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            int radius = Math.min(payload.radius(), 256);

            Map<String, Integer> counts = new HashMap<>();
            BlockPos center = player.getBlockPos();
            int chunkRadius = (radius >> 4) + 1;
            int cx = center.getX() >> 4;
            int cz = center.getZ() >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        int bx = be.getPos().getX() - center.getX();
                        int by = be.getPos().getY() - center.getY();
                        int bz = be.getPos().getZ() - center.getZ();
                        if (Math.abs(bx) > radius || Math.abs(by) > radius || Math.abs(bz) > radius) continue;
                        if (isTrackableContainer(world, be) && be instanceof Inventory inv) {
                            BlockPos rootPos = canonicalContainerPos(world, be.getPos());
                            if (!rootPos.equals(be.getPos())) continue;
                            accumulateLogicalContainer(world, be.getPos(), inv, counts);
                        }
                    }
                }
            }

            ServerPlayNetworking.send(player, new ChestScanResultPayload(counts));
        });

        ServerPlayNetworking.registerGlobalReceiver(TrackedChestQueryPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            Map<Long, Map<String, Integer>> counts = new HashMap<>();
            for (long encoded : payload.positions()) {
                BlockPos pos = BlockPos.fromLong(encoded);
                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                BlockEntity be = world.getBlockEntity(pos);
                if (isTrackableContainer(world, be) && be instanceof Inventory inv) {
                    counts.put(encoded, snapshotLogicalContainer(world, pos, inv));
                } else {
                    counts.put(encoded, Map.of());
                }
            }
            ServerPlayNetworking.send(player, new TrackedChestResultPayload(counts));
        });

        ServerPlayNetworking.registerGlobalReceiver(AutoTrackRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            int radius = Math.min(payload.radius(), 256);
            List<Long> positions = new ArrayList<>();
            BlockPos center = player.getBlockPos();
            int chunkRadius = (radius >> 4) + 1;
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        int bx = pos.getX() - center.getX();
                        int by = pos.getY() - center.getY();
                        int bz = pos.getZ() - center.getZ();
                        if (Math.abs(bx) > radius || Math.abs(by) > radius || Math.abs(bz) > radius) continue;
                        if (isTrackableContainer(world, entry.getValue()) && entry.getValue() instanceof Inventory)
                            positions.add(canonicalContainerPos(world, pos).asLong());
                    }
                }
            }
            ServerPlayNetworking.send(player, new AutoTrackResultPayload(positions));
        });

        ServerPlayNetworking.registerGlobalReceiver(BreakdownRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            Map<String, Integer> result = ServerRecipeBreakdown.breakdown(
                    payload.itemId(), payload.count(), payload.depth(), world);
            boolean invCraftable = ServerRecipeBreakdown.isInventoryCraftable(payload.itemId(), world);
            ServerPlayNetworking.send(player, new BreakdownResultPayload(payload.itemId(), result, invCraftable));
        });

        ServerPlayNetworking.registerGlobalReceiver(ForceMarkNearbyPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            int cx = player.getBlockPos().getX() >> 4;
            int cz = player.getBlockPos().getZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof Inventory) {
                            OpenedContainers.mark(world, be.getPos());
                        }
                    }
                }
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(CollectorTargetsPayload.ID, (payload, context) -> {
            COLLECTOR_TARGETS.put(context.player().getUuid(), new HashSet<>(payload.neededItemIds()));
        });

        ServerPlayNetworking.registerGlobalReceiver(CollectorStateRequestPayload.ID, (payload, context) -> {
            sendOpenCollectorState(context.player());
        });

        ServerPlayNetworking.registerGlobalReceiver(ToggleCollectorPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!(player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) return;
            Inventory inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
            Set<String> liveTargets = COLLECTOR_TARGETS.getOrDefault(player.getUuid(), Set.of());
            Set<String> effectiveTargets = effectiveCollectorTargets(payload.allMode(), payload.selectedItemIds(), liveTargets);
            if (inventory instanceof ShulkerBoxBlockEntity shulker) {
                writeCollectorToBlockEntity(shulker, payload.enabled(), payload.allMode(), payload.selectedItemIds(), payload.leaveOne());
                player.sendMessage(Text.literal("Gather collector: " + (payload.enabled() ? "ON" : "OFF")), true);
                if (payload.enabled() && !effectiveTargets.isEmpty() && moveMatchingInventoryInto(player, inventory, effectiveTargets, payload.leaveOne())) {
                    handler.sendContentUpdates();
                }
                sendOpenCollectorState(player);
            } else {
                ItemStack backingStack = findOpenShulkerStack(player, inventory);
                if (backingStack.isEmpty()) {
                    player.sendMessage(Text.literal("Gather collector could not find this shulker item."), true);
                    return;
                }
                writeCollectorToStack(backingStack, payload.enabled(), payload.allMode(), payload.selectedItemIds(), payload.leaveOne());
                player.sendMessage(Text.literal("Gather collector: " + (payload.enabled() ? "ON" : "OFF")), true);
                if (payload.enabled() && !effectiveTargets.isEmpty() && moveMatchingInventoryInto(player, inventory, effectiveTargets, payload.leaveOne())) {
                    backingStack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(inventoryStacks(inventory)));
                    handler.sendContentUpdates();
                }
                sendOpenCollectorState(player);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(AutoCraftPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();

            // Verify player has all ingredients
            for (AutoCraftPayload.IngredientEntry entry : payload.consume()) {
                Item item = Registries.ITEM.get(Identifier.of(entry.itemId()));
                if (countInInventory(player, item) < entry.count()) return;
            }

            // Consume ingredients
            for (AutoCraftPayload.IngredientEntry entry : payload.consume()) {
                Item item = Registries.ITEM.get(Identifier.of(entry.itemId()));
                removeFromInventory(player, item, entry.count());
            }

            // Give output
            Item outItem = Registries.ITEM.get(Identifier.of(payload.outputItemId()));
            int remaining = payload.outputCount();
            while (remaining > 0) {
                int stackSize = Math.min(remaining, outItem.getMaxCount());
                player.getInventory().insertStack(new ItemStack(outItem, stackSize));
                remaining -= stackSize;
            }
        });
    }

    private static boolean isTrackableContainer(ServerWorld world, BlockEntity be) {
        if (!(be instanceof Inventory)) return false;
        BlockPos partner = doubleChestPartner(world, be.getPos());
        if (!OpenedContainers.contains(world, canonicalContainerPos(world, be.getPos()))
                && !OpenedContainers.contains(world, be.getPos())
                && (partner == null || !OpenedContainers.contains(world, partner))) return false;
        if (be instanceof LootableInventory lootable) {
            return lootable.getLootTable() == null && lootable.getLootTableSeed() == 0L;
        }
        return true;
    }

    private static BlockPos canonicalContainerPos(ServerWorld world, BlockPos pos) {
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return pos;
        return partner.asLong() < pos.asLong() ? partner : pos;
    }

    private static BlockPos doubleChestPartner(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        ChestType type = state.get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) return null;
        Direction facing = state.get(ChestBlock.FACING);
        Direction neighborDir = type == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        BlockPos neighbor = pos.offset(neighborDir);
        return world.getBlockState(neighbor).getBlock() instanceof ChestBlock ? neighbor : null;
    }

    private static void collectIntoCarriedCollectors(ServerPlayerEntity player, Set<String> liveTargets) {
        boolean changed = false;
        ItemStack openBackingStack = ItemStack.EMPTY;

        // Phase A: scan nearby placed shulker block entities (open or closed)
        if (player.getEntityWorld() instanceof ServerWorld world) {
            BlockPos center = player.getBlockPos();
            int chunkRadius = 4;
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                    for (BlockEntity be : world.getChunk(cx + dx, cz + dz).getBlockEntities().values()) {
                        if (!(be instanceof ShulkerBoxBlockEntity shulker)) continue;
                        NbtCompound nbt = shulker.getComponents()
                                .getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
                        if (!nbt.getBoolean(COLLECTOR_ENABLED_KEY, false)) continue;
                        boolean allMode = !COLLECTOR_MODE_CERTAIN.equals(nbt.getString(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
                        List<String> selected = splitLines(nbt.getString(COLLECTOR_ITEMS_KEY, ""));
                        boolean lo = nbt.getBoolean(COLLECTOR_LEAVE_ONE_KEY, true);
                        // Cache config by pos so AFTER handler can use it on break
                        PLACED_COLLECTOR_CONFIGS.put(be.getPos().asLong(), new CollectorPlacement(be.getPos(), allMode, selected, lo));
                        Set<String> targets = effectiveCollectorTargets(allMode, selected, liveTargets);
                        if (!targets.isEmpty() && moveMatchingInventoryInto(player, shulker, targets, lo)) {
                            changed = true;
                        }
                    }
                }
            }
        }

        // Phase B: in-hand shulker currently open (screen handler backed by item inventory, not block entity)
        if (player.currentScreenHandler instanceof ShulkerBoxScreenHandler shulkerHandler) {
            Inventory openInv = ((ShulkerBoxScreenHandlerAccessor) shulkerHandler).gather$getInventory();
            if (!(openInv instanceof ShulkerBoxBlockEntity)) {
                openBackingStack = findOpenShulkerStack(player, openInv);
                if (!openBackingStack.isEmpty() && isCollectorEnabled(openBackingStack)) {
                    Set<String> targets = effectiveCollectorTargets(
                            isCollectorAllMode(openBackingStack),
                            readCollectorItems(openBackingStack),
                            liveTargets);
                    if (!targets.isEmpty() && moveMatchingInventoryInto(player, openInv, targets, isCollectorLeaveOne(openBackingStack))) {
                        openBackingStack.set(DataComponentTypes.CONTAINER,
                                ContainerComponent.fromStacks(inventoryStacks(openInv)));
                        shulkerHandler.sendContentUpdates();
                        changed = true;
                    }
                }
            }
        }

        // Phase C: collector shulker items in player inventory that are not currently open
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack collector = player.getInventory().getStack(slot);
            if (collector == openBackingStack) continue;
            if (!isShulkerBox(collector)) continue;
            boolean enabled = isCollectorEnabled(collector);
            if (!enabled) continue;
            Set<String> targets = effectiveCollectorTargets(isCollectorAllMode(collector), readCollectorItems(collector), liveTargets);
            if (targets.isEmpty()) continue;
            boolean moved = moveMatchingInventoryIntoShulkerStack(player, slot, collector, targets, isCollectorLeaveOne(collector));
            changed |= moved;
        }

        if (changed) player.currentScreenHandler.sendContentUpdates();
    }

    private static List<String> splitLines(String raw) {
        if (raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String id : raw.split("\\n")) {
            if (!id.isBlank()) result.add(id.trim());
        }
        return result;
    }

    private static Set<String> effectiveCollectorTargets(boolean allMode, List<String> selectedItems, Set<String> liveTargets) {
        if (allMode) return liveTargets;
        // Certain mode: collect exactly what was selected, regardless of gather goals
        return new HashSet<>(selectedItems);
    }

    private static void sendOpenCollectorState(ServerPlayerEntity player) {
        if (!(player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) {
            ServerPlayNetworking.send(player, new CollectorStatePayload(false, true, List.of(), true));
            return;
        }
        Inventory inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        NbtCompound data = null;
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            NbtComponent customData = shulker.getComponents().get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null) data = customData.copyNbt();
        } else {
            ItemStack backingStack = findOpenShulkerStack(player, inventory);
            if (!backingStack.isEmpty()) {
                NbtComponent customData = backingStack.get(DataComponentTypes.CUSTOM_DATA);
                if (customData != null) data = customData.copyNbt();
            }
        }
        ServerPlayNetworking.send(player, collectorStateFromNbt(data));
    }

    private static CollectorStatePayload collectorStateFromNbt(NbtCompound data) {
        if (data == null) return new CollectorStatePayload(false, true, List.of(), true);
        boolean enabled = data.getBoolean(COLLECTOR_ENABLED_KEY, false);
        boolean allMode = !COLLECTOR_MODE_CERTAIN.equals(data.getString(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
        List<String> items = splitLines(data.getString(COLLECTOR_ITEMS_KEY, ""));
        boolean leaveOne = data.getBoolean(COLLECTOR_LEAVE_ONE_KEY, true);
        return new CollectorStatePayload(enabled, allMode, items, leaveOne);
    }

    private static void writeCollectorToBlockEntity(ShulkerBoxBlockEntity shulker,
                                                    boolean enabled,
                                                    boolean allMode,
                                                    List<String> selectedItems,
                                                    boolean leaveOne) {
        NbtCompound data = shulker.getComponents().getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        writeCollectorData(data, enabled, allMode, selectedItems, leaveOne);
        ComponentMap components = ComponentMap.builder()
                .addAll(shulker.getComponents())
                .add(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data))
                .build();
        shulker.setComponents(components);
        shulker.markDirty();
    }

    private static void writeCollectorToStack(ItemStack stack, boolean enabled, boolean allMode, List<String> selectedItems, boolean leaveOne) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound data = customData == null ? new NbtCompound() : customData.copyNbt();
        writeCollectorData(data, enabled, allMode, selectedItems, leaveOne);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
    }

    private static void writeCollectorData(NbtCompound data, boolean enabled, boolean allMode, List<String> selectedItems, boolean leaveOne) {
        data.putBoolean(COLLECTOR_ENABLED_KEY, enabled);
        data.putString(COLLECTOR_MODE_KEY, allMode ? COLLECTOR_MODE_ALL : COLLECTOR_MODE_CERTAIN);
        data.putString(COLLECTOR_ITEMS_KEY, String.join("\n", selectedItems));
        data.putBoolean(COLLECTOR_LEAVE_ONE_KEY, leaveOne);
        if (enabled && data.getString(COLLECTOR_ID_KEY, "").isBlank()) {
            data.putString(COLLECTOR_ID_KEY, UUID.randomUUID().toString());
        }
    }

    private static ItemStack findOpenShulkerStack(ServerPlayerEntity player, Inventory openInventory) {
        int selectedSlot = player.getInventory().getSelectedSlot();
        ItemStack selected = player.getInventory().getStack(selectedSlot);
        if (isShulkerBox(selected)
                && (hasCollectorIdentity(selected) || containerMatchesInventory(selected.get(DataComponentTypes.CONTAINER), openInventory))) {
            return selected;
        }

        ItemStack match = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (i == selectedSlot) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (!isShulkerBox(stack)) continue;
            if (!containerMatchesInventory(stack.get(DataComponentTypes.CONTAINER), openInventory)) continue;
            if (!match.isEmpty()) return ItemStack.EMPTY;
            match = stack;
        }
        return match;
    }

    private static boolean hasCollectorIdentity(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return false;
        NbtCompound nbt = customData.copyNbt();
        return nbt.getBoolean(COLLECTOR_ENABLED_KEY, false)
                || !nbt.getString(COLLECTOR_ID_KEY, "").isBlank();
    }

    private static boolean containerMatchesInventory(ContainerComponent container, Inventory inventory) {
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
        if (container != null) container.copyTo(contents);
        for (int i = 0; i < Math.min(contents.size(), inventory.size()); i++) {
            if (!ItemStack.areEqual(contents.get(i), inventory.getStack(i))) return false;
        }
        return true;
    }

    private static DefaultedList<ItemStack> inventoryStacks(Inventory inventory) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) stacks.set(i, inventory.getStack(i).copy());
        return stacks;
    }

    private static boolean moveMatchingInventoryInto(ServerPlayerEntity player, Inventory target, Set<String> targets, boolean leaveOne) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            boolean stackable = stack.getMaxCount() > 1;
            if (stack.isEmpty() || (leaveOne && stackable && stack.getCount() <= 1) || isShulkerBox(stack) || !matchesTarget(stack, targets)) continue;
            int before = stack.getCount();
            insertIntoInventory(target, stack, leaveOne);
            if (stack.getCount() != before) changed = true;
        }
        if (changed) {
            target.markDirty();
            player.getInventory().markDirty();
        }
        return changed;
    }

    private static boolean moveMatchingInventoryIntoShulkerStack(ServerPlayerEntity player,
                                                                 int collectorSlot,
                                                                 ItemStack collector,
                                                                 Set<String> targets,
                                                                 boolean leaveOne) {
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
        ContainerComponent existing = collector.get(DataComponentTypes.CONTAINER);
        if (existing != null) existing.copyTo(contents);

        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (slot == collectorSlot) continue;
            ItemStack source = player.getInventory().getStack(slot);
            boolean stackable = source.getMaxCount() > 1;
            if (source.isEmpty() || (leaveOne && stackable && source.getCount() <= 1) || isShulkerBox(source) || !matchesTarget(source, targets)) continue;
            int before = source.getCount();
            insertIntoStacks(contents, source, leaveOne);
            if (source.getCount() != before) changed = true;
        }

        if (changed) {
            collector.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
            player.getInventory().markDirty();
        }
        return changed;
    }

    private static void insertIntoStacks(DefaultedList<ItemStack> contents, ItemStack source, boolean leaveOne) {
        if (source.isEmpty()) return;
        int transferable = (leaveOne && source.getMaxCount() > 1) ? Math.max(0, source.getCount() - 1) : source.getCount();
        for (int i = 0; i < contents.size() && transferable > 0; i++) {
            ItemStack existing = contents.get(i);
            if (existing.isEmpty() || !ItemStack.areItemsAndComponentsEqual(existing, source)) continue;
            int move = Math.min(transferable, existing.getMaxCount() - existing.getCount());
            if (move <= 0) continue;
            existing.increment(move);
            source.decrement(move);
            transferable -= move;
        }
        for (int i = 0; i < contents.size() && transferable > 0; i++) {
            if (!contents.get(i).isEmpty()) continue;
            int move = Math.min(transferable, source.getMaxCount());
            contents.set(i, source.copyWithCount(move));
            source.decrement(move);
            transferable -= move;
        }
    }

    private static void insertIntoInventory(Inventory inventory, ItemStack source, boolean leaveOne) {
        if (source.isEmpty()) return;
        int transferable = (leaveOne && source.getMaxCount() > 1) ? Math.max(0, source.getCount() - 1) : source.getCount();
        for (int i = 0; i < inventory.size() && transferable > 0; i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty() || !ItemStack.areItemsAndComponentsEqual(existing, source)) continue;
            int max = Math.min(existing.getMaxCount(), inventory.getMaxCount(existing));
            int move = Math.min(transferable, max - existing.getCount());
            if (move <= 0) continue;
            existing.increment(move);
            source.decrement(move);
            transferable -= move;
        }
        for (int i = 0; i < inventory.size() && transferable > 0; i++) {
            if (!inventory.getStack(i).isEmpty()) continue;
            int move = Math.min(transferable, Math.min(source.getMaxCount(), inventory.getMaxCount(source)));
            ItemStack copy = source.copyWithCount(move);
            if (!inventory.isValid(i, copy)) continue;
            inventory.setStack(i, copy);
            source.decrement(move);
            transferable -= move;
        }
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isCollectorEnabled(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && customData.copyNbt().getBoolean(COLLECTOR_ENABLED_KEY, false);
    }

    private static boolean isCollectorAllMode(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return true;
        return !COLLECTOR_MODE_CERTAIN.equals(customData.copyNbt().getString(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
    }

    private static boolean isCollectorLeaveOne(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return true;
        return customData.copyNbt().getBoolean(COLLECTOR_LEAVE_ONE_KEY, true);
    }

    private static List<String> readCollectorItems(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return List.of();
        return splitLines(customData.copyNbt().getString(COLLECTOR_ITEMS_KEY, ""));
    }

    private static boolean matchesTarget(ItemStack stack, Set<String> targets) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (targets.contains(id)) return true;
        for (String target : targets) {
            if (isSameWoodFamily(target, id)) return true;
        }
        return false;
    }

    private static boolean isSameWoodFamily(String idA, String idB) {
        String a = idA.contains(":") ? idA.substring(idA.indexOf(':') + 1) : idA;
        String b = idB.contains(":") ? idB.substring(idB.indexOf(':') + 1) : idB;
        for (String prefix : WOOD_PREFIXES) {
            if (a.startsWith(prefix + "_")) {
                String suffix = a.substring(prefix.length());
                for (String other : WOOD_PREFIXES) {
                    if (b.equals(other + suffix)) return true;
                }
                return false;
            }
        }
        return false;
    }

    private static final class CollectorShulkers {
        private static final Gson GSON = new Gson();
        private static final Type DATA_TYPE = new TypeToken<Map<String, Set<Long>>>() {}.getType();
        private static final Map<Path, Map<String, Set<Long>>> CACHE = new HashMap<>();

        private static boolean toggle(ServerWorld world, BlockPos pos) {
            Path file = file(world);
            Map<String, Set<Long>> data = data(world);
            Set<Long> positions = data.computeIfAbsent(dimensionKey(world), key -> new HashSet<>());
            boolean enabled;
            if (positions.remove(pos.asLong())) {
                enabled = false;
            } else {
                positions.add(pos.asLong());
                enabled = true;
            }
            save(file, data);
            return enabled;
        }

        private static Set<Long> positions(ServerWorld world) {
            return data(world).getOrDefault(dimensionKey(world), Set.of());
        }

        private static Map<String, Set<Long>> data(ServerWorld world) {
            Path file = file(world);
            return CACHE.computeIfAbsent(file, CollectorShulkers::load);
        }

        private static Path file(ServerWorld world) {
            return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("gather_collector_shulkers.json");
        }

        private static String dimensionKey(ServerWorld world) {
            return world.getRegistryKey().getValue().toString();
        }

        private static Map<String, Set<Long>> load(Path file) {
            if (!Files.exists(file)) return new HashMap<>();
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<String, Set<Long>> loaded = GSON.fromJson(reader, DATA_TYPE);
                return loaded == null ? new HashMap<>() : loaded;
            } catch (Exception ignored) {
                return new HashMap<>();
            }
        }

        private static void save(Path file, Map<String, Set<Long>> data) {
            try {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(data, DATA_TYPE, writer);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final class OpenedContainers {
        private static final Gson GSON = new Gson();
        private static final Type DATA_TYPE = new TypeToken<Map<String, Set<Long>>>() {}.getType();
        private static final Map<Path, Map<String, Set<Long>>> CACHE = new HashMap<>();

        private static boolean contains(ServerWorld world, BlockPos pos) {
            return data(world).getOrDefault(dimensionKey(world), Set.of()).contains(pos.asLong());
        }

        private static void mark(ServerWorld world, BlockPos pos) {
            Path file = file(world);
            Map<String, Set<Long>> data = data(world);
            Set<Long> positions = data.computeIfAbsent(dimensionKey(world), key -> new HashSet<>());
            if (positions.add(pos.asLong())) {
                save(file, data);
            }
        }

        private static Map<String, Set<Long>> data(ServerWorld world) {
            Path file = file(world);
            return CACHE.computeIfAbsent(file, OpenedContainers::load);
        }

        private static Path file(ServerWorld world) {
            return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("gather_opened_containers.json");
        }

        private static String dimensionKey(ServerWorld world) {
            return world.getRegistryKey().getValue().toString();
        }

        private static Map<String, Set<Long>> load(Path file) {
            if (!Files.exists(file)) return new HashMap<>();
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<String, Set<Long>> loaded = GSON.fromJson(reader, DATA_TYPE);
                return loaded == null ? new HashMap<>() : loaded;
            } catch (Exception ignored) {
                return new HashMap<>();
            }
        }

        private static void save(Path file, Map<String, Set<Long>> data) {
            try {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(data, DATA_TYPE, writer);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void accumulateInventory(Inventory inv, Map<String, Integer> counts) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
            if (isShulkerBox(stack)) {
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (ItemStack inner : container.iterateNonEmpty()) {
                        if (inner.isEmpty()) continue;
                        String innerId = Registries.ITEM.getId(inner.getItem()).toString();
                        counts.merge(innerId, inner.getCount(), Integer::sum);
                    }
                }
            }
        }
    }

    private static void accumulateLogicalContainer(ServerWorld world, BlockPos pos, Inventory inv, Map<String, Integer> counts) {
        accumulateInventory(inv, counts);
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return;
        BlockEntity partnerBe = world.getBlockEntity(partner);
        if (partnerBe instanceof Inventory partnerInv) {
            accumulateInventory(partnerInv, counts);
        }
    }

    private static Map<String, Integer> snapshotInventory(Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        accumulateInventory(inv, counts);
        return counts;
    }

    private static Map<String, Integer> snapshotLogicalContainer(ServerWorld world, BlockPos pos, Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        accumulateLogicalContainer(world, pos, inv, counts);
        return counts;
    }

    private static int countInInventory(ServerPlayerEntity player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private static void removeFromInventory(ServerPlayerEntity player, Item item, int amount) {
        for (int i = 0; i < player.getInventory().size() && amount > 0; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() != item) continue;
            int take = Math.min(s.getCount(), amount);
            s.decrement(take);
            amount -= take;
        }
    }
}
