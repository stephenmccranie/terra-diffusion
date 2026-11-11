package com.github.xandergos.terraindiffusionmc.pipeline;

// Shapes one column's ground and water from the rivers and lake fields at that column alone, so a column comes out
// the same whichever tile computes it. Every river has the same cross-section about its centreline: a channel that
// holds its water, a bank above that water, and beyond the bank a valley side: ground standing above the bank is
// drawn down towards it, wholly beside the river and not at all at the valley's edge, so it keeps its own shape
// at a lower height and meets the land beyond without a step. Ground already lower is left alone, so a river
// crossing a plain does not mark it. Either bank wanders in and out on its own, so a river is nowhere a ribbon of
// one width. A column inside a channel takes the water of the nearest channel holding it and is carved to the
// deepest channel at that level; any other column is drawn down by the river that leaves it lowest, and never
// left below the water of a channel beside it.
public final class WaterNetwork {

    public static final float NO_WATER = -1.0e9f;

    // A bank lies nearer or farther from its river's centreline by up to this share of the half-width, in bays and
    // points about ninety blocks long
    static final float WIDTH_VARIATION = 0.35f;
    private static final FastNoiseLite WIDTH_VAR = makeFnl(0x3C91, 1f / 90f, 2, 2f, 0.5f);

    // Shallowest drawn channel, in blocks
    private static final float MIN_DEPTH_BLOCKS = 1.5f;

    // Share of the half-width carved to full depth before the bed rises to the bank
    private static final float CHANNEL_FLAT_FRACTION = 0.55f;

    // A river's bank lies this many blocks above its water
    private static final float BANK_BLOCKS = 1f;

    // A channel runs on into the sea floor down to this depth
    private static final float MOUTH_FADE_BLOCKS = 6f;

    private static final float LAKE_DEPTH_GAIN = 1.9f;
    private static final float LAKE_MIN_BED_BLOCKS = 4f;
    private static final float LAKE_MAX_BED_BLOCKS = 17f;

    public static final class Sample {
        public float bedElevM;
        public float waterSurfaceM;
        // The level the water surface was snapped down from, in metres, and whether a lake holds it
        public float levelM;
        public boolean lake;

        public boolean hasWater() {
            return waterSurfaceM > NO_WATER;
        }
    }

    // The rivers near one column, each as the point of one of its segments nearest the column: the distance to it,
    // and the water level, width, depth and valley reach there, all in metres
    static final class Reaches {
        private static final int FIELDS = 5;
        private float[] v = new float[FIELDS * 16];
        private int count;

        void clear() {
            count = 0;
        }

