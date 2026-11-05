package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

// How model heights become world heights. The model learned real-world heights, whose peaks rarely pass
// 4,000 m, while a world's height budget assumes a 10,000 m peak. The land's large shape (its regional base,
// about a kilometre across) is left alone below `start` and rises ever faster above it, until from `full` on
// it rises `gain` metres for every metre of the model's. Ground keeps its height above or below that base,
// so local slopes stay as the model made them, and ground standing above its base (ridges, summits, upper
// walls) rises a further `peaks` times as far once the base is fully lifted. gain 1 with peaks 0 leaves the
// model's heights as they are.
public record Relief(float start, float full, float gain, float peaks) {

    // Heights ease towards TOP_M and never pass it; nothing below KNEE_M is touched by that easing
    static final float TOP_M = 9800f;
    private static final float KNEE_M = 8800f;

    public Relief {
        if (!(start >= 0f && full > start && full < KNEE_M)) {
            throw new IllegalArgumentException("relief.start and relief.full must satisfy 0 <= start < full < "
                    + KNEE_M + "; got start=" + start + ", full=" + full);
        }
        if (!(gain >= 1f)) {
            throw new IllegalArgumentException("relief.gain must be at least 1; got " + gain);
        }
        if (!(peaks >= 0f)) {
            throw new IllegalArgumentException("relief.peaks must be at least 0; got " + peaks);
        }
    }

    public static Relief fromConfig() {
        return new Relief(TerrainDiffusionConfig.reliefStartM(), TerrainDiffusionConfig.reliefFullM(),
                TerrainDiffusionConfig.reliefGain(), TerrainDiffusionConfig.reliefPeaks());
    }

    // World height of ground at model height m whose regional base is at model height base
    public float apply(float m, float base) {
        float t = Math.max(0f, Math.min(1f, (base - start) / (full - start)));
        float world = m + lift(base) + peaks * t * t * (3f - 2f * t) * Math.max(0f, m - base);
        if (world <= KNEE_M) return world;
        float room = TOP_M - KNEE_M;
        return KNEE_M + room * (float) Math.tanh((world - KNEE_M) / room);
    }

    // How far a base at model height m rises; zero below start, with a slope easing from 0 to gain - 1
    float lift(float m) {
        float t = Math.max(0f, Math.min(m, full) - start);
        return (gain - 1f) * (t * t / (2f * (full - start)) + Math.max(0f, m - full));
    }
}
