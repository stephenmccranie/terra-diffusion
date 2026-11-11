package com.github.xandergos.terraindiffusionmc.pipeline;

// Shapes one column's ground and water from the rivers and lake fields at that column alone, so a column comes out
// the same whichever tile computes it. Every river has the same cross-section about its centreline: a channel that
// holds its water, a bank above that water, and beyond the bank a valley side: ground standing above the bank is
// drawn down towards it, wholly beside the river and not at all at the valley's edge, so it keeps its own shape
// at a lower height and meets the land beyond without a step. Ground already lower is left alone, so a river
// crossing a plain does not mark it. Either bank wanders in and out on its own, so a river is nowhere a ribbon of
// one width, and in a bend the channel spreads, its deepest water lies towards the outside bank and a shoal slopes
// up to the inside one. A column inside a channel takes the water of the nearest channel holding it and is carved
// to the deepest channel at that level; any other column is drawn down by the river that leaves it lowest, and
// never left below the water of a channel beside it.
public final class WaterNetwork {

    public static final float NO_WATER = -1.0e9f;

    // A bank lies nearer or farther from its river's centreline by up to this share of the half-width, in bays and
    // points about ninety blocks long
    private static final float WIDTH_VARIATION = 0.35f;
    private static final FastNoiseLite WIDTH_VAR = makeFnl(0x3C91, 1f / 90f, 2, 2f, 0.5f);

    // A channel takes the full shape of a bend where it turns in a radius of this many of its widths or fewer:
    // there it is this share wider and deeper, and its deepest water lies this share of the half-width towards
    // the outside bank. Its inside bank lies no farther from its centreline than this share of the radius, so
    // it rounds the bend however tight
    private static final float BEND_WIDTHS = 1.5f;
    private static final float INSIDE_SHARE = 0.8f;
    private static final float BEND_SPREAD = 0.25f;
    private static final float BEND_POOL = 0.4f;
    private static final float BEND_SHIFT = 0.5f;

    // Shallowest drawn channel, in blocks
    private static final float MIN_DEPTH_BLOCKS = 1.5f;

    // Share of the half-width carved to full depth before the bed rises to the bank
    private static final float CHANNEL_FLAT_FRACTION = 0.55f;

    // A river's bank lies this many blocks above its water
    private static final float BANK_BLOCKS = 1f;

    // A channel runs on into the sea floor down to this depth
    private static final float MOUTH_FADE_BLOCKS = 6f;

    // At the outer edge of its shore a lake covers only ground this many blocks below its water, and less the
    // nearer its open water, so its waterline follows the ground across the shore and never the shore's edge
    private static final float LAKE_SHORE_BLOCKS = 4f;

    // Share of the way out across its shore that a lake fills the channels crossing it and keeps a bank above
    // itself; beyond, a channel runs at its own level and the bank eases down
    private static final float LAKE_HELD_SHARE = 0.75f;

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
    // and the water level, width, depth and valley reach there, all in metres, and how far the column lies to the
    // outside of the river's bend there: the river's width over the radius of the bend, less than nothing on its
    // inside
    static final class Reaches {
        private static final int FIELDS = 6;
        private float[] v = new float[FIELDS * 16];
        private int count;

        void clear() {
            count = 0;
        }

