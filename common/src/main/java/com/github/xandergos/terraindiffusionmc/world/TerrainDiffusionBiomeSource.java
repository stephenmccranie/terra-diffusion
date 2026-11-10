package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.Relief;
import com.github.xandergos.terraindiffusionmc.pipeline.SurfaceBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    public static final int MAX_SCALE = 6;

    private static final Codec<Relief> RELIEF_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("start").forGetter(Relief::start),
            Codec.FLOAT.fieldOf("full").forGetter(Relief::full),
            Codec.FLOAT.fieldOf("gain").forGetter(Relief::gain),
            Codec.FLOAT.fieldOf("peaks").forGetter(Relief::peaks)
    ).apply(instance, Relief::new));

    // With the seed, these two are the whole shape of a world, and they are saved with it: scale is how many
    // blocks span one pixel of the model's 30 m grid, relief how the model's heights become the world's
    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME),
                    Codec.intRange(1, MAX_SCALE).fieldOf("scale").forGetter(TerrainDiffusionBiomeSource::scale),
                    RELIEF_CODEC.fieldOf("relief").forGetter(TerrainDiffusionBiomeSource::relief)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private final HolderGetter<Biome> biomeLookup;
    private final int scale;
    private final Relief relief;
    // By tile id and by CaveBiomes index; null where this world has no such biome
    private Holder<Biome>[] surfaceBiomes;
    private Holder<Biome>[] caveBiomes;
    private boolean terralith;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, int scale, Relief relief) {
        this.biomeLookup = biomeLookup;
        this.scale = scale;
        this.relief = relief;
    }

    public int scale() {
        return scale;
    }

    public Relief relief() {
        return relief;
    }

    public TerrainDiffusionBiomeSource withScale(int scale) {
        return new TerrainDiffusionBiomeSource(biomeLookup, scale, relief);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private Optional<Holder.Reference<Biome>> find(String location) {
        return biomeLookup.get(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(location)));
    }

    // Terralith's biomes are placed where it is installed and the config allows it. An install that lacks some of
    // the biomes this mod places is a version this mod does not know.
    private boolean resolveTerralith() {
        List<String> found = new ArrayList<>(), missing = new ArrayList<>();
        for (SurfaceBiomes.Entry biome : SurfaceBiomes.all()) {
            if (biome.terralith()) (find(biome.location()).isPresent() ? found : missing).add(biome.location());
        }
        for (int i = 0; i < CaveBiomes.IDS.length; i++) {
            if (CaveBiomes.isTerralith(i)) (find(CaveBiomes.IDS[i]).isPresent() ? found : missing).add(CaveBiomes.IDS[i]);
        }
        if (found.isEmpty()) return false;
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Terralith is installed without " + missing.size()
                    + " of the biomes Terrain Diffusion places, such as " + missing.get(0));
        }
        return TerrainDiffusionConfig.terralithEnabled();
    }

    @SuppressWarnings("unchecked")
    private void resolveBiomes() {
        if (surfaceBiomes != null) {
            return;
        }
        terralith = resolveTerralith();
        TerralithCompat.setActive(terralith);

        int size = 0;
        for (SurfaceBiomes.Entry biome : SurfaceBiomes.all()) size = Math.max(size, biome.id() + 1);
        Holder<Biome>[] surface = new Holder[size];
        for (SurfaceBiomes.Entry biome : SurfaceBiomes.all()) {
            if (terralith || !biome.terralith()) surface[biome.id()] = find(biome.location()).orElseThrow();
        }
        Holder<Biome>[] caves = new Holder[CaveBiomes.IDS.length];
        for (int i = 0; i < caves.length; i++) {
            if (terralith || !CaveBiomes.isTerralith(i)) caves[i] = find(CaveBiomes.IDS[i]).orElseThrow();
        }
        caveBiomes = caves;
        surfaceBiomes = surface;
    }

    private Holder<Biome> caveBiomeAt(HeightmapData data, int column, int worldX, int worldZ, int blockY) {
        float depth = CaveBiomes.depthAt(data.surfaceY[column], blockY);
        if (depth < CaveBiomes.MIN_DEPTH) return null;
        float t = CaveBiomes.jitterTemp(data.climateT[column] / 127f, worldX, worldZ);
        float h = CaveBiomes.jitterHumidity(data.climateH[column] / 127f, worldX, worldZ);
        float e = CaveBiomes.jitterErosion(data.climateE[column] / 127f, worldX, worldZ);
        float c = CaveBiomes.continentalnessAt(data.heightmap[column]);
        float w = CaveBiomes.weirdnessAt(worldX, worldZ);
        int idx = CaveBiomes.select(t, h, c, e, w, depth, terralith);
        return idx == CaveBiomes.NONE ? null : caveBiomes[idx];
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        resolveBiomes();
        return Stream.concat(Arrays.stream(surfaceBiomes), Arrays.stream(caveBiomes))
                .filter(Objects::nonNull).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        resolveBiomes();
        return biomeAt(x, y, z, true);
    }

    // The biome at a quart position. A search asks only tiles that are already built (generate false) and gets
    // null elsewhere: hundreds of scattered positions would run the model once per position
    private Holder<Biome> biomeAt(int x, int y, int z, boolean generate) {
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        HeightmapData data = generate
                ? LocalTerrainProvider.tileAt(blockX, blockZ)
                : LocalTerrainProvider.peekTileAt(blockX, blockZ);
        if (data == null) return null;

        int column = data.index(blockX, blockZ);
        Holder<Biome> cave = caveBiomeAt(data, column, blockX, blockZ, QuartPos.toBlock(y));
        if (cave != null) return cave;

        short id = data.biomeIds[column];
        Holder<Biome> biome = surfaceBiomes[id];
        if (biome == null) {
            throw new IllegalStateException("A tile carries " + SurfaceBiomes.of(id).location()
                    + ", which this world does not have");
        }
        return biome;
    }

    private static final int MAX_SAMPLES = 40000;

    // Rings outward in quart space, sweeping vertically
    private Pair<BlockPos, Holder<Biome>> search(int x, int minY, int maxY, int z, int radius,
                                                 int horizontalInterval, int verticalInterval,
                                                 Predicate<Holder<Biome>> predicate) {
        resolveBiomes();
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        int qRadius = Math.max(0, QuartPos.fromBlock(radius));
        int step = Math.max(1, QuartPos.fromBlock(horizontalInterval));
        int qMinY = QuartPos.fromBlock(minY);
        int qMaxY = QuartPos.fromBlock(maxY);
        int vStep = Math.max(1, QuartPos.fromBlock(verticalInterval));

        int budget = MAX_SAMPLES;
        for (int ring = 0; ring <= qRadius; ring += step) {
            for (int dz = -ring; dz <= ring; dz += step) {
                boolean onZEdge = Math.abs(Math.abs(dz) - ring) < step;
                for (int dx = -ring; dx <= ring; dx += step) {
                    if (!onZEdge && Math.abs(Math.abs(dx) - ring) >= step) continue;
                    for (int qy = qMaxY; qy >= qMinY; qy -= vStep) {
                        if (--budget < 0) return null;
                        Holder<Biome> found = biomeAt(qx + dx, qy, qz + dz, false);
                        if (found != null && predicate.test(found)) {
                            return Pair.of(new BlockPos(QuartPos.toBlock(qx + dx),
                                    QuartPos.toBlock(qy), QuartPos.toBlock(qz + dz)), found);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return search(origin.getX(), world.getMinBuildHeight(), world.getMaxBuildHeight() - 1,
                origin.getZ(), radius, horizontalBlockCheckInterval, verticalBlockCheckInterval,
                predicate);
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return search(x, y, y, z, radius, blockCheckInterval, 16, predicate);
    }
}
