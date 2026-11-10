package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;

import java.nio.file.Path;
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

    // One tile of block columns: its corner and side in blocks, its karst, and every column's facts, row-major
    public static final class HeightmapData {
        public static final short NO_WATER = Short.MIN_VALUE;

        public final int originX, originZ, size;
        public final KarstNetwork karst;
        // Minecraft Y of each column's ground, and of the surface of its river or lake; NO_WATER on dry columns
        public final short[] surfaceY;
        public final short[] waterY;
        // The ground in whole metres of world height
        public final short[] groundM;
        public final short[] biomeIds;
        public final byte[] snowLayers;
        // Temperature, humidity and erosion as vanilla's climate axes, -127 to 127
        public final byte[] climateT, climateH, climateE;
        // Model height in metres of the land's regional base under each column
        public final short[] regionalBaseM;
        // Lowest surface Y of each column and the columns CAVE_SIDE blocks around it
        public final short[] sideSurface;
        // Y range, inclusive, where a column has river water within RIVER_WALL blocks of it; empty (low above
        // high) away from rivers
        public final short[] riverWallLow;
        public final short[] riverWallHigh;
        // How each column's top water moves: 0 still, a ramp level up to BlockWater.MAX_RAMP, or
        // BlockWater.CURTAIN plus the height of a fall
        public final byte[] waterFlow;

        HeightmapData(int originX, int originZ, int size, KarstNetwork karst) {
            this.originX = originX;
            this.originZ = originZ;
            this.size = size;
            this.karst = karst;
            int n = size * size;
            surfaceY = new short[n];
            waterY = new short[n];
            groundM = new short[n];
            biomeIds = new short[n];
            snowLayers = new byte[n];
            climateT = new byte[n];
            climateH = new byte[n];
            climateE = new byte[n];
            regionalBaseM = new short[n];
            sideSurface = new short[n];
            riverWallLow = new short[n];
            riverWallHigh = new short[n];
            waterFlow = new byte[n];
        }

        // Where block column (x, z), which lies in this tile, sits in its arrays
        public int index(int x, int z) {
            return (z - originZ) * size + (x - originX);
        }

        boolean holds(int x, int z) {
            return (x & -size) == originX && (z & -size) == originZ;
        }
    }

    private record TileKey(int tileX, int tileZ) {}
    // A tile is computed once, by the first thread to need it; later callers wait for that computation
    private record CacheEntry(CompletableFuture<HeightmapData> data, AtomicLong lastAccessed) {}

    // Blocks of area to keep cached, so tile size does not decide the size of the working set
    private static final long CACHE_AREA_BLOCKS = 16777216L;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<TileKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();

    private static int maxCacheSize(int tileSize) {
        return (int) Math.max(64L, Math.min(4096L, CACHE_AREA_BLOCKS / ((long) tileSize * tileSize)));
    }

    private static volatile LocalTerrainProvider INSTANCE;

    private static LocalTerrainProvider EXPLORER;
    private static volatile long explorerSeed;
    private static volatile String explorerView = "";
    private static volatile int explorerGeneration = -1;
    private static volatile int worldGeneration;

    // A world's terrain is what its generator settings say of it: the seed, how many blocks span one pixel of
    // the model's grid, and how the model's heights become the world's
    private final long seed;
    public final int scale;
    public final Relief relief;
    private final Path worldDir;
    private final WorldPipeline pipeline;

    private LocalTerrainProvider(long seed, int scale, Relief relief, Path worldDir, PipelineModels models,
                                 boolean readOnlyDisk) {
        this.seed = seed;
        this.scale = scale;
        this.relief = relief;
        this.worldDir = worldDir;
        this.pipeline = new WorldPipeline(seed, relief, worldDir, models, readOnlyDisk);
    }

    private static PipelineModels loadedModels() {
        PipelineModels.awaitLoad();
        return PipelineModels.getInstance();
    }

    // Starts the terrain of a world that is loading, saved in worldDir
    public static synchronized void init(long seed, int scale, Relief relief, Path worldDir) {
        DeepCaverns.setSeed(seed);
        CaveBiomes.setSeed(seed);
        LocalTerrainProvider previous = INSTANCE;
        if (previous != null) {
            WorldPipeline.INFERENCE.call(() -> {
                previous.pipeline.close();
                return null;
            });
        }
        INSTANCE = new LocalTerrainProvider(seed, scale, relief, worldDir, loadedModels(), false);
        CACHE.clear();
        worldGeneration++;
    }

    public static LocalTerrainProvider getInstance() {
        LocalTerrainProvider instance = INSTANCE;
        if (instance == null) {
            throw new IllegalStateException("Terrain was asked for before a Terrain Diffusion world loaded");
        }
        return instance;
    }

    public static void clearCache() {
        CACHE.clear();
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
            LocalTerrainProvider world = getInstance();
            EXPLORER = new LocalTerrainProvider(world.seed, world.scale, world.relief, world.worldDir,
                    loadedModels(), true);
            EXPLORER.pipeline.hydrology.buildSeamNeighbours(false);
            explorerSeed = world.seed;
            explorerGeneration = generation;
        }
        return EXPLORER;
    }

    public static long explorerSeed() {
        return explorerGeneration == worldGeneration ? explorerSeed : getInstance().seed;
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
                    int scale = provider.scale;
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

    // Sea-level air temperature for a box of native pixels; reads the coarse climate on the inference thread
    public float[] seaLevelTemperature(int i1, int j1, int i2, int j2) {
        return pipeline.seaLevelTemperature(i1, j1, i2, j2);
    }

    private static final class LastTile {
        int generation = -1;
        HeightmapData tile;
    }

    private static final ThreadLocal<LastTile> LAST = ThreadLocal.withInitial(LastTile::new);

    // The tile holding block column (x, z). Generation asks column by column, so each thread keeps the tile it
    // last asked for.
    public static HeightmapData tileAt(int x, int z) {
        LastTile last = LAST.get();
        int generation = worldGeneration;
        if (last.tile == null || last.generation != generation || !last.tile.holds(x, z)) {
            int size = TerrainDiffusionConfig.tileSize();
            last.tile = getInstance().tile(x & -size, z & -size, size);
            last.generation = generation;
        }
        return last.tile;
    }

    // The tile holding block column (x, z) if it is already built, otherwise null
    public static HeightmapData peekTileAt(int x, int z) {
        int size = TerrainDiffusionConfig.tileSize();
        CacheEntry cached = CACHE.get(new TileKey(x & -size, z & -size));
        if (cached == null || !cached.data.isDone() || cached.data.isCompletedExceptionally()) {
            return null;
        }
        cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        return cached.data.join();
    }

    HeightmapData tile(int tileX, int tileZ, int size) {
        TileKey key = new TileKey(tileX, tileZ);
        CacheEntry entry = CACHE.get(key);
        if (entry == null) {
            CacheEntry mine = new CacheEntry(new CompletableFuture<>(), new AtomicLong());
            entry = CACHE.putIfAbsent(key, mine);
            if (entry == null) {
                entry = mine;
                generate(key, size, mine);
                evictLruTo(maxCacheSize(size));
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
    private void generate(TileKey key, int size, CacheEntry entry) {
        try {
            float blockM = NATIVE_RESOLUTION / scale;
            int i1 = key.tileZ(), j1 = key.tileX();
            HeightmapData data = buildTile(key.tileX(), key.tileZ(), size, scale);
            RiverHydrology.prefetch(pipeline, Math.floorDiv(i1, scale), Math.floorDiv(j1, scale),
                    Math.floorDiv(i1 + size, scale), Math.floorDiv(j1 + size, scale), blockM);
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
    // read real columns instead of stopping at the tile edge; the widest look is BlockWater.REACH
    private static final int HALO = 16;

    // How far around a column the cave carver looks for lower ground
    private static final int CAVE_SIDE = 6;

    // How far from river water caves stay sealed
    private static final int RIVER_WALL = 3;

    // Every column is a function of its position alone: the tile and its halo are rasterized from fields
    // the pipeline computes the same way for any box, and nothing afterwards looks across the raster
    private HeightmapData buildTile(int tileX, int tileZ, int size, int scale) {
        int ti1 = tileZ, tj1 = tileX, tH = size, tW = size;
        int i1 = ti1 - HALO, j1 = tj1 - HALO, i2 = ti1 + size + HALO, j2 = tj1 + size + HALO;
        int H = i2 - i1, W = j2 - j1;
        float blockM = NATIVE_RESOLUTION / scale;

        int i1n = Math.floorDiv(i1, scale) - 2, j1n = Math.floorDiv(j1, scale) - 2;
        int i2n = -Math.floorDiv(-i2, scale) + 2, j2n = -Math.floorDiv(-j2, scale) + 2;
        int nH = i2n - i1n, nW = j2n - j1n, upH = nH * scale, upW = nW * scale;
        int m = BiomeClassifier.LANDFORM_MARGIN, wH = nH + 2 * m, wW = nW + 2 * m;
        float[][] wide = pipeline.get(i1n - m, j1n - m, i2n + m, j2n + m, true);
        float[] heights = crop(wide[0], 0, wW, m, m, nH, nW);
        float[] nativeClimate = cropChannels(wide[1], wH, wW, m, nH, nW);
        float[][] land = BiomeClassifier.landform(wide[0], channel(wide[1], 6, wH * wW), wH, wW);
        float[][] elevUp = LaplacianUtils.bilinearResize(to2D(heights, nH, nW), upH, upW);
        int cropI = i1 - i1n * scale, cropJ = j1 - j1n * scale;
        float[] smooth = cropFlat(elevUp, cropI, cropJ, H, W, upH, upW);
        float[] smoothPadded = cropFlat(elevUp, cropI - 1, cropJ - 1, H + 2, W + 2, upH, upW);
        float[] climate = upsampleClimate(nativeClimate, nH, nW, cropI, cropJ, H, W, scale, upH, upW);
        // At native resolution there is nothing between pixels to fill with detail
        float[] ground = scale > 1 ? addElevationNoise(smooth, smoothPadded, i1, j1, H, W, blockM) : smooth;
        float[][] carved = carveWater(ground, smooth, i1, j1, H, W, blockM, scale, 1);
        byte[] flow = BlockWater.shape(carved[0], carved[1], ground, carved[2], H, W, blockM);

        int channels = climate.length / (H * W);
        float[] tileClimate = new float[channels * tH * tW];
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(crop(climate, ch * H * W, W, HALO, HALO, tH, tW), 0, tileClimate, ch * tH * tW, tH * tW);
        }
        int ti = cropI + HALO, tj = cropJ + HALO;
        var landform = new BiomeClassifier.Landform(
                upsampled(land[0], wH, wW, m, nH, nW, scale, ti - 1, tj - 1, tH + 2, tW + 2),
                upsampled(land[1], wH, wW, m, nH, nW, scale, ti, tj, tH, tW),
                upsampled(land[2], wH, wW, m, nH, nW, scale, ti, tj, tH, tW));
        byte[] snow = new byte[tH * tW];
        short[] biomes = BiomeClassifier.classify(crop(smooth, 0, W, HALO, HALO, tH, tW), tileClimate, ti1, tj1, 1,
                crop(smooth, 0, W, HALO - 1, HALO - 1, tH + 2, tW + 2), landform, tH, tW, blockM, snow,
                riverMask(crop(carved[1], 0, W, HALO, HALO, tH, tW)));
        BiomeClassifier.easeSnowNearWater(snow, carved[1], carved[0], H, W, HALO, tH, tW);
        HeightmapData data = new HeightmapData(tileX, tileZ, size,
                RiverHydrology.karstAt(pipeline, ti1 / (float) scale, tj1 / (float) scale, blockM));
        System.arraycopy(biomes, 0, data.biomeIds, 0, biomes.length);
        System.arraycopy(snow, 0, data.snowLayers, 0, snow.length);
        fillColumns(data, carved[0], carved[1], flow, climate, H, W, blockM);
        return data;
    }

    // Channel ch of a channel-major grid of n pixels
    private static float[] channel(float[] grid, int ch, int n) {
        float[] out = new float[n];
        System.arraycopy(grid, ch * n, out, 0, n);
        return out;
    }

    // Every channel of an h x w channel-major grid cropped to the H x W block m pixels in from its corner
    private static float[] cropChannels(float[] grid, int h, int w, int m, int H, int W) {
        int channels = grid.length / (h * w);
        float[] out = new float[channels * H * W];
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(crop(grid, ch * h * w, w, m, m, H, W), 0, out, ch * H * W, H * W);
        }
        return out;
    }

    // The H x W block at (r0, c0) of the nH x nW native box m pixels inside an h x w grid, upsampled by scale
    // the way the tile's heights are
    private static float[] upsampled(float[] grid, int h, int w, int m, int nH, int nW, int scale,
                                     int r0, int c0, int H, int W) {
        float[][] up = LaplacianUtils.bilinearResize(to2D(crop(grid, 0, w, m, m, nH, nW), nH, nW),
                nH * scale, nW * scale);
        return cropFlat(up, r0, c0, H, W, nH * scale, nW * scale);
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

    // Bed, water surface and where the water's level sits within its top block (-1 for lakes, NaN without water)
    // of every column, each from the river and lake fields at that column alone
    private float[][] carveWater(float[] ground, float[] smooth, int i1, int j1, int H, int W,
                                 float metersPerBlock, int scale, int coordScale) {
        float[] bed = new float[H * W];
        float[] water = new float[H * W];
        float[] frac = new float[H * W];
        WaterNetwork.Sample sample = new WaterNetwork.Sample();
        WaterNetwork.Reaches rivers = new WaterNetwork.Reaches();
        float toNative = coordScale == 1 ? scale : 1;

        for (int r = 0; r < H; r++) {
            InfiniteTensor.throwIfCancelled();
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nativeI = (i1 + r) / toNative;
                float nativeJ = (j1 + c) / toNative;
                RiverHydrology.sampleRivers(pipeline, nativeI, nativeJ, metersPerBlock, rivers);
                float[] lk = RiverHydrology.sampleLake(pipeline, nativeI, nativeJ, metersPerBlock);
                WaterNetwork.sample((j1 + c) * coordScale, (i1 + r) * coordScale, ground[idx], smooth[idx], rivers,
                        lk == null ? WaterNetwork.NO_WATER : lk[0], lk == null ? 0f : lk[1], metersPerBlock, sample);
                bed[idx] = sample.bedElevM;
                water[idx] = sample.waterSurfaceM;
                float level = sample.levelM / metersPerBlock;
                frac[idx] = !sample.hasWater() ? Float.NaN : sample.lake ? -1f
                        : Math.floor(sample.waterSurfaceM / metersPerBlock) == Math.floor(level)
                        ? level - (float) Math.floor(level) : Float.NaN;
            }
        }
        return new float[][]{bed, water, frac};
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
    private static void fillColumns(HeightmapData data, float[] bed, float[] water, byte[] flow, float[] climate,
                                    int H, int W, float metersPerBlock) {
        int[] groundY = new int[H * W];
        int[] waterY = new int[H * W];
        for (int i = 0; i < H * W; i++) {
            groundY[i] = HeightConverter.toY(bed[i], metersPerBlock);
            waterY[i] = water[i] <= WaterNetwork.NO_WATER ? Integer.MIN_VALUE
                    : HeightConverter.toY(water[i], metersPerBlock);
        }

        int size = data.size;
        float mpb = Math.max(0.001f, metersPerBlock);
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++) {
                int t = r * size + c;
                int i = (r + HALO) * W + c + HALO;
                data.groundM[t] = meters(bed[i]);
                data.waterY[t] = waterY[i] == Integer.MIN_VALUE ? HeightmapData.NO_WATER : (short) waterY[i];

                float tempC  = climate[i];
                float precip = Math.max(1f, climate[2 * H * W + i]);
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int dr = -RELIEF_WINDOW; dr <= RELIEF_WINDOW; dr += RELIEF_WINDOW)
                    for (int dc = -RELIEF_WINDOW; dc <= RELIEF_WINDOW; dc += RELIEF_WINDOW) {
                        float v = bed[i + dr * W + dc];
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                data.climateT[t] = packUnit((tempC - 10.4f) / 17.1f);
                data.climateH[t] = packUnit((float) ((Math.log10(precip) - 2.85) / 0.55));
                data.climateE[t] = packUnit(1f - 2f * ((hi - lo) / mpb) / RELIEF_REF);

                data.surfaceY[t] = (short) groundY[i];
                data.waterFlow[t] = flow[i];
                data.regionalBaseM[t] = meters(climate[7 * H * W + i]);
                int side = groundY[i];
                for (int dr = -CAVE_SIDE; dr <= CAVE_SIDE; dr += CAVE_SIDE)
                    for (int dc = -CAVE_SIDE; dc <= CAVE_SIDE; dc += CAVE_SIDE)
                        side = Math.min(side, groundY[i + dr * W + dc]);
                data.sideSurface[t] = (short) side;

                int wallLo = Short.MAX_VALUE, wallHi = Short.MIN_VALUE;
                for (int dr = -RIVER_WALL; dr <= RIVER_WALL; dr++)
                    for (int dc = -RIVER_WALL; dc <= RIVER_WALL; dc++) {
                        int n = i + dr * W + dc;
                        if (waterY[n] == Integer.MIN_VALUE) continue;
                        wallLo = Math.min(wallLo, groundY[n] - RIVER_WALL);
                        wallHi = Math.max(wallHi, waterY[n] + 1);
                    }
                data.riverWallLow[t] = (short) wallLo;
                data.riverWallHigh[t] = (short) wallHi;
            }
    }

    // Window, in blocks, over which local relief sets vanilla's erosion axis
    private static final int RELIEF_WINDOW = 10;

    // Relief, in blocks across the window, that maps to the bottom of the erosion axis
    private static final float RELIEF_REF = 25f;

    private static byte packUnit(float v) {
        float c = v < -1f ? -1f : v > 1f ? 1f : v;
        return (byte) Math.round(c * 127f);
    }
}
