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
- **Surface pass.** `TerrainSurface` walks each column as the surface system does and
  places the same blocks, but tries each block of stone against the surface rule
  specialised to what is already known there. Below a column's preliminary surface the
  Terralith overworld rule reduces to its bedrock and deepslate bands and a few floor tests;
  stone beyond every stone-depth test's reach meets only the bands, and is merely counted
  between them and the preliminary surface. At and above the preliminary surface the
  block's biome is looked up first and the rule specialised to it. Columns are read once
  and written straight into their sections and heightmaps.
- **Heightmap priming.** A generating chunk's heightmaps are primed passing whole any
  section holding no block a heightmap still looks for, such as a sea's water over its floor.
- **Region chunks.** A generation region looks up each chunk around its centre once for
  height and block reads; an ore asks the height of every column in a square around it,
  and each lookup otherwise goes through the server's chunk cache.
- **Cave floors.** Every-layer placement, which Terralith's cave biomes use, looks for a
  column's n-th floor by scanning down from the surface for every try and layer, ending
  with a round that scans each try to the bottom of the world. `ColumnFloors` scans each
  column a call tries once, as deep as the call needs, and passes with one read any
  section with no air, water or lava in it.

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

Chunky pregeneration of a sweep of six 1024-block tiles in a fresh scale-4 world on a
Ryzen 7 5700X3D and RX 7900 GRE with DirectML, C2ME (eight workers), Lithium, ModernFix
and Terralith, without Distant Horizons. The first tile includes cold model and region
work; steady figures are tiles two to six. Three sweeps: open ocean (floor at y −81 to
−4), warm lowland (ground at y 109–120, nearly all dry) and snowy peaks (ground at y
829–1191). Each ran four times with the full build and four times with the build before
the surface pass, heightmap priming and region chunks, alternating; runs of one build
vary by 1–2%:

| Terrain | Steady chunks/s | CPU-ms/chunk | First tile |
| --- | ---: | ---: | ---: |
| Ocean | 278 → 346 (+24%) | 39.7 → 33.2 | 67 → 64 s |
| Lowland | 161 → 200 (+24%) | 63.0 → 53.1 | 87 → 83 s |
| Peaks | 63 → 94 (+48%) | 151 → 106 | 159 → 128 s |

Peaks gain most because their columns are tall: profiled, the surface pass removes 62% of
the surface stage there, and region chunks a quarter of the feature stage, where cave-floor
placements read deep columns a block at a time through the region. Cave floors came after
and add, over three alternating rounds on the peaks, 93 → 98 chunks/s steady (+5%), 106 →
100 CPU-ms per chunk and a 131 → 115 s first tile; the lowland and ocean sweeps barely use
it and do not change beyond noise.

On the ocean sweep, each row below removes one part from the full build and keeps the rest:

| Removed | Steady chunks/s | CPU-ms/chunk | First tile |
| --- | ---: | ---: | ---: |
| Surface pass | 310 | 36.1 | 64 s |
| Heightmap priming | 330 | 34.4 | 63 s |
| Region chunks | 332 | 33.9 | 65 s |

These are single runs against a full build of 339 chunks/s steady, so region chunks alone
is within noise. The rows below were measured on the ocean sweep before those three parts,
against a full build of 300 chunks/s steady (290–309 over eight runs), 38.5 CPU-ms per
chunk and a 78 s first tile:

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
blocks, section flags and worldgen heightmaps identical. The surface pass was checked against
the surface system with ModernFix's surface optimisations on 8,000 chunks, and heightmap
priming against vanilla's on 18.3 million columns: blocks, worldgen heightmaps and
post-processing marks identical. Cave floors were checked against vanilla's scan on 11
million floor lookups over peak, lowland and ocean tiles, all identical. A region hands
out the same chunk for a position throughout its life, so caching it changes nothing.
Drainage and joined requests give the same lakes and rivers to float rounding, and the
inference changes are bit-identical. The cave lattice moves 2–3% of open cave blocks.
