package com.gather.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.nbt.NbtCompound;
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
    private static final long SECTION_CACHE_PRUNE_INTERVAL_MS = 5_000;
    private static final int INITIAL_VERTICAL_SCAN_RADIUS = 10;
    private static final int VERTICAL_PRIORITY_WEIGHT = 3;
    private static final int INITIAL_VISIBLE_OUTLINES = 24;
    private static final int CHEST_XRAY_MAX_DISTANCE = 100;
    private static final double CHEST_XRAY_MAX_DISTANCE_SQ = CHEST_XRAY_MAX_DISTANCE * CHEST_XRAY_MAX_DISTANCE;
    private static final long FAR_CHEST_NOTICE_MS = 3000;
    private static final long CHEST_XRAY_VALIDATE_MS = 2000;
    private static final long SHULKER_ANIMATION_VALIDATE_MS = 25L;
    private static final float OUTLINE_EPSILON = 0.0025f;
    private static final double CONTAINER_OUTLINE_INFLATE = 0.012;
    private static final double CONTAINER_EDGE = 0.018;
    private static final int AUTO_XRAY_FILL = 0x2800C8AA;
    private static final int AUTO_XRAY_EDGE = 0xB000C8AA;
    private static final int MANUAL_XRAY_FILL = 0x28FF9600;
    private static final int MANUAL_XRAY_EDGE = 0xB0FF9600;
    private static final int MANUAL_SCAN_XRAY_FILL = 0x28FFC800;
    private static final int MANUAL_SCAN_XRAY_EDGE = 0xB0FFC800;
    private static final int FINDER_XRAY_FILL    = 0x50FF2020;
    private static final int FINDER_XRAY_EDGE    = 0xD0FF2020;
    private static final int COLLECTOR_XRAY_FILL = 0x28CC44FF;
    private static final int COLLECTOR_XRAY_EDGE = 0xB0CC44FF;
    private static final double COLLECTOR_LABEL_MAX_DISTANCE = 22.0;
    private static final double COLLECTOR_LABEL_MAX_DISTANCE_SQ = COLLECTOR_LABEL_MAX_DISTANCE * COLLECTOR_LABEL_MAX_DISTANCE;

    private static Set<Block> cachedNeededBlocks = new HashSet<>();
    private static int cachedNodeCount = -1;
    private static List<BlockPos> cachedHighlights = null;
    private static long lastScanTime = 0;
    private static long lastSectionCachePruneMs = 0L;
    private static BlockPos lastPlayerPos = null;
    private static ChunkKey currentPlayerChunk = null;
    private static ChunkKey lastVerticalExpandChunk = null;
    private static long currentChunkEnteredAtMs = 0L;
    private static long highlightRampStartedAtMs = 0L;
    private static Boolean cachedBlockXrayMode = null;
    private static ScanJob scanJob = null;
    private static final Map<SectionKey, SectionScanCache> sectionScanCache = new HashMap<>();
    private static final Map<Long, ChestXrayCache> chestXrayCache = new HashMap<>();
    private static final Map<Long, VoxelShape> highlightShapeCache = new HashMap<>();
    private static final Map<Long, Long> shulkerAnimationWatchUntil = new HashMap<>();
    private static final Map<Long, Long> pendingPlacedContainerUntil = new HashMap<>();
    private static long lastFarChestNoticeMs = 0L;
    private static Set<Long> collectorShulkerPositions = new HashSet<>();
    private static Matrix4f lastWorldPositionMatrix = null;
    private static Matrix4f lastWorldProjectionMatrix = null;

    private static final Map<Long, Boolean> collectorShulkerValid = new HashMap<>();
    private static long lastCollectorShulkerLabelValidateMs = 0L;
    private static final long COLLECTOR_LABEL_VALIDATE_MS = 1000L;
    // Sorted top-3 contents per collector, rebuilt in the 1s validation batch (not per frame)
    private static final Map<Long, List<CollectorLabelRow>> collectorSortedContents = new HashMap<>();
    // Reused Vector4f for screen projection — render thread only
    private static final org.joml.Vector4f projectVec = new org.joml.Vector4f();
    // Reused set for highlightShapeCache.retainAll — avoids stream+collect allocation every second
    private static final Set<Long> retainSetBuf = new HashSet<>();
    // Reused set for finder xray pass — avoids new HashSet<>() allocation every frame
    private static final Set<Long> finderDrawn = new HashSet<>();

    private record SectionKey(int x, int y, int z) {}
    private record ChunkKey(int x, int z) {}
    private record SectionScanCache(int neededHash, boolean exposedOnly, long scannedAtMs, List<BlockPos> positions) {}
    private record ScanJob(BlockPos origin, int radius, int verticalRadius, int neededHash,
                           Deque<SectionKey> pending, List<BlockPos> highlights, Set<Long> highlightSet) {}
    private record ChestXrayCache(BlockPos pos, Box bounds, long validatedAtMs, boolean valid, boolean isCollector, boolean animating) {}
    private record DrawSpec(BlockPos pos, Box bounds, VoxelShape shape, int fillArgb, int edgeArgb, boolean isCollector) {}
    private record CollectorLabelRow(String label, ItemStack stack) {}

    private static List<DrawSpec> chestDrawList = null;
    private static boolean chestDrawDirty = true;
    private static long chestDrawListBuiltAt = 0L;
    private static final long CHEST_DRAW_LIST_TTL_MS = 2000L;
    private static final long HIGHLIGHT_VALIDATE_INTERVAL_MS = 1000L;
    private static long lastHighlightValidatedMs = 0L;

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
        cachedBlockXrayMode = null;
        lastHighlightValidatedMs = 0L;
        lastSectionCachePruneMs = 0L;
        scanJob = null;
        sectionScanCache.clear();
        chestXrayCache.clear();
        highlightShapeCache.clear();
        shulkerAnimationWatchUntil.clear();
        pendingPlacedContainerUntil.clear();
        chestDrawList = null;
        chestDrawDirty = true;
        collectorShulkerPositions = new HashSet<>();
        lastWorldPositionMatrix = null;
        lastWorldProjectionMatrix = null;
        collectorShulkerValid.clear();
        lastCollectorShulkerLabelValidateMs = 0L;
        collectorSortedContents.clear();
        finderDrawn.clear();
        retainSetBuf.clear();
    }

    public static void invalidateCollectorCache() {
        collectorShulkerPositions = new HashSet<>();
        chestDrawDirty = true;
        collectorShulkerValid.clear();
        lastCollectorShulkerLabelValidateMs = 0L;
        collectorSortedContents.clear();
        chestXrayCache.clear();
        shulkerAnimationWatchUntil.clear();
        pendingPlacedContainerUntil.clear();
    }

    public static void markChestSetDirty() { chestDrawDirty = true; }

    public static void evictChestXrayCache(long posLong) {
        chestXrayCache.remove(posLong);
        // Evict cardinal neighbors so double-chest partner bounds are recomputed immediately
        BlockPos p = BlockPos.fromLong(posLong);
        chestXrayCache.remove(p.north().asLong());
        chestXrayCache.remove(p.south().asLong());
        chestXrayCache.remove(p.east().asLong());
        chestXrayCache.remove(p.west().asLong());
        chestDrawDirty = true;
    }

    public static void watchShulkerAnimation(long posLong) {
        shulkerAnimationWatchUntil.put(posLong, System.currentTimeMillis() + 1200L);
        evictChestXrayCache(posLong);
    }

    public static void watchNewContainerPlacement(long posLong) {
        long until = System.currentTimeMillis() + 1500L;
        pendingPlacedContainerUntil.put(posLong, until);
        chestXrayCache.remove(posLong);
        chestDrawDirty = true;
        chestDrawListBuiltAt = 0L;
    }

    public static void resetCollectorLabelCache() {
        lastCollectorShulkerLabelValidateMs = 0L;
        collectorSortedContents.clear();
    }

    public static void captureWorldMatrices(Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        lastWorldPositionMatrix = new Matrix4f(positionMatrix);
        lastWorldProjectionMatrix = new Matrix4f(projectionMatrix);
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(WorldHighlightRenderer::onAfterEntities);
    }

    public static boolean shouldRenderTailXray() {
        GatherSettings settings = GatherSettings.get();
        if (!settings.enabled) return false;
        GatherState state = GatherState.get();
        boolean xrayAllowed = GatherState.isServerXrayAllowed();
        boolean hasContainerXray = xrayAllowed && settings.chestOutlinesEnabled && settings.chestXray
                && (settings.countChests ? !state.getTrackedChests().isEmpty() : !state.getManualChests().isEmpty());
        boolean hasCollectorXray = xrayAllowed && settings.collectorOutlinesEnabled && settings.chestXray;
        boolean hasFinderXray = xrayAllowed && state.getChestFinderItemId() != null;
        boolean hasBlockXray = xrayAllowed && settings.highlightEnabled && settings.blockXray && state.hasNeeded();
        return hasContainerXray || hasCollectorXray || hasFinderXray || hasBlockXray;
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
        if (GatherSettings.get().highlightEnabled && !GatherSettings.get().blockXray && state.hasNeeded()) {
            renderNeededBlockHighlights(state, world, client, camPos, consumers, matrices);
        }

        // Chest/collector depth-tested LINES outlines — always drawn; xray fill is separate via WorldRenderer TAIL
        GatherSettings settings = GatherSettings.get();
        if (settings.chestOutlinesEnabled || settings.collectorOutlinesEnabled) {
            renderScannedContainerNormal(state, world, client, camPos, consumers, matrices);
        }

    }

    public static void renderScannedContainerXray(Camera camera) {
        if (!GatherState.isServerXrayAllowed()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;
        if (!GatherSettings.get().enabled) return;

        RenderLayer xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
        if (xrayLayer == null) return;

        GatherState state = GatherState.get();
        GatherSettings settings = GatherSettings.get();
        String finderItemId = state.getChestFinderItemId();

        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer quads = consumers.getBuffer(xrayLayer);
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() - 180.0F));

        Vec3d camPos = camera.getCameraPos();
        BlockPos playerPos = client.player.getBlockPos();
        long now = System.currentTimeMillis();

        // Rebuild cached draw list when dirty or TTL expired (every 2s)
        if (chestDrawDirty || chestDrawList == null || now - chestDrawListBuiltAt > CHEST_DRAW_LIST_TTL_MS) {
            rebuildChestDrawList(world, state, settings, now);
        }

        boolean drew = false;
        int hiddenFar = 0;

        // Render pre-built draw list (auto/manual/collector — cache rebuilt every 2s)
        // Regular chests: xray fill only (no edges — LINES outline drawn in AFTER_ENTITIES, 6.5x cheaper)
        // Collector specs: fill + thick edges when chest xray is enabled (count small, worth keeping for visibility)
        boolean chestXrayOn = settings.chestXray;
        for (DrawSpec spec : chestDrawList) {
            if (!chestXrayOn) continue; // non-xray containers are rendered in AFTER_ENTITIES
            if (isFarChestOutline(playerPos, spec.pos())) { hiddenFar++; continue; }
            drawXrayContainerBox(quads, matrices, camPos, spec.pos(), spec.bounds(), spec.fillArgb(), spec.edgeArgb(), spec.isCollector());
            drew = true;
        }

        // Finder pass — always dynamic, different color per query
        if (finderItemId != null) {
            Set<Long> finderChests = state.getChestsContaining(finderItemId);
            finderDrawn.clear();
            for (long encoded : finderChests) {
                if (finderDrawn.contains(encoded)) continue;
                BlockPos pos = BlockPos.fromLong(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null || !cache.valid()) continue;
                if (isFarChestOutline(playerPos, pos)) { hiddenFar++; continue; }
                markDoubleChestPartner(world, pos, finderDrawn);
                drawXrayContainerBox(quads, matrices, camPos, pos, cache.bounds(), FINDER_XRAY_FILL, FINDER_XRAY_EDGE, false);
                drew = true;
            }
        }

        if (drew) consumers.draw(xrayLayer);
    }

    private static void renderScannedContainerNormal(GatherState state, ClientWorld world,
                                                      MinecraftClient client, Vec3d camPos,
                                                      VertexConsumerProvider consumers, MatrixStack matrices) {
        long now = System.currentTimeMillis();
        GatherSettings settings = GatherSettings.get();
        if (chestDrawDirty || chestDrawList == null || now - chestDrawListBuiltAt > CHEST_DRAW_LIST_TTL_MS) {
            rebuildChestDrawList(world, state, settings, now);
        }
        if (chestDrawList == null || chestDrawList.isEmpty()) return;

        BlockPos playerPos = client.player.getBlockPos();
        VertexConsumer lines = consumers.getBuffer(RenderLayers.LINES);
        boolean collectorEnabled = settings.collectorOutlinesEnabled;
        boolean drew = false;
        for (DrawSpec spec : chestDrawList) {
            if (spec.isCollector() && !collectorEnabled) continue;
            if (isFarChestOutline(playerPos, spec.pos())) continue;
            drawFallbackContainerBox(lines, matrices, camPos, spec.pos(), spec.shape(), spec.edgeArgb());
            drew = true;
        }
        if (drew && consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(RenderLayers.LINES);
        }
    }

    private static void rebuildChestDrawList(ClientWorld world, GatherState state, GatherSettings settings, long now) {
        List<DrawSpec> newList = new ArrayList<>();
        boolean scanAllOn = settings.countChests;
        boolean showChests = settings.chestOutlinesEnabled;
        boolean showCollector = settings.collectorOutlinesEnabled;
        boolean scanMode = state.isChestScanMode();

        Set<Long> collectorPositions = showCollector
                ? new HashSet<>(state.getCollectorPositions().keySet()) : Collections.emptySet();
        collectorShulkerPositions = new HashSet<>(collectorPositions);

        Set<Long> autoChests = (showChests || showCollector) && scanAllOn
                ? state.getTrackedChests() : Collections.emptySet();
        Set<Long> manualChests = (showChests || showCollector) && !scanAllOn
                ? state.getManualChests() : Collections.emptySet();

        Set<Long> drawn = new HashSet<>();
        List<Long> deadAuto = new ArrayList<>(), deadManual = new ArrayList<>();
        boolean hasAnimatingShulker = false;
        boolean hasPendingPlacedContainer = false;
        boolean hasUnresolvedCollectors = false;

        for (long encoded : autoChests) {
            if (drawn.contains(encoded)) continue;
            BlockPos pos = BlockPos.fromLong(encoded);
            ChestXrayCache cache = getChestXrayCache(world, pos);
            if (cache == null) continue;
            if (!cache.valid()) {
                if (isPendingPlacedContainer(encoded, now)) hasPendingPlacedContainer = true;
                else deadAuto.add(encoded);
                continue;
            }
            if (cache.animating()) hasAnimatingShulker = true;
            boolean collectorOutline = collectorPositions.contains(encoded);
            if (!showChests && !(collectorOutline && showCollector)) continue;
            markDoubleChestPartner(world, pos, drawn);
            drawn.add(encoded);
            int fill = collectorOutline ? COLLECTOR_XRAY_FILL : AUTO_XRAY_FILL;
            int edge = collectorOutline ? COLLECTOR_XRAY_EDGE : AUTO_XRAY_EDGE;
            newList.add(new DrawSpec(pos, cache.bounds(), shapeFromBounds(cache.bounds()), fill, edge, collectorOutline));
        }

        for (long encoded : manualChests) {
            if (drawn.contains(encoded)) continue;
            BlockPos pos = BlockPos.fromLong(encoded);
            ChestXrayCache cache = getChestXrayCache(world, pos);
            if (cache == null) continue;
            if (!cache.valid()) {
                if (isPendingPlacedContainer(encoded, now)) hasPendingPlacedContainer = true;
                else deadManual.add(encoded);
                continue;
            }
            if (cache.animating()) hasAnimatingShulker = true;
            boolean collectorOutline = collectorPositions.contains(encoded);
            if (!showChests && !(collectorOutline && showCollector)) continue;
            markDoubleChestPartner(world, pos, drawn);
            drawn.add(encoded);
            int fill = collectorOutline ? COLLECTOR_XRAY_FILL : (scanMode ? MANUAL_SCAN_XRAY_FILL : MANUAL_XRAY_FILL);
            int edge = collectorOutline ? COLLECTOR_XRAY_EDGE : (scanMode ? MANUAL_SCAN_XRAY_EDGE : MANUAL_XRAY_EDGE);
            newList.add(new DrawSpec(pos, cache.bounds(), shapeFromBounds(cache.bounds()), fill, edge, collectorOutline));
        }

        // Standalone collector shulkers not in tracked/manual sets
        if (showCollector) {
            for (long encoded : collectorPositions) {
                if (drawn.contains(encoded)) continue;
                BlockPos pos = BlockPos.fromLong(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null || !cache.valid()) {
                    if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) hasUnresolvedCollectors = true;
                    continue;
                }
                drawn.add(encoded);
                if (cache.animating()) hasAnimatingShulker = true;
                newList.add(new DrawSpec(pos, cache.bounds(), shapeFromBounds(cache.bounds()), COLLECTOR_XRAY_FILL, COLLECTOR_XRAY_EDGE, true));
            }
        }

        deadAuto.forEach(state::removeTrackedChest);
        deadManual.forEach(state::removeManualChest);

        chestDrawList = newList;
        // If collector positions are loaded but block entity not yet synced, retry in 250ms instead of 2s
        chestDrawListBuiltAt = hasAnimatingShulker || hasPendingPlacedContainer
                ? now - CHEST_DRAW_LIST_TTL_MS + SHULKER_ANIMATION_VALIDATE_MS
                : hasUnresolvedCollectors ? now - CHEST_DRAW_LIST_TTL_MS + 250L : now;
        chestDrawDirty = false;
    }

    public static void renderCollectorLabels(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;
        if (!GatherSettings.get().collectorOutlinesEnabled) return;
        if (lastWorldPositionMatrix == null || lastWorldProjectionMatrix == null) return;

        Map<Long, List<String>> collectorPositions = GatherState.get().getCollectorPositions();
        if (collectorPositions.isEmpty()) return;

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        Vec3d playerPos = client.player.getEyePos();

        // Validate shulker block presence at most once per second instead of every frame
        long nowLabel = System.currentTimeMillis();
        if (nowLabel - lastCollectorShulkerLabelValidateMs > COLLECTOR_LABEL_VALIDATE_MS) {
            lastCollectorShulkerLabelValidateMs = nowLabel;
            collectorShulkerValid.keySet().retainAll(collectorPositions.keySet());
            collectorSortedContents.keySet().retainAll(collectorPositions.keySet());
            for (long enc : collectorPositions.keySet()) {
                BlockPos p = BlockPos.fromLong(enc);
                boolean valid = world.getBlockState(p).getBlock() instanceof ShulkerBoxBlock
                        && world.getBlockEntity(p) instanceof ShulkerBoxBlockEntity;
                collectorShulkerValid.put(enc, valid);
                if (valid) {
                    Map<String, Integer> contents = GatherState.get().getCollectorChestContents(enc);
                    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(contents.entrySet());
                    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    int topN = Math.min(3, sorted.size());
                    List<CollectorLabelRow> rows = new ArrayList<>(topN);
                    for (int r = 0; r < topN; r++) {
                        Map.Entry<String, Integer> re = sorted.get(r);
                        rows.add(new CollectorLabelRow(itemLabel(re.getKey()) + " x" + re.getValue(), stackForItemId(re.getKey())));
                    }
                    collectorSortedContents.put(enc, rows);
                } else {
                    collectorSortedContents.remove(enc);
                }
            }
        }

        for (var entry : collectorPositions.entrySet()) {
            long encoded = entry.getKey();
            if (!collectorShulkerValid.getOrDefault(encoded, false)) continue;
            BlockPos pos = BlockPos.fromLong(encoded);
            Vec3d labelPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.35, pos.getZ() + 0.5);
            if (playerPos.squaredDistanceTo(labelPos) > COLLECTOR_LABEL_MAX_DISTANCE_SQ) continue;
            List<CollectorLabelRow> lines = collectorSortedContents.getOrDefault(encoded, List.of());
            if (lines.isEmpty()) continue;

            Vec3d screen = projectToScreen(labelPos, sw, sh);
            if (screen == null) continue;

            double dist = playerPos.distanceTo(labelPos);
            float scale = (float) Math.max(0.65f, Math.min(0.85f, 7.0 / dist));

            int maxWidth = 0;
            for (CollectorLabelRow row : lines) {
                maxWidth = Math.max(maxWidth, client.textRenderer.getWidth(row.label()));
            }

            int lineH = 18;
            int boxW = 22 + maxWidth + 8;
            int boxH = 4 + lines.size() * lineH;
            int left = (int) Math.round(screen.x - boxW / 2.0);
            int top = (int) Math.round(screen.y - boxH - 6);

            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float) screen.x, (float) screen.y);
            matrices.scale(scale, scale);
            matrices.translate((float) -screen.x, (float) -screen.y);
            ctx.fill(left, top, left + boxW, top + boxH, 0xCC101722);
            ctx.fill(left, top, left + boxW, top + 1, 0xFF6B4DFF);
            for (int i = 0; i < lines.size(); i++) {
                int y = top + 3 + i * lineH;
                CollectorLabelRow row = lines.get(i);
                if (!row.stack().isEmpty()) ctx.drawItem(row.stack(), left + 3, y - 1);
                ctx.drawTextWithShadow(client.textRenderer, Text.literal(row.label()), left + 22, y + 4, 0xFFEDE8FF);
            }
            matrices.popMatrix();
        }
    }

    private static Vec3d projectToScreen(Vec3d worldPos, int screenWidth, int screenHeight) {
        if (lastWorldPositionMatrix == null || lastWorldProjectionMatrix == null) return null;
        Vec3d camPos = MinecraftClient.getInstance().getEntityRenderDispatcher().camera.getCameraPos();
        projectVec.set(
                (float) (worldPos.x - camPos.x),
                (float) (worldPos.y - camPos.y),
                (float) (worldPos.z - camPos.z),
                1.0f);
        projectVec.mul(lastWorldPositionMatrix);
        projectVec.mul(lastWorldProjectionMatrix);
        if (projectVec.w <= 0.0f) return null;
        float ndcX = projectVec.x / projectVec.w;
        float ndcY = projectVec.y / projectVec.w;
        if (Math.abs(ndcX) > 1.2f || Math.abs(ndcY) > 1.2f) return null;
        double sx = (ndcX + 1.0f) * 0.5f * screenWidth;
        double sy = (1.0f - ndcY) * 0.5f * screenHeight;
        return new Vec3d(sx, sy, projectVec.z / projectVec.w);
    }

    private static ItemStack stackForItemId(String itemId) {
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        return item == null ? ItemStack.EMPTY : item.getDefaultStack();
    }

    private static String itemLabel(String itemId) {
        if (itemId == null) return "";
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        return item == null ? itemId : item.getName().getString();
    }

    private static List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            if (!line.isBlank()) out.add(line.trim());
        }
        return out;
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
        boolean watchedShulker = isWatchedShulkerAnimation(key, now);
        boolean pendingPlacement = isPendingPlacedContainer(key, now);
        ChestXrayCache cached = chestXrayCache.get(key);
        // Short TTL for invalid entries so newly placed blocks are detected quickly
        long ttl = cached == null ? CHEST_XRAY_VALIDATE_MS
                : !cached.valid() ? (pendingPlacement ? SHULKER_ANIMATION_VALIDATE_MS : 200L)
                : (cached.animating() || watchedShulker || pendingPlacement) ? SHULKER_ANIMATION_VALIDATE_MS
                : CHEST_XRAY_VALIDATE_MS;
        if (cached != null && now - cached.validatedAtMs() < ttl) return cached;
        var be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory)) {
            chestXrayCache.put(key, new ChestXrayCache(pos, null, now, false, false, false));
            return chestXrayCache.get(key);
        }
        boolean collector = be instanceof ShulkerBoxBlockEntity shulker
                && shulker.getComponents().get(DataComponentTypes.CUSTOM_DATA) != null
                && shulker.getComponents().get(DataComponentTypes.CUSTOM_DATA).copyNbt().getBoolean("gather_collector", false);
        boolean animating = be instanceof ShulkerBoxBlockEntity shulker
                && (watchedShulker || shulker.getAnimationStage() != ShulkerBoxBlockEntity.AnimationStage.CLOSED);
        ChestXrayCache fresh = new ChestXrayCache(pos, getInflatedContainerBounds(world, pos), now, true, collector, animating);
        chestXrayCache.put(key, fresh);
        return fresh;
    }

    private static boolean isWatchedShulkerAnimation(long posLong, long now) {
        Long until = shulkerAnimationWatchUntil.get(posLong);
        if (until == null) return false;
        if (until >= now) return true;
        shulkerAnimationWatchUntil.remove(posLong);
        return false;
    }

    private static boolean isPendingPlacedContainer(long posLong, long now) {
        Long until = pendingPlacedContainerUntil.get(posLong);
        if (until == null) return false;
        if (until >= now) return true;
        pendingPlacedContainerUntil.remove(posLong);
        return false;
    }

    private static void drawXrayContainerBox(VertexConsumer quads, MatrixStack matrices,
                                             Vec3d camPos, BlockPos pos, Box bounds, int fillArgb, int edgeArgb,
                                             boolean withEdges) {
        matrices.push();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        drawCuboid(quads, matrix, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, fillArgb);
        if (withEdges) drawContainerEdges(quads, matrix, bounds, edgeArgb);
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

    // shape coords come from cache.bounds() which already includes CONTAINER_OUTLINE_INFLATE
    private static VoxelShape shapeFromBounds(Box b) {
        return VoxelShapes.cuboid(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private static void drawFallbackContainerBox(VertexConsumer lines, MatrixStack matrices,
                                                 Vec3d camPos, BlockPos pos, VoxelShape shape, int lineArgb) {
        matrices.push();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        VertexRendering.drawOutline(matrices, lines, shape, 0.0, 0.0, 0.0, lineArgb, 2.5f);
        matrices.pop();
    }

    private static VoxelShape getHighlightShape(ClientWorld world, BlockPos pos) {
        long key = pos.asLong();
        VoxelShape cached = highlightShapeCache.get(key);
        if (cached != null) return cached;
        BlockState bs = world.getBlockState(pos);
        VoxelShape shape = bs.getOutlineShape(world, pos);
        if (shape.isEmpty()) shape = VoxelShapes.fullCube();
        highlightShapeCache.put(key, shape);
        return shape;
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
        if (!GatherState.isServerXrayAllowed()) return;
        if (!GatherSettings.get().enabled || !GatherSettings.get().highlightEnabled || !GatherSettings.get().blockXray) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        RenderLayer xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
        if (xrayLayer == null) return;

        GatherState state = GatherState.get();
        if (!state.hasNeeded()) return;

        int nodeCount = state.getTotalNodeCount();
        if (nodeCount != cachedNodeCount) {
            cachedNodeCount = nodeCount;
            cachedNeededBlocks = computeNeededBlocks(state);
            cachedHighlights = null;
        }
        if (cachedNeededBlocks.isEmpty()) return;

        BlockPos playerPos = client.player.getBlockPos();
        long now = System.currentTimeMillis();
        syncBlockXrayScanMode(true);
        updateScanJobForPlayer(playerPos, now, cachedHighlights == null);
        processScanJob(world, cachedNeededBlocks);
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return;
        if (now - lastHighlightValidatedMs > HIGHLIGHT_VALIDATE_INTERVAL_MS) {
            lastHighlightValidatedMs = now;
            pruneCachedHighlights(world, cachedNeededBlocks, false);
        }
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
            VoxelShape shape = getHighlightShape(world, pos);
            Box bounds = shape.getBoundingBox()
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
        int nodeCount = state.getTotalNodeCount();
        if (nodeCount != cachedNodeCount) {
            cachedNodeCount = nodeCount;
            cachedNeededBlocks = computeNeededBlocks(state);
            cachedHighlights = null;
        }

        if (cachedNeededBlocks.isEmpty()) return;

        BlockPos playerPos = client.player.getBlockPos();
        long now = System.currentTimeMillis();
        syncBlockXrayScanMode(false);
        updateScanJobForPlayer(playerPos, now, cachedHighlights == null);
        pruneCachedHighlights(world, cachedNeededBlocks, true);
        processScanJob(world, cachedNeededBlocks);

        if (cachedHighlights == null || cachedHighlights.isEmpty()) return;

        if (now - lastHighlightValidatedMs > HIGHLIGHT_VALIDATE_INTERVAL_MS) {
            lastHighlightValidatedMs = now;
            pruneCachedHighlights(world, cachedNeededBlocks, true);
        }

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

        for (BlockPos pos : visibleHighlights(world)) {
            matrices.push();
            matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            matrices.translate(-OUTLINE_EPSILON, -OUTLINE_EPSILON, -OUTLINE_EPSILON);
            matrices.scale(1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f);
            VoxelShape shape = getHighlightShape(world, pos);
            VertexRendering.drawOutline(matrices, consumer, shape, 0.0, 0.0, 0.0, argbColor, 2.5f);
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
        Set<Long> highlightSet = new HashSet<>(highlights.size());
        for (BlockPos pos : highlights) highlightSet.add(pos.asLong());
        if (!preserveExistingHighlights || highlightRampStartedAtMs == 0L) {
            highlightRampStartedAtMs = System.currentTimeMillis();
        }
        scanJob = new ScanJob(origin, radius, clampedVerticalRadius, neededHash,
                buildSectionQueue(origin, radius, clampedVerticalRadius), highlights, highlightSet);
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
            startScanJob(playerPos, cachedNeededBlocks, INITIAL_VERTICAL_SCAN_RADIUS, cachedHighlights != null);
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
            startScanJob(playerPos, cachedNeededBlocks, verticalRadius, cachedHighlights != null);
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
        boolean exposedOnly = !GatherSettings.get().blockXray;
        pruneSectionScanCache(now);
        for (int i = 0; i < budget && !scanJob.pending().isEmpty(); i++) {
            SectionKey section = scanJob.pending().removeFirst();
            if (!world.isChunkLoaded(section.x(), section.z())) continue;
            SectionScanCache cached = sectionScanCache.get(section);
            List<BlockPos> positions;
            if (cached != null
                    && cached.neededHash() == scanJob.neededHash()
                    && cached.exposedOnly() == exposedOnly
                    && now - cached.scannedAtMs() < SECTION_CACHE_TTL_MS) {
                positions = cached.positions();
            } else {
                positions = scanSection(world, section, neededBlocks, exposedOnly);
                sectionScanCache.put(section, new SectionScanCache(scanJob.neededHash(), exposedOnly, now, positions));
            }
            addPositionsInRadius(scanJob.highlights(), positions, scanJob.origin(),
                    scanJob.radius(), scanJob.verticalRadius(), scanJob.highlightSet());
        }
        pruneCachedHighlights(world, neededBlocks, exposedOnly);
        applyHighlightLimit(scanJob.highlights(), scanJob.highlightSet(), scanJob.origin());
        cachedHighlights = scanJob.highlights();
        if (scanJob.pending().isEmpty()) scanJob = null;
    }

    private static void pruneSectionScanCache(long now) {
        if (now - lastSectionCachePruneMs < SECTION_CACHE_PRUNE_INTERVAL_MS) return;
        lastSectionCachePruneMs = now;
        sectionScanCache.entrySet().removeIf(entry -> now - entry.getValue().scannedAtMs() >= SECTION_CACHE_TTL_MS);
    }

    private static List<BlockPos> scanSection(ClientWorld world, SectionKey section,
                                              Set<Block> neededBlocks, boolean exposedOnly) {
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
                    if (neededBlocks.contains(world.getBlockState(mutable).getBlock())
                            && (!exposedOnly || !isHiddenUnderground(world, mutable))) {
                        result.add(mutable.toImmutable());
                    }
                }
            }
        }
        return result;
    }

    private static void syncBlockXrayScanMode(boolean blockXray) {
        if (cachedBlockXrayMode != null && cachedBlockXrayMode == blockXray) return;
        cachedBlockXrayMode = blockXray;
        cachedHighlights = null;
        scanJob = null;
        sectionScanCache.clear();
        highlightShapeCache.clear();
        highlightRampStartedAtMs = 0L;
        lastHighlightValidatedMs = 0L;
        lastScanTime = 0L;
        retainSetBuf.clear();
    }

    private static void pruneCachedHighlights(ClientWorld world, Set<Block> neededBlocks, boolean exposedOnly) {
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return;
        cachedHighlights.removeIf(pos -> !neededBlocks.contains(world.getBlockState(pos).getBlock())
                || (exposedOnly && isHiddenUnderground(world, pos)));
        retainSetBuf.clear();
        for (BlockPos pos : cachedHighlights) retainSetBuf.add(pos.asLong());
        if (scanJob != null) scanJob.highlightSet().retainAll(retainSetBuf);
        highlightShapeCache.keySet().retainAll(retainSetBuf);
        retainSetBuf.clear();
    }

    private static void addPositionsInRadius(List<BlockPos> result, List<BlockPos> positions,
                                             BlockPos origin, int radius, int verticalRadius,
                                             Set<Long> existing) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        for (BlockPos pos : positions) {
            if (Math.abs(pos.getX() - ox) > radius) continue;
            if (Math.abs(pos.getY() - oy) > verticalRadius) continue;
            if (Math.abs(pos.getZ() - oz) > radius) continue;
            if (existing.add(pos.asLong())) result.add(pos);
        }
    }

    private static void applyHighlightLimit(List<BlockPos> result, Set<Long> existing, BlockPos origin) {
        int limit = GatherSettings.get().maxBlockHighlights;
        if (limit > 0 && result.size() > limit) {
            int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
            result.sort((a, b) -> Integer.compare(
                    highlightDistanceScore(a, ox, oy, oz),
                    highlightDistanceScore(b, ox, oy, oz)));
            for (int i = limit; i < result.size(); i++) existing.remove(result.get(i).asLong());
            result.subList(limit, result.size()).clear();
        }
    }

    private static List<BlockPos> visibleHighlights() {
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return List.of();
        int limit = currentVisibleHighlightLimit();
        if (limit >= cachedHighlights.size()) return cachedHighlights;
        return cachedHighlights.subList(0, limit);
    }

    private static List<BlockPos> visibleHighlights(ClientWorld world) {
        if (cachedHighlights == null || cachedHighlights.isEmpty()) return List.of();
        if (GatherSettings.get().blockXray) return visibleHighlights();
        int limit = currentVisibleHighlightLimit();
        List<BlockPos> exposed = new ArrayList<>();
        for (BlockPos pos : cachedHighlights) {
            if (!isHiddenUnderground(world, pos)) {
                exposed.add(pos);
                if (exposed.size() >= limit) break;
            }
        }
        return exposed;
    }

    private static boolean isHiddenUnderground(ClientWorld world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (!world.getBlockState(neighbor).isOpaqueFullCube()) return false;
        }
        return true;
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
