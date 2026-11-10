package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerralithSurfaceRules;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Where the spliced surface rules make water beds stony, sand and clay disks are not laid over them
@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$noStonyBedSand(FeaturePlaceContext<DiskConfiguration> context,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (context.origin().getY() < TerralithSurfaceRules.stonyBedsFromY()) {
            return;
        }
        // A fixed random reads the disk's block without drawing from the feature's own random
        BlockState disk = context.config().stateProvider().fallback().getState(RandomSource.create(0L), context.origin());
        if (disk.is(Blocks.SAND) || disk.is(Blocks.RED_SAND) || disk.is(Blocks.CLAY)) {
            cir.setReturnValue(false);
        }
    }
}
