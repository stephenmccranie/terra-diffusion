package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccessor {
    @Mutable
    @Accessor("settings")
    void terrainDiffusion$setSettings(Holder<NoiseGeneratorSettings> settings);

    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> terrainDiffusion$fluidPicker();
}
