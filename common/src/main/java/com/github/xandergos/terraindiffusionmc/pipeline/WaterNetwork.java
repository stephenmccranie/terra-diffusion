package com.github.xandergos.terraindiffusionmc.pipeline;

// Shapes one column's ground and water from the river and lake fields at that column alone, so a column
// comes out the same whichever tile computes it
public final class WaterNetwork {

    public static final float NO_WATER = -1.0e9f;

    private static final FastNoiseLite WIDTH_VAR = makeFnl(0x3C91, 1f / 1400f, 2, 2f, 0.5f);

    private static final FastNoiseLite BANK_ROUGH = makeFnl(0x1F55, 1f / 11f, 2, 2f, 0.55f);

    // How far the valley reaches from the centreline, in channel half-widths
    private static final float VALLEY_WIDTHS = 2.5f;

    // Narrowest drawn channel, in blocks
    private static final float MIN_CHANNEL_BLOCKS = 32f;

    // Shallowest drawn channel, in blocks
    private static final float MIN_DEPTH_BLOCKS = 1.5f;

    // Width of the soft floor that keeps a channel's bed below its water, in blocks
    private static final float SOFT_MIN_BLOCKS = 1.5f;

    // Share of the half-width carved to full depth before the bed rises to the bank
    private static final float CHANNEL_FLAT_FRACTION = 0.55f;

    private static final float WIDTH_VARIATION = 0.25f;

    private static final float BANK_ROUGH_BLOCKS = 0.9f;

    // A river reaching the sea is cut through ground this close above sea level
    private static final float MOUTH_BAR_BLOCKS = 2f;

    // A channel runs on into the sea floor down to this depth
    private static final float MOUTH_FADE_BLOCKS = 6f;

    private static final float LAKE_DEPTH_GAIN = 1.9f;
    private static final float LAKE_MIN_BED_BLOCKS = 4f;
    private static final float LAKE_MAX_BED_BLOCKS = 17f;

    public static final class Sample {
        public float bedElevM;
        public float waterSurfaceM;

        public boolean hasWater() {
            return waterSurfaceM > NO_WATER;
        }
    }

    private WaterNetwork() {
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(freq);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float fadeOut(float t) {
        t = clamp01(t);
        return 1f - t * t * (3f - 2f * t);
    }

    private static int drawnBlock(float meters, float metersPerBlock) {
        return (int) Math.floor(Math.floor(meters) / metersPerBlock);
    }

    private static float snapToBlock(float meters, float metersPerBlock) {
        return (float) Math.ceil(Math.floor(meters / metersPerBlock) * metersPerBlock);
    }

    // distBlocks is the distance to the nearest channel centreline, negative away from every river. Water is
    // drawn only inside a channel or a lake; beside a channel the ground rises at least to the water's level,
    // so no dry column lies below water next to it.
    public static void sample(int worldX, int worldZ, float elevM, float smoothElevM,
                              float distBlocks, float levelM, float widthM, float depthM,
                              float lakeSurfaceM, float lakeDeepM, float metersPerBlock, Sample out) {
        out.bedElevM = elevM;
        out.waterSurfaceM = NO_WATER;

        boolean submarine = elevM < 0f;
        float seaFade = submarine ? clamp01(1f + elevM / (MOUTH_FADE_BLOCKS * metersPerBlock)) : 1f;
        if (seaFade <= 0f) {
            return;
        }

        float surfaceM = levelM > 0f ? snapToBlock(levelM, metersPerBlock) : 0f;

        boolean nearRiver = distBlocks >= 0f;
        float halfWidth = 0.5f * Math.max(MIN_CHANNEL_BLOCKS, widthM / metersPerBlock)
                * (1f + WIDTH_VARIATION * Math.max(0f, WIDTH_VAR.GetNoise(worldX, worldZ)));
        float inner = CHANNEL_FLAT_FRACTION * halfWidth;
        float channel = !nearRiver ? 0f
                : distBlocks <= inner ? 1f : fadeOut((distBlocks - inner) / (halfWidth - inner));
        float valley = !nearRiver ? 0f : fadeOut(distBlocks / (halfWidth * VALLEY_WIDTHS));

        boolean inBasin = lakeSurfaceM > NO_WATER;
        float groundM = elevM + (smoothElevM - elevM) * (inBasin ? 1f : valley);
        float riverDepth = Math.max(MIN_DEPTH_BLOCKS * metersPerBlock, depthM);

        if (submarine) {
            out.bedElevM = elevM - riverDepth * channel * seaFade;
            return;
        }

        float bed = groundM + (surfaceM - groundM) * valley - riverDepth * channel;

        float need = surfaceM - MIN_DEPTH_BLOCKS * metersPerBlock;
        float k = SOFT_MIN_BLOCKS * metersPerBlock;
        float h = clamp01(0.5f + 0.5f * (bed - need) / k);
        float soft = bed + (need - bed) * h - k * h * (1f - h);
        bed += (soft - bed) * channel;

        // A river that has come all the way down to sea level can still be left short of the sea
        if (surfaceM <= 0f && valley > 0f && groundM < MOUTH_BAR_BLOCKS * metersPerBlock) {
            bed = Math.min(bed, groundM + (-metersPerBlock - groundM) * valley);
        }

        float roughened = bed + BANK_ROUGH.GetNoise(worldX, worldZ) * BANK_ROUGH_BLOCKS * metersPerBlock
                * valley * (1f - channel);
        if (bed >= surfaceM) {
            roughened = Math.max(roughened, surfaceM);
        }
        bed = Math.min(roughened, groundM);

        if (inBasin && lakeDeepM > 0.01f) {
            float here = Math.max(0f, lakeSurfaceM - groundM);
            float t = clamp01(here / lakeDeepM);
            t = t * t * (3f - 2f * t);
            float target = Math.min(LAKE_MAX_BED_BLOCKS * metersPerBlock,
                    Math.max(LAKE_MIN_BED_BLOCKS * metersPerBlock, LAKE_DEPTH_GAIN * lakeDeepM));
            float shaped = bed + (lakeSurfaceM - target * t - bed) * t;
            bed = Math.min(shaped, Math.max(bed, lakeSurfaceM - 0.6f * metersPerBlock));
        }

        boolean inChannel = nearRiver && distBlocks <= halfWidth;
        if (nearRiver && !inChannel && !inBasin) {
            bed = Math.max(bed, surfaceM - (distBlocks - halfWidth) * metersPerBlock);
        }
        out.bedElevM = bed;

        if (inChannel && drawnBlock(surfaceM, metersPerBlock) > drawnBlock(bed, metersPerBlock)) {
            out.waterSurfaceM = surfaceM;
        }
        if (inBasin && bed < lakeSurfaceM && lakeSurfaceM > out.waterSurfaceM) {
            out.waterSurfaceM = lakeSurfaceM;
        }
    }
}
