package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;

// The river centrelines a region draws, as segments between points along each river with the water level, width,
// depth, valley reach and bend at both ends. A region draws the segments that start in it, so each stretch of
// river exists once and the rivers of neighbouring regions meet where one region's last segment ends on the other's
// first. Where the two disagree, because each sees the land beyond its own window only coarsely, a river one region
// carries over the line is drawn on as that region traced it (see drawn). A bucket index finds the segments near
// a point.
final class RiverNetwork {

    // Floats per segment: native i, j, water level, width, depth, valley reach and bend at its upstream end, then
    // at its downstream end
    private static final int END = 7, STRIDE = 2 * END;

    private static final int BUCKET = 16;

    // A river shapes the ground beyond its bank (see WaterNetwork) out to this many of its half-widths where it
    // falls STEEP_SLOPE or more, and this many more where it runs flat, so a steep river runs in a ravine and a
    // flat one in a broad valley
    private static final float VALLEY_WIDTHS_STEEP = 3f;
    private static final float VALLEY_WIDTHS_FLAT = 3f;
    private static final float STEEP_SLOPE = 0.02f;

    // Points on either side over which a river's fall is measured
    private static final int SLOPE_HALF = 8;

    // A river winds in waves this many of its widths long down its valley, leaving the valley's line at up to the
    // first angle where it falls steeply and the second where it runs flat (a sine-generated curve, Langbein and
    // Leopold 1966), so that no bend turns tighter than about its own width, and swings no farther than its valley
    // floor or this many cells either side
    private static final float MEANDER_WAVELENGTH_WIDTHS = 11f;
    private static final float STEEP_ANGLE = (float) Math.toRadians(15);
    private static final float MEANDER_ANGLE = (float) Math.toRadians(42);
    private static final int MAX_MEANDER_CELLS = 48;

    // A river's meanders swing across its valley's line as it runs over this many of its widths to either side, so
    // the valley turns slowly beneath even its widest swings
    private static final float FRAME_WIDTHS = 1.5f;

    // Meanders vary: each wave runs longer or shorter than the river's own by up to this share, so swings the farther
    // the longer it runs, and every bend leans upstream by this much (the skew of a Kinoshita curve)
    private static final float WAVE_JITTER = 0.5f;
    private static final float SKEW = 0.25f;
    private static final FastNoiseLite WAVES = makeWaves();

    private static FastNoiseLite makeWaves() {
        FastNoiseLite fnl = new FastNoiseLite(0x3A7E);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(0.5f);
        fnl.SetFractalOctaves(2);
        return fnl;
    }

    // A river's width and depth at its head; downstream they grow as powers of its discharge, width as its square
    // root (Leopold and Maddock 1953). At 7.5 m a block a river is as many blocks wide as a real river carrying
    // twice its water is metres (about 7 m times the root of its mean flow in cubic metres a second), so boats have
    // room on all but its headwaters: wider than its scaled valley, and deeper than real.
    private static final float STREAM_WIDTH_M = 85f;
    private static final float STREAM_DEPTH_M = 14.5f;
    private static final float WIDTH_Q_EXPONENT = 0.5f;
    private static final float DEPTH_Q_EXPONENT = 0.4f;

    static final float MAX_WIDTH_M = 720f;
    private static final float MAX_DEPTH_M = 45f;

    // A river springs up this share of its width and grows to all of it over this many of its widths
    private static final float SPRING_SHARE = 0.3f;
    private static final float SPRING_WIDTHS = 8f;

    // A river spreads by this share of its width over its last few widths before standing water, and as it
    // leaves a lake
    private static final float MOUTH_SPREAD = 0.6f;
    private static final float MOUTH_WIDTHS = 5f;

    // Below a fall a river has dug a pool this share of the fall deeper, no deeper than twice the river, which
    // shallows again over this many of its widths
    private static final float PLUNGE_SHARE = 0.5f;
    private static final float PLUNGE_WIDTHS = 1.5f;

    // A river's bend is its width over the radius it turns in, no more than this
    private static final float MAX_BEND = 8f;

    // A river's valley floor is the ground within this height of it, found this many cells to either side; a
    // valley narrower than the river holds it narrower and deeper
    private static final float FLOOR_RISE_M = 18f;
    private static final int CONFINEMENT_CELLS = 16;

    // The water of a river lies this many blocks below the valley floor it runs along
    private static final float FREEBOARD_BLOCKS = 1f;

    // The steepest a river's water may fall, at a stream's head and as a power of its discharge relative to a
    // head's: larger rivers run gentler (Flint's slope-area law)
    private static final float HEAD_SLOPE = 0.08f;
    private static final float SLOPE_Q_EXPONENT = 0.45f;

    // Deepest a river cuts below its valley floor, in its own depths, before it climbs back up in a waterfall
    private static final float CANYON_DEPTHS = 3f;

