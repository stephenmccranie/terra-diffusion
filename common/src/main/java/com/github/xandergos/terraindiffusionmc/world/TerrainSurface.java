package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/**
 * Builds a chunk's surface as the surface system does, walking each column down from its top through the same stone
 * depths, water heights and column extensions to place the same blocks, but tries each block of stone against the
 * surface rule as specialised to what is already known there. Below a column's preliminary surface no
 * above-preliminary-surface rule applies, which leaves the overworld rule little more than its bedrock and deepslate
 * bands; a block further under a floor and over a ceiling than any stone-depth test of that rule reaches fails them
 * all, and the rule left can place nothing above the bands, so that stone is only counted. At and above the
 * preliminary surface nearly every block's rule turns on its biome, so the biome is looked up first and the rule
 * specialised to it.
 */
public final class TerrainSurface {
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
    private static final SurfaceRules.SurfaceRule NOTHING = (x, y, z) -> null;
    private static final ThreadLocal<BlockState[]> COLUMN = ThreadLocal.withInitial(() -> new BlockState[0]);
    private static volatile Rules rules;

    private TerrainSurface() {
    }

    public static void build(ChunkAccess chunk, WorldGenerationContext generation, RandomState random,
                             BiomeManager biomeManager, Registry<Biome> biomeRegistry, NoiseChunk noise,
                             NoiseGeneratorSettings settings) {
        Rules rules = rulesFor(settings.surfaceRule());
        SurfaceSystem system = random.surfaceSystem();
        BlockState stone = settings.defaultBlock();
        boolean legacy = settings.useLegacyRandomSource();
        SurfaceRules.Context context = new SurfaceRules.Context(system, random, chunk, noise, biomeManager::getBiome,
                biomeRegistry, generation);
        SurfaceRules.SurfaceRule lowerNear = apply(rules.lowerNear, context);
        SurfaceRules.SurfaceRule lowerInterior = apply(rules.lowerInterior, context);
        int interiorTop = rules.lowerInterior == null ? Integer.MIN_VALUE : top(rules.lowerInterior, generation);
        Map<Holder<Biome>, SurfaceRules.SurfaceRule> upper = new HashMap<>();

        ChunkColumn column = new ChunkColumn(chunk);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinBuildHeight();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        // Column states from the block below the chunk, at index 0, up
        BlockState[] states = COLUMN.get();
        if (states.length < chunk.getHeight() + 3) {
            states = new BlockState[chunk.getHeight() + 3];
            COLUMN.set(states);
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) + 1;
                column.at(x, z);
                Holder<Biome> biome = biomeManager.getBiome(probe.set(x, legacy ? 0 : surface, z));
                if (biome.is(Biomes.ERODED_BADLANDS)) system.erodedBadlandsExtension(column, x, z, surface, chunk);
                // The badlands extension can raise the column, and its pillars are surfaced too
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) + 1;
                context.updateXZ(x, z);
                int preliminary = context.getMinSurfaceLevel();
                int floorReach = reach(rules.floor, context), ceilingReach = reach(rules.ceiling, context);
                read(chunk, lx, lz, minY, top, states);

                int above = 0, water = Integer.MIN_VALUE, stoneBottom = Integer.MAX_VALUE;
                for (int y = top; y >= minY; y--) {
                    BlockState state = states[y - minY + 1];
                    if (state.isAir()) {
                        above = 0;
                        water = Integer.MIN_VALUE;
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        if (water == Integer.MIN_VALUE) water = y + 1;
                        continue;
                    }
                    if (stoneBottom >= y) {
                        stoneBottom = DimensionType.WAY_BELOW_MIN_Y;
                        for (int below = y - 1; below >= minY - 1; below--) {
                            BlockState under = states[below - minY + 1];
                            if (under.isAir() || !under.getFluidState().isEmpty()) {
                                stoneBottom = below + 1;
                                break;
                            }
                        }
                    }
                    above++;
                    int depthBelow = y - stoneBottom + 1;
                    if (state != stone) continue;
                    SurfaceRules.SurfaceRule rule;
                    if (y >= preliminary) {
                        rule = upper.computeIfAbsent(biomeManager.getBiome(probe.set(x, y, z)),
                                b -> apply(rules.upper(b), context));
                    } else if (above > floorReach && depthBelow > ceilingReach) {
                        if (y > interiorTop) continue;
                        rule = lowerInterior;
                    } else {
                        rule = lowerNear;
                    }
                    context.updateY(above, depthBelow, water, x, y, z);
                    BlockState placed = rule.tryApply(x, y, z);
                    if (placed != null) column.setBlock(y, placed);
                }

