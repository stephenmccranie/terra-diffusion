package com.github.xandergos.terraindiffusionmc.pipeline;

public final class DeepCaverns {

    public static final int TOP = 8;
    public static final int BOTTOM = -184;
    public static final int LAVA_Y = -182;

    public static final int AIR = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;

    public static final float FAR = 4096f;

    private static final int BANDS = 4;
    private static final float SPACING = 54f;
    private static final float FIRST_FLOOR = -12f;
    private static final float BAND_HEIGHT = 38f;

    private static final float FLOOR_RELIEF = 12f;
    private static final float FLOOR_DETAIL = 2.5f;

    private static final float ROOM_THRESHOLD = 0.32f;
    private static final float LATERAL_K = 26f;
    private static final float HEIGHT_MIN = 12f;
    private static final float HEIGHT_MAX = 32f;
    private static final float WALL_NOISE = 14f;
    private static final float CEIL_NOISE = 10f;
    private static final float CHEESE_YSQUASH = 1.5f;
    private static final float REGION_BIAS = 0.35f;

    private static final float PILLAR_THRESHOLD = 0.60f;
    private static final float PILLAR_K = 30f;

    private static final int LAKE_SEAL = 4;

    // How far the cavern ceiling wanders below #TOP
    private static final float CONTACT_ROUGH = 6f;

    private static final float LAKE_RISE = -2f;

    private static final float CHIM_THICK = 0.055f;
    private static final float CHIM_K = 22f;
    private static final float CHIM_YSQUASH = 0.28f;

    private static final int CHIM_STEP = 16;
    private static final int CHIM_SAMPLES = (TOP - BOTTOM) / CHIM_STEP + 2;

    // 3D noise is sampled on a lattice and interpolated between, as vanilla samples its own noise: every LATTICE
    // blocks across, and up each lattice column every CHIM_STEP blocks for the chimneys and every LATTICE blocks
    // for the cheese. Every threshold is still taken per block, so shafts and walls keep their edges.
    private static final int LATTICE = 4;
    private static final int CHEESE_LO = Math.floorDiv(BOTTOM, LATTICE) * LATTICE;
    private static final int CHEESE_SAMPLES = -Math.floorDiv(-TOP, LATTICE) - Math.floorDiv(BOTTOM, LATTICE) + 1;

    private static FastNoiseLite cheese;
    private static FastNoiseLite region;
    private static FastNoiseLite floorNoise;
    private static FastNoiseLite floorDetail;
    private static FastNoiseLite pillar;
    private static FastNoiseLite heightNoise;
    private static FastNoiseLite chimA;
    private static FastNoiseLite chimB;
    private static volatile int GEN;

    // Per-thread columns over a 32x32 block neighbourhood, slotted by the low bits of x and z, and lattice columns
    // over 32x32 lattice steps
    private static final int COL_BITS = 5;
    private static final int COL_MASK = (1 << COL_BITS) - 1;
    private static final ThreadLocal<Col[]> COLS = ThreadLocal.withInitial(() -> new Col[1 << (2 * COL_BITS)]);
    private static final ThreadLocal<Lattice[]> LATTICES =
            ThreadLocal.withInitial(() -> new Lattice[1 << (2 * COL_BITS)]);

    static {
        setSeed(0L);
    }

    private DeepCaverns() {
    }

    public static synchronized void setSeed(long seed) {
        int s = (int) (seed ^ (seed >>> 32));
        cheese = make(s ^ 0x0CEE5, 1f / 48f, 2);
        region = make(s ^ 0x0DEE1, 1f / 190f, 2);
        floorNoise = make(s ^ 0x0F100, 1f / 260f, 2);
        floorDetail = make(s ^ 0x0F1A7, 1f / 70f, 2);
        pillar = make(s ^ 0x0D111, 1f / 30f, 2);
        heightNoise = make(s ^ 0x0BE16, 1f / 90f, 2);
        chimA = make(s ^ 0x0C41F, 1f / 70f, 2);
        chimB = make(s ^ 0x0C42F, 1f / 70f, 2);
        GEN++;
    }