    // Points a river's line is averaged over on either side, and how many times, to take the corners off its cells:
    // at least this many, and as many as its width in cells, rounded down to a power of two so the regions either
    // side of a pin round alike. Its width, depth, valley floor and fall are averaged over this many times as many,
    // so a channel narrows, widens and swings over several of its own widths.
    private static final int SMOOTH_HALF = 2;
    private static final int SMOOTH_PASSES = 3;
    private static final int SIZE_SMOOTH = 4;

    private final float[] seg;
    private final int originI, originJ, side;
    // Segments of bucket b are members[first[b]] up to members[first[b + 1]]
    private final int[] first, members;
    // The segment each segment's water runs on into, or -1 where it ends, and whether any segment runs into it
    private final int[] next;
    private final boolean[] fed;

    // Joins are matched on points rounded to this many per cell
    private static final float JOIN_GRID = 64f;

    // Whether segment s + 1 carries on the river of segment s
    private static boolean continues(float[] seg, int count, int s) {
        int o = s * STRIDE;
        return s + 1 < count && seg[o + END] == seg[o + STRIDE] && seg[o + END + 1] == seg[o + STRIDE + 1];
    }

    // The segment each segment's water runs on into: the next along its river, or where its river joins a larger
    // one, the larger river's segment from the join, or -1 where it ends in standing water
    private static int[] runsInto(float[] seg, int count) {
        java.util.Map<Long, Integer> startAt = new java.util.HashMap<>();
        for (int s = 0; s < count; s++) startAt.putIfAbsent(joinKey(seg[s * STRIDE], seg[s * STRIDE + 1], 0, 0), s);
        int[] next = new int[count];
        for (int s = 0; s < count; s++) {
            next[s] = -1;
            if (continues(seg, count, s)) {
                next[s] = s + 1;
                continue;
            }
            float i = seg[s * STRIDE + END], j = seg[s * STRIDE + END + 1];
            for (int d = 0; d < 9 && next[s] < 0; d++) {
                Integer t = startAt.get(joinKey(i, j, d / 3 - 1, d % 3 - 1));
                if (t != null && t != s && Math.abs(seg[t * STRIDE] - i) < 1f / JOIN_GRID
                        && Math.abs(seg[t * STRIDE + 1] - j) < 1f / JOIN_GRID) next[s] = t;
            }
        }
        return next;
    }

    private static long joinKey(float i, float j, int di, int dj) {
        return (long) (Math.round(i * JOIN_GRID) + di) << 32 ^ (Math.round(j * JOIN_GRID) + dj) & 0xFFFFFFFFL;
    }

    // Every segment a region traced across its window, in order down each river, the region each lies in, as OWN for
    // the region itself or a code for the neighbour at row offset code / 3 - 1 and column offset code % 3 - 1, and
    // the segment its water runs on into
    static final class Traced {
        static final byte OWN = 4;
        final float[] seg;
        final byte[] region;
        final int[] next;
        final int count;

        Traced(float[] seg, byte[] region, int count) {
            this.seg = seg;
            this.region = region;
            this.count = count;
            next = runsInto(seg, count);
        }

        // The segments that lie in the region itself
        RiverNetwork own(int regionI, int regionJ, int regionPx) {
            float[] out = new float[count * STRIDE];
            int n = 0;
            for (int s = 0; s < count; s++) {
                if (region[s] == OWN) System.arraycopy(seg, s * STRIDE, out, n++ * STRIDE, STRIDE);
            }
            return new RiverNetwork(out, n, regionI, regionJ, regionPx);
        }
    }

    // The region's own segments and those it adopts from each neighbour's trace. Where a neighbour draws a river up to
    // the line between them, or from it, and the region's own rivers do not carry it on, the region draws the
    // neighbour's river on its side of the line as the neighbour traced it, until it comes within a width of a river
    // of the region's own.
    static RiverNetwork drawn(RiverNetwork own, Traced[] neighbours, int regionI, int regionJ, int regionPx) {
        float[] out = Arrays.copyOf(own.seg, own.seg.length);
        int n = own.seg.length / STRIDE;
        for (int code = 0; code < 9; code++) {
            Traced from = neighbours[code];
            if (from == null) continue;
            // This region as the neighbour codes it
            byte here = (byte) (8 - code);
            boolean[] taken = new boolean[from.count];
            for (int s = 0; s < from.count; s++) {
                if (from.region[s] != Traced.OWN) continue;
                for (int t = from.next[s]; t >= 0 && from.region[t] == here && !taken[t]
                        && own.apart(from.seg, t * STRIDE); t = from.next[t]) {
                    taken[t] = true;
                }
                for (int t = s - 1; t >= 0 && continues(from.seg, from.count, t) && from.region[t] == here && !taken[t]
                        && own.apart(from.seg, t * STRIDE + END); t--) {
                    taken[t] = true;
                }
            }
            for (int t = 0; t < from.count; t++) {
                if (!taken[t]) continue;
                if (n * STRIDE + STRIDE > out.length) out = Arrays.copyOf(out, Math.max(STRIDE, out.length * 2));
                System.arraycopy(from.seg, t * STRIDE, out, n++ * STRIDE, STRIDE);
            }
        }
        return new RiverNetwork(out, n, regionI, regionJ, regionPx);
    }

