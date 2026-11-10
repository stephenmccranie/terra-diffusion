package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fills a chunk's terrain a column at a time. With aquifers and ore veins off, the noise generator places at each block
 * the default block where the terrain density plus structure blending is positive, and otherwise the fluid picker's
 * fluid at that height, or air; it writes every block that is not air and raises each worldgen heightmap to it. This
 * places the same blocks without the generator's cell and interpolation machinery, which only adds cost for a density
 * that is computed per block, and sets each heightmap once per column from its highest block.
 */
public final class TerrainFill {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TerrainFill() {
    }

    // Fills blocks minY..maxY of the chunk, whose columns lie in the tile data
    public static void fill(ChunkAccess chunk, TerrainDiffusionDensityFunction terrain, HeightmapData data,
                            Beardifier structures, Aquifer.FluidPicker fluid, BlockState solid, int minY, int maxY) {
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Block at = new Block();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                BlockState top = null, topFloor = null;
                int topY = 0, topFloorY = 0;
                int sectionIndex = -1;
                LevelChunkSection section = null;
                for (int y = maxY; y >= minY; y--) {
                    at.set(x, y, z);
                    double density = terrain.density(data, x, y, z) + structures.compute(at);
                    BlockState state = density > 0.0 ? solid : fluid.computeFluid(x, y, z).at(y);
                    if (state == AIR) continue;
                    int index = chunk.getSectionIndex(y);
                    if (index != sectionIndex) {
                        sectionIndex = index;
                        section = chunk.getSection(index);
                    }
                    section.setBlockState(lx, y & 15, lz, state, false);
                    if (top == null) {
                        top = state;
                        topY = y;
                    }
                    if (topFloor == null && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                        topFloor = state;
                        topFloorY = y;
                    }
                }
                if (top != null) surface.update(lx, topY, lz, top);
                if (topFloor != null) floor.update(lx, topFloorY, lz, topFloor);
            }
        }
    }

    private static final class Block implements DensityFunction.FunctionContext {
        private int x, y, z;

        void set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int blockX() {
            return x;
        }

        @Override
        public int blockY() {
            return y;
        }

        @Override
        public int blockZ() {
            return z;
        }
    }
}
