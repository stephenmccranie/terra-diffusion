package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RiverHydrology {

    private static final Logger LOG = LoggerFactory.getLogger(RiverHydrology.class);

    private static final int REGION_PX = 1024;

    private static final int MARGIN_PX = 512;
    private static final int WINDOW_PX = REGION_PX + 2 * MARGIN_PX;

    private static final int GRID = WINDOW_PX;

    private static final int CROP_RIM = 28;
    private static final int CROP_OFF = MARGIN_PX - CROP_RIM;
    private static final int CROP_N = REGION_PX + 2 * CROP_RIM;

    private static float[] crop(float[] full) {
        float[] out = new float[CROP_N * CROP_N];
        for (int r = 0; r < CROP_N; r++) {
            System.arraycopy(full, (CROP_OFF + r) * GRID + CROP_OFF, out, r * CROP_N, CROP_N);
        }
        return out;
    }

    private static final float RIVER_THRESHOLD = 600f;

    private static final float WIDTH_GROWTH_DECADES = 2.0f;

    private static final float DISCHARGE_AREA_EXPONENT = 0.8f;
    private static final float WIDTH_Q_EXPONENT = 0.5f;
    private static final float DEPTH_Q_EXPONENT = 0.4f;

    private static final float BANKFULL_WIDTH_M = 348f;
    private static final float BANKFULL_DEPTH_M = 9f;

    private static final float MAX_WIDTH_M = 340f;
    private static final float MAX_DEPTH_M = 45f;

    private static final float CONFINEMENT_FRACTION = 1.0f;

    private static final int CONFINEMENT_SCAN_CELLS = 16;
    private static final float CONFINEMENT_RISE_M = 18f;

    private static final float ORDER_WIDTH_GAIN = 0.06f;

    private static final float FREEBOARD_BLOCKS = 0.5f;

    private static final int FIELD_SMOOTH_PASSES = 12;

    private static final float STAMP_MIN_BLOCKS = 5f;

    private static final float STAMP_REACH = 3.0f;

    private static final int FIELD_BLUR_PASSES = 16;

    private static final int DEQUANT_PASSES = 8;

    private static final float OVERLAP_REACH = 2.0f;

    // Rivers rise between these model heights; above the top one the ground is left to snow, scree and bare
    // rock
    private static final float MIN_SOURCE_ELEV_M = 200f;
    private static final float MAX_SOURCE_ELEV_M = 2600f;

    private static final float HOLD_QUANTILE = 0.15f;

    private static final float OVERLAP_AGREE_BLOCKS = 0.5f;

    private static final int ARC_BLUR_PASSES = 6;

    private static final int SCAN_CAP = 20;

    private static final float MAX_INFLUENCE_CELLS = 12f;

    private static final int[] FACET_CR = { 0, -1, -1, 0, 0, 1, 1, 0};
    private static final int[] FACET_CC = { 1, 0, 0, -1, -1, 0, 0, 1};
    private static final int[] FACET_DR = {-1, -1, -1, -1, 1, 1, 1, 1};
    private static final int[] FACET_DC = { 1, 1, -1, -1, -1, -1, 1, 1};

    private static final float CELL_SIZE_M = 30f;

    private static final double CHANNEL_SLOPE_EXPONENT = 2.0;

    private static final double CHANNEL_INITIATION = 900.0;

    // Ceiling on the slope term at channel initiation
    private static final double CHANNEL_SLOPE_CAP = 0.5;

    private static final float CONVERGENCE_MIN_M = 0.35f;

    private static final int CONVERGENCE_RADIUS = 3;

    private static final double MFD_EXPONENT = 1.1;

    private static final FastNoiseLite ROUTING_DETAIL = makeRoutingNoise();

    private static final float ROUTING_DETAIL_M = 1.0f;

    private static FastNoiseLite makeRoutingNoise() {
        FastNoiseLite fnl = new FastNoiseLite(0x5EA1);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);

        fnl.SetFrequency(1f / 22f);
        fnl.SetFractalOctaves(1);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private static final int MEANDER_TANGENT_CELLS = 8;

    private static final float MEANDER_CLIMB_M = 3f;

    private static final float[] MEANDER_BAND_CELLS = {8f, 24f, 72f};
    private static final FastNoiseLite[] MEANDER_BAND_I = {
            makeMeanderNoise(0x51DE, MEANDER_BAND_CELLS[0]),
            makeMeanderNoise(0x51DF, MEANDER_BAND_CELLS[1]),
            makeMeanderNoise(0x51E0, MEANDER_BAND_CELLS[2])};
    private static final FastNoiseLite[] MEANDER_BAND_J = {
            makeMeanderNoise(0x7A2C, MEANDER_BAND_CELLS[0]),
            makeMeanderNoise(0x7A2D, MEANDER_BAND_CELLS[1]),
            makeMeanderNoise(0x7A2E, MEANDER_BAND_CELLS[2])};

    private static final float MEANDER_AMPLITUDE_WIDTHS = 2.4f;
    private static final float MEANDER_ASPECT = 4.4f;

    private static final float MEANDER_MIN_AMPLITUDE_M = 78f;

    private static final float MEANDER_SPACE_FRACTION = 0.42f;

    private static FastNoiseLite makeMeanderNoise(int seed, float wavelengthCells) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(1f / wavelengthCells);
        fnl.SetFractalOctaves(2);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.45f);
        return fnl;
    }

    private static final float MOUTH_AREA = 140000f;

    private static final float MIN_TRIBUTARY_AREA = 40000f;

    private static final float LAKE_MIN_DEPTH_M = 15f;

    private static final int LAKE_MIN_CELLS = 150;

    private static final int LAKE_MAX_SPAN = MARGIN_PX;

    private static final float LAKE_FLOOR_M = 0.5f;

    private static final int LAKE_RIM_CELLS = 3;

    private static final float LAKE_MIN_SEA_CELLS = 8f;

    private static final int LAKE_MIN_LANDMASS_CELLS = 20000;

    public static final class Region {
        final int regionI, regionJ;
        final float[] distance;
        final float[] water;
        final float[] lake;
        final float[] lakeDeep;
        final float[] width;
        final float[] depth;
        final KarstNetwork karst;
        // 8-connected lake body of each crop cell, -1 where there is no lake
        final int[] lakeBody;
        // Whether each body survives comparison with its neighbours, judged when first sampled
        final byte[] bodyVerdict;

        Region(int regionI, int regionJ, float[] distance, float[] water,
               float[] lake, float[] lakeDeep, float[] width, float[] depth, KarstNetwork karst) {
            this.regionI = regionI;
            this.regionJ = regionJ;
            this.distance = distance;
            this.water = water;
            this.lake = lake;
            this.lakeDeep = lakeDeep;
            this.width = width;
            this.depth = depth;
            this.karst = karst;
            this.lakeBody = labelLakes(lake);
            this.bodyVerdict = new byte[Arrays.stream(lakeBody).max().orElse(-1) + 1];
        }
    }

    private static final float SEAM_BLEND_CELLS = 24f;

    // Room for the regions in use, those prefetched half a region ahead and the seam neighbours both blend
    // with, so prefetching does not evict a region generation still needs
    private static final int MAX_CACHED_REGIONS = 16;

    // Hydrology regions built for one pipeline, least recently used evicted first.
    // Each region is built once; everyone who needs it waits for that one build.
    public static final class RegionCache {
        private final Map<Long, PriorityExecutor.Task<Region>> regions = new ConcurrentHashMap<>();
        // The clock reading when each region was last used; it advances only when a region is added,
        // which is the only time eviction looks, so a lookup never has to write a shared counter
        private final Map<Long, AtomicLong> used = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();
        private boolean seamNeighbours = true;

        public void clear() {
            regions.clear();
            used.clear();
        }

        // When off, a seam blends only with a neighbouring region that is already built
        public void buildSeamNeighbours(boolean on) {
            seamNeighbours = on;
        }
    }

    // Builds every region overlapping the native rectangle [i0, i1) x [j0, j1)
    public static void prepare(WorldPipeline pipeline, int i0, int j0, int i1, int j1, float blockM) {
        int ri0 = Math.floorDiv(i0, REGION_PX), ri1 = Math.floorDiv(i1 - 1, REGION_PX);
        int rj0 = Math.floorDiv(j0, REGION_PX), rj1 = Math.floorDiv(j1 - 1, REGION_PX);
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                regionFor(pipeline, ri, rj, blockM);
            }
        }
    }

    // Queues every region within half a region of the native rectangle that nobody has asked for yet, so it
    // is built while generation is still crossing tiles that need no inference, before it gets there. A
    // build takes minutes on CPU inference against under a minute per 1,024-block tile, so a quarter
    // region of lead left builds in generation's path. Skipped while builds are already waiting, so scans
    // far from the generation front cannot pile up speculative work.
    public static void prefetch(WorldPipeline pipeline, int i0, int j0, int i1, int j1, float blockM) {
        if (BUILDERS.hasQueued()) return;
        int lead = REGION_PX / 2;
        int ri0 = Math.floorDiv(i0 - lead, REGION_PX), ri1 = Math.floorDiv(i1 + lead - 1, REGION_PX);
        int rj0 = Math.floorDiv(j0 - lead, REGION_PX), rj1 = Math.floorDiv(j1 + lead - 1, REGION_PX);
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                request(pipeline, ri, rj, blockM, PriorityExecutor.BACKGROUND);
            }
        }
    }

    // A region build holds about a gigabyte of working arrays, so at most two run at once
    private static final PriorityExecutor BUILDERS = new PriorityExecutor("terrain-diffusion-hydrology", 2);

    private RiverHydrology() {
    }

    private static long key(int regionI, int regionJ) {
        return (((long) regionI) << 32) ^ (regionJ & 0xFFFFFFFFL);
    }

    public static KarstNetwork karstAt(WorldPipeline pipeline, float nativeI, float nativeJ,
                                      float blockM) {
        if (!KarstHydrology.ENABLED) {
            return KarstNetwork.EMPTY;
        }
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, ri, rj, blockM);
        return region == null || region.karst == null ? KarstNetwork.EMPTY : region.karst;
    }

    // {distance to the centreline in native cells, water level, width, depth} of the nearest channel, or
    // null beyond every channel's reach
    public static float[] sampleChannel(WorldPipeline pipeline, float nativeI, float nativeJ,
                                       float blockM) {
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        float[] own = sampleFrom(pipeline, ri, rj, nativeI, nativeJ, blockM);
        if (SEAM_BLEND_CELLS <= 0) {
            return own;
        }

        float fi = nativeI - ri * (float) REGION_PX;
        float fj = nativeJ - rj * (float) REGION_PX;
        float di = Math.min(fi, REGION_PX - fi);
        float dj = Math.min(fj, REGION_PX - fj);
        int ni = ri, nj = rj;
        float dist;
        if (di <= dj) {
            dist = di;
            ni = fi < REGION_PX - fi ? ri - 1 : ri + 1;
        } else {
            dist = dj;
            nj = fj < REGION_PX - fj ? rj - 1 : rj + 1;
        }
        if (dist >= SEAM_BLEND_CELLS) {
            return own;
        }
        RegionCache cache = pipeline.hydrology;
        if (!cache.seamNeighbours && !cache.regions.containsKey(key(ni, nj))) {
            return own;
        }
        float[] other = sampleFrom(pipeline, ni, nj, nativeI, nativeJ, blockM);
        if (own == null && other == null) {
            return null;
        }
        // A river only one region has fades out across the seam instead of ending at it
        if (own == null) own = beyondReach(other);
        if (other == null) other = beyondReach(own);

        float wOther = 0.5f * (1f - dist / SEAM_BLEND_CELLS);
        for (int k = 0; k < own.length; k++) {
            own[k] = own[k] + (other[k] - own[k]) * wOther;
        }
        return own;
    }

    // A channel sample moved to the edge of a river's influence, where it no longer shapes the ground
    private static float[] beyondReach(float[] sample) {
        float[] far = sample.clone();
        far[0] = MAX_INFLUENCE_CELLS;
        return far;
    }

    private static float[] sampleFrom(WorldPipeline pipeline, int regionI, int regionJ,
                                      float nativeI, float nativeJ, float blockM) {
        Region region = regionFor(pipeline, regionI, regionJ, blockM);
        if (region == null) {
            return null;
        }

        int windowI = regionI * REGION_PX - MARGIN_PX;
        int windowJ = regionJ * REGION_PX - MARGIN_PX;

        float gi = (nativeI - windowI) - CROP_OFF;
        float gj = (nativeJ - windowJ) - CROP_OFF;
        int i0 = (int) Math.floor(gi), j0 = (int) Math.floor(gj);
        if (i0 < 0 || j0 < 0 || i0 >= CROP_N - 1 || j0 >= CROP_N - 1) {
            return null;
        }
        float ti = gi - i0, tj = gj - j0;

        int a = i0 * CROP_N + j0;
        int b = a + 1;
        int c = a + CROP_N;
        int d2 = c + 1;

        float d = lerp(lerp(region.distance[a], region.distance[b], tj),
                       lerp(region.distance[c], region.distance[d2], tj), ti);
        if (d >= MAX_INFLUENCE_CELLS) {
            return null;
        }
        float wa = region.water[a], wb = region.water[b];
        float wc = region.water[c], wd = region.water[d2];
        float w = Float.isNaN(wa) || Float.isNaN(wb) || Float.isNaN(wc) || Float.isNaN(wd)
                ? region.water[nearest(region, gi, gj)]
                : lerp(lerp(wa, wb, tj), lerp(wc, wd, tj), ti);
        float wid = lerp(lerp(region.width[a], region.width[b], tj),
                         lerp(region.width[c], region.width[d2], tj), ti);
        float dep = lerp(lerp(region.depth[a], region.depth[b], tj),
                         lerp(region.depth[c], region.depth[d2], tj), ti);
        return new float[]{d, Float.isNaN(w) ? 0f : w, wid, dep};
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // A lake body crossing into a neighbour must be mostly lake there too, or it is dropped
    private static final float LAKE_AGREE = 0.25f;

    private static final byte BODY_KEEP = 1, BODY_DROP = 2;

    private static final int[][] NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    // Labels each 8-connected lake body in a region's crop; -1 where there is no lake
    private static int[] labelLakes(float[] lake) {
        int n = CROP_N * CROP_N;
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int[] stack = new int[n];
        int bodies = 0;
        for (int seed = 0; seed < n; seed++) {
            if (label[seed] != -1 || Float.isNaN(lake[seed])) continue;
            int top = 0;
            stack[top++] = seed;
            label[seed] = bodies;
            while (top > 0) {
                int k = stack[--top];
                int r = k / CROP_N, c = k % CROP_N;
                for (int dr = -1; dr <= 1; dr++) {
                    int rr = r + dr;
                    if (rr < 0 || rr >= CROP_N) continue;
                    for (int dc = -1; dc <= 1; dc++) {
                        int cc = c + dc;
                        if ((dr == 0 && dc == 0) || cc < 0 || cc >= CROP_N) continue;
                        top = claim(lake, label, stack, top, rr * CROP_N + cc, bodies);
                    }
                }
            }
            bodies++;
        }
        return label;
    }

    private static int claim(float[] lake, int[] label, int[] stack, int top, int k, int body) {
        if (label[k] == -1 && !Float.isNaN(lake[k])) {
            label[k] = body;
            stack[top++] = k;
        }
        return top;
    }

    // Whether the lake body holding this cell survives comparison with every neighbour it reaches
    private static boolean lakeConfirmed(WorldPipeline pipeline, Region region, int at, float blockM) {
        if (LAKE_AGREE <= 0f) {
            return true;
        }
        int body = region.lakeBody[at];
        byte verdict = region.bodyVerdict[body];
        if (verdict == 0) {
            verdict = judgeBody(pipeline, region, body, blockM);
            region.bodyVerdict[body] = verdict;
        }
        return verdict == BODY_KEEP;
    }

    private static byte judgeBody(WorldPipeline pipeline, Region region, int body, float blockM) {
        int[] label = region.lakeBody;
        int band = 2 * CROP_RIM;
        for (int[] d : NEIGHBOURS) {
            int r0 = d[0] > 0 ? CROP_N - band : 0, r1 = d[0] < 0 ? band : CROP_N;
            int c0 = d[1] > 0 ? CROP_N - band : 0, c1 = d[1] < 0 ? band : CROP_N;

            boolean reaches = false;
            for (int r = r0; r < r1 && !reaches; r++) {
                for (int c = c0; c < c1; c++) {
                    if (label[r * CROP_N + c] == body) { reaches = true; break; }
                }
            }
            if (!reaches) continue;

            Region other = regionFor(pipeline, region.regionI + d[0], region.regionJ + d[1], blockM);
            if (other == null || other.lake == null) continue;
            int seen = 0, agree = 0;
            for (int r = r0; r < r1; r++) {
                for (int c = c0; c < c1; c++) {
                    if (label[r * CROP_N + c] != body) continue;
                    int ni = r + CROP_OFF + region.regionI * REGION_PX - MARGIN_PX;
                    int nj = c + CROP_OFF + region.regionJ * REGION_PX - MARGIN_PX;
                    int oi = (ni - (other.regionI * REGION_PX - MARGIN_PX)) - CROP_OFF;
                    int oj = (nj - (other.regionJ * REGION_PX - MARGIN_PX)) - CROP_OFF;
                    if (oi < 0 || oj < 0 || oi >= CROP_N || oj >= CROP_N) continue;
                    seen++;
                    if (!Float.isNaN(other.lake[oi * CROP_N + oj])) agree++;
                }
            }
            if (seen > 0 && agree < LAKE_AGREE * seen) return BODY_DROP;
        }
        return BODY_KEEP;
    }

    public static float[] sampleLake(WorldPipeline pipeline, float nativeI, float nativeJ,
                                    float blockM) {
        int regionI = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int regionJ = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, regionI, regionJ, blockM);
        if (region == null || region.lake == null) {
            return null;
        }
        float gi = (nativeI - (regionI * REGION_PX - MARGIN_PX)) - CROP_OFF;
        float gj = (nativeJ - (regionJ * REGION_PX - MARGIN_PX)) - CROP_OFF;
        int i0 = (int) Math.floor(gi), j0 = (int) Math.floor(gj);
        if (i0 < 0 || j0 < 0 || i0 >= CROP_N - 1 || j0 >= CROP_N - 1) {
            return null;
        }

        int at = i0 * CROP_N + j0;
        float near = region.lake[at];
        if (Float.isNaN(near)) {
            return null;
        }
        if (!lakeConfirmed(pipeline, region, at, blockM)) {
            return null;
        }
        float deep = region.lakeDeep == null || Float.isNaN(region.lakeDeep[at])
                ? 0f : region.lakeDeep[at];
        return new float[]{(float) Math.ceil(Math.floor(near / blockM) * blockM), deep};
    }

    private static int[] landmassArea(float[] elev) {
        int n = GRID * GRID;
        int[] area = new int[n];
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int[] nr4 = {-1, 1, 0, 0}, nc4 = {0, 0, -1, 1};

        for (int start = 0; start < n; start++) {
            if (seen[start] || elev[start] < 0f) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int i = queue[head++];
                int r = i / GRID, c = i % GRID;
                for (int k = 0; k < 4; k++) {
                    int rr = r + nr4[k], cc = c + nc4[k];
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int j = rr * GRID + cc;
                    if (seen[j] || elev[j] < 0f) {
                        continue;
                    }
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }
            for (int m = 0; m < tail; m++) {
                area[queue[m]] = tail;
            }
        }
        return area;
    }

    private static float[][] lakes(float[] elev, float[] fillDepth, float[] seaDist, int[] landArea,
                                 boolean[] channel) {
        int n = GRID * GRID;
        float[] lake = new float[n];
        Arrays.fill(lake, Float.NaN);

        float[] deep = new float[n];
        Arrays.fill(deep, Float.NaN);

        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int[] nr4 = {-1, 1, 0, 0}, nc4 = {0, 0, -1, 1};

        for (int start = 0; start < n; start++) {
            if (seen[start] || elev[start] < 0f || fillDepth[start] <= LAKE_FLOOR_M) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;

            float deepest = 0f;
            float nearestSea = Float.MAX_VALUE;
            boolean drained = false;
            int minR = GRID, maxR = -1, minC = GRID, maxC = -1;

            while (head < tail) {
                int i = queue[head++];
                if (fillDepth[i] > deepest) {
                    deepest = fillDepth[i];
                }
                if (channel[i]) {
                    drained = true;
                }
                if (seaDist[i] < nearestSea) {
                    nearestSea = seaDist[i];
                }
                int r = i / GRID, c = i % GRID;
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                if (c < minC) minC = c;
                if (c > maxC) maxC = c;

                for (int k = 0; k < 4; k++) {
                    int rr = r + nr4[k], cc = c + nc4[k];
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int j = rr * GRID + cc;
                    if (seen[j] || elev[j] < 0f || fillDepth[j] <= LAKE_FLOOR_M) {
                        continue;
                    }
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }

            boolean atBorder = minR == 0 || minC == 0 || maxR == GRID - 1 || maxC == GRID - 1;

            if (drained || atBorder
                    || deepest < LAKE_MIN_DEPTH_M || tail < LAKE_MIN_CELLS
                    || nearestSea < LAKE_MIN_SEA_CELLS
                    || landArea[start] < LAKE_MIN_LANDMASS_CELLS
                    || maxR - minR + 1 > LAKE_MAX_SPAN || maxC - minC + 1 > LAKE_MAX_SPAN) {
                continue;
            }
            for (int m = 0; m < tail; m++) {
                int i = queue[m];
                lake[i] = elev[i] + fillDepth[i];
                deep[i] = deepest;
            }
        }

        for (int pass = 0; pass < LAKE_RIM_CELLS; pass++) {
            float[] rim = lake.clone();
            float[] rimDeep = deep.clone();
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) {
                    int i = r * GRID + c;
                    if (!Float.isNaN(rim[i])) {
                        continue;
                    }
                    float best = Float.NaN, bestDeep = Float.NaN;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int rr = r + dr, cc = c + dc;
                            if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                                continue;
                            }
                            float v = rim[rr * GRID + cc];
                            if (!Float.isNaN(v) && (Float.isNaN(best) || v > best)) {
                                best = v;
                                bestDeep = rimDeep[rr * GRID + cc];
                            }
                        }
                    }
                    if (!Float.isNaN(best) && elev[i] >= best) {
                        lake[i] = best;
                        deep[i] = bestDeep;
                    }
                }
            }
        }
        return new float[][]{lake, deep};
    }

    private static long pack(float key, int index) {
        int bits = Float.floatToIntBits(key);
        bits ^= (bits >> 31) & 0x7FFFFFFF;
        return (((long) bits) << 32) | (index & 0xFFFFFFFFL);
    }

    private static int[] orderBy(float[] key, int n) {
        long[] packed = new long[n];
        for (int i = 0; i < n; i++) {
            packed[i] = pack(key[i], i);
        }
        Arrays.sort(packed);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = (int) packed[i];
        }
        return order;
    }

    private static final class LongHeap {
        private long[] heap;
        private int size;

        LongHeap(int capacity) {
            this.heap = new long[Math.max(16, capacity / 4)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(long value) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, heap.length * 2);
            }
            int i = size++;
            heap[i] = value;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heap[parent] <= heap[i]) break;
                long t = heap[parent]; heap[parent] = heap[i]; heap[i] = t;
                i = parent;
            }
        }

        long pop() {
            long top = heap[0];
            heap[0] = heap[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, small = i;
                if (l < size && heap[l] < heap[small]) small = l;
                if (r < size && heap[r] < heap[small]) small = r;
                if (small == i) break;
                long t = heap[small]; heap[small] = heap[i]; heap[i] = t;
                i = small;
            }
            return top;
        }
    }

    private static int nearest(Region region, float gi, float gj) {
        int r = Math.max(0, Math.min(CROP_N - 1, Math.round(gi)));
        int c = Math.max(0, Math.min(CROP_N - 1, Math.round(gj)));
        return r * CROP_N + c;
    }

    private static Region regionFor(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        return PriorityExecutor.join(request(pipeline, regionI, regionJ, blockM, PriorityExecutor.callerPriority()));
    }

    // The region's one build, queued by whoever asks first and lifted to the urgency of anyone who waits
    private static PriorityExecutor.Task<Region> request(WorldPipeline pipeline, int regionI, int regionJ,
                                                         float blockM, int priority) {
        RegionCache cache = pipeline.hydrology;
        long k = key(regionI, regionJ);
        PriorityExecutor.Task<Region> region = cache.regions.get(k);
        if (region == null) {
            PriorityExecutor.Task<Region> mine = new PriorityExecutor.Task<>(() -> {
                try {
                    long start = System.nanoTime();
                    Region built = build(pipeline, regionI, regionJ, blockM);
                    LOG.info("River region {} {} built in {} s", regionI, regionJ,
                            Math.round((System.nanoTime() - start) / 1.0e9));
                    return built;
                } catch (Throwable e) {
                    cache.regions.remove(k);
                    LOG.error("River region {} {} failed to build", regionI, regionJ, e);
                    throw e;
                }
            }, priority);
            region = cache.regions.putIfAbsent(k, mine);
            if (region == null) {
                cache.used.put(k, new AtomicLong(cache.clock.incrementAndGet()));
                evict(cache);
                // The inference thread cannot wait for a builder that needs it, so it builds its own
                if (WorldPipeline.INFERENCE.isCurrent()) {
                    mine.run();
                } else {
                    BUILDERS.execute(mine);
                }
                return mine;
            }
        }
        AtomicLong used = cache.used.get(k);
        long now = cache.clock.get();
        if (used != null && used.get() != now) used.set(now);
        BUILDERS.raise(region, priority);
        return region;
    }

    private static void evict(RegionCache cache) {
        if (cache.regions.size() <= MAX_CACHED_REGIONS) {
            return;
        }
        cache.used.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().get()))
                .limit(Math.max(1, cache.regions.size() - MAX_CACHED_REGIONS))
                .map(Map.Entry::getKey)
                .forEach(k -> { cache.regions.remove(k); cache.used.remove(k); });
    }

    private static Region build(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        int windowI = regionI * REGION_PX - MARGIN_PX;
        int windowJ = regionJ * REGION_PX - MARGIN_PX;

        float[][] heights = pipeline.heights(windowI, windowJ, windowI + WINDOW_PX, windowJ + WINDOW_PX);
        float[] elev = heights[0];
        // Where rivers may rise is a matter of real-world height, like climate, whatever the height curve
        float[] modelElev = heights[1];
        InfiniteTensor.throwIfCancelled();

        float[] routing = new float[GRID * GRID];
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                float nx = windowJ + c;
                float ny = windowI + r;

                float offSea = Math.min(1f, Math.abs(elev[i]) / (2f * ROUTING_DETAIL_M));
                routing[i] = elev[i]
                        + ROUTING_DETAIL.GetNoise(nx, ny) * ROUTING_DETAIL_M * offSea;
            }
        }

        InfiniteTensor.throwIfCancelled();
        float[] seaDist = seaDistance(elev);
        float[] fillDepth = new float[GRID * GRID];
        int[] down = new int[GRID * GRID];
        float[] slope = new float[GRID * GRID];

        InfiniteTensor.throwIfCancelled();
        float[] acc = accumulate(routing, elev, fillDepth, down, slope, windowI, windowJ,
                inflow(pipeline, windowI, windowJ, elev));

        float[] distance = new float[GRID * GRID];
        Arrays.fill(distance, Float.MAX_VALUE);

        boolean[] channel = new boolean[GRID * GRID];
        float[] surface = new float[GRID * GRID];
        InfiniteTensor.throwIfCancelled();
        for (int i = 0; i < GRID * GRID; i++) surface[i] = routing[i] + fillDepth[i];
        int[] bySurface = orderBy(surface, GRID * GRID);

        for (int i = 0; i < GRID * GRID; i++) {
            if (elev[i] < 0f) {
                continue;
            }
            double s = Math.max(slope[i], 1.0e-6);

            int rr0 = i / GRID, cc0 = i % GRID;
            if (rr0 == 0 || cc0 == 0 || rr0 == GRID - 1 || cc0 == GRID - 1) {
                continue;
            }

            double sGate = Math.min(s, CHANNEL_SLOPE_CAP);
            // MIN_SOURCE_ELEV_M asks where a river may be BORN, so it must not be asked of one
            boolean continuation = acc[i] >= SEED_MIN_CELLS;
            if (((modelElev[i] >= MIN_SOURCE_ELEV_M && modelElev[i] <= MAX_SOURCE_ELEV_M) || continuation)
                    && acc[i] * Math.pow(sGate, CHANNEL_SLOPE_EXPONENT) >= CHANNEL_INITIATION) {
                channel[i] = true;
            }
        }


        for (int start = 0; start < GRID * GRID; start++) {
            if (!channel[start]) {
                continue;
            }
            int at = start;
            for (int step = 0; step < GRID * 4; step++) {
                int d = successor(down, at);
                if (d < 0 || elev[d] < 0f || channel[d]) {
                    break;
                }
                channel[d] = true;
                at = d;
            }
        }

        final byte UNKNOWN = 0, REACHES = 1, STRANDED = 2;
        byte[] fate = new byte[GRID * GRID];
        int[] path = new int[GRID * 4 + 2];
        for (int startCell = 0; startCell < GRID * GRID; startCell++) {
            if (!channel[startCell] || fate[startCell] != UNKNOWN) {
                continue;
            }
            int len = 0;
            int at = startCell;
            byte verdict = STRANDED;
            while (len < path.length) {
                path[len++] = at;
                fate[at] = STRANDED;
                int r = at / GRID, c = at % GRID;

                if (r < 1 || c < 1 || r >= GRID - 1 || c >= GRID - 1) {
                    verdict = REACHES;
                    break;
                }
                int d = successor(down, at);
                if (d < 0) {
                    break;
                }
                if (elev[d] < 0f) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == REACHES) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == STRANDED && d != at) {

                    break;
                }
                at = d;
            }
            for (int k = 0; k < len; k++) {
                fate[path[k]] = verdict;
            }
        }
        for (int i = 0; i < GRID * GRID; i++) {
            if (channel[i] && fate[i] == STRANDED) {
                channel[i] = false;
            }
        }
        int[] chanIdx = channelCells(channel, null);
        int[] chanUp = channelCells(channel, bySurface);


        InfiniteTensor.throwIfCancelled();
        float[][] lakeFields = lakes(elev, fillDepth, seaDist, landmassArea(elev), channel);
        float[] lake = lakeFields[0];
        float[] lakeDeep = lakeFields[1];

        float[] perR = new float[GRID * GRID];
        float[] perC = new float[GRID * GRID];
        InfiniteTensor.throwIfCancelled();
        flowTangents(elev, chanIdx, down, perR, perC);

        float[] widthM = new float[GRID * GRID];
        float[] depthM = new float[GRID * GRID];
        InfiniteTensor.throwIfCancelled();
        hydraulicGeometry(elev, channel, chanIdx, chanUp, acc, down, perR, perC, widthM, depthM);

        float[] offR = new float[GRID * GRID];
        float[] offC = new float[GRID * GRID];
        InfiniteTensor.throwIfCancelled();
        meanderOffsets(elev, chanIdx, widthM, perR, perC, windowI, windowJ, offR, offC);



        float[] filled = new float[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) {
            filled[i] = elev[i] + fillDepth[i];
        }

        InfiniteTensor.throwIfCancelled();
        float[] holdM = containGround(elev, elev, chanIdx, offR, offC, perR, perC, widthM);

        float[] water = new float[GRID * GRID];
        solveLevels(channel, chanIdx, chanUp, elev, lake, holdM, depthM, widthM, down, blockM, water);

        if (prunePerched(channel, water, elev, lake, down, blockM)) {
            chanIdx = channelCells(channel, null);
            chanUp = channelCells(channel, bySurface);
        }

        float[] waterOut = new float[GRID * GRID];
        Arrays.fill(waterOut, Float.NaN);
        float[] widthOut = new float[GRID * GRID];
        float[] depthOut = new float[GRID * GRID];
        float[] arcPos = new float[GRID * GRID];
        Arrays.fill(arcPos, Float.NaN);
        for (int i : chanUp) {
            if (elev[i] < 0f) {
                continue;
            }
            int d = successor(down, i);
            float base = 0f;
            float len = 0f;
            if (d >= 0 && channel[d] && !Float.isNaN(arcPos[d])) {
                base = arcPos[d];
                float dr = (d / GRID) + offR[d] - ((i / GRID) + offR[i]);
                float dc = (d % GRID) + offC[d] - ((i % GRID) + offC[i]);
                len = (float) Math.sqrt(dr * dr + dc * dc);
            }
            arcPos[i] = base + len;
        }
        Paint paint = new Paint(distance, waterOut, widthOut, depthOut, arcPos);

        InfiniteTensor.throwIfCancelled();
        meander(elev, channel, chanIdx, down, water, offR, offC, widthM, depthM, paint);
        InfiniteTensor.throwIfCancelled();
        chamfer(paint);

        int active = 0;
        int[] activeIdx;
        {
            int cap = 0;
            for (int r = 1; r < GRID - 1; r++) {
                for (int c = 1; c < GRID - 1; c++) {
                    if (distance[r * GRID + c] < MAX_INFLUENCE_CELLS) {
                        cap++;
                    }
                }
            }
            activeIdx = new int[cap];
            for (int r = 1; r < GRID - 1; r++) {
                for (int c = 1; c < GRID - 1; c++) {
                    int i = r * GRID + c;
                    if (distance[i] < MAX_INFLUENCE_CELLS) {
                        activeIdx[active++] = i;
                    }
                }
            }
        }

        stampSections(chanUp, water, elev, offR, offC, widthM, perR, perC, distance, waterOut, blockM);

        InfiniteTensor.throwIfCancelled();
        {
            float[] src = waterOut;
            float[] dst = new float[GRID * GRID];
            System.arraycopy(src, 0, dst, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_BLUR_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    if (Float.isNaN(src[i])) {
                        continue;
                    }
                    float sum = 0f;
                    float wsum = 0f;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = src[base + dc];
                            if (Float.isNaN(v)) {
                                continue;
                            }
                            sum += v;
                            wsum += 1f;
                        }
                    }
                    if (wsum > 0f) {
                        dst[i] = sum / wsum;
                    }
                }
                float[] swap = src;
                src = dst;
                dst = swap;
            }
            if (src != waterOut) {
                System.arraycopy(src, 0, waterOut, 0, GRID * GRID);
            }
        }

        InfiniteTensor.throwIfCancelled();
        {
            float[] ps = paint.pos;
            float[] pd = new float[GRID * GRID];
            System.arraycopy(ps, 0, pd, 0, GRID * GRID);
            for (int pass = 0; pass < ARC_BLUR_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    if (Float.isNaN(ps[i])) {
                        continue;
                    }
                    float sum = 0f;
                    int cnt = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = ps[base + dc];
                            if (!Float.isNaN(v)) {
                                sum += v;
                                cnt++;
                            }
                        }
                    }
                    if (cnt > 0) {
                        pd[i] = sum / cnt;
                    }
                }
                float[] sw = ps; ps = pd; pd = sw;
            }
            if (ps != paint.pos) {
                System.arraycopy(ps, 0, paint.pos, 0, GRID * GRID);
            }
        }

        int[] up = new int[GRID * GRID];
        Arrays.fill(up, -1);
        float[] bestAcc = new float[GRID * GRID];
        InfiniteTensor.throwIfCancelled();
        for (int i : chanIdx) {
            if (Float.isNaN(water[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && acc[i] > bestAcc[d]) {
                bestAcc[d] = acc[i];
                up[d] = i;
            }
        }

        for (int ai = 0; ai < active; ai++) {
            int k = activeIdx[ai];
            int i = paint.src[k];
            float target = paint.pos[k];
            if (i >= 0 && !Float.isNaN(arcPos[i])) {

                float kr = k / GRID, kc = k % GRID;
                float ir = (i / GRID) + offR[i], ic = (i % GRID) + offC[i];
                float best = Float.MAX_VALUE;
                float bestArc = arcPos[i];
                int dn = successor(down, i);
                int upn = up[i];
                for (int side = 0; side < 2; side++) {
                    int j = side == 0 ? dn : upn;
                    if (j < 0 || !channel[j] || Float.isNaN(arcPos[j])) {
                        continue;
                    }
                    float jr = (j / GRID) + offR[j], jc = (j % GRID) + offC[j];
                    float sr = jr - ir, sc = jc - ic;
                    float len2 = sr * sr + sc * sc;
                    if (len2 < 1.0e-6f) {
                        continue;
                    }
                    float t = ((kr - ir) * sr + (kc - ic) * sc) / len2;
                    t = Math.max(0f, Math.min(1f, t));
                    float pr = ir + sr * t, pc = ic + sc * t;
                    float d2 = (kr - pr) * (kr - pr) + (kc - pc) * (kc - pc);
                    if (d2 < best) {
                        best = d2;
                        bestArc = arcPos[i] + (arcPos[j] - arcPos[i]) * t;
                    }
                }
                if (best != Float.MAX_VALUE) {
                    target = bestArc;
                }
            }
            if (i < 0 || Float.isNaN(target) || Float.isNaN(waterOut[k])
                    || Float.isNaN(arcPos[i]) || distance[k] >= MAX_INFLUENCE_CELLS) {
                continue;
            }

            int a = i;
            int guard = 0;
            if (target < arcPos[a]) {
                while (guard++ < 96) {
                    int d = successor(down, a);
                    if (d < 0 || !channel[d] || Float.isNaN(arcPos[d]) || arcPos[d] <= target) {
                        break;
                    }
                    a = d;
                }
                int d = successor(down, a);
                if (d >= 0 && channel[d] && !Float.isNaN(arcPos[d]) && !Float.isNaN(water[d])) {
                    float span = arcPos[a] - arcPos[d];
                    float t = span > 1.0e-4f ? (arcPos[a] - target) / span : 0f;
                    t = Math.max(0f, Math.min(1f, t));
                    waterOut[k] = water[a] + (water[d] - water[a]) * t;
                    continue;
                }
                waterOut[k] = water[a];
            } else {
                while (guard++ < 96) {
                    int u = up[a];
                    if (u < 0 || Float.isNaN(arcPos[u]) || arcPos[u] >= target) {
                        break;
                    }
                    a = u;
                }
                int u = up[a];
                if (u >= 0 && !Float.isNaN(arcPos[u]) && !Float.isNaN(water[u])) {
                    float span = arcPos[u] - arcPos[a];
                    float t = span > 1.0e-4f ? (target - arcPos[a]) / span : 0f;
                    t = Math.max(0f, Math.min(1f, t));
                    waterOut[k] = water[a] + (water[u] - water[a]) * t;
                    continue;
                }
                waterOut[k] = water[a];
            }
        }

        InfiniteTensor.throwIfCancelled();
        {
            float[] wSrc = widthOut, dSrc = depthOut;
            float[] wDst = new float[GRID * GRID], dDst = new float[GRID * GRID];
            System.arraycopy(wSrc, 0, wDst, 0, GRID * GRID);
            System.arraycopy(dSrc, 0, dDst, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_SMOOTH_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    float ws = 0f, ds = 0f;
                    int n2 = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            int j = base + dc;
                            if (distance[j] >= MAX_INFLUENCE_CELLS) {
                                continue;
                            }
                            ws += wSrc[j];
                            ds += dSrc[j];
                            n2++;
                        }
                    }
                    if (n2 > 0) {
                        wDst[i] = ws / n2;
                        dDst[i] = ds / n2;
                    }
                }
                float[] sw = wSrc; wSrc = wDst; wDst = sw;
                float[] sd = dSrc; dSrc = dDst; dDst = sd;
            }
            if (wSrc != widthOut) {
                System.arraycopy(wSrc, 0, widthOut, 0, GRID * GRID);
                System.arraycopy(dSrc, 0, depthOut, 0, GRID * GRID);
            }
        }

        {
            float[] sq = new float[GRID * GRID];
            for (int i = 0; i < GRID * GRID; i++) {
                sq[i] = distance[i] == 0f ? 0f : 1.0e20f;
            }
        InfiniteTensor.throwIfCancelled();
            edt2d(sq, GRID, GRID);
            for (int i = 0; i < GRID * GRID; i++) {
                distance[i] = (float) Math.sqrt(sq[i]);
            }
        }

        for (int i = 0; i < GRID * GRID; i++) {
            if (!Float.isNaN(waterOut[i])) {
                waterOut[i] = Math.min(waterOut[i], Math.max(0f, elev[i]));
            }
        }


        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (Float.isNaN(waterOut[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && !Float.isNaN(waterOut[d]) && waterOut[d] > waterOut[i]) {
                waterOut[d] = waterOut[i];
            }
        }

        InfiniteTensor.throwIfCancelled();
        {
            float[] src2 = waterOut;
            float[] dst2 = new float[GRID * GRID];
            System.arraycopy(src2, 0, dst2, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_BLUR_PASSES; pass++) {
                for (int a2 = 0; a2 < active; a2++) {
                    int i = activeIdx[a2];
                    if (Float.isNaN(src2[i])) {
                        continue;
                    }
                    float sum = 0f;
                    int cnt = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = src2[base + dc];
                            if (!Float.isNaN(v)) {
                                sum += v;
                                cnt++;
                            }
                        }
                    }
                    if (cnt > 0) {
                        dst2[i] = sum / cnt;
                    }
                }
                float[] sw2 = src2;
                src2 = dst2;
                dst2 = sw2;
            }
            if (src2 != waterOut) {
                System.arraycopy(src2, 0, waterOut, 0, GRID * GRID);
            }
        }

        InfiniteTensor.throwIfCancelled();
        KarstNetwork karst = KarstHydrology.solve(regionI, regionJ, windowI, windowJ, GRID,
                elev, acc, channel, fillDepth, waterOut, lake, widthM, down, REGION_PX, MARGIN_PX, blockM);
        InfiniteTensor.throwIfCancelled();
        return new Region(regionI, regionJ, crop(distance), crop(waterOut), crop(lake), crop(lakeDeep),
                crop(widthOut), crop(depthOut), karst);
    }

    private static void stampSections(int[] chanUp, float[] water,
                                      float[] elev, float[] offR, float[] offC, float[] widthM,
                                      float[] perR, float[] perC, float[] distance,
                                      float[] waterOut, float blockM) {

            for (int oi = chanUp.length - 1; oi >= 0; oi--) {
                int i = chanUp[oi];
                if (Float.isNaN(water[i]) || elev[i] < 0f) {
                    continue;
                }
                float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];

                float drawnM = Math.max(widthM[i], STAMP_MIN_BLOCKS * blockM);
                float halfCells = STAMP_REACH * 0.5f * drawnM / CELL_SIZE_M;

                int steps = Math.max(1, (int) Math.ceil(halfCells * 4f));
                float tanR = -perC[i], tanC = perR[i];
                for (int s = -steps; s <= steps; s++) {
                    float t = halfCells * s / steps;
                    for (int u = -1; u <= 1; u++) {
                    float ut = u * 0.5f;
                    int rr = Math.round(r0 + perR[i] * t + tanR * ut);
                    int cc = Math.round(c0 + perC[i] * t + tanC * ut);
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int k = rr * GRID + cc;

                    if (distance[k] < MAX_INFLUENCE_CELLS
                            && (Float.isNaN(waterOut[k]) || water[i] >= waterOut[k] - blockM)) {
                        waterOut[k] = water[i];
                    }
                    }
                }
            }
    }

    private static int[] strahlerOrder(float[] elev, boolean[] channel, int[] down,
                                       int[] chanUp) {
        int n = GRID * GRID;
        int[] order = new int[n];
        int[] best = new int[n];
        int[] ties = new int[n];

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (elev[i] < 0f) {
                continue;
            }
            int here = best[i] == 0 ? 1 : (ties[i] >= 2 ? best[i] + 1 : best[i]);
            order[i] = here;
            int d = successor(down, i);
            if (d < 0 || !channel[d] || elev[d] < 0f) {
                continue;
            }
            if (here > best[d]) {
                best[d] = here;
                ties[d] = 1;
            } else if (here == best[d]) {
                ties[d]++;
            }
        }
        return order;
    }

    private static float sampleGrid(float[] field, float rf, float cf) {
        int r0 = (int) Math.floor(rf), c0 = (int) Math.floor(cf);
        if (r0 < 0 || c0 < 0 || r0 >= GRID - 1 || c0 >= GRID - 1) {
            return Float.NaN;
        }
        float tr = rf - r0, tc = cf - c0;
        int a = r0 * GRID + c0;
        return lerp(lerp(field[a], field[a + 1], tc),
                    lerp(field[a + GRID], field[a + GRID + 1], tc), tr);
    }

    private static float valleyFloorM(float[] elev, float r0, float c0, float perpR, float perpC) {
        float base = sampleGrid(elev, r0, c0);
        if (Float.isNaN(base)) {
            return CELL_SIZE_M;
        }
        int cells = 0;
        for (int side = -1; side <= 1; side += 2) {
            for (int s = 1; s <= CONFINEMENT_SCAN_CELLS; s++) {
                float g = sampleGrid(elev, r0 + perpR * s * side, c0 + perpC * s * side);
                if (Float.isNaN(g) || g > base + CONFINEMENT_RISE_M) {
                    break;
                }
                cells++;
            }
        }
        return Math.max(1, cells) * CELL_SIZE_M;
    }

    private static void flowTangents(float[] elev, int[] chanIdx, int[] down,
                                     float[] perROut, float[] perCOut) {
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            int ahead = i;
            for (int s = 0; s < MEANDER_TANGENT_CELLS; s++) {
                int step = successor(down, ahead);
                if (step < 0) {
                    break;
                }
                ahead = step;
            }
            float dr = (ahead / GRID) - (i / GRID), dc = (ahead % GRID) - (i % GRID);
            float len = (float) Math.sqrt(dr * dr + dc * dc);
            if (len <= 0f) {
                continue;
            }
            perROut[i] = -dc / len;
            perCOut[i] = dr / len;
        }
    }

    private static void hydraulicGeometry(float[] elev, boolean[] channel, int[] chanIdx, int[] chanUp,
                                          float[] acc, int[] down,
                                          float[] perR, float[] perC,
                                          float[] widthOut, float[] depthOut) {
        int[] order = strahlerOrder(elev, channel, down, chanUp);
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }

            double area = Math.max(1.0, acc[i] / RIVER_THRESHOLD);
            double q = Math.pow(area, DISCHARGE_AREA_EXPONENT);
            float w = BANKFULL_WIDTH_M * (float) Math.pow(q, WIDTH_Q_EXPONENT);
            float dep = BANKFULL_DEPTH_M * (float) Math.pow(q, DEPTH_Q_EXPONENT);

            w *= 1f + ORDER_WIDTH_GAIN * (order[i] - 1);

            w = MAX_WIDTH_M * w / (MAX_WIDTH_M + w);
            dep = MAX_DEPTH_M * dep / (MAX_DEPTH_M + dep);

            float floorM = valleyFloorM(elev, i / GRID, i % GRID, perR[i], perC[i]);
            w = Math.min(w, CONFINEMENT_FRACTION * floorM);

            float squeeze = Math.min(2f, Math.max(1f,
                    (BANKFULL_WIDTH_M * (float) Math.pow(q, WIDTH_Q_EXPONENT)) / Math.max(1f, w)));
            dep *= (float) Math.sqrt(squeeze);

            widthOut[i] = Math.max(CELL_SIZE_M * 0.25f, w);
            depthOut[i] = Math.max(0.5f, dep);
        }
    }

    private static void meanderOffsets(float[] elev, int[] chanIdx, float[] widthM,
                                       float[] perR, float[] perC, int windowI, int windowJ,
                                       float[] offROut, float[] offCOut) {
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            float perpR = perR[i], perpC = perC[i];
            if (perpR == 0f && perpC == 0f) {
                continue;
            }
            int r = i / GRID, c = i % GRID;
            float floorM = valleyFloorM(elev, r, c, perpR, perpC);

            float ampM = MEANDER_AMPLITUDE_WIDTHS * widthM[i];
            ampM = Math.max(ampM, MEANDER_MIN_AMPLITUDE_M);
            ampM = Math.min(ampM, MEANDER_SPACE_FRACTION * floorM);
            if (ampM <= 0f) {
                continue;
            }
            float lamCells = MEANDER_ASPECT * ampM / CELL_SIZE_M;

            float nx = windowJ + c;
            float ny = windowI + r;
            float lateral = 0f, weight = 0f;
            float t = (float) (Math.log(Math.max(1f, lamCells)) / Math.log(2));
            for (int k = 0; k < MEANDER_BAND_CELLS.length; k++) {
                float tk = (float) (Math.log(MEANDER_BAND_CELLS[k]) / Math.log(2));
                float wk = 1f - Math.abs(t - tk) / 1.585f;
                if (wk <= 0f) {
                    continue;
                }
                lateral += wk * (MEANDER_BAND_I[k].GetNoise(nx, ny) * perpR
                        + MEANDER_BAND_J[k].GetNoise(nx, ny) * perpC);
                weight += wk;
            }
            if (weight <= 0f) {
                continue;
            }
            lateral /= weight;

            float want = lateral * (ampM / CELL_SIZE_M);
            float shift = valleyLimit(elev, r, c, perpR, perpC, want, MEANDER_CLIMB_M);
            offROut[i] = perpR * shift;
            offCOut[i] = perpC * shift;
        }
    }

    private static float freeboardM(float depthM, float blockM) {
        return Math.max(FREEBOARD_BLOCKS * blockM, 0.5f * depthM);
    }

    private static float[] containGround(float[] filled, float[] elev, int[] chanIdx,
                                         float[] offR, float[] offC, float[] perR, float[] perC,
                                         float[] widthM) {
        int n = GRID * GRID;
        float[] hold = new float[n];
        Arrays.fill(hold, Float.NaN);
        float[] buf = new float[64];
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];
            float halfCells = 0.5f * widthM[i] / CELL_SIZE_M;
            int span = Math.max(1, (int) Math.ceil(halfCells * 1.25f));
            int got = 0;
            for (int s = -span; s <= span && got < buf.length; s++) {
                float g = sampleGrid(filled, r0 + perR[i] * s, c0 + perC[i] * s);
                if (!Float.isNaN(g)) {
                    buf[got++] = g;
                }
            }
            if (got == 0) {
                hold[i] = filled[i];
                continue;
            }

            int k = Math.min(got - 1, (int) Math.floor(HOLD_QUANTILE * got));
            for (int a = 0; a <= k; a++) {
                int m = a;
                for (int b = a + 1; b < got; b++) {
                    if (buf[b] < buf[m]) {
                        m = b;
                    }
                }
                float t = buf[a];
                buf[a] = buf[m];
                buf[m] = t;
            }
            hold[i] = buf[k];
        }
        return hold;
    }

    private static void solveLevels(boolean[] channel, int[] chanIdx, int[] chanUp, float[] elev,
                                    float[] lake, float[] holdM, float[] depthM, float[] widthM, int[] down,
                                    float blockM, float[] waterOut) {
        int n = GRID * GRID;
        float[] lvl = new float[n];
        Arrays.fill(lvl, Float.NaN);
        boolean[] pinned = new boolean[n];

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            float here;
            if (elev[i] < 0f) {
                here = 0f;
                pinned[i] = true;
            } else if (!Float.isNaN(lake[i])) {
                here = lake[i];
                pinned[i] = true;
            } else {
                float own = Math.max(0f, holdM[i] - freeboardM(depthM[i], blockM));
                here = Float.isNaN(lvl[i]) ? own : Math.min(lvl[i], own);
            }
            lvl[i] = here;
            int d = successor(down, i);
            if (d >= 0 && channel[d] && (Float.isNaN(lvl[d]) || here < lvl[d])) {
                lvl[d] = here;
            }
        }

        int scanCap = SCAN_CAP;
        for (int sweep = 0; sweep < 12; sweep++) {
            boolean moved = false;
            for (int i : chanIdx) {
                if (Float.isNaN(lvl[i]) || pinned[i]) {
                    continue;
                }
                int r = i / GRID, c = i % GRID;
                float halfI = 0.5f * widthM[i] / CELL_SIZE_M;
                int rad = Math.min(scanCap, (int) Math.ceil(halfI) + 3);
                if (r < rad || c < rad || r >= GRID - rad || c >= GRID - rad) {
                    continue;
                }
                float lowest = Float.MAX_VALUE;
                for (int dr = -rad; dr <= rad; dr++) {
                    for (int dc = -rad; dc <= rad; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        int j = i + dr * GRID + dc;
                        if (!channel[j] || Float.isNaN(lvl[j]) || lvl[j] >= lowest) {
                            continue;
                        }
                        float sep = (float) Math.sqrt(dr * dr + dc * dc);

                        float reach = OVERLAP_REACH * (halfI + 0.5f * widthM[j] / CELL_SIZE_M);
                        if (sep <= reach) {
                            lowest = lvl[j];
                        }
                    }
                }
                float tol = OVERLAP_AGREE_BLOCKS * blockM;
                if (lowest != Float.MAX_VALUE && lvl[i] > lowest + tol) {
                    lvl[i] = lowest + tol;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }

        for (int sweep = 0; sweep < 24; sweep++) {
            boolean moved = false;
            for (int i : chanIdx) {
                if (Float.isNaN(lvl[i]) || pinned[i]) {
                    continue;
                }
                int r = i / GRID, c = i % GRID;
                if (r < 1 || c < 1 || r >= GRID - 1 || c >= GRID - 1) {
                    continue;
                }
                float lowest = Float.MAX_VALUE;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        int j = i + dr * GRID + dc;
                        if (channel[j] && !Float.isNaN(lvl[j]) && lvl[j] < lowest) {
                            lowest = lvl[j];
                        }
                    }
                }
                if (lowest != Float.MAX_VALUE && lvl[i] > lowest + blockM) {
                    lvl[i] = lowest + blockM;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (Float.isNaN(lvl[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && !Float.isNaN(lvl[d]) && !pinned[d] && lvl[d] > lvl[i]) {
                lvl[d] = lvl[i];
            }
        }

        for (int i : chanUp) {
            if (Float.isNaN(lvl[i]) || pinned[i]) {
                continue;
            }
            int d = successor(down, i);
            if (d < 0 || !channel[d] || Float.isNaN(lvl[d])) {
                continue;
            }
            if (lvl[i] > lvl[d] + blockM) {
                lvl[i] = lvl[d] + blockM;
            }
        }

        float[] cont = new float[n];
        for (int i = 0; i < n; i++) {
            cont[i] = (channel[i] && !Float.isNaN(lvl[i])) ? lvl[i] : Float.NaN;
        }
        float[] copy = new float[n];
        float[] predSum = new float[n];
        int[] predCnt = new int[n];
        for (int pass = 0; pass < DEQUANT_PASSES; pass++) {
            System.arraycopy(cont, 0, copy, 0, n);
            for (int i : chanIdx) {
                predSum[i] = 0f;
                predCnt[i] = 0;
            }
            for (int i : chanIdx) {
                if (Float.isNaN(copy[i])) {
                    continue;
                }
                int d = successor(down, i);
                if (d >= 0 && !Float.isNaN(copy[d])) {
                    predSum[d] += copy[i];
                    predCnt[d]++;
                }
            }
            for (int i : chanIdx) {
                if (Float.isNaN(copy[i]) || pinned[i]) {
                    continue;
                }
                int d = successor(down, i);
                float dn = (d >= 0 && !Float.isNaN(copy[d])) ? copy[d] : copy[i];
                float up = predCnt[i] > 0 ? predSum[i] / predCnt[i] : copy[i];
                cont[i] = 0.5f * copy[i] + 0.25f * dn + 0.25f * up;
            }

            for (int i : chanIdx) {
                if (Float.isNaN(cont[i]) || pinned[i]) {
                    continue;
                }
                float freeboard = freeboardM(depthM[i], blockM);
                float capM = holdM[i] - freeboard;
                if (cont[i] > capM) {
                    cont[i] = capM;
                }
                if (cont[i] < 0f) {
                    cont[i] = 0f;
                }
            }

            for (int oi = chanUp.length - 1; oi >= 0; oi--) {
                int i = chanUp[oi];
                if (Float.isNaN(cont[i])) {
                    continue;
                }
                int d = successor(down, i);
                if (d >= 0 && !Float.isNaN(cont[d]) && !pinned[d] && cont[d] > cont[i]) {
                    cont[d] = cont[i];
                }
            }
        }

        for (int i = 0; i < n; i++) {
            waterOut[i] = cont[i];
        }
    }

    private static void meander(float[] elev, boolean[] channel, int[] chanIdx, int[] down,
                                float[] water, float[] offR, float[] offC,
                                float[] widthM, float[] depthM, Paint p) {
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }

            float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];
            int d = successor(down, i);
            if (d < 0) {
                plot(p, r0, c0, water[i], widthM[i], depthM[i], i);
                continue;
            }

            boolean live = channel[d] && elev[d] >= 0f;
            trace(p, r0, c0, (d / GRID) + offR[d], (d % GRID) + offC[d],
                    water[i], live ? water[d] : water[i],
                    widthM[i], live ? widthM[d] : widthM[i],
                    depthM[i], live ? depthM[d] : depthM[i],
                    i, live ? d : i);
        }
    }

    private static float[] seaDistance(float[] elev) {
        int n = GRID * GRID;
        float[] d = new float[n];
        float far = GRID * 2f;
        for (int i = 0; i < n; i++) {
            d[i] = elev[i] < 0f ? 0f : far;
        }
        final float ORTH = 1f, DIAG = 1.41421f;
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                if (r > 0) d[i] = Math.min(d[i], d[i - GRID] + ORTH);
                if (c > 0) d[i] = Math.min(d[i], d[i - 1] + ORTH);
                if (r > 0 && c > 0) d[i] = Math.min(d[i], d[i - GRID - 1] + DIAG);
                if (r > 0 && c < GRID - 1) d[i] = Math.min(d[i], d[i - GRID + 1] + DIAG);
            }
        for (int r = GRID - 1; r >= 0; r--)
            for (int c = GRID - 1; c >= 0; c--) {
                int i = r * GRID + c;
                if (r < GRID - 1) d[i] = Math.min(d[i], d[i + GRID] + ORTH);
                if (c < GRID - 1) d[i] = Math.min(d[i], d[i + 1] + ORTH);
                if (r < GRID - 1 && c < GRID - 1) d[i] = Math.min(d[i], d[i + GRID + 1] + DIAG);
                if (r < GRID - 1 && c > 0) d[i] = Math.min(d[i], d[i + GRID - 1] + DIAG);
            }
        return d;
    }

    // Deepest gorge a channel may cut below the ground beside it, in blocks; a channel deeper than this
    // is dropped together with everything upstream of it
    private static final float MAX_GORGE_BLOCKS = 26f;

    // Returns whether any channel cell was removed
    private static boolean prunePerched(boolean[] channel, float[] water, float[] elev, float[] lake,
                                        int[] down, float blockM) {
        int n = GRID * GRID;
        boolean[] blocked = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (channel[i] && !Float.isNaN(water[i]) && Float.isNaN(lake[i])
                    && elev[i] - water[i] > MAX_GORGE_BLOCKS * blockM) {
                blocked[i] = true;
                any = true;
            }
        }
        if (!any) {
            return false;
        }

        final byte UNKNOWN = 0, REACHES = 1, STRANDED = 2;
        byte[] fate = new byte[n];
        int[] path = new int[GRID * 4 + 2];
        for (int start = 0; start < n; start++) {
            if (!channel[start] || fate[start] != UNKNOWN) {
                continue;
            }
            int len = 0;
            int at = start;
            byte verdict = STRANDED;
            while (len < path.length) {
                path[len++] = at;
                fate[at] = STRANDED;
                if (blocked[at]) {
                    break;
                }
                int r = at / GRID, c = at % GRID;
                if (r < 1 || c < 1 || r >= GRID - 1 || c >= GRID - 1) {
                    verdict = REACHES;
                    break;
                }
                int d = successor(down, at);
                if (d < 0) {
                    break;
                }
                if (elev[d] < 0f || fate[d] == REACHES) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == STRANDED && d != at) {
                    break;
                }
                at = d;
            }
            for (int k = 0; k < len; k++) {
                fate[path[k]] = verdict;
            }
        }
        int pruned = 0;
        for (int i = 0; i < n; i++) {
            if (channel[i] && fate[i] == STRANDED) {
                channel[i] = false;
                water[i] = Float.NaN;
                pruned++;
            }
        }
        return pruned > 0;
    }

    // Channel cells in index order, or in the given order when there is one
    private static int[] channelCells(boolean[] channel, int[] order) {
        int count = 0;
        for (boolean b : channel) {
            if (b) count++;
        }
        int[] out = new int[count];
        int k = 0;
        if (order == null) {
            for (int i = 0; i < channel.length; i++) {
                if (channel[i]) out[k++] = i;
            }
        } else {
            for (int i : order) {
                if (channel[i]) out[k++] = i;
            }
        }
        return out;
    }

    private static int successor(int[] down, int i) {
        int d = down[i];
        if (d < 0 || d == i) {
            return -1;
        }
        int dr = (d / GRID) - (i / GRID), dc = (d % GRID) - (i % GRID);
        return (Math.abs(dr) <= 1 && Math.abs(dc) <= 1) ? d : -1;
    }

    private static float valleyLimit(float[] elev, int r, int c,
                                     float perpR, float perpC, float want, float climbM) {
        float limit = Math.abs(want);
        if (limit <= 0f) {
            return 0f;
        }
        float sign = want < 0f ? -1f : 1f;
        float ceiling = elev[r * GRID + c] + climbM;
        float reached = 0f;
        int steps = (int) Math.ceil(limit);
        for (int s = 1; s <= steps; s++) {
            float t = Math.min(s, limit);
            int rr = Math.round(r + perpR * t * sign);
            int cc = Math.round(c + perpC * t * sign);
            if (rr < 1 || cc < 1 || rr >= GRID - 1 || cc >= GRID - 1) {
                break;
            }
            if (elev[rr * GRID + cc] > ceiling) {
                break;
            }
            reached = t;
        }
        return reached * sign;
    }

    private static final class Paint {
        final float[] distance, water, width, depth;

        final float[] pos;

        final int[] src;

        // Arc length of each channel cell along its flow path
        final float[] arc;

        Paint(float[] distance, float[] water, float[] width, float[] depth, float[] arc) {
            this.src = new int[GRID * GRID];
            Arrays.fill(this.src, -1);
            this.pos = new float[GRID * GRID];
            Arrays.fill(this.pos, Float.NaN);
            this.distance = distance;
            this.water = water;
            this.width = width;
            this.depth = depth;
            this.arc = arc;
        }
    }

    private static void trace(Paint p, float r0, float c0, float r1, float c1,
                              float w0, float w1, float wid0, float wid1, float dep0, float dep1,
                              int src0, int src1) {
        float dr = r1 - r0, dc = c1 - c0;
        int steps = Math.max(1, (int) Math.ceil(2f * Math.max(Math.abs(dr), Math.abs(dc))));
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            boolean near = t < 0.5f;
            plot(p, r0 + dr * t, c0 + dc * t, near ? w0 : w1, near ? wid0 : wid1, near ? dep0 : dep1,
                    near ? src0 : src1);
        }
    }

    private static void plot(Paint p, float rf, float cf, float w, float wid, float dep, int srcIdx) {
        int r = Math.round(rf), c = Math.round(cf);
        if (r < 0 || c < 0 || r >= GRID || c >= GRID) {
            return;
        }
        int i = r * GRID + c;
        if (p.distance[i] == 0f) {
            if (!Float.isNaN(w) && (Float.isNaN(p.water[i]) || w > p.water[i])) {
                p.water[i] = w;
                p.src[i] = srcIdx;
                p.pos[i] = srcIdx >= 0 ? p.arc[srcIdx] : Float.NaN;
            }
            if (wid > p.width[i]) {
                p.width[i] = wid;
            }
            if (dep > p.depth[i]) {
                p.depth[i] = dep;
            }
            return;
        }
        p.distance[i] = 0f;
        p.water[i] = w;
        p.src[i] = srcIdx;
        p.pos[i] = srcIdx >= 0 ? p.arc[srcIdx] : Float.NaN;
        p.width[i] = wid;
        p.depth[i] = dep;
    }

    // Smallest catchment worth carrying in, in native cells
    private static final float SEED_MIN_CELLS = 200000f;

    // Catchment of every window cell in native cells, counting what the coarse drainage says enters the window
    private static float[] accumulate(float[] elev, float[] slopeSurface,
                                      float[] fillDepthOut, int[] downOut, float[] slopeOut,
                                      int windowI, int windowJ, float[] inflow) {
        int n = GRID * GRID;
        float[] filled = elev.clone();
        boolean[] closed = new boolean[n];

        Arrays.fill(slopeOut, Float.MAX_VALUE);

        Arrays.fill(downOut, -1);

        LongHeap open = new LongHeap(n);

        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            if (elev[i] < 0f || r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                closed[i] = true;
                open.push(pack(filled[i], i));
            }
        }

        int[] nr4 = {-1, -1, -1, 0, 0, 1, 1, 1}, nc4 = {-1, 0, 1, -1, 1, -1, 0, 1};
        while (!open.isEmpty()) {
            int i = (int) open.pop();
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr4[k], cc = c + nc4[k];
                if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) continue;
                int j = rr * GRID + cc;
                if (closed[j]) continue;
                closed[j] = true;
                filled[j] = Math.max(filled[j], filled[i]);
                open.push(pack(filled[j], j));
            }
        }

        InfiniteTensor.throwIfCancelled();
        int[] nr8 = {-1, -1, -1, 0, 0, 1, 1, 1}, nc8 = {-1, 0, 1, -1, 1, -1, 0, 1};

        // Filling leaves every depression exactly level. A level cell with no lower neighbour finds its way off
        // by a lift compared only between cells of equal height: lower towards where the level drains and, half
        // as strongly, higher near the ground rising above it, so water gathers along the middle of a level
        // instead of running in parallel lines (Garbrecht and Martz 1997; Barnes, Lehman and Mulla 2014).
        // Distances are octile, so the lift has no square contours to channel water along the grid's axes.
        boolean[] flat = new boolean[n];
        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            if (elev[i] < 0f || r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                continue;
            }
            flat[i] = true;
            for (int k = 0; k < 8; k++) {
                if (filled[(r + nr8[k]) * GRID + (c + nc8[k])] < filled[i]) {
                    flat[i] = false;
                    break;
                }
            }
        }
        float[] toOutlet = new float[n];
        float[] fromHigh = new float[n];
        Arrays.fill(toOutlet, -1f);
        Arrays.fill(fromHigh, -1f);
        LongHeap towardsLow = new LongHeap(n), awayFromHigh = new LongHeap(n);
        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr8[k], cc = c + nc8[k];
                if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) continue;
                int j = rr * GRID + cc;
                // A level drains through any equal cell that is not itself flat: a slope's top or an outlet
                if (!flat[i] && flat[j] && filled[j] == filled[i] && toOutlet[i] < 0f) {
                    toOutlet[i] = 0f;
                    towardsLow.push(pack(0f, i));
                }
                if (flat[i] && filled[j] > filled[i] && fromHigh[i] < 0f) {
                    fromHigh[i] = 0f;
                    awayFromHigh.push(pack(0f, i));
                }
            }
        }
        spreadAcrossLevel(towardsLow, toOutlet, flat, filled);
        spreadAcrossLevel(awayFromHigh, fromHigh, flat, filled);

        int[] level = labelLevels(flat, filled);
        int levels = 0;
        for (int i = 0; i < n; i++) levels = Math.max(levels, level[i] + 1);
        float[] highest = new float[levels];
        for (int i = 0; i < n; i++) {
            if (flat[i]) highest[level[i]] = Math.max(highest[level[i]], fromHigh[i]);
        }
        float[] lift = new float[n];
        for (int i = 0; i < n; i++) {
            if (!flat[i] || toOutlet[i] < 0f) continue;
            float away = fromHigh[i] < 0f ? 0f : highest[level[i]] - fromHigh[i];
            float n01 = 0.5f + 0.5f * ROUTING_DETAIL.GetNoise(
                    windowJ + i % GRID, windowI + i / GRID);
            lift[i] = 2f * toOutlet[i] + away + n01;
        }

        InfiniteTensor.throwIfCancelled();
        int[] ascending = orderBy(filled, n);
        breakTies(ascending, filled, lift);
        InfiniteTensor.throwIfCancelled();

        float[] acc = new float[n];
        for (int i = 0; i < n; i++) acc[i] = 1f + inflow[i];

        final float QUADRANT = (float) (Math.PI / 4.0);
        for (int oi = n - 1; oi >= 0; oi--) {
            int i = ascending[oi];
            if (elev[i] < 0f) {
                continue;
            }
            int r = i / GRID, c = i % GRID;
            if (r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                continue;
            }

            float steepest = 0f;
            for (int k = 0; k < 8; k++) {
                int j = (r + nr8[k]) * GRID + (c + nc8[k]);
                float step = (nr8[k] != 0 && nc8[k] != 0) ? 1.41421f : 1f;

                float trueSlope = (slopeSurface[i] - slopeSurface[j]) / (step * CELL_SIZE_M);
                if (trueSlope > steepest) {
                    steepest = trueSlope;
                }
            }
            slopeOut[i] = steepest;

            float bestSlope = 0f, bestAngle = 0f;
            int bestCardinal = -1, bestDiagonal = -1;
            float bestS1 = 0f, bestS2 = 0f;
            int bestClamp = 0;
            for (int f = 0; f < 8; f++) {
                int cardinal = (r + FACET_CR[f]) * GRID + (c + FACET_CC[f]);
                int diagonal = (r + FACET_DR[f]) * GRID + (c + FACET_DC[f]);
                float s1 = fall(filled, lift, flat[i], i, cardinal);
                float s2 = fall(filled, lift, flat[i], cardinal, diagonal);
                float mag;
                int clamp;
                if (s2 < 0f) {
                    mag = s1;
                    clamp = -1;
                } else if ((s1 <= 0f && !(s1 == 0f && s2 == 0f)) || s2 > s1) {
                    mag = fall(filled, lift, flat[i], i, diagonal) / 1.41421f;
                    clamp = 1;
                } else {
                    mag = (float) Math.sqrt(s1 * s1 + s2 * s2);
                    clamp = 0;
                }
                if (mag > bestSlope) {
                    bestSlope = mag;
                    bestCardinal = cardinal;
                    bestDiagonal = diagonal;
                    bestS1 = s1;
                    bestS2 = s2;
                    bestClamp = clamp;
                }
            }
            bestAngle = bestClamp < 0 ? 0f
                    : bestClamp > 0 ? QUADRANT
                    : (float) Math.atan2(bestS2, bestS1);

            if (bestCardinal < 0 || bestSlope <= 0f) {
                downOut[i] = -1;
                continue;
            }

            float toDiagonal = bestAngle / QUADRANT;
            float toCardinal = 1f - toDiagonal;
            acc[bestCardinal] += acc[i] * toCardinal;
            acc[bestDiagonal] += acc[i] * toDiagonal;

            downOut[i] = toCardinal >= toDiagonal ? bestCardinal : bestDiagonal;
        }
        InfiniteTensor.throwIfCancelled();
        for (int i = 0; i < n; i++) fillDepthOut[i] = filled[i] - elev[i];
        return acc;
    }

    // Latent pixels of land drained around a region's window to find the rivers that enter it
    private static final int INFLOW_MARGIN = 192;

    // Native cells of catchment from beyond the window that enter it at each window cell. A coarse drainage
    // map of the land around the window, from the model's low-resolution heights, follows every outside cell
    // to where it first crosses into the window; there its catchment enters beside the lowest rim cell of the
    // coarse cell it crosses into. A river entering the window so arrives at its real size, and regions on
    // either side of a seam agree on which rivers exist.
    private static float[] inflow(WorldPipeline pipeline, int windowI, int windowJ, float[] elev) {
        int lc = WorldPipeline.LATENT_COMPRESSION;
        int inner = GRID / lc, cg = inner + 2 * INFLOW_MARGIN, cn = cg * cg;
        int li0 = Math.floorDiv(windowI, lc) - INFLOW_MARGIN, lj0 = Math.floorDiv(windowJ, lc) - INFLOW_MARGIN;
        float[] height = pipeline.getLowfreqSlice(li0, lj0, li0 + cg, lj0 + cg);
        for (int k = 0; k < cn; k++) {
            float m = Math.signum(height[k]) * height[k] * height[k];
            height[k] = WorldPipeline.RELIEF.apply(m, m);
        }

        // Priority flood from the coarse map's edge and the sea; each cell drains to the cell it was reached
        // from, and cells of equal height are reached in the order they were found, so level ground drains
        // evenly towards its outlet
        int[] parent = new int[cn];
        int[] reached = new int[cn];
        float[] filled = new float[cn];
        boolean[] seen = new boolean[cn];
        LongHeap open = new LongHeap(cn);
        int found = 0;
        for (int k = 0; k < cn; k++) {
            int r = k / cg, c = k % cg;
            if (height[k] < 0f || r == 0 || c == 0 || r == cg - 1 || c == cg - 1) {
                seen[k] = true;
                parent[k] = -1;
                filled[k] = height[k];
                reached[found] = k;
                open.push(pack(filled[k], found++));
            }
        }
        int[] order = new int[cn];
        int popped = 0;
        while (!open.isEmpty()) {
            int k = reached[(int) open.pop()];
            order[popped++] = k;
            int r = k / cg, c = k % cg;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr, cc = c + dc;
                    if ((dr == 0 && dc == 0) || rr < 0 || cc < 0 || rr >= cg || cc >= cg) continue;
                    int j = rr * cg + cc;
                    if (seen[j]) continue;
                    seen[j] = true;
                    parent[j] = k;
                    filled[j] = Math.max(height[j], filled[k]);
                    reached[found] = j;
                    open.push(pack(filled[j], found++));
                }
            }
        }

        float[] inflow = new float[GRID * GRID];
        float[] area = new float[cn];
        for (int o = popped - 1; o >= 0; o--) {
            int k = order[o];
            int r = k / cg - INFLOW_MARGIN, c = k % cg - INFLOW_MARGIN;
            if (r >= 0 && c >= 0 && r < inner && c < inner) continue;
            area[k] += lc * lc;
            int p = parent[k];
            if (p < 0) continue;
            int pr = p / cg - INFLOW_MARGIN, pc = p % cg - INFLOW_MARGIN;
            if (pr < 0 || pc < 0 || pr >= inner || pc >= inner) {
                area[p] += area[k];
                continue;
            }
            // The rim this crossing passes, and the window cells of the coarse cell along it
            boolean acrossRows = r < 0 || r >= inner;
            int rimR = r < 0 ? 0 : GRID - 1, rimC = c < 0 ? 0 : GRID - 1;
            int lowest = -1;
            for (int s = 0; s < lc; s++) {
                int cell = acrossRows ? rimR * GRID + pc * lc + s : (pr * lc + s) * GRID + rimC;
                if (lowest < 0 || elev[cell] < elev[lowest]) lowest = cell;
            }
            int lr = Math.max(1, Math.min(GRID - 2, lowest / GRID + (acrossRows ? (r < 0 ? 1 : -1) : 0)));
            int lcol = Math.max(1, Math.min(GRID - 2, lowest % GRID + (acrossRows ? 0 : (c < 0 ? 1 : -1))));
            inflow[lr * GRID + lcol] += area[k];
        }
        return inflow;
    }

    // How far water falls from cell a to cell b, as seen from a flat cell or not: across a level only the
    // lift tells cells apart
    private static float fall(float[] filled, float[] lift, boolean fromFlat, int a, int b) {
        return fromFlat && filled[a] == filled[b] ? lift[a] - lift[b] : filled[a] - filled[b];
    }

    // Orders cells of equal height by lift, so each level is visited from its high side down
    private static void breakTies(int[] order, float[] filled, float[] lift) {
        for (int start = 0; start < order.length; ) {
            int end = start + 1;
            while (end < order.length && filled[order[end]] == filled[order[start]]) end++;
            if (end - start > 1) {
                long[] run = new long[end - start];
                for (int k = start; k < end; k++) run[k - start] = pack(lift[order[k]], order[k]);
                Arrays.sort(run);
                for (int k = start; k < end; k++) order[k] = (int) run[k - start];
            }
            start = end;
        }
    }

    // Octile distance across a level's flat cells from the cells queued in open, which hold distance 0
    private static void spreadAcrossLevel(LongHeap open, float[] dist, boolean[] flat, float[] filled) {
        boolean[] settled = new boolean[dist.length];
        while (!open.isEmpty()) {
            int i = (int) open.pop();
            if (settled[i]) continue;
            settled[i] = true;
            int r = i / GRID, c = i % GRID;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr, cc = c + dc;
                    if ((dr == 0 && dc == 0) || rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) continue;
                    int j = rr * GRID + cc;
                    if (!flat[j] || filled[j] != filled[i]) continue;
                    float d = dist[i] + (dr != 0 && dc != 0 ? 1.41421f : 1f);
                    if (dist[j] < 0f || d < dist[j]) {
                        dist[j] = d;
                        open.push(pack(d, j));
                    }
                }
            }
        }
    }

    // Numbers each 8-connected group of flat cells of one height; -1 elsewhere
    private static int[] labelLevels(boolean[] flat, float[] filled) {
        int[] label = new int[flat.length];
        Arrays.fill(label, -1);
        int[] stack = new int[flat.length];
        int next = 0;
        for (int start = 0; start < flat.length; start++) {
            if (!flat[start] || label[start] >= 0) continue;
            int top = 0;
            stack[top++] = start;
            label[start] = next;
            while (top > 0) {
                int i = stack[--top];
                int r = i / GRID, c = i % GRID;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int j = (r + dr) * GRID + (c + dc);
                        if (flat[j] && label[j] < 0 && filled[j] == filled[i]) {
                            label[j] = next;
                            stack[top++] = j;
                        }
                    }
                }
            }
            next++;
        }
        return label;
    }

    // Squared distance to the nearest cell holding 0, in place; other cells hold 1e20 on entry
    private static void edt2d(float[] f, int w, int h) {
        int longest = Math.max(w, h);
        float[] col = new float[longest];
        float[] out = new float[longest];
        int[] v = new int[longest];
        float[] z = new float[longest + 1];

        for (int c = 0; c < w; c++) {
            for (int r = 0; r < h; r++) col[r] = f[r * w + c];
            edt1d(col, out, v, z, h);
            for (int r = 0; r < h; r++) f[r * w + c] = out[r];
        }
        for (int r = 0; r < h; r++) {
            System.arraycopy(f, r * w, col, 0, w);
            edt1d(col, out, v, z, w);
            System.arraycopy(out, 0, f, r * w, w);
        }
    }

    private static void edt1d(float[] f, float[] d, int[] v, float[] z, int n) {
        final float INF = Float.MAX_VALUE / 4f;
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            float s = ((f[q] + q * (float) q) - (f[v[k]] + v[k] * (float) v[k]))
                    / (2f * q - 2f * v[k]);
            while (k > 0 && s <= z[k]) {
                k--;
                s = ((f[q] + q * (float) q) - (f[v[k]] + v[k] * (float) v[k]))
                        / (2f * q - 2f * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            float dx = q - v[k];
            d[q] = dx * dx + f[v[k]];
        }
    }

    private static void chamfer(Paint p) {
        final float ORTH = 1f, DIAG = 1.41421f;
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                if (r > 0) relax(p, i, i - GRID, ORTH);
                if (c > 0) relax(p, i, i - 1, ORTH);
                if (r > 0 && c > 0) relax(p, i, i - GRID - 1, DIAG);
                if (r > 0 && c < GRID - 1) relax(p, i, i - GRID + 1, DIAG);
            }
        for (int r = GRID - 1; r >= 0; r--)
            for (int c = GRID - 1; c >= 0; c--) {
                int i = r * GRID + c;
                if (r < GRID - 1) relax(p, i, i + GRID, ORTH);
                if (c < GRID - 1) relax(p, i, i + 1, ORTH);
                if (r < GRID - 1 && c < GRID - 1) relax(p, i, i + GRID + 1, DIAG);
                if (r < GRID - 1 && c > 0) relax(p, i, i + GRID - 1, DIAG);
            }
    }

    private static void relax(Paint p, int i, int from, float step) {
        if (p.distance[from] == Float.MAX_VALUE) {
            return;
        }
        float candidate = p.distance[from] + step;
        if (candidate < p.distance[i]) {
            p.distance[i] = candidate;
            p.src[i] = p.src[from];
            p.pos[i] = p.pos[from];
            p.water[i] = p.water[from];
            p.width[i] = p.width[from];
            p.depth[i] = p.depth[from];
        }
    }
}
