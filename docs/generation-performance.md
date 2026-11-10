# Chunk-generation performance

Chunk workers spend most of their time in vanilla stages (surface rules, features, chunk
storage). The fork keeps its own share small and keeps the GPU and region builds ahead of
the workers.

## Chunk workers

- **Column fill.** With aquifers and ore veins off, the noise stage reduces to: the default
  block where terrain density plus structure blending is positive, otherwise the fluid
  picker's fluid or air. `TerrainFill` places exactly that a column at a time, without the
  noise chunk's cells and interpolation, and sets each worldgen heightmap once per column.
- **Fill ceiling.** The fill stops at the chunk's highest ground, water or structure
  influence instead of filling the 2160-block world height.
- **Bounded structure blending.** `BoundedBeardifier` returns zero outside the structures'
  influence boxes, and its top bounds the ceiling.
- **Cave lattice.** `DeepCaverns` evaluates its 3D noise on a 4-block lattice and
  interpolates.
- **Karst lookup.** A region's cave passages are listed by the cubes of blocks they pass
  through, so a block tests only the passages beside it, however tall its column.

Chunks the fill does not cover (blending, aquifers, ore veins, other density functions,
incomplete height data) take the generator's own fill.

## Terrain and river regions

- **Drainage.** River regions drain with one shore-seeded flood that shares a queue between
  pits and slopes, and accumulate flow in topological order rather than by sorting.
- **Joined requests.** A tile asks for all nine regions it needs before waiting on any.
- **Prefetch.** Regions within a quarter region of a finished tile are queued in the
  background, so they are usually ready before generation reaches them.
- **Inference.** The DirectML decoder gets its fixed input dimensions; tensors are added
  row by row; overlapping model windows share their noise tiles; the 25 most recent
  regions stay cached.

The inference thread runs only model calls and tensor-cache reads. A terrain tile is
computed by the first thread that needs it, and a river region on a two-thread pool,
since one build holds about 1 GB of working arrays. Each request waits at the urgency of
whoever needs it: the main thread before a chunk worker, and both before prefetched
regions. The explorer builds its regions on the inference thread itself.

## What each part is worth

Chunky pregeneration of six 1024-block tiles in a fresh scale-4 world on a Ryzen 7
5700X3D and RX 7900 GRE with DirectML, C2ME (eight workers), Lithium, ModernFix and
Terralith, without Distant Horizons. The first tile includes cold model and region work;
steady figures are tiles two to six. The full build averages 300 chunks/s steady
(290–309 over eight runs), 38.5 CPU-ms per chunk and a 78 s first tile. Each row
removes one part and keeps the rest:

| Removed | Steady chunks/s | CPU-ms/chunk | First tile |
| --- | ---: | ---: | ---: |
| Fill ceiling | 240 | 46.1 | 82 s |
| Prefetch | 257 | 36.6 | 67 s |
| Column fill | 268 | 42.4 | 78 s |
| Drainage | 270 | 40.6 | 89 s |
| Cave lattice | 284 | 40.3 | 79 s |
| Joined requests | 287 | 39.7 | 93 s |
| Bounded blending in the fill | 293 | 39.2 | 78 s |
| Decoder dimensions | 297 | 38.8 | 88 s |
| Row-wise tensor add | 305 | 38.2 | 85 s |

Noise-tile sharing and the 25-region cache show nothing alone but slow new regions when
removed along with other parts.

## Correctness

The column fill was checked in game against the generator's own fill on 26,500 chunks:
blocks, section flags and worldgen heightmaps identical. Drainage and joined requests give
the same lakes and rivers to float rounding, and the inference changes are bit-identical.
The cave lattice moves 2–3% of open cave blocks.