    private static FastNoiseLite make(int seed, float frequency, int octaves) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(frequency);
        fnl.SetFractalOctaves(octaves);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private static final class Col {
        int gen = -1;
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        final float[] floor = new float[BANDS];
        final float[] ceil = new float[BANDS];
        final float[] lat = new float[BANDS];
        final float[] lake = new float[BANDS];
        float bias;
        float pillarCut;
        float topY;
        final float[] ca = new float[CHIM_SAMPLES];
        final float[] cb = new float[CHIM_SAMPLES];
        final float[] cheese = new float[CHEESE_SAMPLES];
    }

    // The 3D noises up one lattice column
    private static final class Lattice {
        int gen = -1;
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        final float[] ca = new float[CHIM_SAMPLES];
        final float[] cb = new float[CHIM_SAMPLES];
        final float[] cheese = new float[CHEESE_SAMPLES];
    }

    private static Lattice lattice(int x, int z) {
        Lattice[] lattices = LATTICES.get();
        int slot = ((Math.floorDiv(z, LATTICE) & COL_MASK) << COL_BITS) | (Math.floorDiv(x, LATTICE) & COL_MASK);
        Lattice l = lattices[slot];
        if (l == null) {
            l = new Lattice();
            lattices[slot] = l;
        }
        if (l.x == x && l.z == z && l.gen == GEN) return l;
        l.gen = GEN;
        l.x = x;
        l.z = z;
        for (int i = 0; i < CHIM_SAMPLES; i++) {
            float sy = (BOTTOM + i * (float) CHIM_STEP) * CHIM_YSQUASH;
            l.ca[i] = chimA.GetNoise(x, sy, z);
            l.cb[i] = chimB.GetNoise(x, sy, z);
        }
        for (int i = 0; i < CHEESE_SAMPLES; i++) {
            l.cheese[i] = cheese.GetNoise(x, (CHEESE_LO + i * LATTICE) * CHEESE_YSQUASH, z);
        }
        return l;
    }

    // Bilinear blend of four lattice columns' samples: a at the low corner, b across x, c across z, d across both
    private static void blend(float[] out, float[] a, float[] b, float[] c, float[] d, float tx, float tz) {
        for (int i = 0; i < out.length; i++) {
            float lo = a[i] + (b[i] - a[i]) * tx;
            float hi = c[i] + (d[i] - c[i]) * tx;
            out[i] = lo + (hi - lo) * tz;
        }
    }

    // Samples taken every step blocks of height from y0, interpolated at y
    private static float alongY(float[] s, float y, int y0, int step) {
        float t = (y - y0) / step;
        int i = (int) Math.floor(t);
        if (i < 0) i = 0;
        if (i >= s.length - 1) i = s.length - 2;
        float f = t - i;
        return s[i] + (s[i + 1] - s[i]) * f;
    }

