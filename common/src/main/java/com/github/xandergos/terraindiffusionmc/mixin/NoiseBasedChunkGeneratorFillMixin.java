package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.BoundedBeardifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.TerrainFill;
import com.github.xandergos.terraindiffusionmc.world.TerrainGenerationBounds;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFillMixin {
    // Terrain-diffusion terrain is filled a column at a time, and only up to the highest ground, water or structure
    // blending in the chunk; anything else, or a chunk whose terrain tile does not cover it, takes the generator's own
    // fill
    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$fillColumns(Blender blender, StructureManager structures, RandomState random,
                                              ChunkAccess chunk, int minCellY, int cellCountY,
                                              CallbackInfoReturnable<ChunkAccess> cir) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        var settings = generator.generatorSettings().value();
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)
                || blender != Blender.empty() || settings.isAquifersEnabled() || settings.oreVeinsEnabled()) return;
        TerrainDiffusionDensityFunction terrain = TerrainGenerationBounds.terrain(settings.noiseRouter().finalDensity());
        if (terrain == null) return;
        int tileSize = TerrainDiffusionConfig.tileSize();
        if (tileSize < 16) return;
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        int tileX = x & -tileSize, tileZ = z & -tileSize;
        int generation = LocalTerrainProvider.generation(), scale = WorldScaleManager.getCurrentScale();
        var data = LocalTerrainProvider.getInstance().fetchHeightmap(tileZ, tileX, tileZ + tileSize, tileX + tileSize);
        long ceiling = TerrainGenerationBounds.upperY(data, x - tileX, z - tileZ, scale, settings.seaLevel());
        if (ceiling == Long.MAX_VALUE) return;
        var beardifier = BoundedBeardifier.wrap(Beardifier.forStructuresInChunk(structures, chunk.getPos()));
        if (!(beardifier instanceof BoundedBeardifier bounded)) return;
        ceiling = Math.max(ceiling, bounded.maxInfluenceY());
        if (generation != LocalTerrainProvider.generation() || scale != WorldScaleManager.getCurrentScale()) return;
        int cellHeight = settings.noiseSettings().getCellHeight();
        int cells = TerrainGenerationBounds.cellCount(ceiling, minCellY, cellHeight, cellCountY);
        var fluid = ((NoiseBasedChunkGeneratorAccessor) (Object) generator).terrainDiffusion$fluidPicker().get();
        TerrainFill.fill(chunk, terrain, data, tileX, tileZ, bounded, fluid, settings.defaultBlock(),
                minCellY * cellHeight, (minCellY + cells) * cellHeight - 1);
        cir.setReturnValue(chunk);
    }
}
