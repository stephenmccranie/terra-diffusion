# Village spacing and terrain scale

Village candidate grids scale with terrain in new Terrain Diffusion worlds.
Spacing and separation are multiplied by the saved world scale; scale 1 retains
the original placement. For example, a 34-chunk spacing becomes 136 chunks at scale 4.
Village sizes remain unchanged. Biome and terrain checks still decide which candidates
become villages, so accepted village counts need not be identical across scales.

## Existing worlds and configuration

Set `structures.scale_spacing` in `config/terrain-diffusion-mc.properties`:

| Value | Behavior |
| --- | --- |
| `auto` (default) | Enable scaling for new worlds and preserve each saved world's policy. Older saves keep their fixed grid. |
| `true` | Enable scaling and save that choice for each loaded Terrain Diffusion world. |
| `false` | Keep fixed spacing and save that choice for each loaded Terrain Diffusion world. |

Restart after changing the setting. Back up an existing world before opting in:
existing structures stay in place, while future starts and locate searches use the
new grid. Returning to `auto` preserves the explicitly saved choice.

## Datapacks

The `terrain-diffusion-mc:scale_with_terrain` structure-set tag includes vanilla villages
and optional Terralith rare villages. Datapacks can extend or replace it. Only standard
random-spread placements are scaled; custom placement subclasses and stronghold rings
retain their definitions. Custom grids are capped at the codec's 4096-chunk spacing
limit, preserving the separation ratio. Generation, locate and exclusion queries share
per-generator copies, leaving registry definitions unchanged.

## Validation

![Accepted village starts at scales 1–6](village-spacing-by-scale.png)

The survey uses the same seed and corresponding land region at each scale. Markers
are accepted native starts after biome, terrain, exclusion and complete jigsaw checks;
the survey does not populate building blocks.

Eight unit tests and Fabric/NeoForge CPU builds pass, including with the generation
optimizations. Earlier native DirectML checks generated 96 FULL chunks around a
natural 57-piece scale-4 village on both platforms. Blocks, layout, saved policy and
locate results matched after save/reload. Additional checks cover legacy saves,
exclusions and operation without Terralith.
