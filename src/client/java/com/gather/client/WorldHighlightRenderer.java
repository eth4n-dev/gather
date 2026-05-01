package com.gather.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

public class WorldHighlightRenderer {

    private static final long SCAN_INTERVAL_MS = 1500;
    private static final long VERTICAL_EXPAND_STABLE_MS = 3500;
    private static final long SECTION_CACHE_TTL_MS = 30_000;
    private static final int INITIAL_VERTICAL_SCAN_RADIUS = 6;
    private static final int VERTICAL_PRIORITY_WEIGHT = 6;
    private static final int INITIAL_VISIBLE_OUTLINES = 24;
    private static final int CHEST_XRAY_MAX_DISTANCE = 100;
    private static final double CHEST_XRAY_MAX_DISTANCE_SQ = CHEST_XRAY_MAX_DISTANCE * CHEST_XRAY_MAX_DISTANCE;
    private static final long FAR_CHEST_NOTICE_MS = 3000;
    private static final long CHEST_XRAY_VALIDATE_MS = 250;
    private static final float OUTLINE_EPSILON = 0.0025f;
    private static final double CONTAINER_OUTLINE_INFLATE = 0.012;
    private static final double CONTAINER_EDGE = 0.018;
    private static final int AUTO_XRAY_FILL = 0x2800C8AA;
    private static final int AUTO_XRAY_EDGE = 0xB000C8AA;
    private static final int MANUAL_XRAY_FILL = 0x28FF9600;
    private static final int MANUAL_XRAY_EDGE = 0xB0FF9600;
    private static final int MANUAL_SCAN_XRAY_FILL = 0x28FFC800;
    private static final int MANUAL_SCAN_XRAY_EDGE = 0xB0FFC800;
    private static final int FINDER_XRAY_FILL = 0x50FF2020;
    private static final int FINDER_XRAY_EDGE = 0xD0FF2020;

    private static Set<Block> cachedNeededBlocks = new HashSet<>();
    private static int cachedNodeCount = -1;
    private static List<BlockPos> cachedHighlights = null;
    private static long lastScanTime = 0;
    private static BlockPos lastPlayerPos = null;
    private static ChunkKey currentPlayerChunk = null;
    private static ChunkKey lastVerticalExpandChunk = null;
    private static long currentChunkEnteredAtMs = 0L;
    private static long highlightRampStartedAtMs = 0L;
    private static ScanJob scanJob = null;
    private static final Map<SectionKey, SectionScanCache> sectionScanCache = new HashMap<>();
    private static final Map<Long, ChestXrayCache> chestXrayCache = new HashMap<>();
    private static long lastFarChestNoticeMs = 0L;

    private record SectionKey(int x, int y, int z) {}
    private record ChunkKey(int x, int z) {}
    private record SectionScanCache(int neededHash, long scannedAtMs, List<BlockPos> positions) {}
    private record ScanJob(BlockPos origin, int radius, int verticalRadius, int neededHash,
                           Deque<SectionKey> pending, List<BlockPos> highlights) {}
    private record ChestXrayCache(BlockPos pos, Box bounds, long validatedAtMs, boolean valid) {}

    public static void invalidateCache() {
        cachedNeededBlocks = new HashSet<>();
        cachedNodeCount = -1;
        cachedHighlights = null;
        lastScanTime = 0;
        lastPlayerPos = null;
        currentPlayerChunk = null;
        lastVerticalExpandChunk = null;
        currentChunkEnteredAtMs = 0L;
        highlightRampStartedAtMs = 0L;
        scanJob = null;
        sectionScanCache.clear();
        chestXrayCache.clear();
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(WorldHighlightRenderer::onAfterEntities);
    }

    public static boolean shouldRenderTailXray() {
        GatherSettings settings = GatherSettings.get();
        if (!settings.enabled) return false;
        GatherState state = GatherState.get();
        boolean hasContainerXray = settings.chestOutlinesEnabled
                && (settings.countChests ? !state.getTrackedChests().isEmpty() : !state.getManualChests().isEmpty());
        boolean hasFinderXray = state.getChestFinderItemId() != null;
        boolean hasBlockXray = settings.highlightEnabled && settings.blockXray && !state.getNeeded().isEmpty();
        return hasContainerXray || hasFinderXray || hasBlockXray;
    }

