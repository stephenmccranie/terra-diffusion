package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.BlockWater;
import com.github.xandergos.terraindiffusionmc.pipeline.DeepCaverns;
import com.github.xandergos.terraindiffusionmc.pipeline.KarstNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class TerrainWater {

    public static final int NO_WATER = Integer.MIN_VALUE;

    // Blocks of standing water a cave holds under the water table; a deeper opening stays dry
    private static final int POOL_DEPTH = 6;

    // Open, dry sides that make a wet block a perch, which holds no water
    private static final int PERCHED_AIR_SIDES = 3;

    // Blocks around a pool column searched for air its water would face
    private static final int RIM_RADIUS = 2;

    // The four sides of a column, then its four corners
    private static final int[] NEAR_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NEAR_DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private static final long NO_POOL =
            ((long) Integer.MIN_VALUE << 32) | (Integer.MAX_VALUE & 0xffffffffL);

    private TerrainWater() {
    }

    // Minecraft Y of the surface of a column's river or lake, or NO_WATER
    public static int waterLevelY(int x, int z) {
        HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
        short y = tile.waterY[tile.index(x, z)];
        return y == HeightmapData.NO_WATER ? NO_WATER : y;
    }

    public static int groundY(int x, int z) {
        HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
        return tile.surfaceY[tile.index(x, z)];
    }

    // Model height in metres of the land's regional base under a column, the field the relief curve lifts
    public static float regionalBaseM(int x, int z) {
        HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
        return tile.regionalBaseM[tile.index(x, z)];
    }

    // True where a block under the ground is opened by a karst conduit or a deep cavern
    private static boolean open(int x, int y, int z) {
        HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
        if (y >= tile.surfaceY[tile.index(x, z)]) return false;
        if (tile.karst.density(x, y, z, 0f) < 0f) return true;
        return y <= DeepCaverns.TOP && DeepCaverns.density(x, y, z) < 0f;
    }

    private static boolean wetNear(int x, int y, int z) {
        if (y <= DeepCaverns.TOP && DeepCaverns.fluid(x, y, z) == DeepCaverns.WATER) return true;
        KarstNetwork karst = LocalTerrainProvider.tileAt(x, z).karst;
        if (karst.isEmpty()) return false;
        int table = Math.round(karst.waterTableY(x, z));
        return y < table && y >= table - POOL_DEPTH;
    }

    // The pool in a column's caves: the open blocks directly under the water table, where there are no more than
    // POOL_DEPTH of them and no doline shaft drains them. Packed as (top << 32) | bottom, or NO_POOL.
    private static long poolAt(int x, int z) {
        HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
        if (tile.karst.isEmpty()) return NO_POOL;
        int top = Math.min(Math.round(tile.karst.waterTableY(x, z)), tile.surfaceY[tile.index(x, z)] - 1);
        if (tile.karst.dolineDensity(x, top - 1, z, 0f) < 0f) return NO_POOL;

        int k = 1;
        while (k <= POOL_DEPTH + 1 && open(x, top - k, z)) k++;
        if (k == 1 || k > POOL_DEPTH + 1) return NO_POOL;
        return ((long) top << 32) | ((top - k + 1) & 0xffffffffL);
    }

    private static boolean covers(long pool, int y) {
        return pool != NO_POOL && y >= (int) pool && y < (int) (pool >> 32);
    }

    // Pools are worked out once per thread and kept over a 32x32 block neighbourhood, slotted by the low bits of
    // x and z
    private static final int SLOT_BITS = 5;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int SLOTS = 1 << (2 * SLOT_BITS);

    private static final byte BANK_UNKNOWN = 0, BANK_NO = 1, BANK_YES = 2;

    private static final class Pools {
        final int[] x = new int[SLOTS];
        final int[] z = new int[SLOTS];
        final int[] generation = new int[SLOTS];
        final long[] pool = new long[SLOTS];
        final byte[] bank = new byte[SLOTS];

        Pools() {
            java.util.Arrays.fill(generation, -1);
        }

        int slot(int x, int z) {
            int s = ((z & SLOT_MASK) << SLOT_BITS) | (x & SLOT_MASK);
            int g = LocalTerrainProvider.generation();
            if (this.x[s] != x || this.z[s] != z || generation[s] != g) {
                this.x[s] = x;
                this.z[s] = z;
                generation[s] = g;
                pool[s] = poolAt(x, z);
                bank[s] = BANK_UNKNOWN;
            }
            return s;
        }
    }

    private static final ThreadLocal<Pools> POOLS = ThreadLocal.withInitial(Pools::new);

    // True where a pool column shows a horizontal face to open air, so the terrain puts stone there
    public static boolean rimSolid(int x, int y, int z) {
        Pools pools = POOLS.get();
        int s = pools.slot(x, z);
        long pool = pools.pool[s];
        if (!covers(pool, y)) return false;
        if (pools.bank[s] == BANK_UNKNOWN) {
            // The columns it looks at are within RIM_RADIUS, so they take other slots and this one stays put
            pools.bank[s] = facesAir(pools, x, z, pool) ? BANK_YES : BANK_NO;
        }
        return pools.bank[s] == BANK_YES;
    }

    private static boolean facesAir(Pools pools, int x, int z, long pool) {
        int bottom = (int) pool, top = (int) (pool >> 32);
        for (int r = 1; r <= RIM_RADIUS; r++) {
            for (int d = 0; d < 8; d++) {
                int nx = x + NEAR_DX[d] * r;
                int nz = z + NEAR_DZ[d] * r;
                long beside = pools.pool[pools.slot(nx, nz)];
                for (int y = bottom; y < top; y++) {
                    if (open(nx, y, nz) && !covers(beside, y)) return true;
                }
            }
        }
        return false;
    }

    // A block with dry air on nearly every side is a perch
    private static boolean perched(int x, int y, int z) {
        int air = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + NEAR_DX[d], nz = z + NEAR_DZ[d];
            if (open(nx, y, nz) && !wetNear(nx, y, nz)) air++;
        }
        return air >= PERCHED_AIR_SIDES;
    }

    // What fills each block that is not solid. Above the ground: water up to the column's river or lake surface, or
    // to sea level where it has none, with flowing water on ramps and falls. Below it: lava at the bottom of the
    // world, and water in cave pools and deep cavern lakes.
    public static Aquifer.FluidPicker fluidPicker(int seaLevel, BlockState fluid) {
        Aquifer.FluidStatus dry =
                new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        Aquifer.FluidStatus wet = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2, fluid);
        Aquifer.FluidStatus lava =
                new Aquifer.FluidStatus(Integer.MAX_VALUE / 2, Blocks.LAVA.defaultBlockState());
        // A ramp's flowing water at each level, and a curtain's falling water
        boolean levelled = fluid.hasProperty(LiquidBlock.LEVEL);
        Aquifer.FluidStatus[] flowing = new Aquifer.FluidStatus[BlockWater.MAX_RAMP + 1];
        for (int k = 1; k <= BlockWater.MAX_RAMP; k++) {
            flowing[k] = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2,
                    levelled ? fluid.setValue(LiquidBlock.LEVEL, k) : fluid);
        }
        Aquifer.FluidStatus falling = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2,
                levelled ? fluid.setValue(LiquidBlock.LEVEL, 8) : fluid);

        return (x, y, z) -> {
            HeightmapData tile = LocalTerrainProvider.tileAt(x, z);
            int i = tile.index(x, z);
            if (y >= tile.surfaceY[i]) {
                int top = tile.waterY[i] == HeightmapData.NO_WATER ? seaLevel : tile.waterY[i];
                int flow = tile.waterFlow[i] & 0xff;
                if (flow != 0 && y >= top) {
                    if (flow <= BlockWater.MAX_RAMP) {
                        if (y == top) return flowing[flow];
                    } else {
                        int fall = flow - BlockWater.CURTAIN;
                        if (y < top + fall) return y == top + fall - 1 ? flowing[1] : falling;
                    }
                }
                return y < top ? wet : dry;
            }
            if (y <= DeepCaverns.LAVA_Y) {
                return lava;
            }
            Pools pools = POOLS.get();
            boolean water = covers(pools.pool[pools.slot(x, z)], y)
                    || (y <= DeepCaverns.TOP && DeepCaverns.fluid(x, y, z) == DeepCaverns.WATER);
            return water && !perched(x, y, z) ? wet : dry;
        };
    }

    /**
     * Water is filled without fluid updates, so where a river or lake runs over a cave its water hangs over the cave as
     * a ceiling. Whenever a chunk starts ticking, the lowest water hanging over air pours down into the cave, as it
     * would after any block update. It reads only placed blocks, and once spilled a chunk has nothing left to do.
     */
    public static void spillIntoCaves(LevelChunk chunk) {
        Level level = chunk.getLevel();
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                top.set(chunk.getPos().getBlockX(lx), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz),
                        chunk.getPos().getBlockZ(lz));
                if (!chunk.getFluidState(top).is(Fluids.WATER)) continue;
                spillIntoCave(chunk, level, top, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz));
            }
        }
    }

    // Water resting on its bed has water on the column's highest solid block; air there means the water above hangs
    // over a cave, so the lowest hanging water block pours down
    private static void spillIntoCave(LevelChunk chunk, Level level, BlockPos.MutableBlockPos top, int floor) {
        int surface = top.getY();
        top.setY(floor + 1);
        if (!chunk.getBlockState(top).isAir()) return;
        while (top.getY() < surface) {
            top.move(Direction.UP);
            FluidState water = chunk.getFluidState(top);
            if (water.is(Fluids.WATER)) {
                if (chunk.getBlockState(top.below()).isAir()) water.tick(level, top);
                return;
            }
        }
    }
}
