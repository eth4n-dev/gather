package com.gather.client.mixin;

import com.gather.client.WorldHighlightRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void gather_renderScannedContainerXray(GraphicsResourceAllocator allocator,
                                                   DeltaTracker tickCounter,
                                                   boolean renderBlockOutline,
                                                   CameraRenderState cameraState,
                                                   Matrix4fc positionMatrix,
                                                   GpuBufferSlice fogBuffer,
                                                   Vector4f fogColor,
                                                   boolean renderSky,
                                                   ChunkSectionsToRender sectionsToRender,
                                                   CallbackInfo ci) {
        WorldHighlightRenderer.captureWorldMatrices(new Matrix4f(positionMatrix), cameraState.projectionMatrix);
        if (!WorldHighlightRenderer.shouldRenderTailXray()) return;
        WorldHighlightRenderer.renderScannedContainerXray();
        WorldHighlightRenderer.renderNeededBlockXray();
    }
}
