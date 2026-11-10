package com.github.xandergos.terraindiffusionmc.pipeline;

public final class KarstNetwork {

    public static final byte ZONE_PHREATIC = 0;
    public static final byte ZONE_VADOSE = 1;
    public static final byte ZONE_KEYHOLE = 2;
    public static final byte ZONE_DOLINE = 3;

    public static final float FAR = 4096f;

    private static final float FLOOR_CUT = 0.35f;

    public static final KarstNetwork EMPTY = new KarstNetwork();

    final int count;
    final float[] ax, ay, az;
    final float[] bx, by, bz;
    final float[] rh;
    final float[] rv;
    final byte[] zone;

    // Segments are listed by the cubes of cellSize blocks they come within a block of
    final int cellSize;
    final int originX, originY, originZ;
    final int nx, ny, nz;
    final int[] cellStart;
    final int[] cellItems;

    final int wtOriginX, wtOriginZ, wtStep, wtNX, wtNZ;
    final short[] wtY;

    // Endpoint bounds per segment, for skipping segments that cannot be nearest
    final float[] loX, hiX, loY, hiY, loZ, hiZ;

    private KarstNetwork() {
        this.count = 0;
        this.ax = this.ay = this.az = this.bx = this.by = this.bz = new float[0];
        this.loX = this.hiX = this.loY = this.hiY = this.loZ = this.hiZ = new float[0];
        this.rh = this.rv = new float[0];
        this.zone = new byte[0];
        this.cellSize = 1;
        this.originX = this.originY = this.originZ = 0;
        this.nx = this.ny = this.nz = 0;
        this.cellStart = new int[1];
        this.cellItems = new int[0];
        this.wtOriginX = this.wtOriginZ = 0;
        this.wtStep = 1;
        this.wtNX = this.wtNZ = 0;
        this.wtY = new short[0];
    }

    KarstNetwork(int count, float[] ax, float[] ay, float[] az,
                 float[] bx, float[] by, float[] bz,
                 float[] rh, float[] rv, byte[] zone,
                 int cellSize, int originX, int originZ, int nx, int nz,
                 int wtOriginX, int wtOriginZ, int wtStep, int wtNX, int wtNZ, short[] wtY) {
        this.count = count;
        this.ax = ax; this.ay = ay; this.az = az;
        this.bx = bx; this.by = by; this.bz = bz;
        this.rh = rh; this.rv = rv;
        this.zone = zone;
        this.cellSize = cellSize;
        this.originX = originX; this.originZ = originZ;
        this.nx = nx; this.nz = nz;
        this.wtOriginX = wtOriginX; this.wtOriginZ = wtOriginZ;
        this.wtStep = wtStep; this.wtNX = wtNX; this.wtNZ = wtNZ;
        this.wtY = wtY;

        this.loX = new float[count]; this.hiX = new float[count];
        this.loY = new float[count]; this.hiY = new float[count];
        this.loZ = new float[count]; this.hiZ = new float[count];
        for (int s = 0; s < count; s++) {
            loX[s] = Math.min(ax[s], bx[s]); hiX[s] = Math.max(ax[s], bx[s]);
            loY[s] = Math.min(ay[s], by[s]); hiY[s] = Math.max(ay[s], by[s]);
            loZ[s] = Math.min(az[s], bz[s]); hiZ[s] = Math.max(az[s], bz[s]);
        }

        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int s = 0; s < count; s++) {
            low = Math.min(low, loY[s] - rv[s] - 1f);
            high = Math.max(high, hiY[s] + rv[s] + 1f);
        }
        this.originY = (int) Math.floor(low);
        this.ny = cellY(high) + 1;

