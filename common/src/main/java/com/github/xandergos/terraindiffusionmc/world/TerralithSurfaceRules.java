package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.HolderReferenceAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.NoiseBasedChunkGeneratorAccessor;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerralithSurfaceRules {
    private static final Logger LOG = LoggerFactory.getLogger(TerralithSurfaceRules.class);

    private static final ResourceKey<NoiseGeneratorSettings> SETTINGS =
            ResourceKey.create(Registries.NOISE_SETTINGS, TerrainDiffusionLifecycle.TERRAIN_DIFFUSION_ID);

    private static NoiseGeneratorSettings splicedInto;

    private TerralithSurfaceRules() {
    }

    // The terrain-diffusion overworld refers to the registered settings, and the splice is bound into that registry
    // entry, so a save stores only their id and every start splices the mod's own settings once
    @SuppressWarnings("unchecked")
    public static void apply(ServerLevel overworld) {
        if (!(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                || !(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource source)) {
            return;
        }
        source.possibleBiomes();
        var registry = overworld.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        Holder.Reference<NoiseGeneratorSettings> settings = registry.getHolderOrThrow(SETTINGS);
        ((NoiseBasedChunkGeneratorAccessor) (Object) generator).terrainDiffusion$setSettings(settings);

        NoiseGeneratorSettings current = settings.value();
        if (current == splicedInto || !TerralithCompat.isActive() || !TerrainDiffusionConfig.terralithInjectSurfaceRules()) {
            return;
        }

        SurfaceRules.RuleSource terralithRule = registry.getOptional(NoiseGeneratorSettings.OVERWORLD)
                .map(NoiseGeneratorSettings::surfaceRule)
                .orElse(null);

        if (terralithRule == null) {
            LOG.warn("Could not read the overworld surface rule; Terralith biomes will use default surfaces");
            return;
        }

        NoiseGeneratorSettings merged = new NoiseGeneratorSettings(
                current.noiseSettings(),
                current.defaultBlock(),
                current.defaultFluid(),
                current.noiseRouter(),
                SurfaceRules.sequence(terralithRule, current.surfaceRule()),
                current.spawnTarget(),
                current.seaLevel(),
                current.disableMobGeneration(),
                current.isAquifersEnabled(),
                current.oreVeinsEnabled(),
                current.useLegacyRandomSource()
        );

        ((HolderReferenceAccessor<NoiseGeneratorSettings>) settings).terrainDiffusion$bindValue(merged);
        splicedInto = merged;
        LOG.info("Applied Terralith surface rules to the terrain-diffusion overworld");
    }
}
