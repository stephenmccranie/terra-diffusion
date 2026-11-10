package com.github.xandergos.terraindiffusionmc.pipeline;

import static com.github.xandergos.terraindiffusionmc.pipeline.SurfaceBiomes.*;

// Rule-based biome classifier port of _classify_biome in minecraft_api.py
public final class BiomeClassifier {
    // One coarse and one fine field vary both the temperature and the snow line; precipitation has its own
    private static final FastNoiseLite COARSE_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
    private static final FastNoiseLite FINE_NOISE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
    private static final FastNoiseLite PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    // Trees need a growing season, the days averaging above 0.9 C, at least 94 days long and averaging
    // at least 6.4 C (Paulsen and Koerner 2014); this one rule places tree lines on mountains and in the arctic
    private static final float GROWING_DAY_C = 0.9f;

    private static final float TREE_SEASON_MIN_DAYS = 94f;

    private static final float TREE_SEASON_MEAN_C = 6.4f;

    // Mean of the warmest three months of a sinusoidal year, above the annual mean, per unit of amplitude
    private static final float SUMMER_OF_AMPLITUDE = 0.9f;

    // Colder summers than this melt nothing; Ohmura's fit holds from here up
    private static final float NO_MELT_SUMMER_C = -10f;

    // The standard atmosphere cools this much per metre of height
    private static final float AIR_LAPSE = 0.0065f;

    private static final int CLIMATE_CHANNELS = 8;

    // Snow cover needs this temperature after noise
    private static final float SNOW_APPEARS_C = -3f;

    // Ice-surfaced mountain biomes need both of these
    static final float FROZEN_SURFACE_C = -12f;

    private static final float FROZEN_SURFACE_M = 4000f;

    private static final float SNOW_TIER1_START_C = -5f;

    private static final float SNOW_TIER1_END_C = -7.5f;

    private static final float SNOW_TIER2_END_C = -10f;

    private static final int LAYERS_PER_TIER = 7;

    private static final int SNOW_BLOCK_DEPTH = 8;

    private static final int MAX_SNOW_DEPTH = SNOW_BLOCK_DEPTH + LAYERS_PER_TIER;

    // Native pixels the land's shape is averaged over: what reads as a slope, a rise or a shore
    public static final int LANDFORM_RADIUS = 6;

    // Native pixels a box is read beyond what it uses: the averaging reach and the one-pixel border
    public static final int LANDFORM_MARGIN = LANDFORM_RADIUS + 1;

    // The land's shape at a grid's pixels: world height averaged over LANDFORM_RADIUS with a one-pixel border,
    // how far the world raised the ground on the same average, and the share of sea within that reach
    public record Landform(float[] heightPadded, float[] raise, float[] sea) {}

    // {height, raise, sea share} averaged over LANDFORM_RADIUS on an h x w grid of native pixels
    public static float[][] landform(float[] world, float[] raise, int h, int w) {
        float[] sea = new float[h * w];
        for (int i = 0; i < sea.length; i++) sea[i] = world[i] < 0f ? 1f : 0f;
        return new float[][]{boxBlur(world, h, w), boxBlur(raise, h, w), boxBlur(sea, h, w)};
    }

