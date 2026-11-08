package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.LevelChunkSectionAccessor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fills a chunk's terrain a section at a time. With aquifers and ore veins off, the noise generator places at each
 * block the default block where the terrain density plus structure blending is positive, and otherwise the fluid
 * picker's fluid at that height, or air; it writes every block that is not air and raises each worldgen heightmap to
 * it. This places the same blocks without the generator's cell and interpolation machinery, which only adds cost for
 * a density computed per block. A section that is mostly the solid block starts as solid throughout and has only its
 * other blocks written, and each heightmap is set once per column from its highest block.
 */
public final class TerrainFill {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TerrainFill() {
    }

    // Fills blocks minY..maxY of the chunk, whose columns lie in data, the heightmap tile with origin (dataX, dataZ)
    public static void fill(ChunkAccess chunk, TerrainDiffusionDensityFunction terrain, HeightmapData data,
                            int dataX, int dataZ, Beardifier structures, Aquifer.FluidPicker fluid, BlockState solid,
                            int minY, int maxY) {
        BlockState[] states = new BlockState[LevelChunkSection.SECTION_SIZE];
        BlockState[] top = new BlockState[256], topFloor = new BlockState[256];
        int[] topY = new int[256], topFloorY = new int[256];
        Point at = new Point();
        // Only a block without fluid can be laid as a section's background; see write
        boolean solidBackground = solid.getFluidState().isEmpty();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int sectionY = maxY >> 4; sectionY >= minY >> 4; sectionY--) {
            int y0 = Math.max(minY, sectionY << 4), y1 = Math.min(maxY, (sectionY << 4) + 15);
            int solidBlocks = 0;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = x0 + lx, z = z0 + lz, column = lz << 4 | lx;
                    for (int y = (sectionY << 4) + 15; y >= sectionY << 4; y--) {
                        BlockState state = AIR;
                        if (y >= y0 && y <= y1) {
                            at.set(x, y, z);
                            double density = terrain.density(data, x, y, z, x - dataX, z - dataZ)
                                    + structures.compute(at);
                            state = density > 0.0 ? solid : fluid.computeFluid(x, y, z).at(y);
                        }
                        states[(y & 15) << 8 | column] = state;
                        if (state == AIR) continue;
                        if (state == solid) solidBlocks++;
                        if (top[column] == null) {
                            top[column] = state;
                            topY[column] = y;
                        }
                        if (topFloor[column] == null && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                            topFloor[column] = state;
                            topFloorY[column] = y;
                        }
                    }
                }
            }
            write(chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY)), states, solid,
                    solidBackground && solidBlocks > LevelChunkSection.SECTION_SIZE / 2);
        }
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int column = 0; column < 256; column++) {
            if (top[column] != null) surface.update(column & 15, topY[column], column >> 4, top[column]);
            if (topFloor[column] != null) floor.update(column & 15, topFloorY[column], column >> 4, topFloor[column]);
        }
    }

    // Writes a section's blocks into it, still all air. A mostly solid section takes a solid container, so only its
    // other blocks are written; the solid block has no fluid, so its block counts come out as writing each would give.
    // The new container takes over the lock the generator holds on the section.
    private static void write(LevelChunkSection section, BlockState[] states, BlockState solid, boolean mostlySolid) {
        BlockState background = AIR;
        if (mostlySolid) {
            PalettedContainer<BlockState> filled =
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, solid, PalettedContainer.Strategy.SECTION_STATES);
            PalettedContainer<BlockState> empty = section.getStates();
            filled.acquire();
            ((LevelChunkSectionAccessor) section).terrainDiffusion$setStates(filled);
            empty.release();
            section.recalcBlockCounts();
            background = solid;
        }
        for (int i = 0; i < states.length; i++) {
            if (states[i] != background) section.setBlockState(i & 15, i >> 8, i >> 4 & 15, states[i], false);
        }
    }

    private static final class Point implements DensityFunction.FunctionContext {
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
