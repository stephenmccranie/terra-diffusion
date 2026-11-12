package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainSurface;
import net.minecraft.core.Registry;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorSurfaceMixin {
    @Shadow
    private NoiseChunk createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random) {
        throw new AssertionError();
    }

    // A terrain-diffusion chunk's surface is built by the mod's surface pass, which places the same blocks
    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/Blender;)V",
            at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$buildSurface(ChunkAccess chunk, WorldGenerationContext generation, RandomState random,
                                               StructureManager structures, BiomeManager biomeManager,
                                               Registry<Biome> biomes, Blender blender, CallbackInfo ci) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> createNoiseChunk(c, structures, blender, random));
        TerrainSurface.build(chunk, generation, random, biomeManager, biomes, noise, generator.generatorSettings().value());
        ci.cancel();
    }
}