        void add(float distanceM, float levelM, float widthM, float depthM, float valleyM, float outside) {
            if ((count + 1) * FIELDS > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
            int o = count++ * FIELDS;
            v[o] = distanceM;
            v[o + 1] = levelM;
            v[o + 2] = widthM;
            v[o + 3] = depthM;
            v[o + 4] = valleyM;
            v[o + 5] = outside;
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
        return 0.5f * widthM * (1f + WIDTH_VARIATION) * (1f + BEND_SPREAD) + valleyM;
    }

    // lakeShore is how far out across its lake's shore the column lies, from nothing in the lake's open water to
    // one at the shore's outer edge
    public static void sample(int worldX, int worldZ, float elevM, float smoothElevM, Reaches rivers,
                              float lakeLevelM, float lakeDeepM, float lakeShore, float metersPerBlock,
                              Sample out) {
        out.bedElevM = elevM;
        out.waterSurfaceM = NO_WATER;
        out.levelM = NO_WATER;
        out.lake = false;

        boolean submarine = elevM < 0f;
        float seaFade = submarine ? clamp01(1f + elevM / (MOUTH_FADE_BLOCKS * metersPerBlock)) : 1f;
        if (seaFade <= 0f) {
            return;
        }

        // Only a column near a river needs to know how the river spreads there
        float widen = rivers.count == 0 ? 1f : 1f + WIDTH_VARIATION * WIDTH_VAR.GetNoise(worldX, worldZ);
        float bank = BANK_BLOCKS * metersPerBlock;
        float minDepth = MIN_DEPTH_BLOCKS * metersPerBlock;
        float[] v = rivers.v;
        float surfaceM = NO_WATER, levelM = NO_WATER, nearest = Float.MAX_VALUE, near = 0f;
        for (int k = 0; k < rivers.count; k++) {
            int o = k * Reaches.FIELDS;
            float d = v[o];
            // From here on the reach's width is its half-width at this column, and its bend how far the channel
            // takes a bend's shape there
            float bend = Math.max(-1f, Math.min(1f, BEND_WIDTHS * v[o + 5]));
            float halfWidth = 0.5f * v[o + 2] * widen * (1f + BEND_SPREAD * Math.abs(bend));
            if (v[o + 5] < 0f) halfWidth = Math.min(halfWidth, INSIDE_SHARE * v[o + 2] / -v[o + 5]);
            v[o + 2] = halfWidth;
            v[o + 5] = bend;
            near = Math.max(near, fadeOut((d - halfWidth) / v[o + 4]));
            if (d < halfWidth && d < nearest) {
                nearest = d;
                levelM = v[o + 1];
                surfaceM = water(levelM, metersPerBlock);
            }
        }
        // A lake's water stands at the whole block below its level and covers the ground below that level, so it
        // fills the basin its rivers drain into. A river's channel fades out over the inner half of the lake's
        // shore, as it does into the sea.
        boolean inBasin = lakeLevelM > NO_WATER;
        float lakeSurfaceM = inBasin ? water(lakeLevelM, metersPerBlock) : NO_WATER;
        float lakeCoversM = lakeSurfaceM - lakeShore * LAKE_SHORE_BLOCKS * metersPerBlock;
        float channelFade = inBasin ? Math.min(1f, 2f * lakeShore) : 1f;
        // The shore a lake leaves dry stands a bank above it, easing down over the shore's outer part to what the
        // lake would cover at its edge, so it ends without a step
        float lakeBankM = lakeSurfaceM + bank - clamp01((lakeShore - LAKE_HELD_SHARE) / (1f - LAKE_HELD_SHARE))
                * (LAKE_SHORE_BLOCKS * metersPerBlock + bank);
        float groundM = elevM + (smoothElevM - elevM) * (inBasin ? 1f : near);
        float channelBed = Float.MAX_VALUE, carve = 0f;
        float cut = Float.MAX_VALUE, held = -Float.MAX_VALUE;
        for (int k = 0; k < rivers.count; k++) {
            int o = k * Reaches.FIELDS;
            float d = v[o];
            float halfWidth = v[o + 2];
            float w = water(v[o + 1], metersPerBlock);
            // The bank falls with the river's level, so the ground beside it steps down smoothly, and it always
            // reaches the block above the water
            float bankM = Math.max(v[o + 1] + bank, water(v[o + 1] + bank, metersPerBlock));
            if (d < halfWidth) {
                if (Math.abs(w - surfaceM) > 0.5f * metersPerBlock) continue;
                // The bed is level about its deepest line and rises from there to either bank, the farther bank
                // the more gently
                float across = d / halfWidth, deepest = BEND_SHIFT * v[o + 5];
                float out01 = across >= deepest ? (across - deepest) / (1f - deepest)
                        : (deepest - across) / (1f + deepest);
                float shape = out01 <= CHANNEL_FLAT_FRACTION ? 1f
                        : fadeOut((out01 - CHANNEL_FLAT_FRACTION) / (1f - CHANNEL_FLAT_FRACTION));
                float depth = Math.max(minDepth, v[o + 3] * channelFade) * (1f + BEND_POOL * Math.abs(v[o + 5])) * shape;
                channelBed = Math.min(channelBed, w - depth);
                carve = Math.max(carve, depth);
            } else {
                float kept = 1f - fadeOut((d - halfWidth) / v[o + 4]);
                cut = Math.min(cut, bankM + Math.max(0f, groundM - bankM) * kept);
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
            // Whatever river runs below a lake leaves the lake's shore standing
            bed = Math.min(groundM, Math.max(cut, lakeBankM));
            if (!inBasin) {
                bed = Math.max(bed, held);
            }
        }

        // A lake covers the ground lying below its water, and a channel as far out across its shore as it keeps
        // its bank; the rest of its shore stands above it, so its water has no lower ground beside it
        boolean covered = inBasin && (surfaceM > NO_WATER ? bed < lakeSurfaceM && lakeShore < LAKE_HELD_SHARE
                : bed < lakeCoversM);
        if (inBasin && !covered && surfaceM <= NO_WATER) {
            bed = Math.max(bed, lakeBankM);
        }
        float here = covered ? lakeLevelM - groundM : 0f;
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
        if (covered && lakeSurfaceM > out.waterSurfaceM) {
            out.waterSurfaceM = lakeSurfaceM;
            out.levelM = lakeLevelM;
            out.lake = true;
        }
    }
}
