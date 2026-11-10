package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.HolderReferenceAccessor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// Fits placements by height to this world. Its features were written for vanilla's world, so their height ranges
// count in vanilla's Y: at world load, anchors measured from the world's floor or ceiling are pinned to where
// vanilla's are, ore bands reach on down to this world's floor and stone patches stay out of the deepslate; as
// features place, bands rise with the relief curve's lift of the land
public final class DeepOres {

    private static final Logger LOG = LoggerFactory.getLogger(DeepOres.class);

    // The lowest and highest block of vanilla's overworld
    private static final int VANILLA_MIN_Y = -64, VANILLA_MAX_Y = 319;

    // Deepslate is pure below Y 0 and mixed up to Y 8, so 8 is the first wholly-stone level
    private static final int STONE_FLOOR = 8;

    private static final double PATCH_SCALE = 0.5;

    // Blob-shaped rock features only, so surface disks and vegetation are not caught
    private static final List<String> PATCH_FEATURES = List.of(
            "minecraft:ore",
            "minecraft:scattered_ore",
            "minecraft:netherrack_replace_blobs",
            "minecraft:geode");

    private static final List<String> PATCH_BLOCKS = List.of(
            "\"minecraft:granite\"",
            "\"minecraft:diorite\"",
            "\"minecraft:andesite\"",
            "\"minecraft:dirt\"");

    private DeepOres() {
    }