        void add(float distanceM, float levelM, float widthM, float depthM, float valleyM) {
            if ((count + 1) * FIELDS > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
            int o = count++ * FIELDS;
            v[o] = distanceM;
            v[o + 1] = levelM;
            v[o + 2] = widthM;
            v[o + 3] = depthM;
            v[o + 4] = valleyM;
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

    // The surface of water at a level, whole blocks up from sea level
    private static float water(float levelM, float metersPerBlock) {
        return levelM > 0f ? snapToBlock(levelM, metersPerBlock) : 0f;
    }

    // Farthest from its centreline a river of this width and valley reach shapes the ground, in metres
    static float reachM(float widthM, float valleyM) {
        return 0.5f * widthM * (1f + WIDTH_VARIATION) + valleyM;
    }

    public static void sample(int worldX, int worldZ, float elevM, float smoothElevM, Reaches rivers,
                              float lakeLevelM, float lakeDeepM, float metersPerBlock, Sample out) {
        out.bedElevM = elevM;
        out.waterSurfaceM = NO_WATER;
        out.levelM = NO_WATER;
        out.lake = false;

        boolean submarine = elevM < 0f;
        float seaFade = submarine ? clamp01(1f + elevM / (MOUTH_FADE_BLOCKS * metersPerBlock)) : 1f;
        if (seaFade <= 0f) {
            return;
        }

        float widen = 1f + WIDTH_VARIATION * WIDTH_VAR.GetNoise(worldX, worldZ);
        float bank = BANK_BLOCKS * metersPerBlock;
        float minDepth = MIN_DEPTH_BLOCKS * metersPerBlock;
        float[] v = rivers.v;
        float surfaceM = NO_WATER, levelM = NO_WATER, nearest = Float.MAX_VALUE, near = 0f;
        for (int k = 0; k < rivers.count; k++) {
            int o = k * Reaches.FIELDS;
            float d = v[o];
            near = Math.max(near, fadeOut((d - 0.5f * v[o + 2] * widen) / v[o + 4]));
            if (d < 0.5f * v[o + 2] * widen && d < nearest) {
                nearest = d;
                levelM = v[o + 1];
                surfaceM = water(levelM, metersPerBlock);
            }
        }
        // A lake's water stands at the whole block below its level and covers all ground below that level, so it
        // fills the basin its rivers drain into
        boolean inBasin = lakeLevelM > NO_WATER;
        float lakeSurfaceM = inBasin ? water(lakeLevelM, metersPerBlock) : NO_WATER;
        float groundM = elevM + (smoothElevM - elevM) * (inBasin ? 1f : near);
        float channelBed = Float.MAX_VALUE, carve = 0f;
        float cut = Float.MAX_VALUE, held = -Float.MAX_VALUE;
        for (int k = 0; k < rivers.count; k++) {
            int o = k * Reaches.FIELDS;
            float d = v[o];
            float halfWidth = 0.5f * v[o + 2] * widen;
            float w = water(v[o + 1], metersPerBlock);
            if (d < halfWidth) {
                if (Math.abs(w - surfaceM) > 0.5f * metersPerBlock) continue;
                float inner = CHANNEL_FLAT_FRACTION * halfWidth;
                float shape = d <= inner ? 1f : fadeOut((d - inner) / (halfWidth - inner));
                float depth = Math.max(minDepth, v[o + 3]) * shape;
                channelBed = Math.min(channelBed, w - depth);
                carve = Math.max(carve, depth);
            } else {
                float kept = 1f - fadeOut((d - halfWidth) / v[o + 4]);
                cut = Math.min(cut, w + bank + Math.max(0f, groundM - w - bank) * kept);
                held = Math.max(held, w - (d - halfWidth));
            }
        }

        if (submarine) {
            out.bedElevM = elevM - carve * seaFade;
            return;
        }

        float bed;
        if (surfaceM > NO_WATER) {
            bed = Math.min(groundM, channelBed);
        } else {
            // A lake's shore keeps a bank above the lake, whatever river runs below it
            bed = Math.min(groundM, Math.max(cut, lakeSurfaceM + bank));
            if (!inBasin) {
                bed = Math.max(bed, held);
            }
        }

        float here = inBasin ? lakeLevelM - groundM : 0f;
        if (here > 0f) {
            float t = clamp01(here / lakeDeepM);
            t = t * t * (3f - 2f * t);
            float target = Math.min(LAKE_MAX_BED_BLOCKS * metersPerBlock,
                    Math.max(LAKE_MIN_BED_BLOCKS * metersPerBlock, LAKE_DEPTH_GAIN * lakeDeepM));
            float shaped = bed + (lakeSurfaceM - target * t - bed) * t;
            bed = Math.min(shaped, lakeSurfaceM - 0.6f * metersPerBlock);
        }
        out.bedElevM = bed;

        if (surfaceM > NO_WATER && drawnBlock(surfaceM, metersPerBlock) > drawnBlock(bed, metersPerBlock)) {
            out.waterSurfaceM = surfaceM;
            out.levelM = levelM;
        }
        if (inBasin && bed < lakeSurfaceM && lakeSurfaceM > out.waterSurfaceM) {
            out.waterSurfaceM = lakeSurfaceM;
            out.levelM = lakeLevelM;
            out.lake = true;
        }
    }
}
