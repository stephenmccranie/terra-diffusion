package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiverLinesTest {
    private static final int STRIDE = 14, END = 7;

    // A stem of ten segments running east along i = 100 from j = 100, and a tributary of four running south into it
    // at j = 105, wider downstream than the tributary is
    private static RiverNetwork network() {
        float[] seg = new float[14 * STRIDE];
        int n = 0;
        for (int s = 0; s < 10; s++) n = segment(seg, n, 100, 100 + s, 100, 101 + s, 60f);
        for (int s = 0; s < 4; s++) n = segment(seg, n, 96 + s, 105, 97 + s, 105, 20f);
        byte[] region = new byte[n];
        java.util.Arrays.fill(region, RiverNetwork.Traced.OWN);
        return new RiverNetwork.Traced(seg, region, n).own(0, 0, 1024);
    }

    private static int segment(float[] seg, int n, float i0, float j0, float i1, float j1, float width) {
        int o = n * STRIDE;
        seg[o] = i0;
        seg[o + 1] = j0;
        seg[o + 3] = width;
        seg[o + END] = i1;
        seg[o + END + 1] = j1;
        seg[o + END + 3] = width;
        return n + 1;
    }

    @Test
    void theStemRunsThroughTheJoinAndTheTributaryEndsOnIt() {
        List<float[]> lines = new ArrayList<>();
        network().lines(0, 0, 1024, 1024, lines);
        assertEquals(2, lines.size());
        float[] stem = lines.get(0), tributary = lines.get(1);
        // One point in three and the mouth, with the river's length above each
        assertArrayEquals(new float[]{100, 100, 0, 60, 100, 103, 3, 60, 100, 106, 6, 60, 100, 109, 9, 60, 100, 110, 10, 60},
                stem, 1e-5f);
        assertArrayEquals(new float[]{96, 105, 0, 20, 99, 105, 3, 20, 100, 105, 4, 20}, tributary, 1e-5f);
    }

    @Test
    void aLineIsCutToTheChordsNearTheBox() {
        List<float[]> lines = new ArrayList<>();
        network().lines(99, 102, 101, 104, lines);
        assertEquals(1, lines.size());
        assertArrayEquals(new float[]{100, 100, 0, 60, 100, 103, 3, 60, 100, 106, 6, 60}, lines.get(0), 1e-5f);
    }
}
