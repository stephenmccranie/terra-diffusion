package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReliefTest {
    private static final Relief DEFAULT = new Relief(300f, 1200f, 2.5f, 0.8f);

    @Test
    void unitGainWithoutPeaksKeepsTheModelsHeights() {
        Relief none = new Relief(300f, 1200f, 1f, 0f);
        for (float base = -2000f; base <= 6000f; base += 250f) {
            for (float m = base - 1500f; m <= base + 1500f; m += 125f) {
                assertEquals(m, none.apply(m, base), 1e-3f);
            }
        }
    }

    @Test
    void lowlandsKeepTheirHeights() {
        for (float base = -500f; base <= 300f; base += 50f) {
            for (float m = base - 200f; m <= base + 200f; m += 20f) {
                assertEquals(m, DEFAULT.apply(m, base), 1e-3f);
            }
        }
    }

    @Test
    void groundBelowItsBaseKeepsItsDepthAndGroundAboveRisesByPeaks() {
        float base = 2500f;
        float atBase = DEFAULT.apply(base, base);
        assertEquals(-300f, DEFAULT.apply(base - 300f, base) - atBase, 1e-2f);
        assertEquals(300f * 1.8f, DEFAULT.apply(base + 300f, base) - atBase, 1e-2f);
    }

    @Test
    void heightsRiseWithTheModelsAndNeverPassTheTop() {
        float previous = -Float.MAX_VALUE;
        for (float m = -1000f; m <= 30000f; m += 10f) {
            float world = DEFAULT.apply(m, m);
            assertTrue(world >= previous, "rising at " + m);
            assertTrue(world <= Relief.TOP_M, "not above the top at " + m);
            previous = world;
        }
    }

    @Test
    void rejectsSettingsThatCannotFormACurve() {
        assertThrows(IllegalArgumentException.class, () -> new Relief(300f, 1200f, 0.9f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Relief(300f, 1200f, 2f, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> new Relief(1200f, 300f, 2f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Relief(Float.NaN, 1200f, 2f, 0f));
    }
}
