# Chunk-generation performance

Five changes avoid redundant work while preserving terrain calculations:

- Request exact bulk density evaluation with Minecraft's cell cache.
- Return zero outside conservative structure-influence bounds.
- Skip density cells above the terrain, water and structure ceiling.
- Replace biome jitter's modulo 1024 with an equivalent bit mask.
- Supply the DirectML decoder's fixed input dimensions, keeping batch size one.

The density function and shared heightmap cache remain original fork sources.

## Measurements

Compared with original fork commit `9086fe3`, using independent JVMs and 1,024 fresh
FULL chunks per run. Startup, the preflight region and result serialization are
outside the timed request; dependency chunks required by generation are included.

| Workload | Original mean | Optimized mean | Less elapsed time |
| --- | ---: | ---: | ---: |
| Cached terrain tensors | 20.081 s | 8.246 s | 58.9% |
| Cold terrain inference | 27.622 s | 16.467 s | 40.4% |

Cached measurements use three original controls and two optimized trials within
an isolation/removal matrix. Original observations are 21.935 / 21.580 / 16.728 s;
optimized observations are 8.018 / 8.475 s. Cold runs use original/optimized/optimized/
original order: 27.526 / 27.718 s original and 16.572 / 16.362 s optimized, with
323 timed model calls each. These are workload-specific estimates with limited repeats.

All tests ran on the desktop in a dedicated instance: Ryzen 7 5700X3D, RX 7900 GRE,
Java 21, Minecraft 1.21.1, NeoForge 21.1.265, C2ME 0.4.0-alpha.0.122, Terralith 2.6.2
and Lithostitched 1.8.1. Seed 734829105, scale 4, tile size 256, minimum Y -192 and
dimension height 1600 were fixed. Both variants use sixteen C2ME workers, its density
compiler and resident DirectML models. Cached runs make no timed model calls.

## Scheduling

The inference thread runs only model calls and tensor-cache reads. A terrain tile is
computed by the first thread that needs it, and a river region on a two-thread pool,
since one build holds about 1 GB of working arrays. Everyone else waits for that one
result. Each request waits at the urgency of whoever needs it: the main thread before a
chunk worker, and both before regions nobody has asked for yet. Regions within a quarter
region of a finished tile are queued in the background, so they are usually ready before
generation reaches them. The explorer builds its regions on the inference thread itself.

This removes waiting, not work: CPU per chunk is unchanged. Before it, chunk workers
spent about half their time waiting on that one thread while it built a region, and the
GPU was busy only in short bursts.

Chunky pregeneration of 1024-block tiles in a fresh world, on the same desktop with
C2ME (eight workers), Lithium, ModernFix, Terralith, Chunky and Distant Horizons,
resident DirectML models and scale 4:

| Workload | Before | After |
| --- | ---: | ---: |
| Two tiles, chunks per second | 96.8 | 112.8 |
| Two tiles, CPU-ms per chunk | 81.2 | 80.0 |
| Six tiles in a sweep, chunks per second | 124–127 | 143–145 |

The gain depends on idle CPU: a machine that is already saturated sees little of it.

## Correctness and compatibility

- All 1,024 cold NOISE chunks match the original after excluding only `LastUpdate`
  and `InhabitedTime`. All 323 model-output tensors' raw-bit SHA-256 hashes match.
- Density, height, ceiling, fluid and cave fixtures pass across six scales and
  sixteen workers. Structure fixtures cover adjustment types and influence boundaries.
- A 4,232-tile churn check finds no tile retention outside the shared cache.
- Earlier checks cover C2ME absent/present, its compiler disabled/enabled, model
  offloading enabled/disabled and minimal versus Terralith mod stacks.
- NeoForge CPU build and Fabric compilation pass. Combined with scale-aware village
  placement, the eight village tests and full Fabric/NeoForge CPU builds also pass.

FULL decoration varies between untouched original repeats, so full decorated-chunk
identity is not claimed. Unsupported density wrappers, blending, aquifers, ore veins,
custom structure-blending implementations or incomplete height data retain full-height
filling. Model weights, precision and inference batching are unchanged.
