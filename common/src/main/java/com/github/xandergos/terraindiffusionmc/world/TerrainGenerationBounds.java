package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.BlockWater;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

/** How high a chunk's terrain and water reach, so the fill stops there. */
public final class TerrainGenerationBounds {
    private TerrainGenerationBounds() {}

    // The highest block of ground, water or falling water in the chunk whose corner is block (x0, z0)
    public static int upperY(HeightmapData data, int x0, int z0, int seaLevel) {
        int highest = seaLevel;
        for (int z = z0; z < z0 + 16; z++) {
            for (int x = x0; x < x0 + 16; x++) {
                int i = data.index(x, z);
                int ground = data.surfaceY[i];
                highest = Math.max(highest, ground);
                int water = ground < seaLevel ? seaLevel : Integer.MIN_VALUE;
                if (data.waterY[i] != HeightmapData.NO_WATER) {
                    water = data.waterY[i];
                    highest = Math.max(highest, water);
                }
                // A curtain falls from up to its height above the water below it
                int flow = data.waterFlow[i] & 0xff;
                if (flow >= BlockWater.CURTAIN && water != Integer.MIN_VALUE) {
                    highest = Math.max(highest, water + flow - BlockWater.CURTAIN - 1);
                }
            }
        }
        return highest;
    }

    // How many of the generator's cells, counted up from minCellY, reach upperY
    public static int cellCount(long upperY, int minCellY, int cellHeight, int original) {
        long cells = Math.floorDiv(upperY, cellHeight) - minCellY + 1;
        return (int) Math.min(original, Math.max(1L, cells));
    }
}
