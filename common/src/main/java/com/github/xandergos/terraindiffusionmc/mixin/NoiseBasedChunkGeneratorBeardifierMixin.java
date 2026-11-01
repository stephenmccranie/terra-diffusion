package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.BoundedBeardifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorBeardifierMixin {
    @ModifyExpressionValue(method = "createNoiseChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/Beardifier;forStructuresInChunk(Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/levelgen/Beardifier;"))
    private Beardifier terrainDiffusion$boundStructureBlending(Beardifier original) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return original;
        return BoundedBeardifier.wrap(original);
    }
}
