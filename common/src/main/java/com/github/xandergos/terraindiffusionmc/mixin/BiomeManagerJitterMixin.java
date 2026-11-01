package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = BiomeManager.class, priority = 900)
public abstract class BiomeManagerJitterMixin {
    // Optional, late redirect: defer when another mod has already replaced this call.
    @Redirect(method = "getFiddle", at = @At(value = "INVOKE", target = "Ljava/lang/Math;floorMod(JI)I", remap = false), require = 0)
    private static int terrainDiffusion$powerOfTwoRemainder(long value, int modulus) {
        return modulus == 1024 ? (int) (value & 1023L) : Math.floorMod(value, modulus);
    }
}