                if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    system.frozenOceanExtension(context.getMinSurfaceLevel(), biome.value(), column, probe, x, z, surface);
                }
            }
        }
    }

    // The column's states from top down to the block below the chunk, which is void air as the chunk reports it
    private static void read(ChunkAccess chunk, int lx, int lz, int minY, int top, BlockState[] states) {
        LevelChunkSection[] sections = chunk.getSections();
        int y = top;
        for (; y >= minY + 16 * sections.length; y--) states[y - minY + 1] = VOID_AIR;
        for (int index = (y - minY) >> 4; index >= 0; index--) {
            LevelChunkSection section = sections[index];
            for (int bottom = minY + 16 * index; y >= bottom; y--) {
                states[y - minY + 1] = section.getBlockState(lx, y & 15, lz);
            }
        }
        states[0] = VOID_AIR;
    }

    private static SurfaceRules.SurfaceRule apply(SurfaceRules.RuleSource rule, SurfaceRules.Context context) {
        return rule == null ? NOTHING : rule.apply(context);
    }

    // The greatest depth of stone at which any of these stone-depth tests holds in the current column
    private static int reach(List<SurfaceRules.StoneDepthCheck> checks, SurfaceRules.Context context) {
        int reach = Integer.MIN_VALUE;
        for (SurfaceRules.StoneDepthCheck check : checks) {
            int depth = 1 + check.offset() + (check.addSurfaceDepth() ? context.surfaceDepth : 0)
                    + (check.secondaryDepthRange() == 0 ? 0
                    : (int) Mth.map(context.getSurfaceSecondary(), -1.0, 1.0, 0.0, check.secondaryDepthRange()));
            reach = Math.max(reach, depth);
        }
        return reach;
    }

    // The highest block the rule can place anything at: a vertical gradient holds at no block above both its ends
    private static int top(SurfaceRules.RuleSource rule, WorldGenerationContext generation) {
        if (rule instanceof SurfaceRules.SequenceRuleSource sequence) {
            int top = Integer.MIN_VALUE;
            for (SurfaceRules.RuleSource r : sequence.sequence()) top = Math.max(top, top(r, generation));
            return top;
        }
        if (rule instanceof SurfaceRules.TestRuleSource test) {
            int top = top(test.thenRun(), generation);
            if (test.ifTrue() instanceof SurfaceRules.VerticalGradientConditionSource gradient) {
                top = Math.min(top, Math.max(gradient.trueAtAndBelow().resolveY(generation),
                        gradient.falseAtAndAbove().resolveY(generation) - 1));
            }
            return top;
        }
        return Integer.MAX_VALUE;
    }

    // Built once for the rule a world's generator surfaces with
    private static Rules rulesFor(SurfaceRules.RuleSource source) {
        Rules current = rules;
        if (current != null && current.source == source) return current;
        synchronized (TerrainSurface.class) {
            current = rules;
            if (current == null || current.source != source) rules = current = new Rules(source);
            return current;
        }
    }

    // The surface rule specialised below the preliminary surface, near and away from stone surfaces, and at and above
    // it for each biome
    private static final class Rules {
        final SurfaceRules.RuleSource source, lowerNear, lowerInterior;
        final List<SurfaceRules.StoneDepthCheck> floor = new ArrayList<>(), ceiling = new ArrayList<>();
        private final Map<Holder<Biome>, Optional<SurfaceRules.RuleSource>> upper = new ConcurrentHashMap<>();

        Rules(SurfaceRules.RuleSource source) {
            this.source = source;
            lowerNear = specialise(source, Boolean.FALSE, false, null);
            lowerInterior = specialise(source, Boolean.FALSE, true, null);
            Set<SurfaceRules.StoneDepthCheck> checks = new LinkedHashSet<>();
            collect(lowerNear, checks);
            for (SurfaceRules.StoneDepthCheck check : checks) {
                (check.surfaceType() == CaveSurface.CEILING ? ceiling : floor).add(check);
            }
        }

        SurfaceRules.RuleSource upper(Holder<Biome> biome) {
            return upper.computeIfAbsent(biome, b -> Optional.ofNullable(specialise(source, Boolean.TRUE, false, b)))
                    .orElse(null);
        }
    }

    // The rule with the conditions already known taken out, or null where it can place nothing: whether the block is
    // above the preliminary surface, if known; whether every stone-depth test fails; and the block's biome, if known.
    // Rules and conditions of other kinds are kept whole, so they are evaluated as before.
    private static SurfaceRules.RuleSource specialise(SurfaceRules.RuleSource rule, Boolean abovePreliminary,
                                                      boolean interior, Holder<Biome> biome) {
        if (rule instanceof SurfaceRules.SequenceRuleSource sequence) {
            List<SurfaceRules.RuleSource> kept = new ArrayList<>();
            for (SurfaceRules.RuleSource r : sequence.sequence()) {
                SurfaceRules.RuleSource s = specialise(r, abovePreliminary, interior, biome);
                if (s != null) kept.add(s);
            }
            if (kept.isEmpty()) return null;
            return kept.size() == 1 ? kept.get(0) : SurfaceRules.sequence(kept.toArray(SurfaceRules.RuleSource[]::new));
        }
        if (rule instanceof SurfaceRules.TestRuleSource test) {
            Boolean holds = holds(test.ifTrue(), abovePreliminary, interior, biome);
            if (holds == Boolean.FALSE) return null;
            SurfaceRules.RuleSource then = specialise(test.thenRun(), abovePreliminary, interior, biome);
            return then == null || holds == Boolean.TRUE ? then : SurfaceRules.ifTrue(test.ifTrue(), then);
        }
        return rule;
    }

    // Whether the condition holds, or null where that is not known
    private static Boolean holds(SurfaceRules.ConditionSource condition, Boolean abovePreliminary, boolean interior,
                                 Holder<Biome> biome) {
        if (condition == SurfaceRules.abovePreliminarySurface()) return abovePreliminary;
        if (condition instanceof SurfaceRules.StoneDepthCheck) return interior ? Boolean.FALSE : null;
        if (condition instanceof SurfaceRules.BiomeConditionSource test) {
            return biome == null ? null : biome.is(test.biomeNameTest);
        }
        if (condition instanceof SurfaceRules.NotConditionSource not) {
            Boolean inner = holds(not.target(), abovePreliminary, interior, biome);
            return inner == null ? null : !inner;
        }
        return null;
    }

    private static void collect(SurfaceRules.RuleSource rule, Set<SurfaceRules.StoneDepthCheck> checks) {
        if (rule instanceof SurfaceRules.SequenceRuleSource sequence) {
            for (SurfaceRules.RuleSource r : sequence.sequence()) collect(r, checks);
        } else if (rule instanceof SurfaceRules.TestRuleSource test) {
            collect(test.ifTrue(), checks);
            collect(test.thenRun(), checks);
        }
    }

    private static void collect(SurfaceRules.ConditionSource condition, Set<SurfaceRules.StoneDepthCheck> checks) {
        if (condition instanceof SurfaceRules.StoneDepthCheck check) {
            checks.add(check);
        } else if (condition instanceof SurfaceRules.NotConditionSource not) {
            collect(not.target(), checks);
        }
    }

    // A column of the chunk, written as a generating chunk writes a block: into its section, then into each heightmap
    // it keeps at its status, which are primed at the first write if missing; a fluid is marked for post-processing.
    // A heightmap is unchanged where a block it counts replaces another it counts, as stone's replacements mostly do.
    private static final class ChunkColumn implements BlockColumn {
        private final ChunkAccess chunk;
        private final LevelChunkSection[] sections;
        private final int minY, maxY;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private Heightmap.Types[] types;
        private Heightmap[] heightmaps;

        ChunkColumn(ChunkAccess chunk) {
            this.chunk = chunk;
            sections = chunk.getSections();
            minY = chunk.getMinBuildHeight();
            maxY = chunk.getMaxBuildHeight();
        }

        void at(int x, int z) {
            pos.setX(x).setZ(z);
        }

        @Override
        public BlockState getBlock(int y) {
            return chunk.getBlockState(pos.setY(y));
        }

        @Override
        public void setBlock(int y, BlockState state) {
            if (y < minY || y >= maxY) return;
            LevelChunkSection section = sections[(y - minY) >> 4];
            if (section.hasOnlyAir() && state.is(Blocks.AIR)) return;
            int x = pos.getX() & 15, z = pos.getZ() & 15;
            BlockState old = section.setBlockState(x, y & 15, z, state, false);
            if (heightmaps == null) keepHeightmaps();
            for (int k = 0; k < types.length; k++) {
                Predicate<BlockState> counted = types[k].isOpaque();
                if (!counted.test(old) || !counted.test(state)) heightmaps[k].update(x, y, z, state);
            }
            if (!state.getFluidState().isEmpty()) chunk.markPosForPostprocessing(pos.setY(y));
        }

        private void keepHeightmaps() {
            Set<Heightmap.Types> kept = chunk.getPersistedStatus().heightmapsAfter();
            Set<Heightmap.Types> missing = EnumSet.noneOf(Heightmap.Types.class);
            for (Heightmap.Types type : kept) if (!chunk.hasPrimedHeightmap(type)) missing.add(type);
            if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
            types = kept.toArray(Heightmap.Types[]::new);
            heightmaps = new Heightmap[types.length];
            for (int k = 0; k < types.length; k++) heightmaps[k] = chunk.getOrCreateHeightmapUnprimed(types[k]);
        }
    }
}
