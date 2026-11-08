package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Conservative terrain/fluid ceiling; unsupported data keeps the full generation range. */
public final class TerrainGenerationBounds {
    // The marker's enum is not public; compare with the exact vanilla marker instead.
    private static final DensityFunctions.MarkerOrMarked CELL_CACHE_MARKER =
            (DensityFunctions.MarkerOrMarked) DensityFunctions.cacheAllInCell(DensityFunctions.zero());
    private TerrainGenerationBounds() {}

    // The terrain-diffusion density a final density is, through holders and a cell cache; null if it is anything else
    public static TerrainDiffusionDensityFunction terrain(DensityFunction density) {
        while (true) {
            if (density instanceof DensityFunctions.HolderHolder holder) density = holder.function().value();
            else if (density.getClass() == CELL_CACHE_MARKER.getClass()
                    && density instanceof DensityFunctions.MarkerOrMarked marker
                    && marker.type() == CELL_CACHE_MARKER.type())
                density = marker.wrapped();
            else return density.getClass() == TerrainDiffusionDensityFunction.class
                    ? (TerrainDiffusionDensityFunction) density : null;
        }
    }

    public static long upperY(HeightmapData data, int x0, int z0, int scale, int seaLevel) {
        if (data == null || data.heightmap == null || x0 < 0 || z0 < 0
                || x0 + 16 > data.width || z0 + 16 > data.height
                || data.heightmap.length < z0 + 16) return Long.MAX_VALUE;
        long highest = seaLevel;
        for (int z = z0; z < z0 + 16; z++) {
            if (data.heightmap[z] == null || data.heightmap[z].length < x0 + 16)
                return Long.MAX_VALUE;
            if (data.waterLevel != null && (data.waterLevel.length <= z
                    || data.waterLevel[z] == null || data.waterLevel[z].length < x0 + 16))
                return Long.MAX_VALUE;
            for (int x = x0; x < x0 + 16; x++) {
                int ground = data.surfaceY[z * data.width + x];
                // Avoid the density function's integer-subtraction overflow for exotic scales/models.
                if (ground < DimensionType.MIN_Y || ground > DimensionType.MAX_Y) return Long.MAX_VALUE;
                highest = Math.max(highest, ground);
                if (data.waterLevel != null && data.waterLevel[z][x] != HeightmapData.NO_WATER) {
                    int water = HeightConverter.convertToMinecraftHeight(data.waterLevel[z][x], scale);
                    if (water < DimensionType.MIN_Y || water > DimensionType.MAX_Y) return Long.MAX_VALUE;
                    highest = Math.max(highest, water);
                }
            }
        }
        return highest;
    }

    public static int cellCount(long upperY, int minCellY, int cellHeight, int original) {
        if (upperY == Long.MAX_VALUE || cellHeight <= 0 || original <= 0) return original;
        long cells = Math.floorDiv(upperY, cellHeight) - minCellY + 1;
        return (int) Math.min(original, Math.max(1L, cells));
    }
}