    // Whether the river point at seg[o] lies more than its width from every river of this network
    private boolean apart(float[] point, int o) {
        float i = point[o], j = point[o + 1];
        float widthCells = point[o + 3] / RiverHydrology.CELL_SIZE_M;
        int bi = (int) Math.floor((i - originI) / BUCKET), bj = (int) Math.floor((j - originJ) / BUCKET);
        if (bi < 0 || bj < 0 || bi >= side || bj >= side) return true;
        int b = bi * side + bj;
        for (int m = first[b]; m < first[b + 1]; m++) {
            int q = members[m] * STRIDE;
            float ai = seg[q], aj = seg[q + 1];
            float di = seg[q + END] - ai, dj = seg[q + END + 1] - aj;
            float len2 = di * di + dj * dj;
            float t = len2 > 0f ? Math.max(0f, Math.min(1f, ((i - ai) * di + (j - aj) * dj) / len2)) : 0f;
            float oi = ai + di * t - i, oj = aj + dj * t - j;
            if (oi * oi + oj * oj < widthCells * widthCells) return false;
        }
        return true;
    }

    private RiverNetwork(float[] seg, int count, int regionI, int regionJ, int regionPx) {
        this.seg = Arrays.copyOf(seg, count * STRIDE);
        next = runsInto(this.seg, count);
        fed = new boolean[count];
        for (int t : next) if (t >= 0) fed[t] = true;
        int reach = (int) Math.ceil(reachCells());
        originI = regionI * regionPx - reach;
        originJ = regionJ * regionPx - reach;
        side = (regionPx + 2 * reach + BUCKET - 1) / BUCKET;
        first = new int[side * side + 1];
        for (int pass = 0; pass < 2; pass++) {
            int[] fill = pass == 0 ? null : Arrays.copyOf(first, side * side);
            int[] out = pass == 0 ? null : new int[first[side * side]];
            for (int s = 0; s < count; s++) {
                int o = s * STRIDE;
                float r = WaterNetwork.reachM(Math.max(seg[o + 3], seg[o + END + 3]),
                        Math.max(seg[o + 5], seg[o + END + 5])) / RiverHydrology.CELL_SIZE_M;
                int b0 = bucket(Math.min(seg[o], seg[o + END]) - r - originI);
                int b1 = bucket(Math.max(seg[o], seg[o + END]) + r - originI);
                int c0 = bucket(Math.min(seg[o + 1], seg[o + END + 1]) - r - originJ);
                int c1 = bucket(Math.max(seg[o + 1], seg[o + END + 1]) + r - originJ);
                for (int bi = b0; bi <= b1; bi++) {
                    for (int bj = c0; bj <= c1; bj++) {
                        if (pass == 0) first[bi * side + bj + 1]++;
                        else out[fill[bi * side + bj]++] = s;
                    }
                }
            }
            if (pass == 0) {
                for (int b = 0; b < side * side; b++) first[b + 1] += first[b];
            } else {
                members = out;
                return;
            }
        }
        throw new IllegalStateException();
    }

    private int bucket(float offset) {
        return Math.max(0, Math.min(side - 1, (int) Math.floor(offset / BUCKET)));
    }

    // Farthest a region's rivers reach past its edges, in native cells: its widest valley side beyond its widest
    // meander, from the last of its points, a cell past its edge
    static float reachCells() {
        float valley = 0.5f * MAX_WIDTH_M * (VALLEY_WIDTHS_STEEP + VALLEY_WIDTHS_FLAT);
        return 2f + MAX_MEANDER_CELLS + WaterNetwork.reachM(MAX_WIDTH_M, valley) / RiverHydrology.CELL_SIZE_M;
    }

    // Adds the point nearest (i, j) of every segment that shapes the ground there. A segment shapes what lies
    // abreast of it. What lies above its upper end belongs to the river running into it, and what lies below its
    // lower end to the river it runs into, but for the wedge that river leaves on the outside of a turn; so only
    // a river's head and mouth are round, and no ground is drawn down to water lower than the water beside it.
    void gather(float i, float j, WaterNetwork.Reaches out) {
        int bi = (int) Math.floor((i - originI) / BUCKET), bj = (int) Math.floor((j - originJ) / BUCKET);
        if (bi < 0 || bj < 0 || bi >= side || bj >= side) return;
        int b = bi * side + bj;
        for (int m = first[b]; m < first[b + 1]; m++) {
            int s = members[m], o = s * STRIDE;
            float ai = seg[o], aj = seg[o + 1];
            float di = seg[o + END] - ai, dj = seg[o + END + 1] - aj;
            float along = along(s, i, j);
            if (along < 0f && fed[s]) continue;
            if (along > 1f && next[s] >= 0 && along(next[s], i, j) >= 0f) continue;
            float t = Math.max(0f, Math.min(1f, along));
            float oi = ai + di * t - i, oj = aj + dj * t - j;
            float d = RiverHydrology.CELL_SIZE_M * (float) Math.sqrt(oi * oi + oj * oj);
            float width = lerp(seg[o + 3], seg[o + END + 3], t), valley = lerp(seg[o + 5], seg[o + END + 5], t);
            if (d >= WaterNetwork.reachM(width, valley)) continue;
            // The river turns towards the side its bend and the point's side of it agree on
            float bend = lerp(seg[o + 6], seg[o + END + 6], t);
            float outside = (di * (j - aj) - dj * (i - ai)) * bend > 0f ? -Math.abs(bend) : Math.abs(bend);
            out.add(d, lerp(seg[o + 2], seg[o + END + 2], t), width, lerp(seg[o + 4], seg[o + END + 4], t), valley,
                    outside);
        }
    }

