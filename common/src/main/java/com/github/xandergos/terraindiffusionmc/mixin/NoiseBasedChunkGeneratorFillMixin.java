package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.BoundedBeardifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.TerrainFill;
import com.github.xandergos.terraindiffusionmc.world.TerrainGenerationBounds;
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
    // blending in the chunk. Only a chunk blended into an older world's terrain takes the generator's own fill.
    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$fillColumns(Blender blender, StructureManager structures, RandomState random,
                                              ChunkAccess chunk, int minCellY, int cellCountY,
                                              CallbackInfoReturnable<ChunkAccess> cir) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource) || blender != Blender.empty()) return;
        var settings = generator.generatorSettings().value();
        TerrainDiffusionDensityFunction terrain = TerrainDiffusionDensityFunction.of(settings);
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        // A tile is at least a chunk across, so the chunk lies in one
        var data = LocalTerrainProvider.tileAt(x, z);
        var beardifier = BoundedBeardifier.of(Beardifier.forStructuresInChunk(structures, chunk.getPos()));
        long ceiling = Math.max(TerrainGenerationBounds.upperY(data, x, z, settings.seaLevel()),
                beardifier.maxInfluenceY());
        int cellHeight = settings.noiseSettings().getCellHeight();
        int cells = TerrainGenerationBounds.cellCount(ceiling, minCellY, cellHeight, cellCountY);
        var fluid = ((NoiseBasedChunkGeneratorAccessor) (Object) generator).terrainDiffusion$fluidPicker().get();
        TerrainFill.fill(chunk, terrain, data, beardifier, fluid, settings.defaultBlock(),
                minCellY * cellHeight, (minCellY + cells) * cellHeight - 1);
        cir.setReturnValue(chunk);
    }
}
