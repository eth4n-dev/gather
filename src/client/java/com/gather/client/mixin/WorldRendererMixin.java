package com.gather.client.mixin;

import com.gather.client.WorldHighlightRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void gather_renderScannedContainerXray(ObjectAllocator allocator,
                                                   RenderTickCounter tickCounter,
                                                   boolean renderBlockOutline,
                                                   Camera camera,
                                                   Matrix4f positionMatrix,
                                                   Matrix4f basicProjectionMatrix,
                                                   Matrix4f projectionMatrix,
                                                   GpuBufferSlice fogBuffer,
                                                   Vector4f fogColor,
                                                   boolean renderSky,
                                                   CallbackInfo ci) {
        WorldHighlightRenderer.captureWorldMatrices(positionMatrix, projectionMatrix);
        if (!WorldHighlightRenderer.shouldRenderTailXray()) return;
        WorldHighlightRenderer.renderScannedContainerXray(camera);
        WorldHighlightRenderer.renderNeededBlockXray(camera);
    }
}
