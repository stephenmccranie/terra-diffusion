package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.HolderReferenceAccessor;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Turns off the snow Terralith paints on rocky mountains, which this generator places above the snow line
public final class TerralithSnow {
    private static final Logger LOG = LoggerFactory.getLogger(TerralithSnow.class);

    private static final String[] ROCKY_SNOW = {
            "mountains/rocky/snow",
            "mountains/rocky/funny_snow",
            "mountains/rocky/funny_snow_segwit_2x"};

    private TerralithSnow() {
    }

    @SuppressWarnings("unchecked")
    public static void apply(ServerLevel world) {
        if (!(world.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource source)) {
            return;
        }
        source.possibleBiomes();
        if (!TerralithCompat.isActive()) {
            return;
        }
        // Placed features stay untouched so the feature order is Terralith's; only what they place changes
        Registry<ConfiguredFeature<?, ?>> configured = world.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        int removed = 0;
        for (String path : ROCKY_SNOW) {
            ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE,
                    ResourceLocation.fromNamespaceAndPath(TerralithCompat.NAMESPACE, path));
            Holder.Reference<ConfiguredFeature<?, ?>> ref = configured.getHolder(key).orElse(null);
            if (ref == null || ref.value().feature() == Feature.NO_OP) continue;
            ((HolderReferenceAccessor<ConfiguredFeature<?, ?>>) ref).terrainDiffusion$bindValue(
                    new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE));
            removed++;
        }
        if (removed > 0) {
            LOG.info("Terralith rocky mountain snow turned off ({} features)", removed);
        }
    }
}
