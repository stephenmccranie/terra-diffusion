package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;

// The river centrelines a region owns, as segments between points along each river with the water level, width
// and depth at both ends. A region owns the segments that start in it, so each stretch of river exists once and
// the rivers of neighbouring regions meet where one region's last segment ends on the other's first. A bucket
// index finds the segments near a point.
final class RiverNetwork {

    // Floats per segment: native i, j, water level, width and depth at its upstream end, then at its downstream end
    private static final int STRIDE = 10;

    private static final int BUCKET = 16;

    // Farthest a region's rivers reach past its edges, in native cells
    static final int REACH_CELLS = 64;

    // A channel shapes the ground out to this many of its half-widths
    static final float INFLUENCE_WIDTHS = WaterNetwork.VALLEY_WIDTHS * (1f + WaterNetwork.WIDTH_VARIATION);

    // The water of a river lies this many blocks below the valley floor it runs along
    private static final float FREEBOARD_BLOCKS = 1f;

    // The steepest a river's water may fall, at a stream's head and as a power of its discharge relative to a
    // head's: larger rivers run gentler (Flint's slope-area law)
    private static final float HEAD_SLOPE = 0.08f;
    private static final float SLOPE_Q_EXPONENT = 0.45f;

    // Deepest a river cuts below its valley floor, in its own depths, before it climbs back up in a waterfall
    private static final float CANYON_DEPTHS = 3f;

    // Points a polyline is averaged over on either side, and how many times, to take the corners off its cells
    private static final int SMOOTH_HALF = 2;
    private static final int SMOOTH_PASSES = 3;

    private final float[] seg;
    private final int originI, originJ, side;
    // Segments of bucket b are members[first[b]] up to members[first[b + 1]]
    private final int[] first, members;

