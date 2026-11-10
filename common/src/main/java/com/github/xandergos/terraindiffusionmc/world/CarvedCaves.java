package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.carver.CarvingContext;

// Where vanilla's carvers start their caves and ravines. Vanilla measures those heights from sea level, the
// level its land lies at. Here they are measured from the ground wherever it stands above the sea, so ground at
// any height has the caves, and the cave mouths, vanilla gives ground at sea level.
public final class CarvedCaves {

    private CarvedCaves() {
    }

    // The start height for a carver starting in a chunk, from the height its configuration gave
    public static int startY(int y, CarvingContext context, ChunkPos chunk) {
        if (!((TerrainCarving) (Object) context).terrainDiffusion$carvesTerrain()) {
            return y;
        }
        int ground = TerrainWater.groundY(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        return y + Math.max(0, ground - HeightConverter.SEA_LEVEL);
    }
}
