package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

// The world's air for other mods: the climate's air temperature at sea level, from which air cools with height,
// and how many metres one block of height stands for. Each call waits while the inference thread reads the
// coarse climate, so callers stay off the server thread.
public final class TerrainClimate {

    private TerrainClimate() {
    }

    public static float metresPerBlock() {
        return WorldPipelineModelConfig.nativeResolution() / LocalTerrainProvider.getInstance().scale;
    }

    public static int seaLevelY() {
        return HeightConverter.SEA_LEVEL;
    }

    // Sea-level air in degrees Celsius for nx by nz columns from (x0, z0), step blocks apart, x varying fastest
    public static float[] seaLevelAirC(int x0, int z0, int step, int nx, int nz) {
        LocalTerrainProvider terrain = LocalTerrainProvider.getInstance();
        int scale = terrain.scale;
        int i1 = Math.floorDiv(z0, scale), j1 = Math.floorDiv(x0, scale);
        int i2 = Math.floorDiv(z0 + step * (nz - 1), scale) + 1, j2 = Math.floorDiv(x0 + step * (nx - 1), scale) + 1;
        float[] box = terrain.seaLevelTemperature(i1, j1, i2, j2);
        int width = j2 - j1;
        float[] out = new float[nx * nz];
        for (int k = 0; k < nz; k++) {
            int row = Math.floorDiv(z0 + k * step, scale) - i1;
            for (int m = 0; m < nx; m++) {
                out[k * nx + m] = box[row * width + Math.floorDiv(x0 + m * step, scale) - j1];
            }
        }
        return out;
    }
}