    // How far along segment s the point (i, j) lies abreast of it: nothing at its upper end, one at its lower
    private float along(int s, float i, float j) {
        int o = s * STRIDE;
        float ai = seg[o], aj = seg[o + 1];
        float di = seg[o + END] - ai, dj = seg[o + END + 1] - aj;
        float len2 = di * di + dj * dj;
        return len2 > 0f ? ((i - ai) * di + (j - aj) * dj) / len2 : 0f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // Rises from nothing at 0 to one at 1 and beyond, level at both
    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // Traces the rivers of a drained window into segments across the whole window, and writes each river cell's water
    // level and width into levelOut and widthOut. Rivers are the channel cells: each sends its water to its down
    // cell over ground filled to filled, and carries acc of catchment. A river begins in the lake it drains, if it
    // drains one, and ends in the water its last cell drains to.
    static Traced trace(int regionI, int regionJ, int regionPx, int windowI, int windowJ, int grid,
                        boolean[] channel, int[] down, float[] acc, float[] filled, float[] elev,
                        boolean[] water, float[] waterLevel, float blockM, float[] levelOut, float[] widthOut) {
        int n = 0;
        for (boolean c : channel) if (c) n++;
        int[] cell = new int[n];
        int[] slot = new int[grid * grid];
        Arrays.fill(slot, -1);
        for (int i = 0, k = 0; i < grid * grid; i++) {
            if (channel[i]) {
                cell[k] = i;
                slot[i] = k++;
            }
        }
        // The node each node drains to, and its main parent: the branch carrying the most water into it
        int[] dn = new int[n], main = new int[n];
        Arrays.fill(main, -1);
        float[] mainAcc = new float[n];
        for (int k = 0; k < n; k++) {
            int d = down[cell[k]];
            dn[k] = d >= 0 ? slot[d] : -1;
            if (dn[k] >= 0 && acc[cell[k]] > mainAcc[dn[k]]) {
                mainAcc[dn[k]] = acc[cell[k]];
                main[dn[k]] = k;
            }
        }
        int[] rank = hopsToMouth(dn);

        // A chain runs from a head down its main stem, to a mouth or to where it joins a larger stem. Chains are
        // laid out downstream first, so a stem is placed before the tributaries that join it.
        int heads = 0;
        for (int k = 0; k < n; k++) if (main[k] < 0) heads++;
        long[] order = new long[heads];
        int[] chained = new int[n];
        int[] chainStart = new int[heads + 1];
        for (int k = 0, h = 0, at = 0; k < n; k++) {
            if (main[k] >= 0) continue;
            chainStart[h] = at;
            int c = k;
            while (true) {
                chained[at++] = c;
                int d = dn[c];
                if (d < 0 || main[d] != c) break;
                c = d;
            }
            order[h] = ((long) rank[c] << 32) | h;
            h++;
            chainStart[h] = at;
        }
        Arrays.sort(order);

        // Per node: where its valley runs, where its channel runs, and its water level, width and depth
        float[] vi = new float[n], vj = new float[n], pi = new float[n], pj = new float[n];
        float[] level = new float[n], width = new float[n], depth = new float[n];
        Chain ch = new Chain(n + 2);
        float[] seg = new float[STRIDE * 1024];
        byte[] segRegion = new byte[1024];
        int count = 0;
        for (long packed : order) {
            int h = (int) packed;
            int s0 = chainStart[h], s1 = chainStart[h + 1];
            int head = chained[s0], last = chained[s1 - 1];
            int join = dn[last], end = down[cell[last]];
            boolean outflow = join < 0 && end >= 0 && water[end];
            int lakeIn = drainedLake(cell[head], down, water, acc, grid);

            // The valley: the chain's cells, ending where the stem it joins runs, averaged over the width of channel
            // its discharge would carve
            ch.m = 0;
            if (lakeIn >= 0) ch.add(windowI + lakeIn / grid, windowJ + lakeIn % grid, lakeIn, -1);
            for (int s = s0; s < s1; s++) {
                int i = cell[chained[s]];
                ch.add(windowI + i / grid, windowJ + i % grid, i, chained[s]);
            }
            if (join >= 0) ch.add(vi[join], vj[join], cell[join], -1);
            else if (outflow) ch.add(windowI + end / grid, windowJ + end % grid, end, -1);
            int m = ch.m;
            int firstNode = lakeIn >= 0 ? 1 : 0, lastNode = m - (join >= 0 || outflow ? 2 : 1);
            for (int p = 0; p < m; p++) {
                int k = ch.node[Math.max(firstNode, Math.min(lastNode, p))];
                double q = acc[cell[k]] / RiverHydrology.STREAM_CELLS;
                ch.wd[p] = (float) Math.min(MAX_WIDTH_M, STREAM_WIDTH_M * Math.pow(q, WIDTH_Q_EXPONENT));
                ch.dp[p] = (float) Math.min(MAX_DEPTH_M, STREAM_DEPTH_M * Math.pow(q, DEPTH_Q_EXPONENT));
                ch.half[p] = Math.max(SMOOTH_HALF,
                        Integer.highestOneBit(Math.max(1, (int) (ch.wd[p] / RiverHydrology.CELL_SIZE_M))));
            }
            for (int p = 0; p < m; p++) {
                float widthCells = ch.wd[p] / RiverHydrology.CELL_SIZE_M;
                float fromHead = p / widthCells, toMouth = (m - 1 - p) / widthCells;
                float size = 1f;
                if (lakeIn < 0) size *= SPRING_SHARE + (1f - SPRING_SHARE) * ease(fromHead / SPRING_WIDTHS);
                else size *= 1f + MOUTH_SPREAD * (1f - ease(fromHead / MOUTH_WIDTHS));
                if (outflow) size *= 1f + MOUTH_SPREAD * (1f - ease(toMouth / MOUTH_WIDTHS));
                ch.wd[p] = Math.min(MAX_WIDTH_M, ch.wd[p] * size);
                ch.dp[p] *= Math.min(1f, (float) Math.sqrt(size));
            }
            smooth(ch.ci, ch.cj, 0, m - 1, ch.tmpI, ch.tmpJ, ch.half, 1);
            ch.frame();

            // Each node's channel, sized by its discharge and held by its valley floor
            for (int p = 0; p < m; p++) {
                int k = ch.node[p];
                if (k < 0) continue;
                float r = ch.ci[p] - windowI, c = ch.cj[p] - windowJ;
                float base = sampleGrid(elev, grid, r, c);
                ch.roomPos[p] = floorCells(elev, grid, r, c, ch.ni[p], ch.nj[p], base);
                ch.roomNeg[p] = floorCells(elev, grid, r, c, -ch.ni[p], -ch.nj[p], base);
                float floor = RiverHydrology.CELL_SIZE_M * Math.max(1f,
                        Math.min(CONFINEMENT_CELLS, ch.roomPos[p]) + Math.min(CONFINEMENT_CELLS, ch.roomNeg[p]));
                float held = Math.min(ch.wd[p], floor);
                ch.dp[p] *= (float) Math.sqrt(Math.min(2f, ch.wd[p] / held));
                ch.wd[p] = held;
                vi[k] = ch.ci[p];
                vj[k] = ch.cj[p];
            }
            if (lakeIn >= 0) {
                ch.wd[0] = ch.wd[1];
                ch.dp[0] = ch.dp[1];
                ch.lv[0] = waterLevel[lakeIn];
            }
            if (join >= 0 || outflow) {
                ch.wd[m - 1] = ch.wd[m - 2];
                ch.dp[m - 1] = ch.dp[m - 2];
                ch.lv[m - 1] = join >= 0 ? level[join] : waterLevel[end];
            }
            smooth(ch.wd, ch.dp, 0, m - 1, ch.tmpI, ch.tmpJ, ch.half, SIZE_SMOOTH);
            smooth(ch.roomPos, ch.roomNeg, 0, m - 1, ch.tmpI, ch.tmpJ, ch.half, SIZE_SMOOTH);
            for (int p = firstNode; p <= lastNode; p++) {
                width[ch.node[p]] = ch.wd[p];
                depth[ch.node[p]] = ch.dp[p];
            }

            // The water surface, graded from the mouth upstream: it keeps a freeboard below the valley floor and
            // rises no faster than the river's slope allows; where that would cut deeper than a canyon, the river
            // climbs back to its valley floor in a waterfall
            for (int p = lastNode; p >= firstNode; p--) {
                int k = ch.node[p], i = cell[k];
                float ceiling = filled[i] - FREEBOARD_BLOCKS * blockM;
                float lv;
                if (p == m - 1) {
                    lv = ceiling;
                } else {
                    float base = ch.lv[p + 1];
                    double q = acc[i] / RiverHydrology.STREAM_CELLS;
                    float slope = HEAD_SLOPE * (float) Math.pow(q, -SLOPE_Q_EXPONENT);
                    float graded = base + slope * stepM(i, ch.at[p + 1], grid);
                    float w = Math.min(ceiling, graded);
                    if (ceiling - w > CANYON_DEPTHS * depth[k]) w = ceiling;
                    lv = Math.max(base, w);
                }
                ch.lv[p] = lv;
                level[k] = lv;
                levelOut[i] = lv;
                widthOut[i] = width[k];
            }
            for (int p = firstNode; p < lastNode; p++) {
                float fall = ch.lv[p] - ch.lv[p + 1];
                int pool = Math.max(1, Math.round(PLUNGE_WIDTHS * ch.wd[p + 1] / RiverHydrology.CELL_SIZE_M));
                for (int q = p + 1; q <= Math.min(lastNode, p + pool); q++) {
                    float dug = Math.min(PLUNGE_SHARE * fall, 2f * depth[ch.node[q]]) * (1f - (q - p - 1) / (float) pool);
                    ch.dp[q] = Math.max(ch.dp[q], depth[ch.node[q]] + dug);
                }
            }

            // A river's valley opens out and it winds wider the flatter it runs
            ch.run[0] = 0f;
            for (int p = 1; p < m; p++) {
                ch.run[p] = ch.run[p - 1] + (float) Math.hypot(ch.ci[p] - ch.ci[p - 1], ch.cj[p] - ch.cj[p - 1]);
            }
            for (int p = 0; p < m; p++) {
                int a = Math.max(0, p - SLOPE_HALF), b = Math.min(m - 1, p + SLOPE_HALF);
                float span = (ch.run[b] - ch.run[a]) * RiverHydrology.CELL_SIZE_M;
                float fall = span > 0f ? (ch.lv[a] - ch.lv[b]) / span : 0f;
                ch.flat[p] = Math.max(0f, 1f - fall / STEEP_SLOPE);
                ch.valley[p] = 0.5f * ch.wd[p] * (VALLEY_WIDTHS_STEEP + VALLEY_WIDTHS_FLAT * ch.flat[p]);
            }
            smooth(ch.flat, ch.valley, 0, m - 1, ch.tmpI, ch.tmpJ, ch.half, SIZE_SMOOTH);

            // Meanders swing between pins in whole half-waves. A pin is an end of the chain or a node where it
            // crosses into another region; there the river runs down its valley's line at the same angle on both
            // sides, so the regions either side draw their own rivers and still meet exactly.
            int pins = 1;
            ch.pin[0] = 0;
            for (int b = 1; b < m; b++) {
                if (b < m - 1 && regionOf(ch.at[b], grid, windowI, windowJ, regionPx)
                        == regionOf(ch.at[b - 1], grid, windowI, windowJ, regionPx)) {
                    continue;
                }
                int a = ch.pin[pins - 1];
                meander(ch, a, b, a > 0, b < m - 1, windowI, windowJ, grid);
                ch.pin[pins++] = b;
            }

            // A tributary bends at its end to meet the stem wherever the stem's channel has swung, leaving its own
            // meanders as it goes
            float di = join >= 0 ? pi[join] - vi[join] : 0f, dj = join >= 0 ? pj[join] - vj[join] : 0f;
            float blend = Math.max(4f, 2f * (float) Math.hypot(di, dj));
            for (int p = 0; p < m; p++) {
                float t = join >= 0 ? Math.max(0f, 1f - (ch.run[m - 1] - ch.run[p]) / blend) : 0f;
                t = t * t * (3f - 2f * t);
                ch.ci[p] += ch.y[p] * (1f - t) * ch.ni[p] + di * t;
                ch.cj[p] += ch.y[p] * (1f - t) * ch.nj[p] + dj * t;
            }
            // A river's line holds no bend smaller than the river is wide, so where its swings fold on the inside
            // of a turn in its valley they open out; its pins stay where they are
            for (int s = 1; s < pins; s++) {
                smooth(ch.ci, ch.cj, ch.pin[s - 1], ch.pin[s], ch.tmpI, ch.tmpJ, ch.half, 1);
            }
            ch.bend[0] = 0f;
            ch.bend[m - 1] = 0f;
            for (int p = 0; p < m; p++) {
                int k = ch.node[p];
                if (k >= 0) {
                    pi[k] = ch.ci[p];
                    pj[k] = ch.cj[p];
                }
                if (p == 0 || p == m - 1) continue;
                float ai = ch.ci[p] - ch.ci[p - 1], aj = ch.cj[p] - ch.cj[p - 1];
                float bi = ch.ci[p + 1] - ch.ci[p], bj = ch.cj[p + 1] - ch.cj[p];
                float span = 0.5f * (float) (Math.hypot(ai, aj) + Math.hypot(bi, bj));
                float turn = (float) Math.atan2(ai * bj - aj * bi, ai * bi + aj * bj);
                float bend = span > 0f ? turn / span * ch.wd[p] / RiverHydrology.CELL_SIZE_M : 0f;
                ch.bend[p] = Math.max(-MAX_BEND, Math.min(MAX_BEND, bend));
            }
            smooth(ch.bend, ch.bend, 0, m - 1, ch.tmpI, ch.tmpJ, ch.half, 1);

            for (int p = 0; p + 1 < m; p++) {
                if (count == segRegion.length) {
                    seg = Arrays.copyOf(seg, seg.length * 2);
                    segRegion = Arrays.copyOf(segRegion, segRegion.length * 2);
                }
                long at = regionOf(ch.at[p], grid, windowI, windowJ, regionPx);
                segRegion[count] = (byte) (((at >> 32) - regionI + 1) * 3 + ((int) at - regionJ + 1));
                int o = count++ * STRIDE;
                for (int e = 0; e < 2; e++) {
                    int q = p + e, f = o + END * e;
                    seg[f] = ch.ci[q];
                    seg[f + 1] = ch.cj[q];
                    seg[f + 2] = ch.lv[q];
                    seg[f + 3] = ch.wd[q];
                    seg[f + 4] = ch.dp[q];
                    seg[f + 5] = ch.valley[q];
                    seg[f + 6] = ch.bend[q];
                }
            }
        }
        return new Traced(seg, segRegion, count);
    }

    // The points of one chain as it is traced: where each is (its valley, then its channel), the direction across
    // the valley there, the valley floor to either side in cells, its level, width, depth, flatness, valley reach,
    // bend, distance down the valley and meander offset, the window cell it belongs to, and the node it is,
    // or -1 for an end it shares with water or a larger stem; and which of the points are pins
    private static final class Chain {
        final float[] ci, cj, tmpI, tmpJ, ni, nj, roomPos, roomNeg, lv, wd, dp, flat, valley, bend, run, y;
        final int[] at, node, half, pin;
        int m;

        Chain(int cap) {
            ci = new float[cap];
            cj = new float[cap];
            tmpI = new float[cap];
            tmpJ = new float[cap];
            ni = new float[cap];
            nj = new float[cap];
            roomPos = new float[cap];
            roomNeg = new float[cap];
            lv = new float[cap];
            wd = new float[cap];
            dp = new float[cap];
            flat = new float[cap];
            valley = new float[cap];
            bend = new float[cap];
            run = new float[cap];
            y = new float[cap];
            at = new int[cap];
            node = new int[cap];
            half = new int[cap];
            pin = new int[cap];
        }

        void add(float i, float j, int cell, int k) {
            ci[m] = i;
            cj[m] = j;
            at[m] = cell;
            node[m] = k;
            roomPos[m] = 0f;
            roomNeg[m] = 0f;
            y[m] = 0f;
            m++;
        }

        // The direction across the valley at each point, along the chord through the valley either side of it over
        // the river's width
        void frame() {
            for (int p = 0; p < m; p++) {
                int half = Math.max(1, Math.round(FRAME_WIDTHS * wd[p] / RiverHydrology.CELL_SIZE_M));
                int a = Math.max(0, p - half), b = Math.min(m - 1, p + half);
                float ti = ci[b] - ci[a], tj = cj[b] - cj[a];
                float len = (float) Math.hypot(ti, tj);
                ni[p] = len > 0f ? -tj / len : 0f;
                nj[p] = len > 0f ? ti / len : 0f;
            }
        }
    }

    // Offsets the points from a to b across their valley in whole half-waves of a sine, nothing at either end and
    // eased back towards the valley's line as they near the edge of its floor. At a
    // pin the river leaves the valley's line towards the side that pin's place picks, so a stretch that ends at a pin
    // takes the parity of half-waves that arrives there heading the same way as the stretch beyond it sets out; a
    // wave's length scales its swing along with it, so the river leaves every pin at the same angle on either side.
    private static void meander(Chain ch, int a, int b, boolean pinA, boolean pinB,
                                int windowI, int windowJ, int grid) {
        // Half-waves down the stretch at the river's own wavelength, and at each wave's varied length
        float lane = Math.floorMod((windowI + ch.at[a] / grid) * 31 + (windowJ + ch.at[a] % grid) * 17, 1009);
        float natural = 0f, halfWaves = 0f;
        ch.tmpI[a] = 0f;
        ch.tmpJ[a] = 0f;
        for (int p = a + 1; p <= b; p++) {
            float step = (ch.run[p] - ch.run[p - 1]) * 2f / wavelengthCells(ch.wd[p]);
            natural += step;
            halfWaves += step / (1f + WAVE_JITTER * WAVES.GetNoise(natural, lane));
            ch.tmpI[p] = natural;
            ch.tmpJ[p] = halfWaves;
        }
        if (halfWaves <= 0f) return;
        int sideA = side(ch.at[a], grid, windowI, windowJ), sideB = side(ch.at[b], grid, windowI, windowJ);
        int count = Math.max(1, Math.round(halfWaves));
        if (pinA && pinB && (count % 2 == 0) != (sideA == sideB)) {
            count += count == 1 || halfWaves > count ? 1 : -1;
        }
        int sign = pinA ? sideA : pinB ? (count % 2 == 0 ? sideB : -sideB) : sideA;
        float fit = count / halfWaves;
        for (int p = a; p <= b; p++) {
            float wave = wavelengthCells(ch.wd[p]) * (1f + WAVE_JITTER * WAVES.GetNoise(ch.tmpI[p], lane)) / fit;
            float angle = STEEP_ANGLE + (MEANDER_ANGLE - STEEP_ANGLE) * ch.flat[p];
            float amplitude = wave * (float) Math.tan(angle) / (2f * (float) Math.PI);
            float phase = ch.tmpJ[p] * fit;
            phase += SKEW * (float) Math.sin(2.0 * Math.PI * phase) / (2f * (float) Math.PI);
            float y = sign * amplitude * (float) Math.sin(Math.PI * phase);
            float halfCells = 0.5f * ch.wd[p] / RiverHydrology.CELL_SIZE_M;
            float pos = Math.min(MAX_MEANDER_CELLS, Math.max(0f, ch.roomPos[p] - halfCells));
            float neg = Math.min(MAX_MEANDER_CELLS, Math.max(0f, ch.roomNeg[p] - halfCells));
            float room = y > 0f ? pos : neg;
            ch.y[p] = room > 0f ? room * (float) Math.tanh(y / room) : 0f;
        }
    }

    private static float wavelengthCells(float widthM) {
        return MEANDER_WAVELENGTH_WIDTHS * widthM / RiverHydrology.CELL_SIZE_M;
    }

    // The side a river leaves a pin towards, fixed by the pin's place in the world
    private static int side(int cell, int grid, int windowI, int windowJ) {
        int h = (windowI + cell / grid) * 0x632BE5AB ^ (windowJ + cell % grid) * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 1) == 0 ? 1 : -1;
    }

