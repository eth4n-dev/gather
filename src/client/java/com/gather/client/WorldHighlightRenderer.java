package com.gather.client;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
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
    private record ChestXrayCache(BlockPos pos, AABB bounds, long validatedAtMs, boolean valid, boolean isCollector, boolean animating) {}
    private record DrawSpec(BlockPos pos, AABB bounds, VoxelShape shape, int fillArgb, int edgeArgb, boolean isCollector) {}
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
        BlockPos p = BlockPos.of(posLong);
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
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(WorldHighlightRenderer::onAfterEntities);
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

    private static void onAfterEntities(LevelRenderContext ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) return;

        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;
        if (!GatherSettings.get().enabled) return;

        GatherState state = GatherState.get();
        Vec3 camPos = minecraft.getEntityRenderDispatcher().camera.position();
        PoseStack matrices = ctx.poseStack();

        // Needed block outlines (depth-tested LINES — skipped when blockXray is on, which uses no-depth quads)
        if (GatherSettings.get().highlightEnabled && !GatherSettings.get().blockXray && state.hasNeeded()) {
            renderNeededBlockHighlights(state, world, minecraft, camPos, consumers, matrices);
        }

        // Chest/collector depth-tested LINES outlines — always drawn; xray fill is separate via WorldRenderer TAIL
        GatherSettings settings = GatherSettings.get();
        if (settings.chestOutlinesEnabled || settings.collectorOutlinesEnabled) {
            renderScannedContainerNormal(state, world, minecraft, camPos, consumers, matrices);
        }

    }

    public static void renderScannedContainerXray() {
        if (!GatherState.isServerXrayAllowed()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) return;
        if (!GatherSettings.get().enabled) return;

        RenderType xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
        if (xrayLayer == null) return;

        GatherState state = GatherState.get();
        GatherSettings settings = GatherSettings.get();
        String finderItemId = state.getChestFinderItemId();

        net.minecraft.client.Camera camera = minecraft.getEntityRenderDispatcher().camera;
        MultiBufferSource.BufferSource consumers = minecraft.renderBuffers().bufferSource();
        VertexConsumer quads = consumers.getBuffer(xrayLayer);
        PoseStack matrices = new PoseStack();
        matrices.mulPose(Axis.XP.rotationDegrees(camera.xRot()));
        matrices.mulPose(Axis.YP.rotationDegrees(camera.yRot() - 180.0F));

        Vec3 camPos = camera.position();
        BlockPos playerPos = minecraft.player.blockPosition();
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
                BlockPos pos = BlockPos.of(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null || !cache.valid()) continue;
                if (isFarChestOutline(playerPos, pos)) { hiddenFar++; continue; }
                markDoubleChestPartner(world, pos, finderDrawn);
                drawXrayContainerBox(quads, matrices, camPos, pos, cache.bounds(), FINDER_XRAY_FILL, FINDER_XRAY_EDGE, false);
                drew = true;
            }
        }

        if (drew) consumers.endBatch(xrayLayer);
    }

    private static void renderScannedContainerNormal(GatherState state, ClientLevel world,
                                                      Minecraft minecraft, Vec3 camPos,
                                                      MultiBufferSource consumers, PoseStack matrices) {
        long now = System.currentTimeMillis();
        GatherSettings settings = GatherSettings.get();
        if (chestDrawDirty || chestDrawList == null || now - chestDrawListBuiltAt > CHEST_DRAW_LIST_TTL_MS) {
            rebuildChestDrawList(world, state, settings, now);
        }
        if (chestDrawList == null || chestDrawList.isEmpty()) return;

        BlockPos playerPos = minecraft.player.blockPosition();
        VertexConsumer lines = consumers.getBuffer(RenderTypes.LINES);
        boolean collectorEnabled = settings.collectorOutlinesEnabled;
        boolean drew = false;
        for (DrawSpec spec : chestDrawList) {
            if (spec.isCollector() && !collectorEnabled) continue;
            if (isFarChestOutline(playerPos, spec.pos())) continue;
            drawFallbackContainerBox(lines, matrices, camPos, spec.pos(), spec.shape(), spec.edgeArgb());
            drew = true;
        }
        if (drew && consumers instanceof MultiBufferSource.BufferSource immediate) {
            immediate.endBatch(RenderTypes.LINES);
        }
    }

    private static void rebuildChestDrawList(ClientLevel world, GatherState state, GatherSettings settings, long now) {
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
            BlockPos pos = BlockPos.of(encoded);
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
            BlockPos pos = BlockPos.of(encoded);
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
                BlockPos pos = BlockPos.of(encoded);
                ChestXrayCache cache = getChestXrayCache(world, pos);
                if (cache == null || !cache.valid()) {
                    if (world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) hasUnresolvedCollectors = true;
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

    public static void renderCollectorLabels(GuiGraphicsExtractor ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) return;
        if (!GatherSettings.get().collectorOutlinesEnabled) return;
        if (lastWorldPositionMatrix == null || lastWorldProjectionMatrix == null) return;

        Map<Long, List<String>> collectorPositions = GatherState.get().getCollectorPositions();
        if (collectorPositions.isEmpty()) return;

        int sw = minecraft.getWindow().getGuiScaledWidth();
        int sh = minecraft.getWindow().getGuiScaledHeight();
        Vec3 playerPos = minecraft.player.getEyePosition();

        // Validate shulker block presence at most once per second instead of every frame
        long nowLabel = System.currentTimeMillis();
        if (nowLabel - lastCollectorShulkerLabelValidateMs > COLLECTOR_LABEL_VALIDATE_MS) {
            lastCollectorShulkerLabelValidateMs = nowLabel;
            collectorShulkerValid.keySet().retainAll(collectorPositions.keySet());
            collectorSortedContents.keySet().retainAll(collectorPositions.keySet());
            for (long enc : collectorPositions.keySet()) {
                BlockPos p = BlockPos.of(enc);
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
            BlockPos pos = BlockPos.of(encoded);
            Vec3 labelPos = new Vec3(pos.getX() + 0.5, pos.getY() + 1.35, pos.getZ() + 0.5);
            if (playerPos.distanceToSqr(labelPos) > COLLECTOR_LABEL_MAX_DISTANCE_SQ) continue;
            List<CollectorLabelRow> lines = collectorSortedContents.getOrDefault(encoded, List.of());
            if (lines.isEmpty()) continue;

            Vec3 screen = projectToScreen(labelPos, sw, sh);
            if (screen == null) continue;

            double dist = playerPos.distanceTo(labelPos);
            float scale = (float) Math.max(0.65f, Math.min(0.85f, 7.0 / dist));

            int maxWidth = 0;
            for (CollectorLabelRow row : lines) {
                maxWidth = Math.max(maxWidth, minecraft.font.width(row.label()));
            }

            int lineH = 18;
            int boxW = 22 + maxWidth + 8;
            int boxH = 4 + lines.size() * lineH;
            int left = (int) Math.round(screen.x - boxW / 2.0);
            int top = (int) Math.round(screen.y - boxH - 6);

            var matrices = ctx.pose();
            matrices.pushMatrix();
            matrices.translate((float) screen.x, (float) screen.y);
            matrices.scale(scale, scale);
            matrices.translate((float) -screen.x, (float) -screen.y);
            GatherTheme.fill(ctx, left, top, left + boxW, top + boxH, 0xCC101722);
            GatherTheme.fill(ctx, left, top, left + boxW, top + 1, 0xFF6B4DFF);
            for (int i = 0; i < lines.size(); i++) {
                int y = top + 3 + i * lineH;
                CollectorLabelRow row = lines.get(i);
                if (!row.stack().isEmpty()) ctx.item(row.stack(), left + 3, y - 1);
                ctx.text(minecraft.font, Component.literal(row.label()), left + 22, y + 4, 0xFFEDE8FF);
            }
            matrices.popMatrix();
        }
    }

    private static Vec3 projectToScreen(Vec3 worldPos, int screenWidth, int screenHeight) {
        if (lastWorldPositionMatrix == null || lastWorldProjectionMatrix == null) return null;
        Vec3 camPos = Minecraft.getInstance().getEntityRenderDispatcher().camera.position();
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
        return new Vec3(sx, sy, projectVec.z / projectVec.w);
    }

    private static ItemStack stackForItemId(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    private static String itemLabel(String itemId) {
        if (itemId == null) return "";
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return item == null ? itemId : com.gather.client.GatherUi.itemName(item).getString();
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
        return playerPos.distSqr(chestPos) > CHEST_XRAY_MAX_DISTANCE_SQ;
    }

    private static void showFarChestNotice(Minecraft minecraft, int hiddenCount) {
        long now = System.currentTimeMillis();
        if (now - lastFarChestNoticeMs < FAR_CHEST_NOTICE_MS) return;
        lastFarChestNoticeMs = now;
        String label = hiddenCount == 1 ? "1 far chest outline hidden" : hiddenCount + " far chest outlines hidden";
        minecraft.player.sendOverlayMessage(Component.literal("Gather: " + label + " because it is over 100 blocks away. Contents still count."));
    }

    private static ChestXrayCache getChestXrayCache(ClientLevel world, BlockPos pos) {
        if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
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
        if (!(be instanceof Container)) {
            chestXrayCache.put(key, new ChestXrayCache(pos, null, now, false, false, false));
            return chestXrayCache.get(key);
        }
        boolean collector = be instanceof ShulkerBoxBlockEntity shulker
                && shulker.components().get(DataComponents.CUSTOM_DATA) != null
                && shulker.components().get(DataComponents.CUSTOM_DATA).copyTag().getBooleanOr("gather_collector", false);
        boolean animating = be instanceof ShulkerBoxBlockEntity shulker
                && (watchedShulker || watchedShulker);
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

    private static void drawXrayContainerBox(VertexConsumer quads, PoseStack matrices,
                                             Vec3 camPos, BlockPos pos, AABB bounds, int fillArgb, int edgeArgb,
                                             boolean withEdges) {
        matrices.pushPose();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        Matrix4f matrix = matrices.last().pose();
        drawCuboid(quads, matrix, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, fillArgb);
        if (withEdges) drawContainerEdges(quads, matrix, bounds, edgeArgb);
        matrices.popPose();
    }

    private static void drawContainerEdges(VertexConsumer quads, Matrix4f matrix, AABB bounds, int argb) {
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
        quads.addVertex(matrix, (float) x, (float) y, (float) z).setColor(argb);
    }

    // shape coords come from cache.bounds() which already includes CONTAINER_OUTLINE_INFLATE
    private static VoxelShape shapeFromBounds(AABB b) {
        return Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private static void drawFallbackContainerBox(VertexConsumer lines, PoseStack matrices,
                                                 Vec3 camPos, BlockPos pos, VoxelShape shape, int lineArgb) {
        matrices.pushPose();
        matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
        ShapeRenderer.renderShape(matrices, lines, shape, 0.0, 0.0, 0.0, lineArgb, 2.5f);
        matrices.popPose();
    }

    private static VoxelShape getHighlightShape(ClientLevel world, BlockPos pos) {
        long key = pos.asLong();
        VoxelShape cached = highlightShapeCache.get(key);
        if (cached != null) return cached;
        BlockState bs = world.getBlockState(pos);
        VoxelShape shape = bs.getShape(world, pos);
        if (shape.isEmpty()) shape = Shapes.block();
        highlightShapeCache.put(key, shape);
        return shape;
    }

    private static VoxelShape getContainerShape(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getShape(world, pos);
        return shape.isEmpty() ? Shapes.block() : shape;
    }

    private static AABB getInflatedContainerBounds(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction facing = state.getValue(ChestBlock.FACING);
                Direction neighborDir = (chestType == ChestType.LEFT)
                        ? facing.getClockWise()
                        : facing.getCounterClockWise();
                BlockPos neighborPos = pos.relative(neighborDir);
                if (world.getBlockState(neighborPos).getBlock() instanceof ChestBlock) {
                    AABB ownBounds = getContainerShape(world, pos).bounds();
                    AABB neighborBounds = getContainerShape(world, neighborPos).bounds()
                            .move(neighborDir.getStepX(), neighborDir.getStepY(), neighborDir.getStepZ());
                    return ownBounds.minmax(neighborBounds).inflate(CONTAINER_OUTLINE_INFLATE);
                }
            }
        }
        return getContainerShape(world, pos).bounds().inflate(CONTAINER_OUTLINE_INFLATE);
    }

    private static void markDoubleChestPartner(ClientLevel world, BlockPos pos, Set<Long> drawn) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return;
        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) return;
        Direction facing = state.getValue(ChestBlock.FACING);
        Direction neighborDir = (chestType == ChestType.LEFT)
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        drawn.add(pos.relative(neighborDir).asLong());
    }

    // ─── NEEDED BLOCK HIGHLIGHTS ─────────────────────────────────────────────

    public static void renderNeededBlockXray() {
        if (!GatherState.isServerXrayAllowed()) return;
        if (!GatherSettings.get().enabled || !GatherSettings.get().highlightEnabled || !GatherSettings.get().blockXray) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) return;

        RenderType xrayLayer = WhereIsItCompat.debugQuadsNoDepth();
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

        BlockPos playerPos = minecraft.player.blockPosition();
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

        net.minecraft.client.Camera camera = minecraft.getEntityRenderDispatcher().camera;
        MultiBufferSource.BufferSource consumers = minecraft.renderBuffers().bufferSource();
        VertexConsumer quads = consumers.getBuffer(xrayLayer);
        PoseStack matrices = new PoseStack();
        matrices.mulPose(Axis.XP.rotationDegrees(camera.xRot()));
        matrices.mulPose(Axis.YP.rotationDegrees(camera.yRot() - 180.0F));

        Vec3 camPos = camera.position();
        boolean drew = false;
        for (BlockPos pos : visibleHighlights()) {
            VoxelShape shape = getHighlightShape(world, pos);
            AABB bounds = shape.bounds()
                    .inflate(OUTLINE_EPSILON)
                    .move(pos.getX(), pos.getY(), pos.getZ());
            matrices.pushPose();
            matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            Matrix4f matrix = matrices.last().pose();
            AABB local = bounds.move(-pos.getX(), -pos.getY(), -pos.getZ());
            drawContainerEdges(quads, matrix, local, edgeColor);
            matrices.popPose();
            drew = true;
        }
        if (drew) consumers.endBatch(xrayLayer);
    }

    private static void renderNeededBlockHighlights(GatherState state, ClientLevel world,
                                                    Minecraft minecraft, Vec3 camPos,
                                                    MultiBufferSource consumers, PoseStack matrices) {
        int nodeCount = state.getTotalNodeCount();
        if (nodeCount != cachedNodeCount) {
            cachedNodeCount = nodeCount;
            cachedNeededBlocks = computeNeededBlocks(state);
            cachedHighlights = null;
        }

        if (cachedNeededBlocks.isEmpty()) return;

        BlockPos playerPos = minecraft.player.blockPosition();
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
        VertexConsumer consumer = consumers.getBuffer(RenderTypes.LINES);

        for (BlockPos pos : visibleHighlights(world)) {
            matrices.pushPose();
            matrices.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            matrices.translate(-OUTLINE_EPSILON, -OUTLINE_EPSILON, -OUTLINE_EPSILON);
            matrices.scale(1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f,
                    1.0f + OUTLINE_EPSILON * 2.0f);
            VoxelShape shape = getHighlightShape(world, pos);
            ShapeRenderer.renderShape(matrices, consumer, shape, 0.0, 0.0, 0.0, argbColor, 2.5f);
            matrices.popPose();
        }
        if (consumers instanceof MultiBufferSource.BufferSource immediate) {
            immediate.endBatch(RenderTypes.LINES);
        }
    }

    private static Set<Block> computeNeededBlocks(GatherState state) {
        Set<Block> result = new HashSet<>();
        for (String itemId : state.getChestScanTargets(id -> 0)) {
            if (state.isBaseMaterialHidden(itemId)) continue;
            Identifier id = Identifier.parse(itemId);
            Item item = BuiltInRegistries.ITEM.getValue(id);
            addOreSourceBlocks(result, id);
            if (!(item instanceof BlockItem bi)) continue;
            result.add(bi.getBlock());

            String path = id.getPath();
            for (String prefix : GatherState.WOOD_PREFIXES) {
                if (path.startsWith(prefix + "_")) {
                    String suffix = path.substring(prefix.length());
                    for (String other : GatherState.WOOD_PREFIXES) {
                        Item variant = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", other + suffix));
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
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
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

        boolean movedEnough = lastPlayerPos == null || lastPlayerPos.distManhattan(playerPos) > 8;
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

    private static void processScanJob(ClientLevel world, Set<Block> neededBlocks) {
        if (scanJob == null) return;
        int budget = Math.max(1, GatherSettings.get().highlightScanBudget);
        long now = System.currentTimeMillis();
        boolean exposedOnly = !GatherSettings.get().blockXray;
        pruneSectionScanCache(now);
        for (int i = 0; i < budget && !scanJob.pending().isEmpty(); i++) {
            SectionKey section = scanJob.pending().removeFirst();
            if (!world.getChunkSource().hasChunk(section.x(), section.z())) continue;
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

    private static List<BlockPos> scanSection(ClientLevel world, SectionKey section,
                                              Set<Block> neededBlocks, boolean exposedOnly) {
        List<BlockPos> result = new ArrayList<>();
        int minX = section.x() * 16;
        int maxX = minX + 15;
        int minY = section.y() * 16;
        int maxY = minY + 15;
        int minZ = section.z() * 16;
        int maxZ = minZ + 15;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (neededBlocks.contains(world.getBlockState(mutable).getBlock())
                            && (!exposedOnly || !isHiddenUnderground(world, mutable))) {
                        result.add(mutable.immutable());
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

    private static void pruneCachedHighlights(ClientLevel world, Set<Block> neededBlocks, boolean exposedOnly) {
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

    private static List<BlockPos> visibleHighlights(ClientLevel world) {
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

    private static boolean isHiddenUnderground(ClientLevel world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (!world.getBlockState(pos.relative(dir)).canOcclude()) return false;
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
