package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

public class HeightConverter {
    public static final int SEA_LEVEL = 63;

    private static final float MAX_OCEAN_DEPTH_BLOCKS = 196f;

    private static float getResolutionForScale(int configuredScale) {
        return WorldPipelineModelConfig.nativeResolution() / WorldScaleManager.clampScale(configuredScale);
    }

    public static int convertToMinecraftHeight(short meters) {
        return convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale());
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        int baseY;
        float resolution = getResolutionForScale(configuredScale);

        if (meters >= 0) {
            baseY = (int) (meters / resolution);
        } else {
            float depthBlocks = Math.abs((float) meters) / resolution;
            float limitedDepth = MAX_OCEAN_DEPTH_BLOCKS * depthBlocks
                    / (depthBlocks + MAX_OCEAN_DEPTH_BLOCKS);
            baseY = -((int) limitedDepth) - 1;
        }

        return baseY + SEA_LEVEL;
    }
}