    private static long regionOf(int cell, int grid, int windowI, int windowJ, int regionPx) {
        long ri = Math.floorDiv(windowI + cell / grid, regionPx), rj = Math.floorDiv(windowJ + cell % grid, regionPx);
        return ri << 32 | (rj & 0xFFFFFFFFL);
    }

    // Cells of valley floor from a window point towards (di, dj) before the ground rises above the floor's height
    // over base, as far as any meander or confinement looks
    private static float floorCells(float[] elev, int grid, float r, float c, float di, float dj, float base) {
        int most = MAX_MEANDER_CELLS + (int) Math.ceil(WaterNetwork.reachM(MAX_WIDTH_M, 0f)
                / RiverHydrology.CELL_SIZE_M);
        for (int s = 1; s <= most; s++) {
            float g = sampleGrid(elev, grid, r + di * s, c + dj * s);
            if (Float.isNaN(g) || g > base + FLOOR_RISE_M) return s - 1;
        }
        return most;
    }

    private static float sampleGrid(float[] field, int grid, float r, float c) {
        int r0 = (int) Math.floor(r), c0 = (int) Math.floor(c);
        if (r0 < 0 || c0 < 0 || r0 >= grid - 1 || c0 >= grid - 1) return Float.NaN;
        float tr = r - r0, tc = c - c0;
        int a = r0 * grid + c0;
        return lerp(lerp(field[a], field[a + 1], tc), lerp(field[a + grid], field[a + grid + 1], tc), tr);
    }

