package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalTerrainProvider {

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f, 2, 2f, 0.6f);

    private static final FastNoiseLite ELEV_DITHER       = makeFnl(77777, 1f/1.7f, 1, 2f, 0.5f);

    // Slope, in blocks of rise per block, at which a column risks showing height bands without dither
    private static final float DITHER_BAND_GRAD = 0.125f;

    // Least detail kept on banding-prone slopes, in blocks
    private static final float DETAIL_FLOOR_BLOCKS = 0.20f;

    // Detail noise amplitudes at native resolution, in metres
    private static final float AMP_COARSE = 100f;
    private static final float AMP_FINE = 70f;

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public static final short NO_WATER = Short.MIN_VALUE;

        public final short[][] heightmap;
        public final short[][] biomeIds;

        public final byte[][] snowLayers;
        public final short[][] waterLevel;
        public final byte[][] climateT;
        public final byte[][] climateH;
        public final byte[][] climateE;
        public KarstNetwork karst = KarstNetwork.EMPTY;
        // Lowest surface Y of each column and the columns CAVE_SIDE blocks around it, row-major
        public final short[] sideSurface;
        // Y range, inclusive and row-major, where a column has river water within RIVER_WALL blocks
        // of it; empty (low above high) away from rivers
        public final short[] riverWallLow;
        public final short[] riverWallHigh;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, byte[][] snowLayers,
                             short[][] waterLevel, byte[][] climateT, byte[][] climateH,
                             byte[][] climateE, short[] sideSurface, short[] riverWallLow,
                             short[] riverWallHigh, int width, int height) {
            this.heightmap  = heightmap;
            this.biomeIds   = biomeIds;
            this.snowLayers = snowLayers;
            this.waterLevel = waterLevel;
            this.climateT   = climateT;
            this.climateH   = climateH;
            this.climateE   = climateE;
            this.sideSurface = sideSurface;
            this.riverWallLow = riverWallLow;
            this.riverWallHigh = riverWallHigh;
            this.width      = width;
            this.height     = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    // A tile is computed once, by the first thread to need it; later callers wait for that computation
    private static record CacheEntry(CompletableFuture<HeightmapData> data, AtomicLong lastAccessed) {}

    // Blocks of area to keep cached, so tile size does not decide the size of the working set
    private static final long CACHE_AREA_BLOCKS =
            Long.parseLong(System.getProperty("terradiff.tileCacheArea", "16777216"));
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static volatile int maxCacheSize;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();

    private static int maxCacheSize() {
        int cached = maxCacheSize;
        if (cached != 0) return cached;
        int tile = TerrainDiffusionConfig.tileSize();
        long tiles = CACHE_AREA_BLOCKS / ((long) tile * tile);
        cached = (int) Math.max(64L, Math.min(4096L, tiles));
        maxCacheSize = cached;
        return cached;
    }

    private static volatile LocalTerrainProvider INSTANCE;
    private static volatile long instanceSeed;

    private static LocalTerrainProvider EXPLORER;
    private static volatile long explorerSeed;
    private static volatile String explorerView = "";
    private static volatile int explorerGeneration = -1;
    private static volatile int worldGeneration;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models, boolean readOnlyDisk) {
        this.pipeline = new WorldPipeline(seed, models, readOnlyDisk);
    }

    private static PipelineModels loadedModels() {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        return models;
    }

    public static synchronized void init(long seed) {
        DeepCaverns.setSeed(seed);
        CaveBiomes.setSeed(seed);
        PipelineModels models = loadedModels();
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models, false);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            CACHE.clear();
        }
        worldGeneration++;
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = new LocalTerrainProvider(0L, loadedModels(), false);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static long getSeed() {
        return instanceSeed;
    }

    // Changes on every world load, so position-keyed caches know to refill
    public static int generation() {
        return worldGeneration;
    }

    // The explorer's own pipeline, recreated on the world's seed after each world load; inference thread only
    private static LocalTerrainProvider explorer() {
        int generation = worldGeneration;
        if (EXPLORER == null || explorerGeneration != generation) {
            if (EXPLORER != null) {
                EXPLORER.pipeline.close();
            }
            long seed = instanceSeed;
            EXPLORER = new LocalTerrainProvider(seed, loadedModels(), true);
            EXPLORER.pipeline.hydrology.buildSeamNeighbours(false);
            explorerSeed = seed;
            explorerGeneration = generation;
        }
        return EXPLORER;
    }

    public static long explorerSeed() {
        return explorerGeneration == worldGeneration ? explorerSeed : instanceSeed;
    }

    public static void setExplorerSeed(long newSeed) {
        WorldPipeline.INFERENCE.call(() -> {
            explorer().pipeline.setSeed(newSeed);
            explorerSeed = newSeed;
            return null;
        });
    }

    public static long randomExplorerSeed() {
        long newSeed = new Random().nextLong();
        setExplorerSeed(newSeed);
        return newSeed;
    }

    // Frees the explorer's pipeline; the next explorer request rebuilds it on the world's seed
    public static void releaseExplorer() {
        WorldPipeline.INFERENCE.submit(() -> {
            if (EXPLORER != null) {
                EXPLORER.pipeline.close();
                EXPLORER = null;
            }
            explorerGeneration = -1;
            return null;
        }, PriorityExecutor.BLOCKING);
    }

    public static float[][] explorerData(int i1, int j1, int i2, int j2, boolean withClimate) {
        return WorldPipeline.INFERENCE.call(() -> explorer().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    public static FloatTensor explorerCoarse(int ci0, int cj0, int ci1, int cj1) {
        return WorldPipeline.INFERENCE.call(() -> explorer().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    // The explorer view most recently asked for; water for any other view is abandoned
    public static void markExplorerView(String view) {
        explorerView = view;
    }

    // Returns {bed elevation, water surface} for an explorer view, or null if the view changed first
    public static float[][] explorerWater(int i1, int j1, int i2, int j2, String view) throws Exception {
        BooleanSupplier stale = () -> !view.equals(explorerView);
        return WorldPipeline.INFERENCE.submit(() -> {
            if (stale.getAsBoolean()) return null;
            try {
                return InfiniteTensor.cancellable(stale, () -> {
                    LocalTerrainProvider provider = explorer();
                    int H = i2 - i1, W = j2 - j1;
                    int scale = WorldScaleManager.getCurrentScale();
                    float blockM = NATIVE_RESOLUTION / scale;
                    RiverHydrology.prepare(provider.pipeline, i1, j1, i2, j2, blockM);
                    float[] elev = provider.pipeline.get(i1, j1, i2, j2, false)[0];
                    return provider.carveWater(elev, elev, i1, j1, H, W, blockM, scale, scale);
                });
            } catch (CancellationException e) {
                return null;
            }
        }, PriorityExecutor.BACKGROUND).get();
    }

    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) {
        return getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1);
    }

    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry entry = CACHE.get(key);
        if (entry == null) {
            CacheEntry mine = new CacheEntry(new CompletableFuture<>(), new AtomicLong());
            entry = CACHE.putIfAbsent(key, mine);
            if (entry == null) {
                entry = mine;
                generate(key, mine);
                evictLruTo(maxCacheSize());
            }
        }
        entry.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        try {
            return entry.data.join();
        } catch (CompletionException e) {
            throw new RuntimeException("Terrain tile failed: " + key, e.getCause());
        }
    }

    // Computes a tile on this thread; only its tensor reads run on the inference thread
    private void generate(CacheKey key, CacheEntry entry) {
        int i1 = key.i1(), j1 = key.j1(), i2 = key.i2(), j2 = key.j2();
        try {
            int scale = WorldScaleManager.getCurrentScale();
            float blockM = NATIVE_RESOLUTION / scale;
            HeightmapData data = buildTile(i1, j1, i2, j2, scale);
            data.karst = RiverHydrology.karstAt(pipeline, i1 / (float) scale, j1 / (float) scale, blockM);
            RiverHydrology.prefetch(pipeline, Math.floorDiv(i1, scale), Math.floorDiv(j1, scale),
                    Math.floorDiv(i2, scale), Math.floorDiv(j2, scale), blockM);
            entry.data.complete(data);
        } catch (Throwable e) {
            CACHE.remove(key, entry);
            entry.data.completeExceptionally(e);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // Blocks computed around every tile like the tile itself, so per-column facts that look at neighbours
    // read real columns instead of stopping at the tile edge
    private static final int HALO = 10;

    // How far around a column the cave carver looks for lower ground
    private static final int CAVE_SIDE = 6;

    // How far from river water caves stay sealed
    private static final int RIVER_WALL = 3;

    // Every column is a function of its position alone: the tile and its halo are rasterized from fields
    // the pipeline computes the same way for any box, and nothing afterwards looks across the raster
    private HeightmapData buildTile(int ti1, int tj1, int ti2, int tj2, int scale) {
        int i1 = ti1 - HALO, j1 = tj1 - HALO, i2 = ti2 + HALO, j2 = tj2 + HALO;
        int H = i2 - i1, W = j2 - j1;
        float blockM = NATIVE_RESOLUTION / scale;

        int i1n = Math.floorDiv(i1, scale) - 2, j1n = Math.floorDiv(j1, scale) - 2;
        int i2n = -Math.floorDiv(-i2, scale) + 2, j2n = -Math.floorDiv(-j2, scale) + 2;
        int nH = i2n - i1n, nW = j2n - j1n, upH = nH * scale, upW = nW * scale;
        float[][] out = pipeline.get(i1n, j1n, i2n, j2n, true);
        float[][] elevUp = LaplacianUtils.bilinearResize(to2D(out[0], nH, nW), upH, upW);
        int cropI = i1 - i1n * scale, cropJ = j1 - j1n * scale;
        float[] smooth = cropFlat(elevUp, cropI, cropJ, H, W, upH, upW);
        float[] smoothPadded = cropFlat(elevUp, cropI - 1, cropJ - 1, H + 2, W + 2, upH, upW);
        float[] climate = upsampleClimate(out[1], nH, nW, cropI, cropJ, H, W, scale, upH, upW);
        // At native resolution there is nothing between pixels to fill with detail
        float[] ground = scale > 1 ? addElevationNoise(smooth, smoothPadded, i1, j1, H, W, blockM) : smooth;
        float[][] carved = carveWater(ground, smooth, i1, j1, H, W, blockM, scale, 1);

        int tH = ti2 - ti1, tW = tj2 - tj1;
        int channels = climate.length / (H * W);
        float[] tileClimate = new float[channels * tH * tW];
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(crop(climate, ch * H * W, W, HALO, HALO, tH, tW), 0, tileClimate, ch * tH * tW, tH * tW);
        }
        byte[] snow = new byte[tH * tW];
        short[] biomes = BiomeClassifier.classify(crop(smooth, 0, W, HALO, HALO, tH, tW), tileClimate, ti1, tj1,
                crop(smooth, 0, W, HALO - 1, HALO - 1, tH + 2, tW + 2), tH, tW, blockM, snow,
                riverMask(crop(carved[1], 0, W, HALO, HALO, tH, tW)));
        return buildHeightmapData(carved[0], carved[1], climate, biomes, snow, H, W, tH, tW, blockM, scale);
    }

    // The H x W block of a row-major grid srcW wide starting at offset, row r0, column c0
    private static float[] crop(float[] src, int offset, int srcW, int r0, int c0, int H, int W) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) System.arraycopy(src, offset + (r0 + r) * srcW + c0, out, r * W, W);
        return out;
    }

    public static float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = AMP_COARSE * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = AMP_FINE * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float bandRisk = Math.min(1f, grad / (pixelSizeM * DITHER_BAND_GRAD));
                float floorM = DETAIL_FLOOR_BLOCKS * pixelSizeM * bandRisk;
                float offSea = Math.min(1f, Math.abs(e) / (2f * floorM));

                float nx = j1 + c, ny = i1 + r;

                float fineAmp = ampF * sf;
                float ditherAmp = Math.max(0f, floorM - fineAmp) * offSea;

                elevOut[idx] = e
                        + ELEV_DITHER.GetNoise(nx, ny) * ditherAmp
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * fineAmp;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        int channels = climNative.length / (nH * nW);
        float[] result = new float[channels * H * W];
        for (int ch = 0; ch < channels; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    // Bed and water surface of every column, each from the river and lake fields at that column alone
    private float[][] carveWater(float[] ground, float[] smooth, int i1, int j1, int H, int W,
                                 float metersPerBlock, int scale, int coordScale) {
        float[] bed = new float[H * W];
        float[] water = new float[H * W];
        WaterNetwork.Sample sample = new WaterNetwork.Sample();
        float toNative = coordScale == 1 ? scale : 1;

        for (int r = 0; r < H; r++) {
            InfiniteTensor.throwIfCancelled();
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nativeI = (i1 + r) / toNative;
                float nativeJ = (j1 + c) / toNative;
                float[] ch = RiverHydrology.sampleChannel(pipeline, nativeI, nativeJ, metersPerBlock);
                float[] lk = RiverHydrology.sampleLake(pipeline, nativeI, nativeJ, metersPerBlock);
                WaterNetwork.sample((j1 + c) * coordScale, (i1 + r) * coordScale, ground[idx], smooth[idx],
                        ch == null ? -1f : ch[0] * scale, ch == null ? 0f : ch[1],
                        ch == null ? 0f : ch[2], ch == null ? 0f : ch[3],
                        lk == null ? WaterNetwork.NO_WATER : lk[0], lk == null ? 0f : lk[1],
                        metersPerBlock, sample);
                bed[idx] = sample.bedElevM;
                water[idx] = sample.waterSurfaceM;
            }
        }
        return new float[][]{bed, water};
    }

    private static boolean[] riverMask(float[] waterFlat) {
        boolean[] mask = new boolean[waterFlat.length];
        for (int i = 0; i < waterFlat.length; i++) {
            mask[i] = waterFlat[i] > WaterNetwork.NO_WATER;
        }
        return mask;
    }

    private static short meters(float m) {
        return (short) Math.max(-32768, Math.min(32767, (int) Math.floor(m)));
    }

    // Heights, water and per-column facts for the tile at the centre of an H x W grid with HALO around it
    private static HeightmapData buildHeightmapData(float[] bed, float[] water, float[] climate,
                                                     short[] biomeFlat, byte[] snowFlat,
                                                     int H, int W, int tH, int tW, float metersPerBlock,
                                                     int scale) {
        int[] groundY = new int[H * W];
        int[] waterY = new int[H * W];
        for (int i = 0; i < H * W; i++) {
            groundY[i] = HeightConverter.convertToMinecraftHeight(meters(bed[i]), scale);
            waterY[i] = water[i] <= WaterNetwork.NO_WATER ? Integer.MIN_VALUE
                    : HeightConverter.convertToMinecraftHeight(meters(water[i]), scale);
        }

        short[][] heightmap = new short[tH][tW];
        short[][] biomeIds  = new short[tH][tW];
        byte[][] snowLayers = new byte[tH][tW];
        short[][] waterLevel = new short[tH][tW];
        byte[][] climateT = new byte[tH][tW];
        byte[][] climateH = new byte[tH][tW];
        byte[][] climateE = new byte[tH][tW];
        short[] sideSurface = new short[tH * tW];
        short[] riverWallLow = new short[tH * tW];
        short[] riverWallHigh = new short[tH * tW];
        float mpb = Math.max(0.001f, metersPerBlock);
        for (int r = 0; r < tH; r++)
            for (int c = 0; c < tW; c++) {
                int t = r * tW + c;
                int i = (r + HALO) * W + c + HALO;
                heightmap[r][c]  = meters(bed[i]);
                biomeIds[r][c]   = biomeFlat[t];
                snowLayers[r][c] = snowFlat[t];
                waterLevel[r][c] = water[i] <= WaterNetwork.NO_WATER
                        ? HeightmapData.NO_WATER
                        : (short) Math.max(-32767, Math.min(32767, (int) Math.floor(water[i])));

                float tempC  = climate[i];
                float precip = Math.max(1f, climate[2 * H * W + i]);
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int dr = -RELIEF_WINDOW; dr <= RELIEF_WINDOW; dr += RELIEF_WINDOW)
                    for (int dc = -RELIEF_WINDOW; dc <= RELIEF_WINDOW; dc += RELIEF_WINDOW) {
                        float v = bed[i + dr * W + dc];
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                climateT[r][c] = packUnit((tempC - 10.4f) / 17.1f);
                climateH[r][c] = packUnit((float) ((Math.log10(precip) - 2.85) / 0.55));
                climateE[r][c] = packUnit(1f - 2f * ((hi - lo) / mpb) / RELIEF_REF);

                int side = groundY[i];
                for (int dr = -CAVE_SIDE; dr <= CAVE_SIDE; dr += CAVE_SIDE)
                    for (int dc = -CAVE_SIDE; dc <= CAVE_SIDE; dc += CAVE_SIDE)
                        side = Math.min(side, groundY[i + dr * W + dc]);
                sideSurface[t] = (short) side;

                int wallLo = Short.MAX_VALUE, wallHi = Short.MIN_VALUE;
                for (int dr = -RIVER_WALL; dr <= RIVER_WALL; dr++)
                    for (int dc = -RIVER_WALL; dc <= RIVER_WALL; dc++) {
                        int n = i + dr * W + dc;
                        if (waterY[n] == Integer.MIN_VALUE) continue;
                        wallLo = Math.min(wallLo, groundY[n] - RIVER_WALL);
                        wallHi = Math.max(wallHi, waterY[n] + 1);
                    }
                riverWallLow[t] = (short) wallLo;
                riverWallHigh[t] = (short) wallHi;
            }
        return new HeightmapData(heightmap, biomeIds, snowLayers, waterLevel, climateT, climateH, climateE,
                sideSurface, riverWallLow, riverWallHigh, tW, tH);
    }

    // Window, in blocks, over which local relief sets vanilla's erosion axis
    private static final int RELIEF_WINDOW = 10;

    // Relief, in blocks across the window, that maps to the bottom of the erosion axis
    private static final float RELIEF_REF = 25f;

    private static byte packUnit(float v) {
        float c = v < -1f ? -1f : v > 1f ? 1f : v;
        return (byte) Math.round(c * 127f);
    }

    public static HeightmapData peekHeightmap(int i1, int j1, int i2, int j2) {
        CacheEntry cached = CACHE.get(new CacheKey(i1, j1, i2, j2));
        if (cached == null || !cached.data.isDone() || cached.data.isCompletedExceptionally()) {
            return null;
        }
        cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        return cached.data.join();
    }
}
