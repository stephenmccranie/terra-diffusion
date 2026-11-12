package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A region lends the same chunk for a position for as long as it lives, so each chunk around its centre is looked up
 * once. Features read heights and blocks through the region a column or block at a time; an ore alone asks the height
 * of every column in a square around it, and a chunk lookup costs far more than the read.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow
    @Final
    private ChunkAccess center;

    @Unique
    private final ChunkAccess[] terrainDiffusion$chunks = new ChunkAccess[9];

    @Redirect(method = {"getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
            "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/WorldGenRegion;getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess terrainDiffusion$chunk(WorldGenRegion region, int x, int z) {
        ChunkPos middle = center.getPos();
        int dx = x - middle.x + 1, dz = z - middle.z + 1;
        if (dx < 0 || dx > 2 || dz < 0 || dz > 2) return region.getChunk(x, z);
        int i = 3 * dz + dx;
        ChunkAccess chunk = terrainDiffusion$chunks[i];
        if (chunk == null) terrainDiffusion$chunks[i] = chunk = region.getChunk(x, z);
        return chunk;
    }
}
