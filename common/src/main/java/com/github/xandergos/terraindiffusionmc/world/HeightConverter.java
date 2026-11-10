package com.github.xandergos.terraindiffusionmc.world;

// Where a height in metres falls among a world's blocks
public final class HeightConverter {
    public static final int SEA_LEVEL = 63;

    // The sea floor is squeezed so that no depth reaches this many blocks below sea level
    private static final float MAX_OCEAN_DEPTH_BLOCKS = 196f;

    private HeightConverter() {
    }

    // The Y of the block holding a height of metres above sea level, in a world whose blocks stand for blockM
    // metres. Heights count in whole metres.
    public static int toY(float metres, float blockM) {
        float whole = (float) Math.floor(metres);
        if (whole >= 0f) {
            return (int) (whole / blockM) + SEA_LEVEL;
        }
        float depthBlocks = -whole / blockM;
        float squeezed = MAX_OCEAN_DEPTH_BLOCKS * depthBlocks / (depthBlocks + MAX_OCEAN_DEPTH_BLOCKS);
        return -((int) squeezed) - 1 + SEA_LEVEL;
    }
}
