package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.Arrays;

// An N-dimensional float array with row-major (C-order) layout
public class FloatTensor {
    public final int[] shape;
    public final float[] data;
    final int[] strides;

    public FloatTensor(int[] shape) {
        this.shape = shape.clone();
        int total = 1;
        for (int d : shape) total *= d;
        this.data = new float[total];
        this.strides = computeStrides(shape);
    }

    public FloatTensor(int[] shape, float[] data) {
        this.shape = shape.clone();
        this.data = data;
        this.strides = computeStrides(shape);
    }

    static int[] computeStrides(int[] shape) {
        int n = shape.length;
        int[] s = new int[n];
        int stride = 1;
        for (int i = n - 1; i >= 0; i--) {
            s[i] = stride;
            stride *= shape[i];
        }
        return s;
    }

    public long byteSize() {
        return (long) data.length * Float.BYTES;
    }

    // Add values from src into this tensor at a sub-region, one contiguous run along the last axis at a time
    public void addFrom(FloatTensor src, int[][] dstRegion, int[][] srcRegion) {
        int n = shape.length;
        int[] count = new int[n];
        int rows = 1;
        for (int d = 0; d < n; d++) {
            count[d] = dstRegion[d][1] - dstRegion[d][0];
            if (d < n - 1) rows *= count[d];
        }
        int run = count[n - 1];
        if (rows == 0 || run == 0) return;

        // Position of the current run in the region's leading axes, counted like an odometer
        int[] at = new int[n - 1];
        for (int row = 0; row < rows; row++) {
            int dst = dstRegion[n - 1][0], from = srcRegion[n - 1][0];
            for (int d = 0; d < n - 1; d++) {
                dst += (dstRegion[d][0] + at[d]) * strides[d];
                from += (srcRegion[d][0] + at[d]) * src.strides[d];
            }
            for (int k = 0; k < run; k++) data[dst + k] += src.data[from + k];
            for (int d = n - 2; d >= 0 && ++at[d] == count[d]; d--) at[d] = 0;
        }
    }

    // An overlap-added [C + 1, H, W] tensor holds its windows' weighted sums and, in the last channel, the weight
    // they summed at each pixel. This is its C blended channels.
    public FloatTensor blended() {
        int channels = shape[0] - 1, n = shape[1] * shape[2];
        FloatTensor out = new FloatTensor(new int[]{channels, shape[1], shape[2]});
        for (int px = 0; px < n; px++) {
            float weight = data[channels * n + px];
            if (weight > 1e-6f) {
                for (int ch = 0; ch < channels; ch++) out.data[ch * n + px] = data[ch * n + px] / weight;
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "FloatTensor(shape=" + Arrays.toString(shape) + ")";
    }
}