    // The lake cell draining into a river's head, the one carrying the most water if several do, or -1
    private static int drainedLake(int head, int[] down, boolean[] water, float[] acc, int grid) {
        int r = head / grid, c = head % grid, best = -1;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int u = (r + dr) * grid + (c + dc);
                if (u != head && down[u] == head && water[u] && (best < 0 || acc[u] > acc[best])) best = u;
            }
        }
        return best;
    }

    // Metres from a cell to its neighbour
    private static float stepM(int a, int b, int grid) {
        boolean diagonal = a / grid != b / grid && a % grid != b % grid;
        return RiverHydrology.CELL_SIZE_M * (diagonal ? 1.41421f : 1f);
    }

    // How many nodes lie between each node and its river's mouth
    private static int[] hopsToMouth(int[] dn) {
        int n = dn.length;
        int[] rank = new int[n];
        Arrays.fill(rank, -1);
        int[] stack = new int[n];
        for (int k = 0; k < n; k++) {
            int top = 0, at = k;
            while (at >= 0 && rank[at] < 0) {
                stack[top++] = at;
                at = dn[at];
            }
            int r = at < 0 ? -1 : rank[at];
            while (top > 0) rank[stack[--top]] = ++r;
        }
        return rank;
    }

    // Averages two values along the points of a polyline between a and b, each over times its half points either
    // side in a window that narrows towards a and b, which stay as they are
    private static void smooth(float[] ci, float[] cj, int a, int b, float[] tmpI, float[] tmpJ, int[] half,
                               int times) {
        for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
            System.arraycopy(ci, a, tmpI, a, b - a + 1);
            System.arraycopy(cj, a, tmpJ, a, b - a + 1);
            for (int p = a + 1; p < b; p++) {
                int w = Math.min(times * half[p], Math.min(p - a, b - p));
                float si = 0f, sj = 0f;
                for (int q = p - w; q <= p + w; q++) {
                    si += tmpI[q];
                    sj += tmpJ[q];
                }
                ci[p] = si / (2 * w + 1);
                cj[p] = sj / (2 * w + 1);
            }
        }
    }
}