    private RiverNetwork(float[] seg, int count, int regionI, int regionJ, int regionPx) {
        this.seg = Arrays.copyOf(seg, count * STRIDE);
        originI = regionI * regionPx - REACH_CELLS;
        originJ = regionJ * regionPx - REACH_CELLS;
        side = (regionPx + 2 * REACH_CELLS + BUCKET - 1) / BUCKET;
        first = new int[side * side + 1];
        for (int pass = 0; pass < 2; pass++) {
            int[] fill = pass == 0 ? null : Arrays.copyOf(first, side * side);
            int[] out = pass == 0 ? null : new int[first[side * side]];
            for (int s = 0; s < count; s++) {
                int o = s * STRIDE;
                float r = INFLUENCE_WIDTHS * 0.5f * Math.max(seg[o + 3], seg[o + 8]) / RiverHydrology.CELL_SIZE_M;
                int b0 = bucket(Math.min(seg[o], seg[o + 5]) - r - originI);
                int b1 = bucket(Math.max(seg[o], seg[o + 5]) + r - originI);
                int c0 = bucket(Math.min(seg[o + 1], seg[o + 6]) - r - originJ);
                int c1 = bucket(Math.max(seg[o + 1], seg[o + 6]) + r - originJ);
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

    // Writes {distance in native cells, water level, width, depth} at the segment nearest (i, j) relative to its
    // half-width into out, if that is nearer than best, and returns the nearest relative distance so far
    float nearest(float i, float j, float[] out, float best) {
        int bi = (int) Math.floor((i - originI) / BUCKET), bj = (int) Math.floor((j - originJ) / BUCKET);
        if (bi < 0 || bj < 0 || bi >= side || bj >= side) return best;
        int b = bi * side + bj;
        for (int m = first[b]; m < first[b + 1]; m++) {
            int o = members[m] * STRIDE;
            float ai = seg[o], aj = seg[o + 1];
            float di = seg[o + 5] - ai, dj = seg[o + 6] - aj;
            float len2 = di * di + dj * dj;
            float t = len2 > 0f ? Math.max(0f, Math.min(1f, ((i - ai) * di + (j - aj) * dj) / len2)) : 0f;
            float oi = ai + di * t - i, oj = aj + dj * t - j;
            float d = (float) Math.sqrt(oi * oi + oj * oj);
            float width = lerp(seg[o + 3], seg[o + 8], t);
            float relative = d / (0.5f * width / RiverHydrology.CELL_SIZE_M);
            if (relative < best) {
                best = relative;
                out[0] = d;
                out[1] = lerp(seg[o + 2], seg[o + 7], t);
                out[2] = width;
                out[3] = lerp(seg[o + 4], seg[o + 9], t);
            }
        }
        return best;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // Traces the rivers of a drained window into the segments its region owns, and writes each river cell's water
    // level into levelOut. Rivers are the channel cells: each sends its water to its down cell over ground filled
    // to filled, carries acc of catchment, has its width and depth, and has its centre moved by its meander offset.
    // A river begins in the lake it drains, if it drains one, and ends in the water its last cell drains to.
    static RiverNetwork trace(int regionI, int regionJ, int regionPx, int windowI, int windowJ, int grid,
                              boolean[] channel, int[] down, float[] acc, float[] filled, float[] width,
                              float[] depth, float[] offR, float[] offC, boolean[] water, float[] waterLevel,
                              float blockM, float[] levelOut) {
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

        // The water surface, graded from each mouth upstream: it keeps a freeboard below the valley floor and rises
        // no faster than the river's slope allows; where that would cut deeper than a canyon, the river climbs back
        // to its valley floor in a waterfall
        float[] level = new float[n];
        for (int k : byRank(rank)) {
            int i = cell[k], d = down[i];
            float ceiling = filled[i] - FREEBOARD_BLOCKS * blockM;
            float base, run;
            if (dn[k] >= 0) {
                base = level[dn[k]];
                run = stepM(i, d, grid);
            } else if (d >= 0 && water[d]) {
                base = waterLevel[d];
                run = stepM(i, d, grid);
            } else {
                base = ceiling;
                run = 0f;
            }
            float q = acc[i] / RiverHydrology.STREAM_CELLS;
            float graded = base + HEAD_SLOPE * (float) Math.pow(q, -SLOPE_Q_EXPONENT) * run;
            float w = Math.min(ceiling, graded);
            if (ceiling - w > CANYON_DEPTHS * depth[i]) w = ceiling;
            level[k] = Math.max(base, w);
            levelOut[i] = level[k];
        }

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

        // Each chain's points: where it is, its water level, width and depth, the window cell it belongs to, and
        // the node it is, or -1 for an end it shares with water or a larger stem, which stays where that is
        float[] pi = new float[n], pj = new float[n];
        int cap = n + 2;
        float[] ci = new float[cap], cj = new float[cap], tmpI = new float[cap], tmpJ = new float[cap];
        float[] lv = new float[cap], wd = new float[cap], dp = new float[cap];
        int[] at = new int[cap], node = new int[cap];
        float[] seg = new float[STRIDE * 1024];
        int count = 0;
        for (long packed : order) {
            int h = (int) packed;
            int s0 = chainStart[h], s1 = chainStart[h + 1];
            int head = chained[s0], last = chained[s1 - 1];
            int m = 0;
            int lakeIn = drainedLake(cell[head], down, water, acc, grid);
            if (lakeIn >= 0) {
                m = point(ci, cj, lv, wd, dp, at, node, m, windowI + lakeIn / grid, windowJ + lakeIn % grid,
                        waterLevel[lakeIn], width[cell[head]], depth[cell[head]], lakeIn, -1);
            }
            for (int s = s0; s < s1; s++) {
                int k = chained[s], i = cell[k];
                m = point(ci, cj, lv, wd, dp, at, node, m, windowI + i / grid, windowJ + i % grid,
                        level[k], width[i], depth[i], i, k);
            }
            int join = dn[last], end = down[cell[last]];
            if (join >= 0) {
                int i = cell[join];
                m = point(ci, cj, lv, wd, dp, at, node, m, pi[join], pj[join], level[join], width[i], depth[i], i, -1);
            } else if (end >= 0 && water[end]) {
                int i = cell[last];
                m = point(ci, cj, lv, wd, dp, at, node, m, windowI + end / grid, windowJ + end % grid,
                        waterLevel[end], width[i], depth[i], end, -1);
            }
            smooth(ci, cj, m, tmpI, tmpJ);
            for (int p = 0; p < m; p++) {
                int k = node[p];
                if (k < 0) continue;
                ci[p] += offR[cell[k]];
                cj[p] += offC[cell[k]];
                pi[k] = ci[p];
                pj[k] = cj[p];
            }

            for (int p = 0; p + 1 < m; p++) {
                if (Math.floorDiv(windowI + at[p] / grid, regionPx) != regionI
                        || Math.floorDiv(windowJ + at[p] % grid, regionPx) != regionJ) {
                    continue;
                }
                if (count * STRIDE + STRIDE > seg.length) seg = Arrays.copyOf(seg, seg.length * 2);
                int o = count++ * STRIDE;
                for (int e = 0; e < 2; e++) {
                    int q = p + e, f = o + 5 * e;
                    seg[f] = ci[q];
                    seg[f + 1] = cj[q];
                    seg[f + 2] = lv[q];
                    seg[f + 3] = wd[q];
                    seg[f + 4] = dp[q];
                }
            }
        }
        return new RiverNetwork(seg, count, regionI, regionJ, regionPx);
    }

    private static int point(float[] ci, float[] cj, float[] lv, float[] wd, float[] dp, int[] at, int[] node, int m,
                             float i, float j, float level, float width, float depth, int cell, int k) {
        ci[m] = i;
        cj[m] = j;
        lv[m] = level;
        wd[m] = width;
        dp[m] = depth;
        at[m] = cell;
        node[m] = k;
        return m + 1;
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

    // Nodes ordered so each comes after the node it drains to
    private static int[] byRank(int[] rank) {
        int n = rank.length, top = 0;
        for (int r : rank) top = Math.max(top, r + 1);
        int[] start = new int[top + 1];
        for (int r : rank) start[r + 1]++;
        for (int r = 0; r < top; r++) start[r + 1] += start[r];
        int[] out = new int[n];
        for (int k = 0; k < n; k++) out[start[rank[k]]++] = k;
        return out;
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

    // Averages the interior points of a polyline of m points over a window that narrows towards its ends, which
    // stay where they are
    private static void smooth(float[] ci, float[] cj, int m, float[] tmpI, float[] tmpJ) {
        for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
            System.arraycopy(ci, 0, tmpI, 0, m);
            System.arraycopy(cj, 0, tmpJ, 0, m);
            for (int p = 1; p < m - 1; p++) {
                int w = Math.min(SMOOTH_HALF, Math.min(p, m - 1 - p));
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
