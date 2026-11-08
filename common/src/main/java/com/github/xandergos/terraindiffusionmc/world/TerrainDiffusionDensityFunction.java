package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.DeepCaverns;
import com.github.xandergos.terraindiffusionmc.pipeline.KarstNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class TerrainDiffusionDensityFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("caves", Boolean.TRUE).forGetter(f -> f.caves)
            ).apply(instance, TerrainDiffusionDensityFunction::new));

    private static final int CAVE_ROOF =
            Integer.parseInt(System.getProperty("terradiff.caveRoof", "5"));

    private static final int DEEP_CLEARANCE =
            Integer.parseInt(System.getProperty("terradiff.deepClearance", "8"));

    private static final int SEA_FLOOR_ROOF =
            Integer.parseInt(System.getProperty("terradiff.seaFloorRoof", "8"));

    private static boolean nearRiverWater(HeightmapData data, int localX, int localZ, int y) {
        int i = localZ * data.width + localX;
        return y >= data.riverWallLow[i] && y <= data.riverWallHigh[i];
    }

    public static final TerrainDiffusionDensityFunction INSTANCE =
            new TerrainDiffusionDensityFunction(Boolean.TRUE);

    private final boolean caves;

    public TerrainDiffusionDensityFunction(Boolean caves) {
        this.caves = caves == null || caves;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = x >> tileShift;
        int tileZ = z >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;

        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

        if (data == null || data.heightmap == null) {
            return 0.0;
        }

        int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
        return density(data, x, y, z, localX, localZ);
    }

    // The density at a block, from the heightmap tile holding its column at (localX, localZ): height above the
    // block, with caves carved out of the ground
    public double density(HeightmapData data, int x, int y, int z, int localX, int localZ) {
        int column = localZ * data.width + localX;
        int targetHeight = data.surfaceY[column];
        double terrain = targetHeight - y;
        if (!caves || terrain <= 0.0) {
            return terrain;
        }
        return carve(data, x, y, z, terrain, targetHeight, data.sideSurface[column] - y, localX, localZ);
    }

    private static final class FillContext {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        HeightmapData data;

        void update(int x, int z) {
            if (x < blockStartX || x >= blockEndX) this.init(x, z);
            if (z < blockStartZ || z >= blockEndZ) this.init(x, z);
        }

        void init(int x, int z) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);

            int tileX = x >> tileShift;
            int tileZ = z >> tileShift;

            this.blockStartX = tileX << tileShift;
            this.blockStartZ = tileZ << tileShift;
            this.blockEndX = blockStartX + tileSize;
            this.blockEndZ = blockStartZ + tileSize;

            this.data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        }
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        if (densities.length == 0) return;

        FillContext ctx = new FillContext();
        DensityFunction.FunctionContext pos = applier.forIndex(0);
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();
        ctx.init(x, z);

        for (int i = 0; i < densities.length; i++) {
            pos = applier.forIndex(i);
            x = pos.blockX();
            z = pos.blockZ();
            y = pos.blockY();
            ctx.update(x, z);

            HeightmapData data = ctx.data;
            if (data == null || data.heightmap == null) {
                densities[i] = -y;
                continue;
            }

            int localX = Math.max(0, Math.min(data.width  - 1, x - ctx.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - ctx.blockStartZ));
            densities[i] = density(data, x, y, z, localX, localZ);
        }
    }

    private static double carve(HeightmapData data, int x, int y, int z,
                                double terrain, int targetHeight, double sideDepth,
                                int localX, int localZ) {
        double best = terrain;
        boolean underSea = targetHeight <= HeightConverter.SEA_LEVEL;
        int roof = underSea ? Math.max(CAVE_ROOF, SEA_FLOOR_ROOF) : CAVE_ROOF;
        boolean shallow = terrain < roof || sideDepth < roof;
        KarstNetwork karst = data.karst;
        if (karst != null && !karst.isEmpty() && !(shallow && underSea)) {
            float cave = shallow ? karst.dolineDensity(x, y, z, (float) best) : karst.density(x, y, z, (float) best);
            if (cave < best) best = cave;
        }
        int deepClear = underSea ? Math.max(DEEP_CLEARANCE, SEA_FLOOR_ROOF) : DEEP_CLEARANCE;
        if (!shallow && y <= DeepCaverns.TOP && y < targetHeight - deepClear) {
            float deep = DeepCaverns.density(x, y, z);
            if (deep < best) best = deep;
        }
        if (best < 0.0 && (TerrainWater.rimSolid(x, y, z)
                || nearRiverWater(data, localX, localZ, y))) return -best;
        return best;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
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