    private static float[] boxBlur(float[] src, int h, int w) {
        int radius = LANDFORM_RADIUS;
        float[] rows = new float[h * w], out = new float[h * w];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float sum = 0f;
                for (int k = -radius; k <= radius; k++) sum += src[r * w + Math.max(0, Math.min(w - 1, c + k))];
                rows[r * w + c] = sum / (2 * radius + 1);
            }
        }
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float sum = 0f;
                for (int k = -radius; k <= radius; k++) sum += rows[Math.max(0, Math.min(h - 1, r + k)) * w + c];
                out[r * w + c] = sum / (2 * radius + 1);
            }
        }
        return out;
    }

    // Classify biomes for a grid of pixels
    public static short[] classify(float[] elev, float[] climate, int i0, int j0, int coordStep,
                                    float[] elevPadded, Landform land, int H, int W, float pixelSizeM,
                                    byte[] snowLayersOut, boolean[] riverMask) {
        if (climate.length != CLIMATE_CHANNELS * H * W) {
            throw new IllegalArgumentException("climate needs " + CLIMATE_CHANNELS + " channels of " + H + "x" + W);
        }
        short[] out = new short[H * W];

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c * coordStep, ny = i0 + r * coordStep;
                float coarse = COARSE_NOISE.GetNoise(nx, ny);
                float fine = FINE_NOISE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * coarse + 0.2f * fine;
                snowNoise[idx] = 3.0f * coarse + 2.0f * fine;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);
        float[] landSlope = computeSlopeRatio(land.heightPadded(), H, W, pixelSizeM);

        // Process per-pixel
        TerrainSample sample = new TerrainSample();
        boolean useTerralith = TerralithCompat.isActive();

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels as WorldPipeline.computeClimate lays them out
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float pet      = potentialEvaporation(temp, tStd);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                float lapseRate = -climate[4 * H * W + idx];
                float lapse = Math.max(MIN_LAPSE, lapseRate);
                float tReg = climate[5 * H * W + idx] + tempNoise[idx];
                // Zones and rises follow the land's shape: the model's height of the averaged surface, against
                // the model's height here
                float modelAlt = Math.max(0f, elevVal - climate[6 * H * W + idx]);
                float landAlt = Math.max(0f, land.heightPadded()[(r + 1) * (W + 2) + c + 1] - land.raise()[idx]);
                float zoneTemp = temp + (modelAlt - landAlt) * lapseRate;
                float relElev = (tReg - zoneTemp) / lapse;
                float moist = precip / potentialEvaporation(tReg, tStd) * seasonPenalty;
                // Trees, snow and ice feel the air of their ground as the world raised it; climate keeps the
                // model's height
                float airC = temp - AIR_LAPSE * climate[6 * H * W + idx];
                float frost = airC + FROST_NOISE * snowNoise[idx];

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                boolean aboveTreeLine = aboveTreeLine(airC, amplitude);
                float summerC = airC + SUMMER_OF_AMPLITUDE * amplitude + FROST_NOISE * snowNoise[idx];
                boolean permanentSnow = permanentSnow(summerC, precip);
                if (aboveTreeLine) effTreeMoisture = 0f;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = airC + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < SNOW_APPEARS_C && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -7f;
                boolean cold      = temp >= -7f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                sample.worldX = j0 + c * coordStep;
                sample.worldZ = i0 + r * coordStep;
                sample.elev = elevVal;
                sample.altM = altM;
                sample.slope = landSlope[idx];
                sample.seaShare = land.sea()[idx];
                sample.temp = temp;
                sample.zoneTemp = zoneTemp;
                sample.airC = airC;
                sample.tReg = tReg;
                sample.relElev = relElev;
                sample.moist = moist;
                sample.frost = frost;
                sample.tStd = tStd;
                sample.precip = precip;
                sample.pCV = pCV;
                sample.aridity = aridity;
                sample.treeMoisture = treeMoisture;
                sample.growingSeason = growingSeason;
                sample.effTreeMoisture = effTreeMoisture;
                sample.aboveTreeLine = aboveTreeLine;
                sample.permanentSnow = permanentSnow;
                sample.bareThreshold = bareThreshold;
                sample.treesNone = treesNone;
                sample.treesSparse = treesSparse;
                sample.treesForest = treesForest;
                sample.treesDense = treesDense;
                sample.treesRainforest = treesRainforest;
                sample.barren = barren;
                sample.tooArid = tooArid;
                sample.tooCold = tooCold;
                sample.slopeMedium = slopeMedium;
                sample.slopeBare = slopeBare;
                sample.hasSnow = hasSnow;
                sample.isOcean = isOcean;
                sample.mountains = mountains;
                sample.lowland = lowland;
                sample.frozen = frozen;
                sample.cold = cold;
                sample.cool = cool;
                sample.temperate = temperate;
                sample.warm = warm;
                sample.hot = hot;

                short biome;
                if (!sample.isOcean && riverMask != null && riverMask[idx]) {
                    biome = riverBiome(sample, useTerralith);
                } else {
                    biome = TerralithClassifier.NONE;
                    if (useTerralith) {
                        biome = TerralithClassifier.pick(sample);
                    }
                    if (biome == TerralithClassifier.NONE) {
                        biome = classifyVanilla(sample);
                    }
                }

                out[idx] = biome;
                if (snowLayersOut != null) {
                    snowLayersOut[idx] = snowDepthFor(sample);
                }
            }
        }
        return out;
    }

    // The year is a sinusoid of this amplitude around the annual mean; its growing season is the arc above
    // GROWING_DAY_C, whose mean is the annual mean plus the amplitude times the arc's mean sine
    static boolean aboveTreeLine(float meanC, float amplitudeC) {
        float x = Math.max(-1f, Math.min(1f, (GROWING_DAY_C - meanC) / Math.max(1e-3f, amplitudeC)));
        double start = Math.asin(x);
        double arc = Math.PI - 2 * start;
        double days = 365 * arc / (2 * Math.PI);
        return days < TREE_SEASON_MIN_DAYS || meanC + amplitudeC * 2 * Math.cos(start) / arc < TREE_SEASON_MEAN_C;
    }

    // Snow outlasts the summer where a year's precipitation exceeds what the summer melts; at glacier lines
    // P = 645 + 296 T + 9 T^2 mm for mean summer air temperature T (Ohmura, Kasser and Funk 1992)
    static boolean permanentSnow(float summerC, float precipMm) {
        float t = Math.max(NO_MELT_SUMMER_C, summerC);
        return precipMm > 645f + 296f * t + 9f * t * t;
    }

    // True where a mountain surface may be ice: frozen peaks, frozen cliffs, glacial chasm
    static boolean frozenSurface(TerrainSample s) {
        return s.airC < FROZEN_SURFACE_C && s.altM > FROZEN_SURFACE_M;
    }

    private static byte snowDepthFor(TerrainSample s) {
        float temp = s.airC;
        if (temp > SNOW_TIER1_START_C) {
            return 1;
        }

        if (temp > SNOW_TIER1_END_C) {
            float step = (SNOW_TIER1_START_C - SNOW_TIER1_END_C) / LAYERS_PER_TIER;
            int layers = 1 + (int) ((SNOW_TIER1_START_C - temp) / step);
            return (byte) Math.max(1, Math.min(LAYERS_PER_TIER, layers));
        }

        float step = (SNOW_TIER1_END_C - SNOW_TIER2_END_C) / LAYERS_PER_TIER;
        int stacked = 1 + (int) ((SNOW_TIER1_END_C - temp) / step);
        int depth = SNOW_BLOCK_DEPTH + Math.max(1, Math.min(LAYERS_PER_TIER, stacked));
        return (byte) Math.min(MAX_SNOW_DEPTH, depth);
    }

    // Snow thins toward open water and the sea: within EASE_REACH_BLOCKS of it a column holds at most
    // EASE_LAYERS_PER_BLOCK layers per block of distance
    private static final int EASE_REACH_BLOCKS = 8;
    private static final float EASE_LAYERS_PER_BLOCK = 2f;

    // Caps the snow of the tH x tW tile at the centre of an H x W block grid by each column's distance to water
    // or below-sea ground; the grid's halo must be at least EASE_REACH_BLOCKS, so every cap depends only on the
    // column's position
    static void easeSnowNearWater(byte[] snow, float[] water, float[] bed, int H, int W, int halo, int tH, int tW) {
        int n = H * W;
        float[] dist = new float[n];
        for (int i = 0; i < n; i++) {
            dist[i] = water[i] > WaterNetwork.NO_WATER || bed[i] < 0f ? 0f : Float.MAX_VALUE;
        }
        float diag = (float) Math.sqrt(2.0);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                float d = dist[i];
                if (c > 0) d = Math.min(d, dist[i - 1] + 1f);
                if (r > 0) {
                    d = Math.min(d, dist[i - W] + 1f);
                    if (c > 0) d = Math.min(d, dist[i - W - 1] + diag);
                    if (c < W - 1) d = Math.min(d, dist[i - W + 1] + diag);
                }
                dist[i] = d;
            }
        }
        for (int r = H - 1; r >= 0; r--) {
            for (int c = W - 1; c >= 0; c--) {
                int i = r * W + c;
                float d = dist[i];
                if (c < W - 1) d = Math.min(d, dist[i + 1] + 1f);
                if (r < H - 1) {
                    d = Math.min(d, dist[i + W] + 1f);
                    if (c < W - 1) d = Math.min(d, dist[i + W + 1] + diag);
                    if (c > 0) d = Math.min(d, dist[i + W - 1] + diag);
                }
                dist[i] = d;
            }
        }
        for (int r = 0; r < tH; r++) {
            for (int c = 0; c < tW; c++) {
                float d = dist[(r + halo) * W + c + halo];
                if (d <= 0f || d >= EASE_REACH_BLOCKS) continue;
                int t = r * tW + c;
                int cap = Math.max(1, Math.round(EASE_LAYERS_PER_BLOCK * d));
                if (snow[t] > cap) snow[t] = (byte) cap;
            }
        }
    }

    private static final float MIN_LAPSE = 0.003f;

    private static final float FROST_NOISE = 0.3f;

    private static float potentialEvaporation(float temp, float tStd) {
        float tEff = Math.max(0f, temp + 0.5f * tStd);
        return Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
    }

    private static short riverBiome(TerrainSample s, boolean useTerralith) {
        if (useTerralith) {
            return TerralithClassifier.river(s);
        }
        return s.hasSnow ? FROZEN_RIVER : RIVER;
    }

    private static short classifyVanilla(TerrainSample s) {
        short biome = PLAINS;

        if (s.isOcean) {
            boolean deep = TerralithClassifier.deepSea(s);
            if (s.frozen) biome = deep ? DEEP_FROZEN_OCEAN : FROZEN_OCEAN;
            else if (s.cold) biome = deep ? DEEP_COLD_OCEAN : COLD_OCEAN;
            else if (s.warm || s.hot) biome = deep ? DEEP_LUKEWARM_OCEAN : WARM_OCEAN;
            else biome = deep ? DEEP_OCEAN : OCEAN;
        } else if (s.mountains) {
            if (s.slopeBare) {
                if (frozenSurface(s)) biome = FROZEN_PEAKS;
                else if (s.hasSnow) biome = SNOWY_SLOPES;
                else biome = STONY_PEAKS;
            } else if (s.hasSnow) {
                if (s.treesNone) biome = SNOWY_SLOPES;
                else if (s.treesSparse || s.treesForest) biome = SNOWY_TAIGA_SPARSE;
                else biome = SNOWY_TAIGA;
            } else if (s.treesNone) {
                if (s.barren) biome = WINDSWEPT_HILLS;
                else if (s.treeMoisture < 0.35f || s.precip < 350f) biome = GROVE_DRY;
                else biome = PLAINS;
            } else if (s.treesSparse || s.treesForest) {
                biome = TAIGA_SPARSE;
            } else {
                biome = TAIGA;
            }
        } else {
            // Lowland/midland
            if (s.hasSnow && s.treesNone) {
                biome = SNOWY_PLAINS;
            } else if (s.hasSnow) {
                biome = (s.treesSparse || s.treesForest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
            } else if (s.treesNone) {
                if (s.warm || s.hot) biome = DESERT;
                else if (s.barren && !s.lowland && (s.cold || s.cool || s.temperate)) biome = GROVE_DRY;
                else if (s.treeMoisture < 0.35f || s.precip < 350f) biome = GROVE_DRY;
                else biome = PLAINS;
            } else if (s.treesSparse || s.treesForest) {
                if (s.hot) biome = JUNGLE;
                else if (s.warm && s.treesSparse && !s.slopeMedium) biome = SAVANNA;
                else if (s.warm && s.treesForest) biome = FOREST_SPARSE;
                else if (s.temperate) biome = FOREST_SPARSE;
                else biome = TAIGA_SPARSE;
            } else if (s.treesDense) {
                if (s.hot) biome = JUNGLE;
                else if (s.warm && s.lowland) biome = SWAMP;
                else if (s.cool || s.cold) biome = TAIGA;
                else biome = FOREST;
            } else {
                if (s.hot || (s.warm && s.temp >= 18f && s.tStd < 5f)) biome = JUNGLE;
                else if (s.lowland) biome = SWAMP;
                else if (s.cool || s.cold) biome = TAIGA;
                else biome = FOREST;
            }
        }

        // Bare slope override for lowland/non-mountain cliffs
        if (s.slopeBare && !s.isOcean && !s.mountains) {
            if (frozenSurface(s)) biome = FROZEN_PEAKS;
            else if (s.hasSnow) biome = SNOWY_SLOPES;
            else biome = STONY_PEAKS;
        }

        return biome;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
