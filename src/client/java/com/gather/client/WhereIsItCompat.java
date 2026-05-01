package com.gather.client;

import net.minecraft.client.render.RenderLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class WhereIsItCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Gather/WhereIsItCompat");

    private static boolean lookedUp;
    private static RenderLayer debugQuadsNoDepth;

    private WhereIsItCompat() {
    }

    static RenderLayer debugQuadsNoDepth() {
        if (lookedUp) return debugQuadsNoDepth;
        lookedUp = true;

        try {
            Class<?> pipelines = Class.forName("red.jackf.whereisit.client.render.WhereIsItPipelines");
            Field layerField = pipelines.getField("DEBUG_QUADS_NO_DEPTH");
            Object layer = layerField.get(null);

            if (layer == null) {
                Field pipelineField = pipelines.getField("DEBUG_QUADS_NO_DEPTH_PIPELINE");
                if (pipelineField.get(null) != null) {
                    Method init = pipelines.getMethod("initRenderTypes");
                    init.invoke(null);
                    layer = layerField.get(null);
                }
            }

            if (layer instanceof RenderLayer renderLayer) {
                debugQuadsNoDepth = renderLayer;
                LOGGER.info("Using WhereIsIt DEBUG_QUADS_NO_DEPTH RenderLayer for scanned chest xray.");
            } else {
                LOGGER.warn("WhereIsIt is present, but DEBUG_QUADS_NO_DEPTH was not available; scanned chest xray will use normal outline fallback.");
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            LOGGER.warn("WhereIsIt xray RenderLayer was not found; scanned chest xray will use normal outline fallback.");
            debugQuadsNoDepth = null;
        }

        return debugQuadsNoDepth;
    }
}
