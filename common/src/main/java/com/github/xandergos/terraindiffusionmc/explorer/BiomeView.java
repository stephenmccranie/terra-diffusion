package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.SurfaceBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BiomeView {
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();
    private static final double LIGHT_X = -0.7, LIGHT_Y = -0.7;
    private static final double SHADE_MIN = 0.55, SHADE_RANGE = 0.6;
    private static final double EDGE_SHADE = 0.72;

    record Result(String key, int h, int w, short[] ids, float[][] rgba) {}

    private static Result last;

    private BiomeView() {
    }

    static synchronized Result get(String key, int i0, int j0, int h, int w) throws Exception {
        if (last != null && last.key.equals(key)) {
            return last;
        }
        int scale = LocalTerrainProvider.getInstance().scale;
        int m = BiomeClassifier.LANDFORM_MARGIN, ww = w + 2 * m, wh = h + 2 * m;
        float[][] raw = LocalTerrainProvider.explorerData(i0 - m, j0 - m, i0 + h + m, j0 + w + m, true);
        int pw = w + 2, ph = h + 2;
        float[] padded = crop(raw[0], 0, ww, m - 1, m - 1, ph, pw);
        float[] elev = crop(raw[0], 0, ww, m, m, h, w);
        int channels = raw[1].length / (wh * ww);
        float[] climate = new float[channels * h * w];
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(crop(raw[1], ch * wh * ww, ww, m, m, h, w), 0, climate, ch * h * w, h * w);
        }
        float[] raise = crop(raw[1], 6 * wh * ww, ww, 0, 0, wh, ww);
        float[][] land = BiomeClassifier.landform(raw[0], raise, wh, ww);
        var landform = new BiomeClassifier.Landform(crop(land[0], 0, ww, m - 1, m - 1, ph, pw),
                crop(land[1], 0, ww, m, m, h, w), crop(land[2], 0, ww, m, m, h, w));
        short[] ids = BiomeClassifier.classify(elev, climate, i0 * scale, j0 * scale, scale,
                padded, landform, h, w, NATIVE_RESOLUTION, null, null);
        last = new Result(key, h, w, ids, paint(ids, padded, h, w));
        return last;
    }

    // The h x w block at (r0, c0) of a row-major grid srcW wide starting at offset
    private static float[] crop(float[] src, int offset, int srcW, int r0, int c0, int h, int w) {
        float[] out = new float[h * w];
        for (int r = 0; r < h; r++) System.arraycopy(src, offset + (r0 + r) * srcW + c0, out, r * w, w);
        return out;
    }

    private static float[][] paint(short[] ids, float[] padded, int h, int w) {
        float[][] rgba = new float[4][h * w];
        int pw = w + 2;
        double run = 2 * NATIVE_RESOLUTION;
        double norm = Math.sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + 1);
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                int k = r * w + c;
                short id = ids[k];
                SurfaceBiomes.Entry biome = SurfaceBiomes.of(id);
                int p = (r + 1) * pw + c + 1;
                double dx = (padded[p + 1] - padded[p - 1]) / run;
                double dy = (padded[p + pw] - padded[p - pw]) / run;
                double light = (LIGHT_X * dx + LIGHT_Y * dy + 1) / Math.sqrt(1 + dx * dx + dy * dy) / norm;
                double shade = biome.water() ? 1.0 : SHADE_MIN + SHADE_RANGE * Math.max(0, Math.min(1, light));
                boolean edge = (c + 1 < w && ids[k + 1] != id) || (r + 1 < h && ids[k + w] != id);
                if (edge) shade *= EDGE_SHADE;
                int rgb = biome.display();
                rgba[0][k] = (float) Math.min(1.0, ((rgb >> 16) & 255) / 255.0 * shade);
                rgba[1][k] = (float) Math.min(1.0, ((rgb >> 8) & 255) / 255.0 * shade);
                rgba[2][k] = (float) Math.min(1.0, (rgb & 255) / 255.0 * shade);
                rgba[3][k] = 1f;
            }
        }
        return rgba;
    }

    static Map<String, Object> summary(Result result) {
        TreeMap<Short, Integer> counts = new TreeMap<>();
        for (short id : result.ids) counts.merge(id, 1, Integer::sum);
        List<Map.Entry<Short, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        List<Map<String, Object>> legend = new ArrayList<>();
        double total = result.ids.length;
        for (Map.Entry<Short, Integer> e : sorted) {
            SurfaceBiomes.Entry p = SurfaceBiomes.of(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", (int) e.getKey());
            row.put("name", p.title());
            row.put("color", String.format("#%06X", p.display()));
            row.put("grass", String.format("#%06X", p.grass()));
            row.put("share", e.getValue() / total);
            legend.add(row);
        }
        ByteBuffer buf = ByteBuffer.allocate(result.ids.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short id : result.ids) buf.putShort(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("h", result.h);
        out.put("w", result.w);
        out.put("legend", legend);
        out.put("ids", Base64.getEncoder().encodeToString(buf.array()));
        return out;
    }
}
