package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainWater;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Terrain Diffusion chunk starting to tick lets its still water spill over steps and falls; see {@link TerrainWater#spillStillWater}. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSpillMixin {
    @Inject(method = "postProcessGeneration", at = @At("TAIL"))
    private void terrainDiffusion$spillStillWater(CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel server
                && server.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            TerrainWater.spillStillWater(chunk);
        }
    }
}
