package com.github.xandergos.terraindiffusionmc.world;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The floors of one column, top down from its surface, as every-layer placement counts them: an empty block (air,
 * water or lava) standing on a block that is neither empty nor bedrock. The column is scanned only as deep as the
 * floors asked for so far, a section at a time, and a section holding nothing empty is passed with one read, since a
 * floor can stand only on its first block.
 */
public final class ColumnFloors {
    private final ChunkAccess chunk;
    private final int x, z;
    private int y;
    private BlockState above;
    private int[] floors = new int[8];
    private int found;

    public ColumnFloors(WorldGenLevel level, int x, int surface, int z) {
        chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        this.x = x & 15;
        this.z = z & 15;
        y = surface;
        above = chunk.getBlockState(new BlockPos(x, surface, z));
    }

    /** The y of the column's floor {@code n} from the top, or {@link Integer#MAX_VALUE} where it has no more floors. */
    public int floor(int n) {
        while (found <= n && y > chunk.getMinBuildHeight()) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y - 1));
            int bottom = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y - 1));
            if (!section.maybeHas(ColumnFloors::empty)) {
                if (empty(above) && !section.getBlockState(x, (y - 1) & 15, z).is(Blocks.BEDROCK)) add(y);
                above = section.getBlockState(x, 0, z);
                y = bottom;
                continue;
            }
            for (; y > bottom; y--) {
                BlockState state = section.getBlockState(x, (y - 1) & 15, z);
                if (empty(above) && !empty(state) && !state.is(Blocks.BEDROCK)) add(y);
                above = state;
            }
        }
        return n < found ? floors[n] : Integer.MAX_VALUE;
    }

    private void add(int floor) {
        if (found == floors.length) floors = Arrays.copyOf(floors, 2 * found);
        floors[found++] = floor;
    }

    private static boolean empty(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }
}
