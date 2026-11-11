package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.carver.CarvingContext;

// Where vanilla's carvers start their caves and ravines. Vanilla measures those heights from sea level, the
// level its land lies at. Here they are measured from the ground wherever it stands above the sea, so ground at
// any height has the caves, and the cave mouths, vanilla gives ground at sea level. Ravines, which cut the
// broad, gentle land of a scaled world as long open gashes, start only RAVINE_SHARE as often as vanilla's.
public final class CarvedCaves {

    private static final float RAVINE_SHARE = 0.25f;

    private CarvedCaves() {
    }

    private static boolean carvesTerrain(CarvingContext context) {
        return ((TerrainCarving) (Object) context).terrainDiffusion$carvesTerrain();
    }

    // The start height for a carver starting in a chunk, from the height its configuration gave
    public static int startY(int y, CarvingContext context, ChunkPos chunk) {
        if (!carvesTerrain(context)) {
            return y;
        }
        int ground = TerrainWater.groundY(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        return y + Math.max(0, ground - HeightConverter.SEA_LEVEL);
    }

    // Whether a ravine its configuration started goes on to be carved. The draw comes from the ravine's own random,
    // seeded by the chunk it starts in, so every chunk it would reach agrees.
    public static boolean ravineCarved(CarvingContext context, RandomSource random) {
        return !carvesTerrain(context) || random.nextFloat() < RAVINE_SHARE;
    }
}