    @SuppressWarnings("unchecked")
    public static void apply(ServerLevel world) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, world.registryAccess());
        int minY = world.getMinBuildHeight();

        // The features this world's biomes place; the registry also holds those of other dimensions
        Set<Holder.Reference<PlacedFeature>> features = new LinkedHashSet<>();
        for (Holder<Biome> biome : world.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> feature : step) {
                    if (feature instanceof Holder.Reference<PlacedFeature> ref) features.add(ref);
                }
            }
        }

        int pinned = 0, oresExtended = 0, alreadyDeep = 0, patches = 0, failed = 0;
        for (Holder.Reference<PlacedFeature> ref : features) {
            PlacedFeature placed = ref.value();

            JsonElement encoded = PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed)
                    .result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) continue;
            JsonObject root = encoded.getAsJsonObject();

            boolean anchored = pinToVanilla(root.get("placement"));
            boolean patch = placesPatchBlock(ops, placed.feature().value());
            boolean ore = placed.feature().value().config() instanceof OreConfiguration;
            int outcome = !hasHeightRange(root) ? 0 : patch ? clampPatch(root) : ore ? extendOre(root, minY) : 0;
            if (outcome < 0) failed++;
            else if (outcome == 0 && ore && !patch) alreadyDeep++;
            if (!anchored && outcome <= 0) continue;

            PlacedFeature rebuilt = PlacedFeature.DIRECT_CODEC.parse(ops, encoded)
                    .resultOrPartial(err -> LOG.warn("rewrite rejected for {}: {}",
                            ref.key().location(), err))
                    .orElse(null);
            if (rebuilt == null) {
                failed++;
                continue;
            }

            ((HolderReferenceAccessor<PlacedFeature>) ref).terrainDiffusion$bindValue(rebuilt);
            if (anchored) pinned++;
            if (outcome > 0 && patch) patches++;
            else if (outcome > 0) oresExtended++;
        }

        LOG.info("Height ranges pinned to vanilla's Y: {}. Ore bands extended to Y {}: {} rewritten, {} already "
                        + "reached the bottom. Stone and dirt patches floored at Y {} and scaled by {}: {}. "
                        + "Left alone: {}",
                pinned, minY, oresExtended, alreadyDeep, STONE_FLOOR, PATCH_SCALE, patches, failed);
    }

    // Turns every anchor measured from the world's floor or ceiling into the Y it names in vanilla's world, and
    // tells whether there was one
    static boolean pinToVanilla(JsonElement node) {
        boolean pinned = false;
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) pinned |= pinToVanilla(child);
        } else if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (object.has("above_bottom")) {
                object.addProperty("absolute", VANILLA_MIN_Y + object.remove("above_bottom").getAsInt());
                return true;
            }
            if (object.has("below_top")) {
                object.addProperty("absolute", VANILLA_MAX_Y - object.remove("below_top").getAsInt());
                return true;
            }
            for (Map.Entry<String, JsonElement> child : object.entrySet()) pinned |= pinToVanilla(child.getValue());
        }
        return pinned;
    }

    /**
     * Where a feature placed by height goes in this world. Vanilla made its bands for unraised ground, and the relief
     * curve lifts the land wherever its regional base stands above relief.start. There each band rises with the
     * terrain: a band at vanilla Y y lies at the world height Relief#band gives its model height (y - 63) blocks at
     * this world's metres per block, over the column's regional base, so bands follow the lift and summit stretch
     * the ground got there, agree between neighbouring columns, and keep their depth under ground above the sea.
     * Each vanilla block layer becomes as many layers as the transform stretches it, with as many placements, so
     * ores keep vanilla's density. Deepslate, and ground the curve leaves alone, keeps vanilla's heights and random
     * draws.
     */
    public static Stream<BlockPos> positions(PlacementContext context, RandomSource random, BlockPos pos,
                                             HeightProvider height) {
        int y = height.sample(random, context);
        float base = TerrainWater.regionalBaseM(pos.getX(), pos.getZ());
        if (y < STONE_FLOOR || !LocalTerrainProvider.getInstance().relief.raises(base)) return Stream.of(pos.atY(y));
        double low = bandY(y, base);
        double span = bandY(y + 1, base) - low;
        int copies = (int) span + (random.nextDouble() < span - (int) span ? 1 : 0);
        Stream.Builder<BlockPos> out = Stream.builder();
        for (int k = 0; k < copies; k++) {
            out.add(pos.atY((int) Math.floor(low + random.nextDouble() * span)));
        }
        return out.build();
    }

    // World Y, fractional, of the bottom of vanilla's block layer y over a regional base at model height base
    private static double bandY(int y, float base) {
        float blockM = TerrainClimate.metresPerBlock();
        int sea = HeightConverter.SEA_LEVEL;
        return sea + LocalTerrainProvider.getInstance().relief.band((y - sea) * blockM, base, (STONE_FLOOR - sea) * blockM)
                / blockM;
    }

    private static boolean hasHeightRange(JsonObject root) {
        if (!root.has("placement") || !root.get("placement").isJsonArray()) return false;
        for (JsonElement element : root.getAsJsonArray("placement")) {
            if (element.isJsonObject() && element.getAsJsonObject().has("type")
                    && element.getAsJsonObject().get("type").getAsString()
                            .equals("minecraft:height_range")) {
                return true;
            }
        }
        return false;
    }

    // Inspects the configured feature as encoded JSON
    private static boolean placesPatchBlock(RegistryOps<JsonElement> ops, ConfiguredFeature<?, ?> cf) {
        JsonElement encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, cf).result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) return false;
        JsonObject root = encoded.getAsJsonObject();
        if (!root.has("type") || !PATCH_FEATURES.contains(root.get("type").getAsString())) return false;

        String text = encoded.toString();
        for (String block : PATCH_BLOCKS) {
            if (text.contains(block)) return true;
        }
        return false;
    }

    private static JsonObject find(JsonArray placement, String type) {
        for (JsonElement element : placement) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            if (modifier.has("type") && modifier.get("type").getAsString().equals(type)) {
                return modifier;
            }
        }
        return null;
    }

    private static int clampPatch(JsonObject root) {
        JsonArray placement = root.getAsJsonArray("placement");
        JsonObject range = find(placement, "minecraft:height_range");
        if (range == null || !range.has("height") || !range.get("height").isJsonObject()) return -1;

        JsonObject provider = range.getAsJsonObject("height");
        if (!provider.has("min_inclusive") || !provider.has("max_inclusive")) return -1;
        if (!provider.get("min_inclusive").isJsonObject()
                || !provider.get("max_inclusive").isJsonObject()) return -1;

        Integer low = anchor(provider.getAsJsonObject("min_inclusive"));
        Integer high = anchor(provider.getAsJsonObject("max_inclusive"));
        if (low == null || high == null) return -1;
        if (high <= STONE_FLOOR) return -1;

        if (low < STONE_FLOOR) provider.add("min_inclusive", absolute(STONE_FLOOR));

        scale(root, placement, find(placement, "minecraft:count"),
                find(placement, "minecraft:rarity_filter"), PATCH_SCALE);
        return 1;
    }

    private static int extendOre(JsonObject root, int minY) {
        JsonArray placement = root.getAsJsonArray("placement");
        JsonObject range = find(placement, "minecraft:height_range");
        if (range == null || !range.has("height") || !range.get("height").isJsonObject()) return -1;

        JsonObject provider = range.getAsJsonObject("height");
        if (!provider.has("min_inclusive") || !provider.has("max_inclusive")) return -1;
        if (!provider.get("min_inclusive").isJsonObject()
                || !provider.get("max_inclusive").isJsonObject()) return -1;

        Integer low = anchor(provider.getAsJsonObject("min_inclusive"));
        Integer high = anchor(provider.getAsJsonObject("max_inclusive"));
        if (low == null || high == null || high < low) return -1;
        if (low <= minY) return 0;

        int oldSpan = high - low + 1;
        int tailSpan = low - minY;

        JsonObject tail = new JsonObject();
        tail.addProperty("type", "minecraft:uniform");
        tail.add("min_inclusive", absolute(minY));
        tail.add("max_inclusive", absolute(low - 1));

        JsonArray distribution = new JsonArray();
        distribution.add(weighted(provider, oldSpan));
        distribution.add(weighted(tail, tailSpan));

        JsonObject mixed = new JsonObject();
        mixed.addProperty("type", "minecraft:weighted_list");
        mixed.add("distribution", distribution);
        range.add("height", mixed);

        scale(root, placement, find(placement, "minecraft:count"), null,
                (double) (oldSpan + tailSpan) / oldSpan);
        return 1;
    }

    private static JsonObject weighted(JsonObject data, int weight) {
        JsonObject entry = new JsonObject();
        entry.add("data", data);
        entry.addProperty("weight", Math.max(1, weight));
        return entry;
    }

    private static JsonObject absolute(int y) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("absolute", y);
        return anchor;
    }

    private static Integer anchor(JsonObject anchor) {
        return anchor.has("absolute") ? anchor.get("absolute").getAsInt() : null;
    }

    // Modifiers run in order, so a count added where none existed must lead
    private static void scale(JsonObject root, JsonArray placement, JsonObject count,
                              JsonObject rarity, double factor) {
        if (count != null) {
            scaleCount(count, factor);
            return;
        }
        if (rarity != null && rarity.has("chance") && rarity.get("chance").isJsonPrimitive()) {
            int chance = rarity.get("chance").getAsInt();
            rarity.addProperty("chance", Math.max(1, (int) Math.round(chance / factor)));
            return;
        }
        JsonObject added = new JsonObject();
        added.addProperty("type", "minecraft:count");
        added.addProperty("count", Math.max(1, (int) Math.round(factor)));
        JsonArray reordered = new JsonArray();
        reordered.add(added);
        for (JsonElement element : placement) reordered.add(element);
        root.add("placement", reordered);
    }

    private static void scaleCount(JsonObject count, double factor) {
        JsonElement value = count.get("count");
        if (value == null) return;
        if (value.isJsonPrimitive()) {
            count.addProperty("count", Math.max(1, (int) Math.round(value.getAsInt() * factor)));
            return;
        }
        if (!value.isJsonObject()) return;

        JsonObject inner = value.getAsJsonObject();
        if (inner.has("distribution") && inner.get("distribution").isJsonArray()) {
            for (JsonElement entry : inner.getAsJsonArray("distribution")) {
                if (!entry.isJsonObject()) continue;
                JsonObject wrapper = entry.getAsJsonObject();
                JsonElement data = wrapper.get("data");
                if (data != null && data.isJsonPrimitive()) {
                    wrapper.addProperty("data",
                            Math.max(1, (int) Math.round(data.getAsInt() * factor)));
                }
            }
            return;
        }

        JsonObject bounds = inner.has("value") && inner.get("value").isJsonObject()
                ? inner.getAsJsonObject("value")
                : inner;
        for (String key : List.of("min_inclusive", "max_inclusive", "value")) {
            if (bounds.has(key) && bounds.get(key).isJsonPrimitive()) {
                bounds.addProperty(key,
                        Math.max(1, (int) Math.round(bounds.get(key).getAsInt() * factor)));
            }
        }
    }
}