        int cells = nx * ny * nz;
        int[] counts = new int[cells + 1];
        for (int s = 0; s < count; s++) {
            int c0 = Math.max(0, cellX(loX[s] - rh[s] - 1f)), c1 = Math.min(nx - 1, cellX(hiX[s] + rh[s] + 1f));
            int d0 = Math.max(0, cellZ(loZ[s] - rh[s] - 1f)), d1 = Math.min(nz - 1, cellZ(hiZ[s] + rh[s] + 1f));
            int e0 = cellY(loY[s] - rv[s] - 1f), e1 = cellY(hiY[s] + rv[s] + 1f);
            for (int e = e0; e <= e1; e++) {
                for (int d = d0; d <= d1; d++) {
                    for (int c = c0; c <= c1; c++) counts[(e * nz + d) * nx + c + 1]++;
                }
            }
        }
        for (int i = 0; i < cells; i++) counts[i + 1] += counts[i];
        this.cellStart = counts;
        this.cellItems = new int[counts[cells]];
        int[] cursor = new int[cells];
        for (int s = 0; s < count; s++) {
            int c0 = Math.max(0, cellX(loX[s] - rh[s] - 1f)), c1 = Math.min(nx - 1, cellX(hiX[s] + rh[s] + 1f));
            int d0 = Math.max(0, cellZ(loZ[s] - rh[s] - 1f)), d1 = Math.min(nz - 1, cellZ(hiZ[s] + rh[s] + 1f));
            int e0 = cellY(loY[s] - rv[s] - 1f), e1 = cellY(hiY[s] + rv[s] + 1f);
            for (int e = e0; e <= e1; e++) {
                for (int d = d0; d <= d1; d++) {
                    for (int c = c0; c <= c1; c++) {
                        int cell = (e * nz + d) * nx + c;
                        cellItems[cellStart[cell] + cursor[cell]++] = s;
                    }
                }
            }
        }
    }

    private int cellX(float x) {
        return Math.floorDiv((int) Math.floor(x) - originX, cellSize);
    }

    private int cellY(float y) {
        return Math.floorDiv((int) Math.floor(y) - originY, cellSize);
    }

    private int cellZ(float z) {
        return Math.floorDiv((int) Math.floor(z) - originZ, cellSize);
    }

    public boolean isEmpty() {
        return count == 0;
    }

    // Signed distance to the nearest conduit wall in blocks, negative inside a conduit: exact where it is below
    // limit and a conduit lies within a block, otherwise some greater value
    public float density(float x, float y, float z, float limit) {
        return density(x, y, z, false, limit);
    }

    // The same distance to doline shafts alone
    public float dolineDensity(float x, float y, float z, float limit) {
        return density(x, y, z, true, limit);
    }

    // Allowance for float rounding when ruling a segment out, growing with coordinate size
    private static float slack(float x, float y, float z) {
        return 0.02f + 4.0e-6f * (Math.abs(x) + Math.abs(y) + Math.abs(z));
    }

    // Lower bound on a segment's signed distance, from its endpoint bounds
    private float lowerBound(int s, float x, float y, float z, float rhs, float rvs) {
        float out = Math.max(Math.max(loX[s] - x, x - hiX[s]), Math.max(loZ[s] - z, z - hiZ[s])) / rhs;
        float up = Math.max(loY[s] - y, y - hiY[s]) / rvs;
        return (Math.max(out, up) - 1f) * (rhs < rvs ? rhs : rvs);
    }

    private float density(float x, float y, float z, boolean dolinesOnly, float limit) {
        if (count == 0) return FAR;
        int c = cellX(x), d = cellZ(z), e = cellY(y);
        if (c < 0 || d < 0 || e < 0 || c >= nx || d >= nz || e >= ny) return FAR;
        int cell = (e * nz + d) * nx + c;
        int from = cellStart[cell], to = cellStart[cell + 1];
        if (from == to) return FAR;

        float slack = slack(x, y, z);
        float best = FAR;
        for (int k = from; k < to; k++) {
            int s = cellItems[k];
            if (dolinesOnly && zone[s] != ZONE_DOLINE) continue;
            if (rh[s] > 0.01f && rv[s] > 0.01f
                    && lowerBound(s, x, y, z, rh[s], rv[s]) > Math.min(best, limit) + slack) continue;
            float ex = bx[s] - ax[s], ey = by[s] - ay[s], ez = bz[s] - az[s];
            float len2 = ex * ex + ey * ey + ez * ez;
            float t = 0f;
            if (len2 > 1.0e-6f) {
                t = ((x - ax[s]) * ex + (y - ay[s]) * ey + (z - az[s]) * ez) / len2;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
            }
            float qx = ax[s] + ex * t, qy = ay[s] + ey * t, qz = az[s] + ez * t;
            float dx = x - qx, dy = y - qy, dz = z - qz;
            float rhs = rh[s], rvs = rv[s];
            if (rhs <= 0.01f || rvs <= 0.01f) continue;
            if (y < qy - rvs * FLOOR_CUT) continue;
            float hh = (float) Math.sqrt(dx * dx + dz * dz) / rhs;
            float vv = (dy < 0f ? -dy : dy) / rvs;
            float n = (float) Math.sqrt(hh * hh + vv * vv);
            float scale = rhs < rvs ? rhs : rvs;
            float sd = (n - 1f) * scale;
            if (sd < best) best = sd;
        }
        return best;
    }

    // Water table height in blocks, bilinearly sampled
    public float waterTableY(float x, float z) {
        if (wtNX == 0) return Float.NEGATIVE_INFINITY;
        float fx = (x - wtOriginX) / (float) wtStep;
        float fz = (z - wtOriginZ) / (float) wtStep;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = fx - x0, tz = fz - z0;
        int x1 = x0 + 1, z1 = z0 + 1;
        x0 = clamp(x0, 0, wtNX - 1); x1 = clamp(x1, 0, wtNX - 1);
        z0 = clamp(z0, 0, wtNZ - 1); z1 = clamp(z1, 0, wtNZ - 1);
        float a = wtY[z0 * wtNX + x0], b = wtY[z0 * wtNX + x1];
        float c = wtY[z1 * wtNX + x0], e = wtY[z1 * wtNX + x1];
        return (a + (b - a) * tx) + ((c + (e - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
