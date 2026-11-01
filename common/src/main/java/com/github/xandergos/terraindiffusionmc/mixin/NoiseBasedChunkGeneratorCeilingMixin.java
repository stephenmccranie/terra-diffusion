package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.BoundedBeardifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorCeilingMixin {
    @ModifyVariable(method = "doFill", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int terrainDiffusion$limitEmptySky(int count, Blender blender, StructureManager structures,
                                              RandomState randomState, ChunkAccess chunk,
                                              int minCellY, int originalCount) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        var settings = generator.generatorSettings().value();
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)
                || blender != Blender.empty() || settings.aquifersEnabled() || settings.oreVeinsEnabled()) return count;
        if (!TerrainGenerationBounds.supports(settings.noiseRouter().finalDensity())) return count;
        int tileSize = TerrainDiffusionConfig.tileSize();
        if (tileSize < 16) return count;
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        int tileX = x & -tileSize, tileZ = z & -tileSize;
        int generation = LocalTerrainProvider.generation(), scale = WorldScaleManager.getCurrentScale();
        var data = LocalTerrainProvider.getInstance().fetchHeightmap(tileZ, tileX, tileZ + tileSize, tileX + tileSize);
        long ceiling = TerrainGenerationBounds.upperY(data, x - tileX, z - tileZ, scale, settings.seaLevel());
        if (ceiling == Long.MAX_VALUE) return count;
        var beardifier = BoundedBeardifier.wrap(Beardifier.forStructuresInChunk(structures, chunk.getPos()));
        if (!(beardifier instanceof BoundedBeardifier bounded)) return count;
        ceiling = Math.max(ceiling, bounded.maxInfluenceY());
        if (generation != LocalTerrainProvider.generation() || scale != WorldScaleManager.getCurrentScale()) return count;
        return TerrainGenerationBounds.cellCount(ceiling, minCellY, settings.noiseSettings().getCellHeight(), count);
    }
}
