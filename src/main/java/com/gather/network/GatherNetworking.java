package com.gather.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gather.mixin.ShulkerBoxScreenHandlerAccessor;
import com.gather.GatherServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.entity.item.ItemEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.gather.network.BreakdownEntry;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

public class GatherNetworking {

    private static final Map<UUID, Set<String>> COLLECTOR_TARGETS = new HashMap<>();
    private static final Map<Item, String> ITEM_ID_STR_CACHE = new HashMap<>();
    private static final Map<UUID, CollectorPlacement> PENDING_COLLECTOR_PLACEMENTS = new HashMap<>();
    private static final Map<Long, CollectorPlacement> PLACED_COLLECTOR_CONFIGS = new HashMap<>();
    private static final Map<UUID, PendingBreak> PENDING_COLLECTOR_BREAKS = new HashMap<>();
    private static final Map<UUID, Integer> PENDING_COLLECTOR_SYNC = new HashMap<>();
    private static final Map<UUID, Long> LAST_CHEST_SCAN_MS = new HashMap<>();
    private static final Map<UUID, Long> LAST_AUTO_TRACK_MS = new HashMap<>();
    private static final long CHEST_SCAN_COOLDOWN_MS = 1500L;
    private static int collectorTick = 0;
    private static int collectorWarmCursor = 0;

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
        PayloadTypeRegistry.serverboundPlay().register(ForceMarkNearbyPayload.ID, ForceMarkNearbyPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleCollectorPayload.ID, ToggleCollectorPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CollectorStateRequestPayload.ID, CollectorStateRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CollectorTargetsPayload.ID, CollectorTargetsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChestScanRequestPayload.ID, ChestScanRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AutoCraftPayload.ID, AutoCraftPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BreakdownRequestPayload.ID, BreakdownRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TrackedChestQueryPayload.ID, TrackedChestQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AutoTrackRequestPayload.ID, AutoTrackRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChestScanResultPayload.ID, ChestScanResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CollectorStatePayload.ID, CollectorStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BreakdownResultPayload.ID, BreakdownResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TrackedChestResultPayload.ID, TrackedChestResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AutoTrackResultPayload.ID, AutoTrackResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlacedCollectorPositionsPayload.ID, PlacedCollectorPositionsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChestDirtyPayload.ID, ChestDirtyPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(XrayPermissionPayload.ID, XrayPermissionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(XrayCommandPayload.ID, XrayCommandPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(GatherServerConfig::load);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.player;
            boolean xrayAllowed = !server.isDedicatedServer() || GatherServerConfig.isXrayAllowed();
            ServerPlayNetworking.send(p, new XrayPermissionPayload(xrayAllowed));
            if (p.level() instanceof ServerLevel sw) {
                warmCollectorCacheForPlayer(p, sw);
                ServerPlayNetworking.send(p, buildCollectorPayloadFor(p, sw));
                PENDING_COLLECTOR_SYNC.put(p.getUUID(), 20);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUUID();
            COLLECTOR_TARGETS.remove(playerId);
            PENDING_COLLECTOR_PLACEMENTS.remove(playerId);
            PENDING_COLLECTOR_BREAKS.remove(playerId);
            PENDING_COLLECTOR_SYNC.remove(playerId);
            LAST_CHEST_SCAN_MS.remove(playerId);
            LAST_AUTO_TRACK_MS.remove(playerId);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!PENDING_COLLECTOR_SYNC.isEmpty()) {
                var syncIt = PENDING_COLLECTOR_SYNC.entrySet().iterator();
                while (syncIt.hasNext()) {
                    var entry = syncIt.next();
                    int left = entry.getValue() - 1;
                    if (left > 0) {
                        entry.setValue(left);
                        continue;
                    }
                    ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                    if (p != null && p.level() instanceof ServerLevel sw) {
                        ServerPlayNetworking.send(p, buildCollectorPayloadFor(p, sw));
                    }
                    syncIt.remove();
                }
            }

            // Apply collector config to shulkers that were just placed this tick
            if (!PENDING_COLLECTOR_PLACEMENTS.isEmpty()) {
                var it = PENDING_COLLECTOR_PLACEMENTS.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    it.remove();
                    ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                    if (p == null) continue;
                    ServerLevel sw = (ServerLevel) p.level();
                    BlockEntity be = sw.getBlockEntity(entry.getValue().pos());
                    if (be instanceof ShulkerBoxBlockEntity shulker) {
                        writeCollectorToBlockEntity(shulker, true, entry.getValue().allMode(), entry.getValue().items(), entry.getValue().leaveOne());
                        broadcastCollectorPositions(server, sw);
                    }
                }
            }

            // Retry writing collector config to dropped shulker item entities
            if (!PENDING_COLLECTOR_BREAKS.isEmpty()) {
                var brit = PENDING_COLLECTOR_BREAKS.entrySet().iterator();
                while (brit.hasNext()) {
                    var entry = brit.next();
                    PendingBreak pb = entry.getValue();
                    ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                    if (p == null || !(p.level() instanceof ServerLevel sw)) {
                        if (pb.ticksLeft() <= 0) brit.remove();
                        else entry.setValue(new PendingBreak(pb.pos(), pb.allMode(), pb.items(), pb.leaveOne(), pb.ticksLeft() - 1));
                        continue;
                    }
                    net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pb.pos()).inflate(2.0);
                    List<ItemEntity> hits = sw.getEntitiesOfClass(ItemEntity.class, box, ie -> isShulkerBox(ie.getItem()));
                    if (!hits.isEmpty()) {
                        for (ItemEntity ie : hits) {
                            ItemStack copy = ie.getItem().copy();
                            writeCollectorToStack(copy, true, pb.allMode(), pb.items(), pb.leaveOne());
                            ie.setItem(copy);
                        }
                        brit.remove();
                    } else if (pb.ticksLeft() <= 0) {
                        brit.remove();
                    } else {
                        entry.setValue(new PendingBreak(pb.pos(), pb.allMode(), pb.items(), pb.leaveOne(), pb.ticksLeft() - 1));
                    }
                }
            }

            collectorTick++;

            // Stagger collector cache re-warms so chunk scans do not bunch onto one tick.
            if (collectorTick % 10 == 0) {
                List<ServerPlayer> players = server.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    if (collectorWarmCursor >= players.size()) collectorWarmCursor = 0;
                    ServerPlayer p = players.get(collectorWarmCursor++);
                    if (p.level() instanceof ServerLevel sw) warmCollectorCacheForPlayer(p, sw);
                }
            }

