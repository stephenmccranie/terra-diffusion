package com.github.xandergos.terraindiffusionmc.mixin;

import java.util.Arrays;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Primes a generating chunk's heightmaps as vanilla does, scanning each column down from the top of its highest section,
 * but passes over whole any section whose palette holds no block a heightmap still looks for. The ocean floor is found
 * under all of a sea's or lake's water, for every chunk's features and again whenever a feature reads a neighbour's
 * height, and the sections of water above it are passed in one step each.
 */
@Mixin(Heightmap.class)
public class HeightmapMixin {
    @Shadow
    private void setHeight(int x, int z, int y) {
        throw new AssertionError();
    }

    @Inject(method = "primeHeightmaps", at = @At("HEAD"), cancellable = true)
    private static void terrainDiffusion$skipSections(ChunkAccess chunk, Set<Heightmap.Types> types, CallbackInfo ci) {
        if (!(chunk instanceof ProtoChunk) || chunk instanceof ImposterProtoChunk) return;
        Heightmap.Types[] kinds = types.toArray(Heightmap.Types[]::new);
        HeightmapMixin[] maps = new HeightmapMixin[kinds.length];
        for (int k = 0; k < kinds.length; k++) maps[k] = (HeightmapMixin) (Object) chunk.getOrCreateHeightmapUnprimed(kinds[k]);
        int minY = chunk.getMinBuildHeight(), top = minY + 16 * (chunk.getHighestFilledSectionIndex() + 1);
        boolean[] found = new boolean[kinds.length];
        for (int i = 0; i < 256; i++) {
            int x = i & 15, z = i >> 4;
            Arrays.fill(found, false);
            int left = kinds.length;
            for (int y = top - 1; y >= minY && left > 0; ) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                int bottom = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y));
                if (!section.maybeHas(state -> sought(state, kinds, found))) {
                    y = bottom - 1;
                    continue;
                }
                for (; y >= bottom && left > 0; y--) {
                    BlockState state = section.getBlockState(x, y & 15, z);
                    if (state.is(Blocks.AIR)) continue;
                    for (int k = 0; k < kinds.length; k++) {
                        if (!found[k] && kinds[k].isOpaque().test(state)) {
                            maps[k].setHeight(x, z, y + 1);
                            found[k] = true;
                            left--;
                        }
                    }
                }
            }
        }
        ci.cancel();
    }

    private static boolean sought(BlockState state, Heightmap.Types[] kinds, boolean[] found) {
        for (int k = 0; k < kinds.length; k++) {
            if (!found[k] && kinds[k].isOpaque().test(state)) return true;
        }
        return false;
    }
}
