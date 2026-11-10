package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public final class RiverHydrology {

    private static final Logger LOG = LoggerFactory.getLogger(RiverHydrology.class);

    private static final int REGION_PX = 1024;

    // Land a region drains beyond each of its edges. A river's head catchment fits inside it, so the regions
    // either side of a line drain the same land in full detail and agree on where every river begins.
    private static final int MARGIN_PX = 1024;
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

    // A river begins where this much catchment drains through one cell, in native cells of land that sheds
    // REFERENCE_RUNOFF_MM a year
    static final float STREAM_CELLS = 150000f;

    private static final float REFERENCE_RUNOFF_MM = 300f;

    private static final int[] FACET_CR = { 0, -1, -1, 0, 0, 1, 1, 0};
    private static final int[] FACET_CC = { 1, 0, 0, -1, -1, 0, 0, 1};
    private static final int[] FACET_DR = {-1, -1, -1, -1, 1, 1, 1, 1};
    private static final int[] FACET_DC = { 1, 1, -1, -1, -1, -1, 1, 1};

    static final float CELL_SIZE_M = 30f;

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

    private static final float LAKE_MIN_DEPTH_M = 15f;

    private static final int LAKE_MIN_CELLS = 150;

    private static final int LAKE_MAX_SPAN = MARGIN_PX;

    private static final int LAKE_RIM_CELLS = 3;

    private static final float LAKE_MIN_SEA_CELLS = 8f;

    private static final int LAKE_MIN_LANDMASS_CELLS = 20000;

    // A river reaches the sea where the ground below sea level lies this many cells from any dry ground. Before
    // that, in a puddle, a creek or the shallows along a coast, it runs on in a channel of its own width.
    private static final float OPEN_SEA_CELLS = 4f;

    public static final class Region {
        final int regionI, regionJ;
        // Every river the region traced across its window, the ones in the region itself, and the ones it draws
        // once its neighbours are known
        final RiverNetwork.Traced traced;
        final RiverNetwork own;
        volatile RiverNetwork drawn;
        final float[] lake;
        final float[] lakeDeep;
        final KarstNetwork karst;
        // 8-connected lake body of each crop cell, -1 where there is no lake
        final int[] lakeBody;
        // Whether each body survives comparison with its neighbours, judged when first sampled
        final byte[] bodyVerdict;

        Region(int regionI, int regionJ, RiverNetwork.Traced traced, float[] lake, float[] lakeDeep,
               KarstNetwork karst, float blockM) {
            this.regionI = regionI;
            this.regionJ = regionJ;
            this.traced = traced;
            this.own = traced.own(regionI, regionJ, REGION_PX, blockM);
            this.lake = lake;
            this.lakeDeep = lakeDeep;
            this.karst = karst;
            this.lakeBody = labelLakes(lake);
            this.bodyVerdict = new byte[Arrays.stream(lakeBody).max().orElse(-1) + 1];
        }
    }

    // A tile at a region corner reads the 4x4 regions around it, since each region draws rivers from its eight
    // neighbours; room for a 5x5 block keeps those while generation moves on and the next row is prefetched
    private static final int MAX_CACHED_REGIONS = 25;

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

        // When off, a column near a region edge reads rivers only from a neighbouring region that is already built
        public void buildSeamNeighbours(boolean on) {
            seamNeighbours = on;
        }
    }

    // Builds every region overlapping the native rectangle [i0, i1) x [j0, j1)
    public static void prepare(WorldPipeline pipeline, int i0, int j0, int i1, int j1, float blockM) {
        int ri0 = Math.floorDiv(i0, REGION_PX), ri1 = Math.floorDiv(i1 - 1, REGION_PX);
        int rj0 = Math.floorDiv(j0, REGION_PX), rj1 = Math.floorDiv(j1 - 1, REGION_PX);
        List<PriorityExecutor.Task<Region>> asked = new ArrayList<>();
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                asked.add(request(pipeline, ri, rj, blockM, PriorityExecutor.callerPriority()));
            }
        }
        asked.forEach(PriorityExecutor::join);
    }

    // Queues every neighbour of every region within a quarter region of the native rectangle that nobody has
    // asked for yet. A rectangle reads the regions it overlaps and, within a river's reach of an edge (which
    // also covers a tile's halo), the region across it; reading a region's lakes checks them against that
    // region's neighbours, which are built on the spot. Looking a quarter region out queues those before
    // generation reaches the edge, while it is still crossing tiles that need no inference. A first build takes minutes on CPU inference against under a minute per 1,024-block
    // tile, and only a first build is costly, since its model tensors stay on disk. Skipped while builds are
    // already waiting, so scans far from the generation front cannot pile up speculative work.
    public static void prefetch(WorldPipeline pipeline, int i0, int j0, int i1, int j1, float blockM) {
        if (BUILDERS.hasQueued()) return;
        int reach = REGION_PX / 4;
        int ri0 = Math.floorDiv(i0 - reach, REGION_PX) - 1, ri1 = Math.floorDiv(i1 + reach - 1, REGION_PX) + 1;
        int rj0 = Math.floorDiv(j0 - reach, REGION_PX) - 1, rj1 = Math.floorDiv(j1 + reach - 1, REGION_PX) + 1;
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                request(pipeline, ri, rj, blockM, PriorityExecutor.BACKGROUND);
            }
        }
    }

    // A region build holds about two gigabytes of working arrays, so at most two run at once
    private static final PriorityExecutor BUILDERS = new PriorityExecutor("terrain-diffusion-hydrology", 2);

    private RiverHydrology() {
    }

    private static long key(int regionI, int regionJ) {
        return (((long) regionI) << 32) ^ (regionJ & 0xFFFFFFFFL);
    }

    public static KarstNetwork karstAt(WorldPipeline pipeline, float nativeI, float nativeJ,
                                      float blockM) {
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, ri, rj, blockM);
        return region == null || region.karst == null ? KarstNetwork.EMPTY : region.karst;
    }

    // Gathers the rivers that shape the ground at a native point into out. A region's rivers reach past its edges, so
    // near an edge the region beyond it is asked too.
    public static void sampleRivers(WorldPipeline pipeline, float nativeI, float nativeJ, float blockM,
                                    WaterNetwork.Reaches out) {
        out.clear();
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        float fi = nativeI - ri * (float) REGION_PX, fj = nativeJ - rj * (float) REGION_PX;
        float reach = RiverNetwork.reachCells(blockM);
        RegionCache cache = pipeline.hydrology;
        for (int di = -1; di <= 1; di++) {
            if (di < 0 ? fi >= reach : di > 0 && fi < REGION_PX - reach) continue;
            for (int dj = -1; dj <= 1; dj++) {
                if (dj < 0 ? fj >= reach : dj > 0 && fj < REGION_PX - reach) continue;
                boolean own = di == 0 && dj == 0;
                if (!own && !cache.seamNeighbours && !cache.regions.containsKey(key(ri + di, rj + dj))) continue;
                rivers(pipeline, ri + di, rj + dj, blockM).gather(nativeI, nativeJ, out);
            }
        }
    }

    // The rivers a region draws: its own, and those it takes on from its neighbours' traces where they carry a river
    // over the line between them and it does not. Settled when the region is first sampled, from all eight
    // neighbours, so it comes out the same whoever asks first. Until then all nine regions are asked for before any
    // is waited on, so they build side by side.
    private static RiverNetwork rivers(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        int priority = PriorityExecutor.callerPriority();
        PriorityExecutor.Task<Region> asked = request(pipeline, regionI, regionJ, blockM, priority);
        if (asked.isDone()) {
            RiverNetwork drawn = PriorityExecutor.join(asked).drawn;
            if (drawn != null) return drawn;
        }
        RegionCache cache = pipeline.hydrology;
        List<PriorityExecutor.Task<Region>> neighbours = new ArrayList<>(Collections.nCopies(9, null));
        for (int code = 0; code < 9; code++) {
            int ri = regionI + code / 3 - 1, rj = regionJ + code % 3 - 1;
            if (code == RiverNetwork.Traced.OWN
                    || !cache.seamNeighbours && !cache.regions.containsKey(key(ri, rj))) continue;
            neighbours.set(code, request(pipeline, ri, rj, blockM, priority));
        }
        Region region = PriorityExecutor.join(asked);
        if (region.drawn == null) {
            RiverNetwork.Traced[] traced = new RiverNetwork.Traced[9];
            for (int code = 0; code < 9; code++) {
                if (neighbours.get(code) != null) traced[code] = PriorityExecutor.join(neighbours.get(code)).traced;
            }
            region.drawn = RiverNetwork.drawn(region.own, traced, regionI, regionJ, REGION_PX, blockM);
        }
        return region.drawn;
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
            if (other == null) continue;
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
        if (region == null) {
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
        float deep = Float.isNaN(region.lakeDeep[at]) ? 0f : region.lakeDeep[at];
        return new float[]{near, deep};
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

    // Gives each basin whose inflow falls short of the evaporation from its full surface a lake at the level where
    // the two balance, filling the lowest ground first, and makes that lake a sink, as is the lowest point of a
    // basin too dry to hold one. Returns whether there were any.
    private static boolean terminalLakes(Drainage d, float[] elev, float[] evaporation, boolean[] sink,
                                         float[] lake, float[] deep) {
        int basins = d.loss.length;
        boolean[] closed = new boolean[basins];
        int count = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            int b = d.basin[i];
            if (b >= 0 && d.inflow[b] < d.loss[b]) {
                closed[b] = true;
                count++;
            }
        }
        if (count == 0) {
            return false;
        }
        long[] lowestFirst = new long[count];
        count = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            if (d.basin[i] >= 0 && closed[d.basin[i]]) lowestFirst[count++] = pack(elev[i], i);
        }
        Arrays.sort(lowestFirst);
        float[] met = new float[basins], surface = new float[basins], bottom = new float[basins];
        Arrays.fill(surface, Float.NaN);
        Arrays.fill(bottom, Float.NaN);
        for (long p : lowestFirst) {
            int i = (int) p, b = d.basin[i];
            if (Float.isNaN(bottom[b])) {
                bottom[b] = elev[i];
                sink[i] = true;
            }
            if (!Float.isNaN(surface[b])) continue;
            if (met[b] + evaporation[i] > d.inflow[b]) {
                surface[b] = elev[i];
            } else {
                met[b] += evaporation[i];
            }
        }
        for (long p : lowestFirst) {
            int i = (int) p, b = d.basin[i];
            if (elev[i] < surface[b]) {
                lake[i] = surface[b];
                deep[i] = surface[b] - bottom[b];
                sink[i] = true;
            }
        }
        return true;
    }

    // Fills every remaining basin to its rim and carries each lake's surface a few cells onto its shore
    private static void fillLakes(Drainage d, float[] elev, float[] lake, float[] deep) {
        for (int i = 0; i < GRID * GRID; i++) {
            int b = d.basin[i];
            if (b >= 0) {
                lake[i] = d.level[b];
                deep[i] = d.deepest[b];
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
    }

    static long pack(float key, int index) {
        int bits = Float.floatToIntBits(key);
        bits ^= (bits >> 31) & 0x7FFFFFFF;
        return (((long) bits) << 32) | (index & 0xFFFFFFFFL);
    }

    static final class LongHeap {
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

        float[] elev = pipeline.heights(windowI, windowJ, windowI + WINDOW_PX, windowJ + WINDOW_PX)[0];
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
        boolean[] below = new boolean[GRID * GRID], dry = new boolean[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) {
            below[i] = elev[i] < 0f;
            dry[i] = !below[i];
        }
        float[] seaDist = distanceTo(below);
        float[] shoreDist = distanceTo(dry);
        int[] landArea = landmassArea(elev);
        float[][] climate = waterBalance(pipeline, windowI, windowJ, GRID, 1);
        float[] entering = inflow(pipeline, windowI, windowJ, elev);

        boolean[] sink = new boolean[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) sink[i] = shoreDist[i] >= OPEN_SEA_CELLS;
        float[] lake = new float[GRID * GRID];
        float[] lakeDeep = new float[GRID * GRID];
        Arrays.fill(lake, Float.NaN);
        Arrays.fill(lakeDeep, Float.NaN);
        // A lake that cannot fill its basin keeps all the water that reaches it, so the land below gets less and
        // the window drains again, until every basin left overflows
        Drainage drained;
        do {
            InfiniteTensor.throwIfCancelled();
            drained = drain(routing, sink, climate[0], climate[1], entering, seaDist, landArea, windowI, windowJ);
        } while (terminalLakes(drained, elev, climate[1], sink, lake, lakeDeep));
        fillLakes(drained, elev, lake, lakeDeep);
        float[] fillDepth = drained.fillDepth;
        int[] down = drained.down;
        float[] acc = drained.acc;

        // Where rivers end: the sea, a lake, or the bottom of a basin too dry to hold one
        boolean[] outlet = new boolean[GRID * GRID];
        float[] outletLevel = new float[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) {
            outlet[i] = sink[i] || drained.basin[i] >= 0;
            outletLevel[i] = below[i] ? 0f : Float.isNaN(lake[i]) ? elev[i] : lake[i];
        }

        boolean[] channel = new boolean[GRID * GRID];
        float[] filled = new float[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) {
            int r = i / GRID, c = i % GRID;
            channel[i] = !outlet[i] && acc[i] >= STREAM_CELLS
                    && r > 0 && c > 0 && r < GRID - 1 && c < GRID - 1;
            filled[i] = routing[i] + fillDepth[i];
        }
        float[] level = new float[GRID * GRID];
        float[] width = new float[GRID * GRID];
        Arrays.fill(level, Float.NaN);
        InfiniteTensor.throwIfCancelled();
        RiverNetwork.Traced traced = RiverNetwork.trace(regionI, regionJ, REGION_PX, windowI, windowJ, GRID, channel,
                down, acc, filled, elev, outlet, outletLevel, blockM, level, width);

        InfiniteTensor.throwIfCancelled();
        KarstNetwork karst = KarstHydrology.solve(regionI, regionJ, windowI, windowJ, GRID,
                elev, acc, channel, fillDepth, level, lake, REGION_PX, MARGIN_PX, blockM);
        InfiniteTensor.throwIfCancelled();
        return new Region(regionI, regionJ, traced, crop(lake), crop(lakeDeep), karst, blockM);
    }

    // Cells from each cell to the nearest target cell
    private static float[] distanceTo(boolean[] target) {
        int n = GRID * GRID;
        float[] d = new float[n];
        float far = GRID * 2f;
        for (int i = 0; i < n; i++) {
            d[i] = target[i] ? 0f : far;
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

    // How water drains a window: where each cell sends it, how much passes through, and the lake basins it
    // fills on the way
    private static final class Drainage {
        final float[] fillDepth = new float[GRID * GRID];
        final int[] down = new int[GRID * GRID];
        final float[] acc = new float[GRID * GRID];
        // The lake basin holding each cell, -1 outside every basin
        final int[] basin = new int[GRID * GRID];
        // Per basin: its surface when full, its depth there, what evaporates from that surface, and the water
        // that reaches it
        float[] level, deepest, loss, inflow;
    }

    // Drains the window into the sea, its edges and every sink, each cell counted by its runoff, with what the
    // coarse drainage says enters the window. A lake basin is a filled depression deep and broad enough to hold
    // a lake; water crosses it to its outlet and leaves less what evaporates from its surface.
    private static Drainage drain(float[] elev, boolean[] sink, float[] runoff, float[] evaporation,
                                  float[] inflow, float[] seaDist, int[] landArea, int windowI, int windowJ) {
        int n = GRID * GRID;
        Drainage d = new Drainage();
        int[] downOut = d.down;
        float[] filled = elev.clone();
        boolean[] closed = new boolean[n];

        Arrays.fill(downOut, -1);

        LongHeap open = new LongHeap(n);

        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            closed[i] = sink[i] || r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1;
        }
        int[] nr8 = {-1, -1, -1, 0, 0, 1, 1, 1}, nc8 = {-1, 0, 1, -1, 1, -1, 0, 1};
        // The flood spreads from the sea and the window's edge, from just the cells of them beside open ground
        for (int i = 0; i < n; i++) {
            if (!closed[i]) continue;
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr8[k], cc = c + nc8[k];
                if (rr >= 0 && cc >= 0 && rr < GRID && cc < GRID && !closed[rr * GRID + cc]) {
                    open.push(pack(filled[i], i));
                    break;
                }
            }
        }

        // A cell no higher than the one it is reached from lies in a depression and fills to that level at once,
        // so it is queued in arrival order instead of by height (Barnes, Lehman and Mulla 2014). A cell higher
        // than every open neighbour is on a slope: those neighbours drain through it at their own height whenever
        // it is reached, so slopes are climbed in arrival order too, and only a cell beside open ground no higher
        // than itself waits its turn by height (Zhou, Sun and Fu 2016). Each cell is queued once, so the
        // depression queue fills its array from the front and the slope queue from the back.
        int[] queued = new int[n];
        int pitHead = 0, pitTail = 0, slopeHead = n - 1, slopeTail = n - 1;
        while (true) {
            int i;
            if (pitHead < pitTail) {
                i = queued[pitHead++];
            } else if (slopeHead > slopeTail) {
                i = queued[slopeHead--];
                if (spillsBeside(i, filled, closed)) {
                    open.push(pack(filled[i], i));
                    continue;
                }
            } else if (!open.isEmpty()) {
                i = (int) open.pop();
            } else {
                break;
            }
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr8[k], cc = c + nc8[k];
                if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) continue;
                int j = rr * GRID + cc;
                if (closed[j]) continue;
                closed[j] = true;
                if (filled[j] <= filled[i]) {
                    filled[j] = filled[i];
                    queued[pitTail++] = j;
                } else {
                    queued[slopeTail--] = j;
                }
            }
        }

        InfiniteTensor.throwIfCancelled();
        // Filling leaves every depression exactly level. A level cell with no lower neighbour finds its way off
        // by a lift compared only between cells of equal height: lower towards where the level drains and, half
        // as strongly, higher near the ground rising above it, so water gathers along the middle of a level
        // instead of running in parallel lines (Garbrecht and Martz 1997; Barnes, Lehman and Mulla 2014).
        // Distances are octile, so the lift has no square contours to channel water along the grid's axes.
        boolean[] flat = new boolean[n];
        IntStream.range(1, GRID - 1).parallel().forEach(r -> {
            for (int c = 1; c < GRID - 1; c++) {
                int i = r * GRID + c;
                if (sink[i]) continue;
                flat[i] = true;
                for (int k = 0; k < 8; k++) {
                    if (filled[(r + nr8[k]) * GRID + (c + nc8[k])] < filled[i]) {
                        flat[i] = false;
                        break;
                    }
                }
            }
        });
        float[] toOutlet = new float[n];
        float[] fromHigh = new float[n];
        Arrays.fill(toOutlet, -1f);
        Arrays.fill(fromHigh, -1f);
        LongHeap towardsLow = new LongHeap(n), awayFromHigh = new LongHeap(n);
        // Flat cells lie inside the window's edge, so all their neighbours are in it
        for (int i = 0; i < n; i++) {
            if (!flat[i]) continue;
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int j = (r + nr8[k]) * GRID + (c + nc8[k]);
                // A level drains through any equal cell that is not itself flat: a slope's top or an outlet
                if (!flat[j] && filled[j] == filled[i] && toOutlet[j] < 0f) {
                    toOutlet[j] = 0f;
                    towardsLow.push(pack(0f, j));
                }
                if (filled[j] > filled[i] && fromHigh[i] < 0f) {
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
        basins(d, level, levels, filled, elev, evaporation, seaDist, landArea);
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

        // Where each cell sends its water: down the steepest of its eight triangular facets, split between the
        // facet's cardinal and diagonal neighbour by the angle of fall across it (Tarboton 1997)
        InfiniteTensor.throwIfCancelled();
        byte[] facet = new byte[n];
        Arrays.fill(facet, (byte) -1);
        float[] toDiagonalOf = new float[n];
        final float QUADRANT = (float) (Math.PI / 4.0);
        IntStream.range(1, GRID - 1).parallel().forEach(r -> {
            for (int c = 1; c < GRID - 1; c++) {
                int i = r * GRID + c;
                if (sink[i]) {
                    continue;
                }
                float bestSlope = 0f;
                int bestFacet = -1;
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
                        bestFacet = f;
                        bestS1 = s1;
                        bestS2 = s2;
                        bestClamp = clamp;
                    }
                }
                if (bestFacet < 0) {
                    continue;
                }
                float bestAngle = bestClamp < 0 ? 0f
                        : bestClamp > 0 ? QUADRANT
                        : (float) Math.atan2(bestS2, bestS1);
                float toDiagonal = bestAngle / QUADRANT;
                facet[i] = (byte) bestFacet;
                toDiagonalOf[i] = toDiagonal;
                downOut[i] = 1f - toDiagonal >= toDiagonal ? cardinalOf(i, bestFacet) : diagonalOf(i, bestFacet);
            }
        });

        // Each cell passes its water on once every cell draining into it has. Water only runs downhill, so every
        // cell is reached, in an order that needs no sorting of the window.
        InfiniteTensor.throwIfCancelled();
        int[] donors = new int[n];
        for (int i = 0; i < n; i++) {
            if (facet[i] < 0) continue;
            if (toDiagonalOf[i] < 1f) donors[cardinalOf(i, facet[i])]++;
            if (toDiagonalOf[i] > 0f) donors[diagonalOf(i, facet[i])]++;
        }
        int[] ready = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (donors[i] == 0) ready[tail++] = i;
        }
        float[] acc = d.acc;
        for (int i = 0; i < n; i++) acc[i] = runoff[i] + inflow[i];
        float[] unmet = d.loss.clone();
        while (head < tail) {
            int i = ready[head++];
            if (facet[i] < 0) continue;
            int cardinal = cardinalOf(i, facet[i]), diagonal = diagonalOf(i, facet[i]);
            float toDiagonal = toDiagonalOf[i];
            float toCardinal = 1f - toDiagonal;
            int b = d.basin[i];
            if (b >= 0 && d.basin[downOut[i]] != b) {
                d.inflow[b] += acc[i];
                float evaporated = Math.min(acc[i], unmet[b]);
                acc[i] -= evaporated;
                unmet[b] -= evaporated;
            }
            // Water spreads down open ground, but once it has gathered a river's catchment, or crosses a lake, it
            // keeps to one channel, which so carries all of it to the sea
            if (b >= 0 || acc[i] >= STREAM_CELLS) {
                acc[downOut[i]] += acc[i];
            } else {
                acc[cardinal] += acc[i] * toCardinal;
                acc[diagonal] += acc[i] * toDiagonal;
            }
            if (toDiagonal < 1f && --donors[cardinal] == 0) ready[tail++] = cardinal;
            if (toDiagonal > 0f && --donors[diagonal] == 0) ready[tail++] = diagonal;
        }
        if (tail != n) {
            throw new IllegalStateException("Drainage has a cycle: " + (n - tail) + " cells never drained");
        }
        InfiniteTensor.throwIfCancelled();
        for (int i = 0; i < n; i++) d.fillDepth[i] = filled[i] - elev[i];
        return d;
    }

    // Marks the levels that are lake basins: deep, broad, inland and wholly inside the window
    private static void basins(Drainage d, int[] level, int levels, float[] filled, float[] elev,
                               float[] evaporation, float[] seaDist, int[] landArea) {
        int[] cells = new int[levels], land = new int[levels];
        int[] minR = new int[levels], maxR = new int[levels], minC = new int[levels], maxC = new int[levels];
        float[] nearestSea = new float[levels];
        Arrays.fill(minR, GRID);
        Arrays.fill(minC, GRID);
        Arrays.fill(nearestSea, Float.MAX_VALUE);
        d.level = new float[levels];
        d.deepest = new float[levels];
        d.loss = new float[levels];
        d.inflow = new float[levels];
        for (int i = 0; i < GRID * GRID; i++) {
            int b = level[i];
            if (b < 0) continue;
            int r = i / GRID, c = i % GRID;
            cells[b]++;
            land[b] = landArea[i];
            d.level[b] = filled[i];
            d.deepest[b] = Math.max(d.deepest[b], filled[i] - elev[i]);
            d.loss[b] += evaporation[i];
            nearestSea[b] = Math.min(nearestSea[b], seaDist[i]);
            minR[b] = Math.min(minR[b], r);
            maxR[b] = Math.max(maxR[b], r);
            minC[b] = Math.min(minC[b], c);
            maxC[b] = Math.max(maxC[b], c);
        }
        boolean[] lake = new boolean[levels];
        for (int b = 0; b < levels; b++) {
            lake[b] = d.deepest[b] >= LAKE_MIN_DEPTH_M && cells[b] >= LAKE_MIN_CELLS
                    && nearestSea[b] >= LAKE_MIN_SEA_CELLS && land[b] >= LAKE_MIN_LANDMASS_CELLS
                    && minR[b] > 1 && minC[b] > 1 && maxR[b] < GRID - 2 && maxC[b] < GRID - 2
                    && maxR[b] - minR[b] < LAKE_MAX_SPAN && maxC[b] - minC[b] < LAKE_MAX_SPAN;
            if (!lake[b]) d.loss[b] = 0f;
        }
        for (int i = 0; i < GRID * GRID; i++) {
            d.basin[i] = level[i] >= 0 && lake[level[i]] ? level[i] : -1;
        }
    }

    // {runoff from land, net evaporation from open water} of each cell, relative to REFERENCE_RUNOFF_MM, on an
    // n x n grid of cells step native px wide from native (i0, j0), interpolated between the coarse map's cells.
    // Potential evaporation follows the mean temperature (Holdridge); land keeps the rain evaporation leaves,
    // after Budyko's curve in Fu's form, and open water loses what potential evaporation takes beyond the rain.
    private static float[][] waterBalance(WorldPipeline pipeline, int i0, int j0, int n, int step) {
        int px = 32 * WorldPipeline.LATENT_COMPRESSION;
        int ci0 = Math.floorDiv(i0, px) - 1, cj0 = Math.floorDiv(j0, px) - 1;
        int ch = Math.floorDiv(i0 + n * step, px) + 2 - ci0, cw = Math.floorDiv(j0 + n * step, px) + 2 - cj0;
        FloatTensor coarse = pipeline.getCoarseSlice(ci0, cj0, ci0 + ch, cj0 + cw);
        int cn = ch * cw;
        float[][] cell = new float[2][cn];
        for (int k = 0; k < cn; k++) {
            double rain = Math.max(0f, coarse.data[4 * cn + k]);
            double evaporation = 58.93 * Math.max(0f, coarse.data[2 * cn + k]);
            double aridity = evaporation / Math.max(1.0, rain);
            cell[0][k] = (float) (rain * (Math.pow(1 + Math.pow(aridity, 2.6), 1 / 2.6) - aridity)
                    / REFERENCE_RUNOFF_MM);
            cell[1][k] = (float) (Math.max(0.0, evaporation - rain) / REFERENCE_RUNOFF_MM);
        }
        float[][] out = new float[2][n * n];
        for (int r = 0; r < n; r++) {
            float u = (i0 + (r + 0.5f) * step) / px - 0.5f - ci0;
            int a = (int) Math.floor(u);
            float tu = u - a;
            for (int c = 0; c < n; c++) {
                float v = (j0 + (c + 0.5f) * step) / px - 0.5f - cj0;
                int b = (int) Math.floor(v);
                float tv = v - b;
                int k = a * cw + b;
                for (int f = 0; f < 2; f++) {
                    float[] g = cell[f];
                    out[f][r * n + c] = lerp(lerp(g[k], g[k + 1], tv), lerp(g[k + cw], g[k + cw + 1], tv), tu);
                }
            }
        }
        return out;
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
        float[] runoff = waterBalance(pipeline, li0 * lc, lj0 * lc, cg, lc)[0];
        for (int k = 0; k < cn; k++) {
            float m = Math.signum(height[k]) * height[k] * height[k];
            height[k] = pipeline.relief.apply(m, m);
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
            area[k] += lc * lc * runoff[k];
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

    // Whether an open neighbour of a cell is no higher than it, so would fill from it
    private static boolean spillsBeside(int i, float[] filled, boolean[] closed) {
        int r = i / GRID, c = i % GRID;
        for (int rr = Math.max(0, r - 1); rr <= Math.min(GRID - 1, r + 1); rr++) {
            for (int cc = Math.max(0, c - 1); cc <= Math.min(GRID - 1, c + 1); cc++) {
                int j = rr * GRID + cc;
                if (!closed[j] && filled[j] <= filled[i]) return true;
            }
        }
        return false;
    }

    // The cardinal and diagonal neighbour of a cell that bound one of its eight facets
    private static int cardinalOf(int i, int f) {
        return i + FACET_CR[f] * GRID + FACET_CC[f];
    }

    private static int diagonalOf(int i, int f) {
        return i + FACET_DR[f] * GRID + FACET_DC[f];
    }

    // How far water falls from cell a to cell b, as seen from a flat cell or not: across a level only the
    // lift tells cells apart
    private static float fall(float[] filled, float[] lift, boolean fromFlat, int a, int b) {
        return fromFlat && filled[a] == filled[b] ? lift[a] - lift[b] : filled[a] - filled[b];
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
}