    private static void onAfterEntities(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        VertexConsumerProvider consumers = ctx.consumers();
        if (consumers == null) return;
        if (!GatherSettings.get().enabled) return;

        GatherState state = GatherState.get();
        Vec3d camPos = client.getEntityRenderDispatcher().camera.getCameraPos();
        MatrixStack matrices = ctx.matrices();

        // Needed block outlines (depth-tested LINES — skipped when blockXray is on, which uses no-depth quads)
        if (GatherSettings.get().highlightEnabled && !GatherSettings.get().blockXray && !state.getNeeded().isEmpty()) {
            renderNeededBlockHighlights(state, world, client, camPos, consumers, matrices);
        }
    }

    public static void renderScannedContainerXray(Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;
        if (!GatherSettings.get().enabled) return;

        RenderLayer xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
        if (xrayLayer == null) return;

        GatherState state = GatherState.get();
        GatherSettings settings = GatherSettings.get();
        boolean scanAllOn = settings.countChests;
        String finderItemId = state.getChestFinderItemId();
        Set<Long> autoChests = settings.chestOutlinesEnabled && scanAllOn
                ? state.getTrackedChests() : Collections.emptySet();
        Set<Long> manualChests = settings.chestOutlinesEnabled && !scanAllOn
                ? state.getManualChests() : Collections.emptySet();
        Set<Long> finderChests = finderItemId != null
                ? state.getChestsContaining(finderItemId) : Collections.emptySet();

        if (autoChests.isEmpty() && manualChests.isEmpty() && finderChests.isEmpty()) return;

        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer quads = consumers.getBuffer(xrayLayer);
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() - 180.0F));

        Vec3d camPos = camera.getCameraPos();
        BlockPos playerPos = client.player.getBlockPos();
        boolean scanMode = state.isChestScanMode();
        boolean drew = false;
        int hiddenFar = 0;
        Set<Long> drawn = new HashSet<>();

        if (settings.chestOutlinesEnabled) {
            List<Long> deadAuto = new ArrayList<>(), deadManual = new ArrayList<>();

            for (long encoded : autoChests) {
                if (drawn.contains(encoded)) continue;
                BlockPos pos = BlockPos.fromLong(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null) continue;
                if (!cache.valid()) { deadAuto.add(encoded); continue; }
                if (isFarChestOutline(playerPos, pos)) { hiddenFar++; continue; }
                markDoubleChestPartner(world, pos, drawn);
                drawXrayContainerBox(quads, matrices, camPos, cache, AUTO_XRAY_FILL, AUTO_XRAY_EDGE);
                drew = true;
            }

            for (long encoded : manualChests) {
                if (drawn.contains(encoded)) continue;
                BlockPos pos = BlockPos.fromLong(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null) continue;
                if (!cache.valid()) { deadManual.add(encoded); continue; }
                if (isFarChestOutline(playerPos, pos)) { hiddenFar++; continue; }
                markDoubleChestPartner(world, pos, drawn);
                drawXrayContainerBox(quads, matrices, camPos, cache,
                        scanMode ? MANUAL_SCAN_XRAY_FILL : MANUAL_XRAY_FILL,
                        scanMode ? MANUAL_SCAN_XRAY_EDGE : MANUAL_XRAY_EDGE);
                drew = true;
            }

            deadAuto.forEach(state::removeTrackedChest);
            deadManual.forEach(state::removeManualChest);
        }

        // Finder pass — always render regardless of chestOutlinesEnabled
        if (finderItemId != null) {
            Set<Long> finderDrawn = new HashSet<>();
            for (long encoded : finderChests) {
                if (finderDrawn.contains(encoded)) continue;
                BlockPos pos = BlockPos.fromLong(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null || !cache.valid()) continue;
                if (isFarChestOutline(playerPos, pos)) { hiddenFar++; continue; }
                markDoubleChestPartner(world, pos, finderDrawn);
                drawXrayContainerBox(quads, matrices, camPos, cache, FINDER_XRAY_FILL, FINDER_XRAY_EDGE);
                drew = true;
            }
        }

        if (hiddenFar > 0) showFarChestNotice(client, hiddenFar);
        if (drew) consumers.draw(xrayLayer);
    }

    private static boolean isFarChestOutline(BlockPos playerPos, BlockPos chestPos) {
        return playerPos.getSquaredDistance(chestPos) > CHEST_XRAY_MAX_DISTANCE_SQ;
    }

    private static void showFarChestNotice(MinecraftClient client, int hiddenCount) {
        long now = System.currentTimeMillis();
        if (now - lastFarChestNoticeMs < FAR_CHEST_NOTICE_MS) return;
        lastFarChestNoticeMs = now;
        String label = hiddenCount == 1 ? "1 far chest outline hidden" : hiddenCount + " far chest outlines hidden";
        client.player.sendMessage(Text.literal("Gather: " + label + " because it is over 100 blocks away. Contents still count."), true);
    }

    private static ChestXrayCache getChestXrayCache(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        long key = pos.asLong();
        long now = System.currentTimeMillis();
        ChestXrayCache cached = chestXrayCache.get(key);
        if (cached != null && now - cached.validatedAtMs() < CHEST_XRAY_VALIDATE_MS) return cached;
        if (!(world.getBlockEntity(pos) instanceof Inventory)) {
            ChestXrayCache invalid = new ChestXrayCache(pos, null, now, false);
            chestXrayCache.put(key, invalid);
            return invalid;
        }
        ChestXrayCache fresh = new ChestXrayCache(pos, getInflatedContainerBounds(world, pos), now, true);
        chestXrayCache.put(key, fresh);
        return fresh;
    }

    private static void drawXrayContainerBox(VertexConsumer quads, MatrixStack matrices,
                                             Vec3d camPos, ChestXrayCache cache, int fillArgb, int edgeArgb) {
        BlockPos pos = cache.pos();
        Box bounds = cache.bounds();
        matrices.push();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        drawCuboid(quads, matrix, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, fillArgb);
        drawContainerEdges(quads, matrix, bounds, edgeArgb);
        matrices.pop();
    }

    private static void drawContainerEdges(VertexConsumer quads, Matrix4f matrix, Box bounds, int argb) {
        double e = Math.min(CONTAINER_EDGE, Math.min(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ) / 3.0);
        double minX = bounds.minX, minY = bounds.minY, minZ = bounds.minZ;
        double maxX = bounds.maxX, maxY = bounds.maxY, maxZ = bounds.maxZ;
        drawEdge(quads, matrix, minX, minY, minZ, maxX, minY + e, minZ + e, argb);
        drawEdge(quads, matrix, minX, minY, maxZ - e, maxX, minY + e, maxZ, argb);
        drawEdge(quads, matrix, minX, maxY - e, minZ, maxX, maxY, minZ + e, argb);
        drawEdge(quads, matrix, minX, maxY - e, maxZ - e, maxX, maxY, maxZ, argb);

        drawEdge(quads, matrix, minX, minY, minZ, minX + e, minY + e, maxZ, argb);
        drawEdge(quads, matrix, maxX - e, minY, minZ, maxX, minY + e, maxZ, argb);
        drawEdge(quads, matrix, minX, maxY - e, minZ, minX + e, maxY, maxZ, argb);
        drawEdge(quads, matrix, maxX - e, maxY - e, minZ, maxX, maxY, maxZ, argb);

        drawEdge(quads, matrix, minX, minY, minZ, minX + e, maxY, minZ + e, argb);
        drawEdge(quads, matrix, maxX - e, minY, minZ, maxX, maxY, minZ + e, argb);
        drawEdge(quads, matrix, minX, minY, maxZ - e, minX + e, maxY, maxZ, argb);
        drawEdge(quads, matrix, maxX - e, minY, maxZ - e, maxX, maxY, maxZ, argb);
    }

    private static void drawEdge(VertexConsumer quads, Matrix4f matrix,
                                 double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ, int argb) {
        drawCuboid(quads, matrix, minX, minY, minZ, maxX, maxY, maxZ, argb);
    }

    private static void drawCuboid(VertexConsumer quads, Matrix4f matrix,
                                   double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ, int argb) {
        vertex(quads, matrix, minX, minY, minZ, argb);
        vertex(quads, matrix, minX, maxY, minZ, argb);
        vertex(quads, matrix, maxX, maxY, minZ, argb);
        vertex(quads, matrix, maxX, minY, minZ, argb);

        vertex(quads, matrix, minX, minY, maxZ, argb);
        vertex(quads, matrix, maxX, minY, maxZ, argb);
        vertex(quads, matrix, maxX, maxY, maxZ, argb);
        vertex(quads, matrix, minX, maxY, maxZ, argb);

        vertex(quads, matrix, minX, minY, minZ, argb);
        vertex(quads, matrix, minX, minY, maxZ, argb);
        vertex(quads, matrix, minX, maxY, maxZ, argb);
        vertex(quads, matrix, minX, maxY, minZ, argb);

        vertex(quads, matrix, maxX, minY, minZ, argb);
        vertex(quads, matrix, maxX, maxY, minZ, argb);
        vertex(quads, matrix, maxX, maxY, maxZ, argb);
        vertex(quads, matrix, maxX, minY, maxZ, argb);

        vertex(quads, matrix, minX, minY, minZ, argb);
        vertex(quads, matrix, maxX, minY, minZ, argb);
        vertex(quads, matrix, maxX, minY, maxZ, argb);
        vertex(quads, matrix, minX, minY, maxZ, argb);

        vertex(quads, matrix, minX, maxY, minZ, argb);
        vertex(quads, matrix, minX, maxY, maxZ, argb);
        vertex(quads, matrix, maxX, maxY, maxZ, argb);
        vertex(quads, matrix, maxX, maxY, minZ, argb);
    }

    private static void vertex(VertexConsumer quads, Matrix4f matrix, double x, double y, double z, int argb) {
        quads.vertex(matrix, (float) x, (float) y, (float) z).color(argb);
    }

    private static void drawFallbackContainerBox(VertexConsumer lines, MatrixStack matrices, ClientWorld world,
                                                 Vec3d camPos, BlockPos pos, int lineArgb) {
        VoxelShape shape = getContainerShape(world, pos);
        matrices.push();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        matrices.translate(-CONTAINER_OUTLINE_INFLATE, -CONTAINER_OUTLINE_INFLATE, -CONTAINER_OUTLINE_INFLATE);
        matrices.scale(1.0f + (float) CONTAINER_OUTLINE_INFLATE * 2.0f,
                1.0f + (float) CONTAINER_OUTLINE_INFLATE * 2.0f,
                1.0f + (float) CONTAINER_OUTLINE_INFLATE * 2.0f);
        VertexRendering.drawOutline(matrices, lines, shape, 0.0, 0.0, 0.0, lineArgb, 1.0f);
        matrices.pop();
    }

    private static VoxelShape getContainerShape(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);
        return shape.isEmpty() ? VoxelShapes.fullCube() : shape;
    }

    private static Box getInflatedContainerBounds(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction facing = state.get(ChestBlock.FACING);
                Direction neighborDir = (chestType == ChestType.LEFT)
                        ? facing.rotateYClockwise()
                        : facing.rotateYCounterclockwise();
                BlockPos neighborPos = pos.offset(neighborDir);
                if (world.getBlockState(neighborPos).getBlock() instanceof ChestBlock) {
                    Box ownBounds = getContainerShape(world, pos).getBoundingBox();
                    Box neighborBounds = getContainerShape(world, neighborPos).getBoundingBox()
                            .offset(neighborDir.getOffsetX(), neighborDir.getOffsetY(), neighborDir.getOffsetZ());
                    return ownBounds.union(neighborBounds).expand(CONTAINER_OUTLINE_INFLATE);
                }
            }
        }
        return getContainerShape(world, pos).getBoundingBox().expand(CONTAINER_OUTLINE_INFLATE);
    }

    private static void markDoubleChestPartner(ClientWorld world, BlockPos pos, Set<Long> drawn) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return;
        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) return;
        Direction facing = state.get(ChestBlock.FACING);
        Direction neighborDir = (chestType == ChestType.LEFT)
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        drawn.add(pos.offset(neighborDir).asLong());
    }

    // ─── NEEDED BLOCK HIGHLIGHTS ─────────────────────────────────────────────

    public static void renderNeededBlockXray(Camera camera) {
        if (!GatherSettings.get().enabled || !GatherSettings.get().highlightEnabled || !GatherSettings.get().blockXray) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        RenderLayer xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
        if (xrayLayer == null) return;

        GatherState state = GatherState.get();
        if (state.getNeeded().isEmpty()) return;

        int nodeCount = state.getNodes().size();
        if (nodeCount != cachedNodeCount) {
            cachedNodeCount = nodeCount;
            cachedNeededBlocks = computeNeededBlocks(state);
            cachedHighlights = null;
        }
        if (cachedNeededBlocks.isEmpty()) return;

        BlockPos playerPos = client.player.getBlockPos();
        long now = System.currentTimeMillis();
        updateScanJobForPlayer(playerPos, now, cachedHighlights == null);
        processScanJob(world, cachedNeededBlocks);
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return;
        cachedHighlights.removeIf(pos -> !cachedNeededBlocks.contains(world.getBlockState(pos).getBlock()));
        if (cachedHighlights.isEmpty()) return;

        long time = System.currentTimeMillis();
        int solidColor = switch (GatherSettings.get().outlineColor) {
            case "blue"   -> 0xFF4488FF;
            case "red"    -> 0xFFFF4444;
            case "green"  -> 0xFF44FF44;
            case "yellow" -> 0xFFFFFF44;
            case "white"  -> 0xFFFFFFFF;
            default -> { float[] rgb = rainbowColor(time); yield 0xFF000000 | ((int)(rgb[0]*255) << 16) | ((int)(rgb[1]*255) << 8) | (int)(rgb[2]*255); }
        };
        int edgeColor = (solidColor & 0x00FFFFFF) | 0x48000000;

        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer quads = consumers.getBuffer(xrayLayer);
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() - 180.0F));

        Vec3d camPos = camera.getCameraPos();
        boolean drew = false;
        for (BlockPos pos : visibleHighlights()) {
            VoxelShape shape = world.getBlockState(pos).getOutlineShape(world, pos);
            Box bounds = (shape.isEmpty() ? VoxelShapes.fullCube() : shape).getBoundingBox()
                    .expand(OUTLINE_EPSILON)
                    .offset(pos.getX(), pos.getY(), pos.getZ());
            matrices.push();
            matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            Box local = bounds.offset(-pos.getX(), -pos.getY(), -pos.getZ());
            drawContainerEdges(quads, matrix, local, edgeColor);
            matrices.pop();
            drew = true;
        }
        if (drew) consumers.draw(xrayLayer);
    }

    private static void renderNeededBlockHighlights(GatherState state, ClientWorld world,
                                                    MinecraftClient client, Vec3d camPos,
                                                    VertexConsumerProvider consumers, MatrixStack matrices) {
        int nodeCount = state.getNodes().size();
        if (nodeCount != cachedNodeCount) {
            cachedNodeCount = nodeCount;
            cachedNeededBlocks = computeNeededBlocks(state);
            cachedHighlights = null;
        }

        if (cachedNeededBlocks.isEmpty()) return;

        BlockPos playerPos = client.player.getBlockPos();
        long now = System.currentTimeMillis();
        updateScanJobForPlayer(playerPos, now, cachedHighlights == null);
        processScanJob(world, cachedNeededBlocks);

        if (cachedHighlights == null || cachedHighlights.isEmpty()) return;

        cachedHighlights.removeIf(pos -> !cachedNeededBlocks.contains(world.getBlockState(pos).getBlock()));

        if (cachedHighlights.isEmpty()) return;

        long time = System.currentTimeMillis();
        int argbColor = switch (GatherSettings.get().outlineColor) {
            case "blue"   -> 0xFF4488FF;
            case "red"    -> 0xFFFF4444;
            case "green"  -> 0xFF44FF44;
            case "yellow" -> 0xFFFFFF44;
            case "white"  -> 0xFFFFFFFF;
            default -> { float[] rgb = rainbowColor(time); yield 0xFF000000 | ((int)(rgb[0]*255) << 16) | ((int)(rgb[1]*255) << 8) | (int)(rgb[2]*255); }
        };
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.LINES);

        for (BlockPos pos : visibleHighlights()) {
            matrices.push();
            matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            matrices.translate(-OUTLINE_EPSILON, -OUTLINE_EPSILON, -OUTLINE_EPSILON);
            matrices.scale(1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f);
            BlockState bs = world.getBlockState(pos);
            VoxelShape shape = bs.getOutlineShape(world, pos);
            if (shape.isEmpty()) shape = VoxelShapes.fullCube();
            VertexRendering.drawOutline(matrices, consumer, shape, 0.0, 0.0, 0.0, argbColor, 1.0f);
            matrices.pop();
        }
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(RenderLayers.LINES);
        }
    }

    private static Set<Block> computeNeededBlocks(GatherState state) {
        Set<Block> result = new HashSet<>();
        for (String itemId : state.getChestScanTargets(id -> 0)) {
            if (state.isBaseMaterialHidden(itemId)) continue;
            Identifier id = Identifier.of(itemId);
            Item item = Registries.ITEM.get(id);
            addOreSourceBlocks(result, id);
            if (!(item instanceof BlockItem bi)) continue;
            result.add(bi.getBlock());

            String path = id.getPath();
            for (String prefix : GatherState.WOOD_PREFIXES) {
                if (path.startsWith(prefix + "_")) {
                    String suffix = path.substring(prefix.length());
                    for (String other : GatherState.WOOD_PREFIXES) {
                        Item variant = Registries.ITEM.get(Identifier.of("minecraft", other + suffix));
                        if (variant instanceof BlockItem vbi) result.add(vbi.getBlock());
                    }
                    break;
                }
            }
        }
        return result;
    }

    private static void addOreSourceBlocks(Set<Block> result, Identifier id) {
        switch (id.toString()) {
            case "minecraft:iron_ingot", "minecraft:raw_iron" -> addBlocks(result,
                    "minecraft:iron_ore", "minecraft:deepslate_iron_ore");
            case "minecraft:gold_ingot", "minecraft:raw_gold" -> addBlocks(result,
                    "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore");
            case "minecraft:copper_ingot", "minecraft:raw_copper" -> addBlocks(result,
                    "minecraft:copper_ore", "minecraft:deepslate_copper_ore");
            case "minecraft:redstone" -> addBlocks(result,
                    "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore");
            case "minecraft:lapis_lazuli" -> addBlocks(result,
                    "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore");
            case "minecraft:diamond" -> addBlocks(result,
                    "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");
            case "minecraft:emerald" -> addBlocks(result,
                    "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore");
            case "minecraft:coal" -> addBlocks(result,
                    "minecraft:coal_ore", "minecraft:deepslate_coal_ore");
            case "minecraft:quartz" -> addBlocks(result, "minecraft:nether_quartz_ore");
            case "minecraft:netherite_scrap" -> addBlocks(result, "minecraft:ancient_debris");
            case "minecraft:glowstone_dust" -> addBlocks(result, "minecraft:glowstone");
        }
    }

    private static void addBlocks(Set<Block> result, String... itemIds) {
        for (String itemId : itemIds) {
            Item item = Registries.ITEM.get(Identifier.of(itemId));
            if (item instanceof BlockItem bi) result.add(bi.getBlock());
        }
    }

    private static void startScanJob(BlockPos origin, Set<Block> neededBlocks,
                                     int verticalRadius, boolean preserveExistingHighlights) {
        int radius = GatherSettings.get().highlightRadius;
        int neededHash = neededBlocks.hashCode();
        int clampedVerticalRadius = Math.max(0, Math.min(radius, verticalRadius));
        List<BlockPos> highlights = preserveExistingHighlights && cachedHighlights != null
                ? new ArrayList<>(cachedHighlights)
                : new ArrayList<>();
        if (!preserveExistingHighlights || highlightRampStartedAtMs == 0L) {
            highlightRampStartedAtMs = System.currentTimeMillis();
        }
        scanJob = new ScanJob(origin, radius, clampedVerticalRadius, neededHash,
                buildSectionQueue(origin, radius, clampedVerticalRadius), highlights);
        cachedHighlights = scanJob.highlights();
    }

    private static void updateScanJobForPlayer(BlockPos playerPos, long now, boolean force) {
        ChunkKey chunk = new ChunkKey(playerPos.getX() >> 4, playerPos.getZ() >> 4);
        if (!chunk.equals(currentPlayerChunk)) {
            currentPlayerChunk = chunk;
            currentChunkEnteredAtMs = now;
            lastVerticalExpandChunk = null;
        }

        boolean movedEnough = lastPlayerPos == null || lastPlayerPos.getManhattanDistance(playerPos) > 8;
        if (force || movedEnough) {
            lastScanTime = now;
            lastPlayerPos = playerPos;
            currentChunkEnteredAtMs = now;
            lastVerticalExpandChunk = null;
            startScanJob(playerPos, cachedNeededBlocks, INITIAL_VERTICAL_SCAN_RADIUS, false);
            return;
        }

        boolean shouldExpandVertically = now - currentChunkEnteredAtMs >= VERTICAL_EXPAND_STABLE_MS
                && !chunk.equals(lastVerticalExpandChunk)
                && (scanJob == null || scanJob.verticalRadius() < GatherSettings.get().highlightRadius);
        if (shouldExpandVertically) {
            lastScanTime = now;
            lastPlayerPos = playerPos;
            lastVerticalExpandChunk = chunk;
            startScanJob(playerPos, cachedNeededBlocks, GatherSettings.get().highlightRadius, true);
            return;
        }

        boolean timedRefresh = scanJob == null && now - lastScanTime > SCAN_INTERVAL_MS;

        if (timedRefresh) {
            lastScanTime = now;
            lastPlayerPos = playerPos;
            int verticalRadius = chunk.equals(lastVerticalExpandChunk)
                    ? GatherSettings.get().highlightRadius
                    : INITIAL_VERTICAL_SCAN_RADIUS;
            startScanJob(playerPos, cachedNeededBlocks, verticalRadius, true);
        }
    }

    private static Deque<SectionKey> buildSectionQueue(BlockPos origin, int radius, int verticalRadius) {
        List<SectionKey> sections = new ArrayList<>();
        int minX = Math.floorDiv(origin.getX() - radius, 16);
        int maxX = Math.floorDiv(origin.getX() + radius, 16);
        int minY = Math.floorDiv(origin.getY() - verticalRadius, 16);
        int maxY = Math.floorDiv(origin.getY() + verticalRadius, 16);
        int minZ = Math.floorDiv(origin.getZ() - radius, 16);
        int maxZ = Math.floorDiv(origin.getZ() + radius, 16);
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    sections.add(new SectionKey(sx, sy, sz));
                }
            }
        }
        sections.sort(Comparator.comparingInt(s -> sectionDistanceScore(origin, s)));
        return new ArrayDeque<>(sections);
    }

    private static int sectionDistanceScore(BlockPos origin, SectionKey section) {
        int cx = section.x() * 16 + 8;
        int cy = section.y() * 16 + 8;
        int cz = section.z() * 16 + 8;
        int dx = cx - origin.getX();
        int dy = cy - origin.getY();
        int dz = cz - origin.getZ();
        return dx * dx + dz * dz + dy * dy * VERTICAL_PRIORITY_WEIGHT;
    }

    private static void processScanJob(ClientWorld world, Set<Block> neededBlocks) {
        if (scanJob == null) return;
        int budget = Math.max(1, GatherSettings.get().highlightScanBudget);
        long now = System.currentTimeMillis();
        for (int i = 0; i < budget && !scanJob.pending().isEmpty(); i++) {
            SectionKey section = scanJob.pending().removeFirst();
            SectionScanCache cached = sectionScanCache.get(section);
            List<BlockPos> positions;
            if (cached != null
                    && cached.neededHash() == scanJob.neededHash()
                    && now - cached.scannedAtMs() < SECTION_CACHE_TTL_MS) {
                positions = cached.positions();
            } else {
                positions = scanSection(world, section, neededBlocks);
                sectionScanCache.put(section, new SectionScanCache(scanJob.neededHash(), now, positions));
            }
            addPositionsInRadius(scanJob.highlights(), positions, scanJob.origin(),
                    scanJob.radius(), scanJob.verticalRadius());
        }
        applyHighlightLimit(scanJob.highlights(), scanJob.origin());
        cachedHighlights = scanJob.highlights();
        if (scanJob.pending().isEmpty()) scanJob = null;
    }

    private static List<BlockPos> scanSection(ClientWorld world, SectionKey section, Set<Block> neededBlocks) {
        List<BlockPos> result = new ArrayList<>();
        int minX = section.x() * 16;
        int maxX = minX + 15;
        int minY = section.y() * 16;
        int maxY = minY + 15;
        int minZ = section.z() * 16;
        int maxZ = minZ + 15;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (neededBlocks.contains(world.getBlockState(mutable).getBlock())) {
                        result.add(mutable.toImmutable());
                    }
                }
            }
        }
        return result;
    }

    private static void addPositionsInRadius(List<BlockPos> result, List<BlockPos> positions,
                                             BlockPos origin, int radius, int verticalRadius) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        for (BlockPos pos : positions) {
            if (Math.abs(pos.getX() - ox) > radius) continue;
            if (Math.abs(pos.getY() - oy) > verticalRadius) continue;
            if (Math.abs(pos.getZ() - oz) > radius) continue;
            if (!result.contains(pos)) result.add(pos);
        }
    }

    private static void applyHighlightLimit(List<BlockPos> result, BlockPos origin) {
        int limit = GatherSettings.get().maxBlockHighlights;
        if (limit > 0 && result.size() > limit) {
            int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
            result.sort((a, b) -> Integer.compare(
                    highlightDistanceScore(a, ox, oy, oz),
                    highlightDistanceScore(b, ox, oy, oz)));
            result.subList(limit, result.size()).clear();
        }
    }

    private static List<BlockPos> visibleHighlights() {
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return List.of();
        int limit = currentVisibleHighlightLimit();
        if (limit >= cachedHighlights.size()) return cachedHighlights;
        return cachedHighlights.subList(0, limit);
    }

    private static int currentVisibleHighlightLimit() {
        int max = Math.max(1, GatherSettings.get().maxBlockHighlights);
        int rampMs = Math.max(0, GatherSettings.get().highlightRampSeconds) * 1000;
        if (rampMs <= 0 || highlightRampStartedAtMs == 0L) return max;
        long elapsed = Math.max(0L, System.currentTimeMillis() - highlightRampStartedAtMs);
        if (elapsed >= rampMs) return max;
        int floor = Math.min(max, INITIAL_VISIBLE_OUTLINES);
        int ramped = (int) Math.ceil(max * (elapsed / (double) rampMs));
        return Math.max(floor, Math.min(max, ramped));
    }

    private static int highlightDistanceScore(BlockPos pos, int ox, int oy, int oz) {
        int dx = Math.abs(pos.getX() - ox);
        int dy = Math.abs(pos.getY() - oy);
        int dz = Math.abs(pos.getZ() - oz);
        return dx + dz + dy * VERTICAL_PRIORITY_WEIGHT;
    }

    public static float[] rainbowColor(long time) {
        float hue = (time % 3000) / 3000f;
        return hsvToRgb(hue, 1f, 1f);
    }

    public static int rainbowColorInt(long time) {
        float[] c = rainbowColor(time);
        return ((int)(c[0]*255) << 16) | ((int)(c[1]*255) << 8) | (int)(c[2]*255);
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        int i = (int)(h * 6);
        float f = h * 6 - i;
        float p = v*(1-s), q = v*(1-f*s), t = v*(1-(1-f)*s);
        return switch (i % 6) {
            case 0 -> new float[]{v, t, p};
            case 1 -> new float[]{q, v, p};
            case 2 -> new float[]{p, v, t};
            case 3 -> new float[]{p, q, v};
            case 4 -> new float[]{t, p, v};
            default -> new float[]{v, p, q};
        };
    }
}