    private static Col column(int x, int z) {
        Col[] cols = COLS.get();
        int slot = ((z & COL_MASK) << COL_BITS) | (x & COL_MASK);
        Col c = cols[slot];
        if (c == null) {
            c = new Col();
            cols[slot] = c;
        }
        if (c.x == x && c.z == z && c.gen == GEN) return c;
        c.gen = GEN;
        c.x = x;
        c.z = z;

        float fx = x, fz = z;
        c.bias = region.GetNoise(fx, fz) * REGION_BIAS;
        c.pillarCut = (PILLAR_THRESHOLD - pillar.GetNoise(fx, fz)) * PILLAR_K;
        float cn = heightNoise.GetNoise(fx * 0.55f, fz * 0.55f) * 2.2f;
        if (cn < -1f) cn = -1f; else if (cn > 1f) cn = 1f;
        c.topY = TOP - CONTACT_ROUGH * (0.5f + 0.5f * cn);

        for (int b = 0; b < BANDS; b++) {
            float ox = b * 7919f, oz = b * 6151f;
            float nominal = FIRST_FLOOR - b * SPACING;
            c.floor[b] = nominal
                    + floorNoise.GetNoise(fx + ox, fz + oz) * FLOOR_RELIEF
                    + floorDetail.GetNoise(fx + ox, fz + oz) * FLOOR_DETAIL;
            c.lat[b] = (region.GetNoise(fx + ox, fz + oz) - ROOM_THRESHOLD) * LATERAL_K;
            float hn = (heightNoise.GetNoise(fx + ox, fz + oz) + 1f) * 0.5f;
            c.ceil[b] = c.floor[b] + HEIGHT_MIN + (HEIGHT_MAX - HEIGHT_MIN) * hn;
            if (b == 0 && c.ceil[b] < TOP + 2f) c.ceil[b] = TOP + 2f;
            c.lake[b] = nominal + LAKE_RISE;
        }

        int x0 = Math.floorDiv(x, LATTICE) * LATTICE, z0 = Math.floorDiv(z, LATTICE) * LATTICE;
        float tx = (x - x0) / (float) LATTICE, tz = (z - z0) / (float) LATTICE;
        Lattice a = lattice(x0, z0), b = lattice(x0 + LATTICE, z0);
        Lattice d = lattice(x0, z0 + LATTICE), e = lattice(x0 + LATTICE, z0 + LATTICE);
        blend(c.ca, a.ca, b.ca, d.ca, e.ca, tx, tz);
        blend(c.cb, a.cb, b.cb, d.cb, e.cb, tx, tz);
        blend(c.cheese, a.cheese, b.cheese, d.cheese, e.cheese, tx, tz);
        return c;
    }

    private static float chimneyAt(Col c, float y) {
        float a = alongY(c.ca, y, BOTTOM, CHIM_STEP);
        float b2 = alongY(c.cb, y, BOTTOM, CHIM_STEP);
        float o = CHIM_THICK - Math.abs(a);
        float p = CHIM_THICK - Math.abs(b2);
        return (o < p ? o : p) * CHIM_K;
    }

    // Signed distance in blocks; negative inside a cavern, #FAR in solid rock
    private static boolean lakeBed(Col c, int y) {
        for (int b = 0; b < BANDS; b++) {
            if (c.floor[b] >= c.lake[b]) continue;
            if (y < c.lake[b] && y >= c.floor[b] - LAKE_SEAL) return true;
        }
        return false;
    }

    public static float density(int x, int y, int z) {
        if (y > TOP || y < BOTTOM) return FAR;
        Col c = column(x, z);
        if (y > c.topY) return FAR;

        float best = chimneyAt(c, y);
        // A shaft must not punch through the bed of a lake it passes under: the water drains into
        if (best > 0f && lakeBed(c, y)) best = 0f;

        int band = -1;
        float above = 0f;
        for (int b = 0; b < BANDS; b++) {
            float h = y - c.floor[b];
            if (h >= 0f && h <= BAND_HEIGHT) {
                band = b;
                above = h;
                break;
            }
        }

        if (band >= 0) {
            float n3 = alongY(c.cheese, y, CHEESE_LO, LATTICE);
            float o = c.lat[band] + c.bias * LATERAL_K + n3 * WALL_NOISE;
            float ceil = c.ceil[band] - y + n3 * CEIL_NOISE;
            if (ceil < o) o = ceil;
            if (above < o) o = above;
            if (c.pillarCut < o) o = c.pillarCut;
            if (o > best) best = o;
        }

        return best > 0f ? -best : FAR;
    }

    // What fills an open cavern block: #AIR, #WATER or #LAVA
    public static int fluid(int x, int y, int z) {
        if (y > TOP || y < BOTTOM) return AIR;
        if (y <= LAVA_Y) return LAVA;
        Col c = column(x, z);
        for (int b = 0; b < BANDS; b++) {
            if (y < c.floor[b] || y >= c.lake[b] || y - c.floor[b] > BAND_HEIGHT) continue;
            int floorY = (int) Math.floor(c.floor[b]);
            if (density(x, floorY - 1, z) < 0f) return AIR;
            return WATER;
        }
        return AIR;
    }
}
