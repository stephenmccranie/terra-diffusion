package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainCarving;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carvers are handed only this context, so it remembers which generator it carves for. */
@Mixin(CarvingContext.class)
public abstract class CarvingContextMixin implements TerrainCarving {
    @Unique private boolean terrainDiffusion$carvesTerrain;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terrainDiffusion$rememberGenerator(NoiseBasedChunkGenerator generator, RegistryAccess registryAccess,
                                                    LevelHeightAccessor level, NoiseChunk noiseChunk,
                                                    RandomState randomState, SurfaceRules.RuleSource surfaceRule,
                                                    CallbackInfo ci) {
        terrainDiffusion$carvesTerrain = generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource;
    }

    @Override
    public boolean terrainDiffusion$carvesTerrain() {
        return terrainDiffusion$carvesTerrain;
    }
}
