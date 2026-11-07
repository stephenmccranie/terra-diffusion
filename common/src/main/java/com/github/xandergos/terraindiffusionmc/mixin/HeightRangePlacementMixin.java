package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.DeepOres;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The relief curve raises Terrain Diffusion's land above the ground vanilla's bands were made for; see {@link DeepOres#positions}. */
@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {
    @Shadow
    @Final
    private HeightProvider height;

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$stretchTallGround(PlacementContext context, RandomSource random, BlockPos pos,
                                                    CallbackInfoReturnable<Stream<BlockPos>> cir) {
        if (context.generator().getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            cir.setReturnValue(DeepOres.positions(context, random, pos, height));
        }
    }
}
