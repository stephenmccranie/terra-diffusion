package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.CarvedCaves;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ravines start at heights measured from the land, and fewer of them are carved; see {@link CarvedCaves}. */
@Mixin(CanyonWorldCarver.class)
public abstract class CanyonWorldCarverMixin {
    private static final String CARVE = "carve(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
            + "Lnet/minecraft/world/level/levelgen/carver/CanyonCarverConfiguration;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;"
            + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/Aquifer;"
            + "Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/CarvingMask;)Z";

    @Inject(method = CARVE, at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$fewerRavines(CarvingContext context, CanyonCarverConfiguration config,
                                               ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomeAccessor,
                                               RandomSource random, Aquifer aquifer, ChunkPos chunkPos,
                                               CarvingMask carvingMask, CallbackInfoReturnable<Boolean> carved) {
        if (!CarvedCaves.ravineCarved(context, random)) carved.setReturnValue(false);
    }

    @Redirect(method = CARVE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/heightproviders/HeightProvider;"
                    + "sample(Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)I"))
    private int terrainDiffusion$startFromTheLand(HeightProvider height, RandomSource heightRandom,
                                                  WorldGenerationContext heightContext, CarvingContext context,
                                                  CanyonCarverConfiguration config, ChunkAccess chunk,
                                                  Function<BlockPos, Holder<Biome>> biomeAccessor, RandomSource random,
                                                  Aquifer aquifer, ChunkPos chunkPos, CarvingMask carvingMask) {
        return CarvedCaves.startY(height.sample(heightRandom, heightContext), context, chunkPos);
    }
}