            if (collectorTick % 10 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Set<String> targets = COLLECTOR_TARGETS.getOrDefault(player.getUUID(), Set.of());
                if (!targets.isEmpty()) collectIntoCarriedCollectors(player, targets);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel serverWorld)) {
                return InteractionResult.PASS;
            }
            BlockEntity be = serverWorld.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof Container) {
                BlockPos canonPos = canonicalContainerPos(serverWorld, hitResult.getBlockPos());
                OpenedContainers.mark(serverWorld, canonPos);
                BlockPos partner = doubleChestPartner(serverWorld, hitResult.getBlockPos());
                if (partner != null) OpenedContainers.mark(serverWorld, partner);
                // Broadcast dirty so clients refresh this chest's contents immediately
                ChestDirtyPayload dirty = new ChestDirtyPayload(canonPos.asLong());
                for (ServerPlayer gp : serverWorld.getServer().getPlayerList().getPlayers()) {
                    ServerPlayNetworking.send(gp, dirty);
                }
            }
            ItemStack held = player.getItemInHand(hand);
            // When placing a chest, dirty any adjacent tracked chest so double-chest bounds update immediately
            if (be == null && held.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ChestBlock) {
                BlockPos placementPos = hitResult.getBlockPos().relative(hitResult.getDirection());
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = placementPos.relative(dir);
                    if (!serverWorld.getChunkSource().hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4)) continue;
                    if (!(serverWorld.getBlockEntity(neighbor) instanceof Container)) continue;
                    if (!OpenedContainers.contains(serverWorld, neighbor)) continue;
                    ChestDirtyPayload dirtyNeighbor = new ChestDirtyPayload(neighbor.asLong());
                    for (ServerPlayer gp : serverWorld.getServer().getPlayerList().getPlayers()) {
                        ServerPlayNetworking.send(gp, dirtyNeighbor);
                    }
                }
            }
            // If player is placing a collector-enabled shulker, schedule config write for next tick
            if (isShulkerBox(held) && isCollectorEnabled(held) && be == null) {
                BlockPos placementPos = hitResult.getBlockPos().relative(hitResult.getDirection());
                PENDING_COLLECTOR_PLACEMENTS.put(player.getUUID(),
                        new CollectorPlacement(placementPos, isCollectorAllMode(held), readCollectorItems(held), isCollectorLeaveOne(held)));
            }
            return InteractionResult.PASS;
        });

        // After break: get config from Phase A cache (most reliable) or blockEntity fallback, schedule item write
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return;
            // Notify clients if a tracked container was broken so outlines update immediately
            if (world instanceof ServerLevel sw && blockEntity instanceof Container) {
                if (OpenedContainers.contains(sw, pos)) {
                    ChestDirtyPayload dirty = new ChestDirtyPayload(pos.asLong());
                    for (ServerPlayer gp : sw.getServer().getPlayerList().getPlayers()) {
                        ServerPlayNetworking.send(gp, dirty);
                    }
                }
            }
            // Primary: Phase A has already successfully read CUSTOM_DATA from this pos
            CollectorPlacement cached = PLACED_COLLECTOR_CONFIGS.remove(pos.asLong());
            CollectorPlacement config = cached;
            // Fallback: read directly from block entity object (still in memory even if removed from world)
            if (config == null && blockEntity instanceof ShulkerBoxBlockEntity shulker) {
                CustomData cd = shulker.components().get(DataComponents.CUSTOM_DATA);
                if (cd != null) {
                    CompoundTag nbt = cd.copyTag();
                    if (nbt.getBooleanOr(COLLECTOR_ENABLED_KEY, false)) {
                        boolean am = !COLLECTOR_MODE_CERTAIN.equals(nbt.getStringOr(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
                        boolean lo = nbt.getBooleanOr(COLLECTOR_LEAVE_ONE_KEY, true);
                        config = new CollectorPlacement(pos, am, splitLines(nbt.getStringOr(COLLECTOR_ITEMS_KEY, "")), lo);
                    }
                }
            }
            if (config == null) return;
            PENDING_COLLECTOR_BREAKS.put(player.getUUID(),
                    new PendingBreak(pos, config.allMode(), config.items(), config.leaveOne(), 5));
            if (world instanceof ServerLevel sw) broadcastCollectorPositions(sw.getServer(), sw);
        });

        ServerPlayNetworking.registerGlobalReceiver(XrayCommandPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            boolean isOp = player.permissions().hasPermission(
                    new net.minecraft.server.permissions.Permission.HasCommandLevel(
                            net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS));
            if (!isOp) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Gather] You need OP (level 2) to change xray settings."));
                return;
            }
            boolean value = payload.enable();
            GatherServerConfig.setXray(value);
            broadcastXrayPermission(context.server(), value);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Gather] Xray " + (value ? "enabled" : "disabled") + " for all players."));
        });

        ServerPlayNetworking.registerGlobalReceiver(ChestScanRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (isRateLimited(LAST_CHEST_SCAN_MS, player.getUUID(), CHEST_SCAN_COOLDOWN_MS)) return;
            ServerLevel world = (ServerLevel) player.level();
            int radius = Math.min(payload.radius(), 256);

            Map<String, Integer> counts = new HashMap<>();
            BlockPos center = player.blockPosition();
            int chunkRadius = (radius >> 4) + 1;
            int cx = center.getX() >> 4;
            int cz = center.getZ() >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!world.getChunkSource().hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        int bx = be.getBlockPos().getX() - center.getX();
                        int by = be.getBlockPos().getY() - center.getY();
                        int bz = be.getBlockPos().getZ() - center.getZ();
                        if (Math.abs(bx) > radius || Math.abs(by) > radius || Math.abs(bz) > radius) continue;
                        if (isTrackableContainer(world, be) && be instanceof Container inv) {
                            BlockPos rootPos = canonicalContainerPos(world, be.getBlockPos());
                            if (!rootPos.equals(be.getBlockPos())) continue;
                            accumulateLogicalContainer(world, be.getBlockPos(), inv, counts);
                        }
                    }
                }
            }

            ServerPlayNetworking.send(player, new ChestScanResultPayload(counts));
        });

        ServerPlayNetworking.registerGlobalReceiver(TrackedChestQueryPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel world = (ServerLevel) player.level();
            Map<Long, Map<String, Integer>> counts = new HashMap<>();
            for (long encoded : payload.positions()) {
                BlockPos pos = BlockPos.of(encoded);
                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
                BlockEntity be = world.getBlockEntity(pos);
                if (isCollectorEnabled(be) && be instanceof Container inv) {
                    counts.put(encoded, snapshotInventory(inv));
                } else if (isTrackableContainer(world, be) && be instanceof Container inv) {
                    counts.put(encoded, snapshotLogicalContainer(world, pos, inv));
                } else {
                    counts.put(encoded, Map.of());
                }
            }
            ServerPlayNetworking.send(player, new TrackedChestResultPayload(counts));
        });

        ServerPlayNetworking.registerGlobalReceiver(AutoTrackRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (isRateLimited(LAST_AUTO_TRACK_MS, player.getUUID(), CHEST_SCAN_COOLDOWN_MS)) return;
            ServerLevel world = (ServerLevel) player.level();
            int radius = Math.min(payload.radius(), 256);
            List<Long> positions = new ArrayList<>();
            BlockPos center = player.blockPosition();
            int chunkRadius = (radius >> 4) + 1;
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!world.getChunkSource().hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        int bx = pos.getX() - center.getX();
                        int by = pos.getY() - center.getY();
                        int bz = pos.getZ() - center.getZ();
                        if (Math.abs(bx) > radius || Math.abs(by) > radius || Math.abs(bz) > radius) continue;
                        if (isTrackableContainer(world, entry.getValue()) && entry.getValue() instanceof Container)
                            positions.add(canonicalContainerPos(world, pos).asLong());
                    }
                }
            }
            ServerPlayNetworking.send(player, new AutoTrackResultPayload(positions));
        });

        ServerPlayNetworking.registerGlobalReceiver(BreakdownRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel world = (ServerLevel) player.level();
            List<BreakdownEntry> result = ServerRecipeBreakdown.breakdown(
                    payload.itemId(), payload.count(), payload.depth(), world);
            boolean invCraftable = ServerRecipeBreakdown.isInventoryCraftable(payload.itemId(), world);
            ServerPlayNetworking.send(player, new BreakdownResultPayload(payload.itemId(), result, invCraftable));
        });

        ServerPlayNetworking.registerGlobalReceiver(ForceMarkNearbyPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel world = (ServerLevel) player.level();
            int cx = player.blockPosition().getX() >> 4;
            int cz = player.blockPosition().getZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!world.getChunkSource().hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = world.getChunk(cx + dx, cz + dz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof Container) {
                            OpenedContainers.mark(world, be.getBlockPos());
                        }
                    }
                }
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(CollectorTargetsPayload.ID, (payload, context) -> {
            COLLECTOR_TARGETS.put(context.player().getUUID(), new HashSet<>(payload.neededItemIds()));
        });

        ServerPlayNetworking.registerGlobalReceiver(CollectorStateRequestPayload.ID, (payload, context) -> {
            sendOpenCollectorState(context.player());
        });

        ServerPlayNetworking.registerGlobalReceiver(ToggleCollectorPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!(player.containerMenu instanceof ShulkerBoxMenu handler)) return;
            Container inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
            Set<String> liveTargets = COLLECTOR_TARGETS.getOrDefault(player.getUUID(), Set.of());
            Set<String> effectiveTargets = effectiveCollectorTargets(payload.allMode(), payload.selectedItemIds(), liveTargets);
            if (inventory instanceof ShulkerBoxBlockEntity shulker) {
                writeCollectorToBlockEntity(shulker, payload.enabled(), payload.allMode(), payload.selectedItemIds(), payload.leaveOne());
                player.sendOverlayMessage(Component.literal("Gather collector: " + (payload.enabled() ? "ON" : "OFF")));
                if (payload.enabled() && !effectiveTargets.isEmpty() && moveMatchingInventoryInto(player, inventory, effectiveTargets, payload.leaveOne())) {
                    handler.broadcastChanges();
                }
                sendOpenCollectorState(player);
                ServerLevel sw = (ServerLevel) player.level();
                broadcastCollectorPositions(sw.getServer(), sw);
            } else {
                ItemStack backingStack = findOpenShulkerStack(player, inventory);
                if (backingStack.isEmpty()) {
                    player.sendOverlayMessage(Component.literal("Gather collector could not find this shulker item."));
                    return;
                }
                writeCollectorToStack(backingStack, payload.enabled(), payload.allMode(), payload.selectedItemIds(), payload.leaveOne());
                player.sendOverlayMessage(Component.literal("Gather collector: " + (payload.enabled() ? "ON" : "OFF")));
                if (payload.enabled() && !effectiveTargets.isEmpty() && moveMatchingInventoryInto(player, inventory, effectiveTargets, payload.leaveOne())) {
                    backingStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inventoryStacks(inventory)));
                    handler.broadcastChanges();
                }
                sendOpenCollectorState(player);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(AutoCraftPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();

            // Verify player has all ingredients
            for (AutoCraftPayload.IngredientEntry entry : payload.consume()) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.itemId()));
                if (countInInventory(player, item) < entry.count()) return;
            }

            // Consume ingredients
            for (AutoCraftPayload.IngredientEntry entry : payload.consume()) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.itemId()));
                removeFromInventory(player, item, entry.count());
            }

            // Give output
            Item outItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(payload.outputItemId()));
            int remaining = payload.outputCount();
            while (remaining > 0) {
                int stackSize = Math.min(remaining, outItem.getDefaultMaxStackSize());
                player.getInventory().add(new ItemStack(outItem, stackSize));
                remaining -= stackSize;
            }
        });
    }

    private static boolean isTrackableContainer(ServerLevel world, BlockEntity be) {
        if (!(be instanceof Container)) return false;
        BlockPos partner = doubleChestPartner(world, be.getBlockPos());
        if (!OpenedContainers.contains(world, canonicalContainerPos(world, be.getBlockPos()))
                && !OpenedContainers.contains(world, be.getBlockPos())
                && (partner == null || !OpenedContainers.contains(world, partner))) return false;
        if (be instanceof RandomizableContainer lootable) {
            return lootable.getLootTable() == null && lootable.getLootTableSeed() == 0L;
        }
        return true;
    }

    private static BlockPos canonicalContainerPos(ServerLevel world, BlockPos pos) {
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return pos;
        return partner.asLong() < pos.asLong() ? partner : pos;
    }

    private static BlockPos doubleChestPartner(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;
        Direction facing = state.getValue(ChestBlock.FACING);
        Direction neighborDir = type == ChestType.LEFT
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockPos neighbor = pos.relative(neighborDir);
        return world.getBlockState(neighbor).getBlock() instanceof ChestBlock ? neighbor : null;
    }

    private static void collectIntoCarriedCollectors(ServerPlayer player, Set<String> liveTargets) {
        boolean changed = false;
        ItemStack openBackingStack = ItemStack.EMPTY;

        // Phase A: process only known placed collector shulkers from cache (no chunk scanning)
        if (player.level() instanceof ServerLevel world) {
            BlockPos center = player.blockPosition();
            int chunkRadius = 4;
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            for (var it = PLACED_COLLECTOR_CONFIGS.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                BlockPos pos = BlockPos.of(entry.getKey());
                if (Math.abs((pos.getX() >> 4) - cx) > chunkRadius ||
                    Math.abs((pos.getZ() >> 4) - cz) > chunkRadius) continue;
                if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                if (!(world.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity shulker)) {
                    it.remove();
                    continue;
                }
                CollectorPlacement config = entry.getValue();
                Set<String> targets = effectiveCollectorTargets(config.allMode(), config.items(), liveTargets);
                if (!targets.isEmpty() && moveMatchingInventoryInto(player, shulker, targets, config.leaveOne())) {
                    changed = true;
                }
            }
        }

        // Phase B: in-hand shulker currently open (screen handler backed by item inventory, not block entity)
        if (player.containerMenu instanceof ShulkerBoxMenu shulkerHandler) {
            Container openInv = ((ShulkerBoxScreenHandlerAccessor) shulkerHandler).gather$getInventory();
            if (!(openInv instanceof ShulkerBoxBlockEntity)) {
                openBackingStack = findOpenShulkerStack(player, openInv);
                if (!openBackingStack.isEmpty() && isCollectorEnabled(openBackingStack)) {
                    Set<String> targets = effectiveCollectorTargets(
                            isCollectorAllMode(openBackingStack),
                            readCollectorItems(openBackingStack),
                            liveTargets);
                    if (!targets.isEmpty() && moveMatchingInventoryInto(player, openInv, targets, isCollectorLeaveOne(openBackingStack))) {
                        openBackingStack.set(DataComponents.CONTAINER,
                                ItemContainerContents.fromItems(inventoryStacks(openInv)));
                        shulkerHandler.broadcastChanges();
                        changed = true;
                    }
                }
            }
        }

        // Phase C: collector shulker items in player inventory that are not currently open
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack collector = player.getInventory().getItem(slot);
            if (collector == openBackingStack) continue;
            if (!isShulkerBox(collector)) continue;
            CompoundTag nbt = enabledCollectorNbt(collector); // single copyNbt call
            if (nbt == null) continue;
            boolean allMode = !COLLECTOR_MODE_CERTAIN.equals(nbt.getStringOr(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
            List<String> selectedItems = splitLines(nbt.getStringOr(COLLECTOR_ITEMS_KEY, ""));
            boolean lo = nbt.getBooleanOr(COLLECTOR_LEAVE_ONE_KEY, true);
            Set<String> targets = effectiveCollectorTargets(allMode, selectedItems, liveTargets);
            if (targets.isEmpty()) continue;
            boolean moved = moveMatchingInventoryIntoShulkerStack(player, slot, collector, targets, lo);
            changed |= moved;
        }

        if (changed) player.containerMenu.broadcastChanges();
    }

    private static List<String> splitLines(String raw) {
        if (raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String id : raw.split("\\n")) {
            if (!id.isBlank()) result.add(id.trim());
        }
        return result;
    }

    private static boolean isRateLimited(Map<UUID, Long> lastSeenMs, UUID playerId, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = lastSeenMs.get(playerId);
        if (last != null && now - last < cooldownMs) return true;
        lastSeenMs.put(playerId, now);
        return false;
    }

    private static Set<String> effectiveCollectorTargets(boolean allMode, List<String> selectedItems, Set<String> liveTargets) {
        if (allMode) return liveTargets;
        // Certain mode: collect exactly what was selected, regardless of gather goals
        return new HashSet<>(selectedItems);
    }

    public static void broadcastXrayPermission(net.minecraft.server.MinecraftServer server, boolean allowed) {
        XrayPermissionPayload packet = new XrayPermissionPayload(allowed);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, packet);
        }
    }

    public static void broadcastCollectorPositions(net.minecraft.server.MinecraftServer server, ServerLevel world) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == world) {
                ServerPlayNetworking.send(p, buildCollectorPayloadFor(p, world));
            }
        }
    }

    private static void warmCollectorCacheForPlayer(ServerPlayer player, ServerLevel world) {
        BlockPos center = player.blockPosition();
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (!world.getChunkSource().hasChunk(cx + dx, cz + dz)) continue;
                for (BlockEntity be : world.getChunk(cx + dx, cz + dz).getBlockEntities().values()) {
                    if (!(be instanceof ShulkerBoxBlockEntity shulker)) continue;
                    CompoundTag nbt = shulker.components()
                            .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    long key = be.getBlockPos().asLong();
                    if (!nbt.getBooleanOr(COLLECTOR_ENABLED_KEY, false)) {
                        PLACED_COLLECTOR_CONFIGS.remove(key);
                        continue;
                    }
                    boolean allMode = !COLLECTOR_MODE_CERTAIN.equals(nbt.getStringOr(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
                    List<String> items = splitLines(nbt.getStringOr(COLLECTOR_ITEMS_KEY, ""));
                    boolean lo = nbt.getBooleanOr(COLLECTOR_LEAVE_ONE_KEY, true);
                    PLACED_COLLECTOR_CONFIGS.put(key, new CollectorPlacement(be.getBlockPos(), allMode, items, lo));
                }
            }
        }
    }

    private static PlacedCollectorPositionsPayload buildCollectorPayloadFor(ServerPlayer player, ServerLevel world) {
        List<PlacedCollectorPositionsPayload.Entry> entries = new ArrayList<>();
        BlockPos center = player.blockPosition();
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        for (var entry : PLACED_COLLECTOR_CONFIGS.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            if (Math.abs((pos.getX() >> 4) - cx) > 6 || Math.abs((pos.getZ() >> 4) - cz) > 6) continue;
            CollectorPlacement config = entry.getValue();
            entries.add(new PlacedCollectorPositionsPayload.Entry(entry.getKey(), config.allMode(), config.items()));
        }
        return new PlacedCollectorPositionsPayload(entries);
    }

    private static void sendOpenCollectorState(ServerPlayer player) {
        if (!(player.containerMenu instanceof ShulkerBoxMenu handler)) {
            ServerPlayNetworking.send(player, new CollectorStatePayload(false, true, List.of(), true));
            return;
        }
        Container inventory = ((ShulkerBoxScreenHandlerAccessor) handler).gather$getInventory();
        CompoundTag data = null;
        if (inventory instanceof ShulkerBoxBlockEntity shulker) {
            CustomData customData = shulker.components().get(DataComponents.CUSTOM_DATA);
            if (customData != null) data = customData.copyTag();
        } else {
            ItemStack backingStack = findOpenShulkerStack(player, inventory);
            if (!backingStack.isEmpty()) {
                CustomData customData = backingStack.get(DataComponents.CUSTOM_DATA);
                if (customData != null) data = customData.copyTag();
            }
        }
        ServerPlayNetworking.send(player, collectorStateFromNbt(data));
    }

    private static CollectorStatePayload collectorStateFromNbt(CompoundTag data) {
        if (data == null) return new CollectorStatePayload(false, true, List.of(), true);
        boolean enabled = data.getBooleanOr(COLLECTOR_ENABLED_KEY, false);
        boolean allMode = !COLLECTOR_MODE_CERTAIN.equals(data.getStringOr(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
        List<String> items = splitLines(data.getStringOr(COLLECTOR_ITEMS_KEY, ""));
        boolean leaveOne = data.getBooleanOr(COLLECTOR_LEAVE_ONE_KEY, true);
        return new CollectorStatePayload(enabled, allMode, items, leaveOne);
    }

    private static void writeCollectorToBlockEntity(ShulkerBoxBlockEntity shulker,
                                                    boolean enabled,
                                                    boolean allMode,
                                                    List<String> selectedItems,
                                                    boolean leaveOne) {
        CompoundTag data = shulker.components().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        writeCollectorData(data, enabled, allMode, selectedItems, leaveOne);
        DataComponentMap components = DataComponentMap.builder()
                .addAll(shulker.components())
                .set(DataComponents.CUSTOM_DATA, CustomData.of(data))
                .build();
        shulker.setComponents(components);
        shulker.setChanged();
        updatePlacedCollectorCache(shulker.getBlockPos(), enabled, allMode, selectedItems, leaveOne);
    }

    private static void updatePlacedCollectorCache(BlockPos pos, boolean enabled,
                                                   boolean allMode, List<String> selectedItems,
                                                   boolean leaveOne) {
        if (enabled) {
            PLACED_COLLECTOR_CONFIGS.put(pos.asLong(),
                    new CollectorPlacement(pos, allMode, List.copyOf(selectedItems), leaveOne));
        } else {
            PLACED_COLLECTOR_CONFIGS.remove(pos.asLong());
        }
    }

    private static void writeCollectorToStack(ItemStack stack, boolean enabled, boolean allMode, List<String> selectedItems, boolean leaveOne) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag data = customData == null ? new CompoundTag() : customData.copyTag();
        writeCollectorData(data, enabled, allMode, selectedItems, leaveOne);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static void writeCollectorData(CompoundTag data, boolean enabled, boolean allMode, List<String> selectedItems, boolean leaveOne) {
        data.putBoolean(COLLECTOR_ENABLED_KEY, enabled);
        data.putString(COLLECTOR_MODE_KEY, allMode ? COLLECTOR_MODE_ALL : COLLECTOR_MODE_CERTAIN);
        data.putString(COLLECTOR_ITEMS_KEY, String.join("\n", selectedItems));
        data.putBoolean(COLLECTOR_LEAVE_ONE_KEY, leaveOne);
        if (enabled && data.getStringOr(COLLECTOR_ID_KEY, "").isBlank()) {
            data.putString(COLLECTOR_ID_KEY, UUID.randomUUID().toString());
        }
    }

    private static ItemStack findOpenShulkerStack(ServerPlayer player, Container openInventory) {
        int selectedSlot = player.getInventory().getSelectedSlot();
        ItemStack selected = player.getInventory().getItem(selectedSlot);
        if (isShulkerBox(selected)
                && (hasCollectorIdentity(selected) || containerMatchesInventory(selected.get(DataComponents.CONTAINER), openInventory))) {
            return selected;
        }

        ItemStack match = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (i == selectedSlot) continue;
            ItemStack stack = player.getInventory().getItem(i);
            if (!isShulkerBox(stack)) continue;
            if (!containerMatchesInventory(stack.get(DataComponents.CONTAINER), openInventory)) continue;
            if (!match.isEmpty()) return ItemStack.EMPTY;
            match = stack;
        }
        return match;
    }

    private static boolean hasCollectorIdentity(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag nbt = customData.copyTag();
        return nbt.getBooleanOr(COLLECTOR_ENABLED_KEY, false)
                || !nbt.getStringOr(COLLECTOR_ID_KEY, "").isBlank();
    }

    private static boolean containerMatchesInventory(ItemContainerContents container, Container inventory) {
        NonNullList<ItemStack> contents = NonNullList.create();
        for (int i = 0; i < 27; i++) contents.add(ItemStack.EMPTY);
        if (container != null) container.copyInto(contents);
        for (int i = 0; i < Math.min(contents.size(), inventory.getContainerSize()); i++) {
            if (!ItemStack.matches(contents.get(i), inventory.getItem(i))) return false;
        }
        return true;
    }

    private static NonNullList<ItemStack> inventoryStacks(Container inventory) {
        NonNullList<ItemStack> stacks = NonNullList.create();
        for (int i = 0; i < inventory.getContainerSize(); i++) stacks.add(inventory.getItem(i).copy());
        return stacks;
    }

    private static boolean moveMatchingInventoryInto(ServerPlayer player, Container target, Set<String> targets, boolean leaveOne) {
        boolean changed = false;
        Set<String> leftOneFor = leaveOne ? new HashSet<>() : null;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || isShulkerBox(stack) || !matchesTarget(stack, targets)) continue;
            boolean stackable = stack.getMaxStackSize() > 1;
            boolean effectiveLeaveOne = leaveOne && stackable;
            if (effectiveLeaveOne) {
                String id = ITEM_ID_STR_CACHE.computeIfAbsent(stack.getItem(), item -> BuiltInRegistries.ITEM.getKey(item).toString());
                if (leftOneFor.contains(id)) {
                    effectiveLeaveOne = false; // already reserved 1 from an earlier stack
                } else if (stack.getCount() <= 1) {
                    leftOneFor.add(id); // this single item is the kept one
                    continue;
                } else {
                    leftOneFor.add(id); // will keep 1 from this stack
                }
            }
            int before = stack.getCount();
            insertIntoInventory(target, stack, effectiveLeaveOne);
            if (stack.getCount() != before) changed = true;
        }
        if (changed) {
            target.setChanged();
            player.getInventory().setChanged();
        }
        return changed;
    }

    private static boolean moveMatchingInventoryIntoShulkerStack(ServerPlayer player,
                                                                 int collectorSlot,
                                                                 ItemStack collector,
                                                                 Set<String> targets,
                                                                 boolean leaveOne) {
        NonNullList<ItemStack> contents = NonNullList.create();
        for (int j = 0; j < 27; j++) contents.add(ItemStack.EMPTY);
        ItemContainerContents existing = collector.get(DataComponents.CONTAINER);
        if (existing != null) existing.copyInto(contents);

        boolean changed = false;
        Set<String> leftOneFor = leaveOne ? new HashSet<>() : null;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (slot == collectorSlot) continue;
            ItemStack source = player.getInventory().getItem(slot);
            if (source.isEmpty() || isShulkerBox(source) || !matchesTarget(source, targets)) continue;
            boolean stackable = source.getMaxStackSize() > 1;
            boolean effectiveLeaveOne = leaveOne && stackable;
            if (effectiveLeaveOne) {
                String id = ITEM_ID_STR_CACHE.computeIfAbsent(source.getItem(), item -> BuiltInRegistries.ITEM.getKey(item).toString());
                if (leftOneFor.contains(id)) {
                    effectiveLeaveOne = false;
                } else if (source.getCount() <= 1) {
                    leftOneFor.add(id);
                    continue;
                } else {
                    leftOneFor.add(id);
                }
            }
            int before = source.getCount();
            insertIntoStacks(contents, source, effectiveLeaveOne);
            if (source.getCount() != before) changed = true;
        }

        if (changed) {
            collector.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            player.getInventory().setChanged();
        }
        return changed;
    }

    private static void insertIntoStacks(NonNullList<ItemStack> contents, ItemStack source, boolean leaveOne) {
        if (source.isEmpty()) return;
        int transferable = (leaveOne && source.getMaxStackSize() > 1) ? Math.max(0, source.getCount() - 1) : source.getCount();
        for (int i = 0; i < contents.size() && transferable > 0; i++) {
            ItemStack existing = contents.get(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, source)) continue;
            int move = Math.min(transferable, existing.getMaxStackSize() - existing.getCount());
            if (move <= 0) continue;
            existing.grow(move);
            source.shrink(move);
            transferable -= move;
        }
        for (int i = 0; i < contents.size() && transferable > 0; i++) {
            if (!contents.get(i).isEmpty()) continue;
            int move = Math.min(transferable, source.getMaxStackSize());
            contents.set(i, source.copyWithCount(move));
            source.shrink(move);
            transferable -= move;
        }
    }

    private static void insertIntoInventory(Container inventory, ItemStack source, boolean leaveOne) {
        if (source.isEmpty()) return;
        int transferable = (leaveOne && source.getMaxStackSize() > 1) ? Math.max(0, source.getCount() - 1) : source.getCount();
        for (int i = 0; i < inventory.getContainerSize() && transferable > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, source)) continue;
            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize(existing));
            int move = Math.min(transferable, max - existing.getCount());
            if (move <= 0) continue;
            existing.grow(move);
            source.shrink(move);
            transferable -= move;
        }
        for (int i = 0; i < inventory.getContainerSize() && transferable > 0; i++) {
            if (!inventory.getItem(i).isEmpty()) continue;
            int move = Math.min(transferable, Math.min(source.getMaxStackSize(), inventory.getMaxStackSize(source)));
            ItemStack copy = source.copyWithCount(move);
            if (!inventory.canPlaceItem(i, copy)) continue;
            inventory.setItem(i, copy);
            source.shrink(move);
            transferable -= move;
        }
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Returns the collector NBT if enabled, null otherwise. Copies NBT exactly once. */
    private static CompoundTag enabledCollectorNbt(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag nbt = cd.copyTag();
        return nbt.getBooleanOr(COLLECTOR_ENABLED_KEY, false) ? nbt : null;
    }

    private static boolean isCollectorEnabled(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && customData.copyTag().getBooleanOr(COLLECTOR_ENABLED_KEY, false);
    }

    private static boolean isCollectorEnabled(BlockEntity be) {
        if (!(be instanceof ShulkerBoxBlockEntity shulker)) return false;
        CustomData customData = shulker.components().get(DataComponents.CUSTOM_DATA);
        return customData != null && customData.copyTag().getBooleanOr(COLLECTOR_ENABLED_KEY, false);
    }

    private static boolean isCollectorAllMode(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return true;
        return !COLLECTOR_MODE_CERTAIN.equals(customData.copyTag().getStringOr(COLLECTOR_MODE_KEY, COLLECTOR_MODE_ALL));
    }

    private static boolean isCollectorLeaveOne(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return true;
        return customData.copyTag().getBooleanOr(COLLECTOR_LEAVE_ONE_KEY, true);
    }

    private static List<String> readCollectorItems(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return List.of();
        return splitLines(customData.copyTag().getStringOr(COLLECTOR_ITEMS_KEY, ""));
    }

    private static boolean matchesTarget(ItemStack stack, Set<String> targets) {
        String id = ITEM_ID_STR_CACHE.computeIfAbsent(stack.getItem(), item -> BuiltInRegistries.ITEM.getKey(item).toString());
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

        private static boolean toggle(ServerLevel world, BlockPos pos) {
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

        private static Set<Long> positions(ServerLevel world) {
            return data(world).getOrDefault(dimensionKey(world), Set.of());
        }

        private static Map<String, Set<Long>> data(ServerLevel world) {
            Path file = file(world);
            return CACHE.computeIfAbsent(file, CollectorShulkers::load);
        }

        private static Path file(ServerLevel world) {
            return world.getServer().getWorldPath(LevelResource.ROOT).resolve("gather_collector_shulkers.json");
        }

        private static String dimensionKey(ServerLevel world) {
            return world.dimension().identifier().toString();
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

        private static boolean contains(ServerLevel world, BlockPos pos) {
            return data(world).getOrDefault(dimensionKey(world), Set.of()).contains(pos.asLong());
        }

        private static void mark(ServerLevel world, BlockPos pos) {
            Path file = file(world);
            Map<String, Set<Long>> data = data(world);
            Set<Long> positions = data.computeIfAbsent(dimensionKey(world), key -> new HashSet<>());
            if (positions.add(pos.asLong())) {
                save(file, data);
            }
        }

        private static Map<String, Set<Long>> data(ServerLevel world) {
            Path file = file(world);
            return CACHE.computeIfAbsent(file, OpenedContainers::load);
        }

        private static Path file(ServerLevel world) {
            return world.getServer().getWorldPath(LevelResource.ROOT).resolve("gather_opened_containers.json");
        }

        private static String dimensionKey(ServerLevel world) {
            return world.dimension().identifier().toString();
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

    private static void accumulateInventory(Container inv, Map<String, Integer> counts) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
            if (isShulkerBox(stack)) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    for (ItemStack inner : container.nonEmptyItemCopyStream().toList()) {
                        if (inner.isEmpty()) continue;
                        String innerId = BuiltInRegistries.ITEM.getKey(inner.getItem()).toString();
                        counts.merge(innerId, inner.getCount(), Integer::sum);
                    }
                }
            }
        }
    }

    private static void accumulateLogicalContainer(ServerLevel world, BlockPos pos, Container inv, Map<String, Integer> counts) {
        accumulateInventory(inv, counts);
        BlockPos partner = doubleChestPartner(world, pos);
        if (partner == null) return;
        BlockEntity partnerBe = world.getBlockEntity(partner);
        if (partnerBe instanceof Container partnerInv) {
            accumulateInventory(partnerInv, counts);
        }
    }

    private static Map<String, Integer> snapshotInventory(Container inv) {
        Map<String, Integer> counts = new HashMap<>();
        accumulateInventory(inv, counts);
        return counts;
    }

    private static Map<String, Integer> snapshotLogicalContainer(ServerLevel world, BlockPos pos, Container inv) {
        Map<String, Integer> counts = new HashMap<>();
        accumulateLogicalContainer(world, pos, inv, counts);
        return counts;
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private static void removeFromInventory(ServerPlayer player, Item item, int amount) {
        for (int i = 0; i < player.getInventory().getContainerSize() && amount > 0; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() != item) continue;
            int take = Math.min(s.getCount(), amount);
            s.shrink(take);
            amount -= take;
        }
    }
}
