package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.DeepCaverns;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public class TerrainDiffusionDensityFunction implements DensityFunction.SimpleFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("caves", Boolean.TRUE).forGetter(f -> f.caves)
            ).apply(instance, TerrainDiffusionDensityFunction::new));

    // Blocks of ground a cave keeps over it and beside it; under the sea floor, and over a deep cavern, more
    private static final int CAVE_ROOF = 5;
    private static final int SEA_FLOOR_ROOF = 8;
    private static final int DEEP_CLEARANCE = 8;

    // The terrain-diffusion density a final density is, through holders and cache markers; null if it is
    // anything else
    public static TerrainDiffusionDensityFunction unwrap(DensityFunction density) {
        while (true) {
            if (density instanceof DensityFunctions.HolderHolder holder) {
                density = holder.function().value();
            } else if (density instanceof DensityFunctions.MarkerOrMarked marker) {
                density = marker.wrapped();
            } else {
                return density instanceof TerrainDiffusionDensityFunction terrain ? terrain : null;
            }
        }
    }

    // The density a terrain-diffusion world generates from. Its noise settings are the mod's own: this density
    // alone as the final density, with no aquifers or ore veins, so each block is solid, the fluid picker's fluid
    // or air, and a chunk can be filled a column at a time
    public static TerrainDiffusionDensityFunction of(NoiseGeneratorSettings settings) {
        TerrainDiffusionDensityFunction terrain = unwrap(settings.noiseRouter().finalDensity());
        if (terrain == null || settings.isAquifersEnabled() || settings.oreVeinsEnabled()) {
            throw new IllegalStateException("A Terrain Diffusion world must use the mod's noise settings: its own "
                    + "density as the final density, with aquifers and ore veins off");
        }
        return terrain;
    }

    private final boolean caves;

    public TerrainDiffusionDensityFunction(Boolean caves) {
        this.caves = caves == null || caves;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX(), z = pos.blockZ();
        return density(LocalTerrainProvider.tileAt(x, z), x, pos.blockY(), z);
    }

    // The density at a block of a column in the tile data: the ground's height above the block, with caves
    // carved out of it
    public double density(HeightmapData data, int x, int y, int z) {
        int column = data.index(x, z);
        int ground = data.surfaceY[column];
        double best = ground - y;
        if (!caves || best <= 0.0) {
            return best;
        }
        boolean underSea = ground <= HeightConverter.SEA_LEVEL;
        int roof = underSea ? SEA_FLOOR_ROOF : CAVE_ROOF;
        boolean shallow = best < roof || data.sideSurface[column] - y < roof;
        // Near the surface only doline shafts open, and none under the sea
        if (!(shallow && underSea)) {
            float cave = shallow
                    ? data.karst.dolineDensity(x, y, z, (float) best)
                    : data.karst.density(x, y, z, (float) best);
            if (cave < best) best = cave;
        }
        if (!shallow && y <= DeepCaverns.TOP && y < ground - DEEP_CLEARANCE) {
            float deep = DeepCaverns.density(x, y, z);
            if (deep < best) best = deep;
        }
        // A cave stays shut where it would open the side of a pool or come within reach of river water
        if (best < 0.0 && (TerrainWater.rimSolid(x, y, z)
                || (y >= data.riverWallLow[column] && y <= data.riverWallHigh[column]))) return -best;
        return best;
    }

    @Override
    public double minValue() {
        return -4096;
    }

    @Override
    public double maxValue() {
        return 4096;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
