<img width="1029" height="1029" alt="TDTR_logo" src="https://github.com/user-attachments/assets/9f4bbe46-3c48-441b-b277-7a3bab7e77c8" />

# Terra Diffusion — Terralith compatibility fork

A fork of [xandergos/terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc)
that adds [Terralith](https://modrinth.com/mod/terralith)'s biomes in worldgen, adds
temperature-driven snow depth, carves rivers, and places water lakes.

<img width="1914" height="1029" alt="Screenshot 2026-07-31 120407" src="https://github.com/user-attachments/assets/1019878e-d109-4e94-ad13-d6daf8ba3eee" />
<img width="1914" height="1030" alt="Screenshot 2026-07-31 130154" src="https://github.com/user-attachments/assets/0e8e82ad-c2e5-4873-bf57-95ae1bd0d425" />

Targets **Minecraft 1.21.1**, Fabric and NeoForge.

## What this fork changes

**Terralith biomes generate.** Upstream replaces the biome source and ships its own noise
settings. This fork maps the model's elevation and climate output onto **86** Terralith
surface biomes, and pulls Terralith's surface rules in at world load. Trees grow only below
the tree line, which follows the global rule real ones do: a growing season, the days averaging
above 0.9 °C, at least 94 days long and averaging at least 6.4 °C, so tropical tree lines
stand high, continental ones low, and the same rule ends forest towards the poles. Above it lie
alpine grass and rock, and snow only where it outlasts the summer: where a year's precipitation
exceeds what the summer melts, the relation real glacier lines follow (Ohmura 1992). Forests
below the line keep their winter snow. Details in
[Terralith compatibility](#terralith-compatibility) below.

**Snow mantle creation.** Vanilla lays one snow layer everywhere it snows. Here the depth comes from the air temperature of the ground (see Taller mountains): 1–7 layers between −5 °C and −7.5 °C, then a full snow block plus another 1–7 layers down to −10 °C. Snow on a tree canopy also snows the ground underneath. If [Snow Real Magic](https://modrinth.com/mod/snow-real-magic) is installed, columns where it is keeping a plant alive get filled as well. 

**Rivers.** Rivers are post-processed on top of the model's terrain. Each 1,024-pixel river region drains a window a region wider on every side, counting each cell by the runoff its climate leaves, and a coarse drainage map of the land beyond tells it how much water flows in. Filled depressions are exactly level, and water crosses them towards where they drain and away from the ground rising around them, so it gathers into one channel instead of ruled parallel lines. A river begins where a cell gathers a river's catchment. Its width grows with the square root of its discharge, as real rivers' do, so at scale 4 (7.5 m a block) it is as many blocks wide as a real river carrying the same water is metres; it runs deeper than real so boats and swimmers have water. Its water surface is graded from the mouth upstream: a block below the valley floor, never steeper than its discharge allows, and where that would cut more than three times the river's depth into the ground it climbs back to the valley floor in a waterfall. Water is placed still, so wherever a river's surface steps down a block or falls it spills over when its chunk first starts ticking, worlds generated earlier included: a step leaves a short rapid of flowing water that a boat can paddle up, a fall becomes a waterfall, and where a river or lake runs over a cave its water pours down into the cave instead of hanging over it. Flat rivers meander in sine waves about eleven widths long, as far as their valley floor lets them; steep ones run straight. A region keeps the stretches of river that start inside it, and a river crossing into the next region is pinned there to its valley's line, so neighbouring regions meet exactly without blending; about one crossing in twenty is a small stream only one of the two regions draws, which ends at the line. Every block column takes its ground and water from the cross-sections of the rivers near it: a channel holding the water, a floodplain cut flat a block above it that widens the flatter the river runs, and valley walls rising beyond. Terrain therefore never depends on how the world is split into tiles, so there are no straight walls along tile edges.

**Taller mountains.** The model learned real-world heights, so its peaks rarely pass 4,000 m while each world's height assumes a 10,000 m peak. The land's large shape, about a kilometre across, keeps the model's height below 300 m, so coasts, rivers and farmland stay put; above that it rises ever faster, until from 1,200 m on it rises 2.5 m for every metre. Ground keeps its height above or below that shape, so slopes stay as buildable as the model made them, and ridges and summits standing above their surroundings rise a further 80%. A 2,000 m plateau stands about 3,900 m tall, a 3,000 m summit on it about 5,700 m, and a valley cut 500 m into it stays 500 m deep; heights ease towards 9,800 m and never pass it. The curve is set by the `relief.*` keys below. Climate keeps the model's heights, so climate zones stay where the model put them, while snow, ice, tree lines and bare rock follow the raised ground: its air is 6.5 °C colder for every kilometre the ground was raised.

**Lakes** are made similar to rivers, filling depressions elegible for holding water to create deep and vast lakes, or small and contained depending on their area of generation.

**Additional *additions***. I'm a big Create Mod fan, and usually play with [TFMG](https://github.com/DrMango14/Create-The_Factory_Must_Grow). Because `min_y` has been pinned at -192 for deeper oceans, oil wells have been placed at -189. 
The snow has been made to work with another project of mine called [Snowier Snow](null), which adds snow displacement physics for snow blocks and snow layers, alongside compatibility with the physics engine [Sable](https://github.com/ryanhcode/sable), to work with [Create Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project).
For compatibility requests, open a discussion. I'm actively looking for testers/helpers on the project as it gets more complex!   
NOTE THAT THE MODS LISTED ARE NOT REQUIRED AND THEIR EFFECTS WILL ENABLE WHEN THE PAIRED MOD HAS BEEN FOUND!

## Requirements

- Windows with a GPU, or Linux with an NVIDIA GPU. CPU inference works but is very slow. ***AMD only works with windows version, theoretically***
- **2.5** GB VRAM, **8+** GB RAM for Minecraft: river regions build two at a time and each holds about 2 GB while it builds (raise Minecraft's memory allocation).
- Minecraft 1.21.1 with either [Fabric](https://fabricmc.net/) plus [Fabric API](https://modrinth.com/mod/fabric-api), or [NeoForge](https://neoforged.net/).
- DEPENDING ON ADVANCE IN DEVELOPMENT, THESE REQUIREMENTS ARE SUBJECT TO CHANGE!

Only relevant to this fork:

- **[Terralith](https://modrinth.com/mod/terralith)** 
- **[Lithostitched](https://modrinth.com/mod/lithostitched)** 
- **[Snow Real Magic](https://modrinth.com/mod/snow-real-magic)** 

## Installing

Grab a jar from [Releases](https://github.com/Alesrr/terra-diffusion-compat/releases) and copy
it into your `mods/` folder.

| Your machine                | Backend      | Download                          | Extra setup                     |
|-----------------------------|--------------|-----------------------------------|---------------------------------|
| Windows with any modern GPU | DirectML     | `-windows+1.21.1.jar` (~7 MB)     | none                            |
| NVIDIA GPU, any system      | CUDA         | `-cuda+1.21.1.jar` (~550 MB)      | [CUDA + cuDNN](CUDA_INSTALL.md) |
| macOS, or no usable GPU     | CPU / CoreML | `-cpu+1.21.1.jar` (~95 MB)        | none                            |

Then:

1. Drop the jar in your `mods/` folder.
2. Launch once while online so the models download (~2.5 GB). This is separate from the jar
   size above and happens on first run regardless of which jar you picked.
3. Create a world, pick the **Terrain Diffusion** world type, and click **Customize** to set
   `World Scale` (see [Per-world settings](#per-world-settings)).

**Using the CUDA build?** Read [CUDA_INSTALL.md](CUDA_INSTALL.md) first.

## Exploring the world

Run `/td-explore` in game and it prints a clickable link (`http://localhost:19801` by default) that opens an interactive map. Click the map on the left for a detailed view; click that for coordinates in the bottom left. You can use filters for narrowing down search (Detail Maps can take up to about 60 seconds to compute!)

## Configuration

`config/terrain-diffusion-mc.properties`, created on first launch:

```properties
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# How the model's heights become world heights; see Taller mountains above. The land's large shape
# keeps its height below relief.start metres and, from relief.full on, rises relief.gain metres per
# metre of the model's; ground standing above its surroundings rises a further relief.peaks times as
# far. relief.gain=1 with relief.peaks=0 keeps the model's heights. Set these before generating.
relief.start=300
relief.full=1200
relief.gain=2.5
relief.peaks=0.8

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128

# Snow lies deeper the colder the ground is, instead of a uniform single layer everywhere.
snow.depth_scaling=true

# Layer cap where snow is covering vegetation instead of bare ground. Keep this equal to
# Snow Real Magic's accumulation.maxLayers. Ignored when Snow Real Magic is not installed.
snow.max_layers_over_vegetation=6

# Place Terralith's biomes when Terralith is installed.
terralith.enabled=true

# Give Terralith's biomes their own surface blocks. See caveates in compatibility!
terralith.inject_surface_rules=true
```

The `relief.*` keys and the last four keys are specific to this fork.

### Terralith compatibility

Terralith places biomes by extending the vanilla multi-noise biome parameter list through Lithostitched, and defines their surfaces by overriding `minecraft:worldgen/noise_settings/overworld`. 

### Per-world settings

For Terrain Diffusion worlds, click **Customize** during world creation and set:

- `World Scale` (integer `1..6`)

Saved with the world. It controls:

- how many real-world meters each block represents (`scale=1` → `30 m/block`, `scale=2` →
  `15 m/block`, and so on)
- max world height for newly created worlds (assumes the tallest point is 10000 m)

2 is a good balance. Use 1 for smaller, more compressed worlds. Lower values lean on the GPU
(the model runs more often); higher values lean on the CPU (taller worlds). 
*I don't suggest using scale 5-6 unless you have a 64core cpu or something!*

## Common issues

**A dynamic link library (DLL) initialization routine failed**

Some older Java versions. Update to the latest Java 21 or higher; the [latest Microsoft OpenJDK 21](https://learn.microsoft.com/en-us/java/openjdk/download) is known to work.

**LoadLibrary failed with error 126** *(CUDA build only)*

Usually a bad CUDA or cuDNN install. See [CUDA_INSTALL.md](CUDA_INSTALL.md).

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

Almost always out of memory — the logs should confirm it. The models need about 2.5 GB of RAM,
so allocate enough.

**Terralith is installed but I see no Terralith biomes**

Check the log at world load. The biome source logs how many Terralith biomes it resolved, and it deliberately falls back to the vanilla palette if any expected biome is missing rather than generating a half-broken world. A version mismatch between Terralith and this fork's biome table is the usual cause.

For anything in the base mod, use
[upstream's issue tracker](https://github.com/xandergos/terrain-diffusion-mc/issues). For the
Terralith, snow, river or lake behaviour, open an issue here.
**Check Issues on HOW TO MAKE AN ISSUE for this fork** (Unless it's code-related)

## Building from source

Needs **JDK 21**. Newer JDKs fail in `buildSrc` with `Unsupported class file major version` because of the Gradle version in use.

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

The `-windows` build needs `libs/onnxruntime-dml.jar`, which ships in the repo. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build it yourself.

### Build tasks

Use `Windows` when you want the DirectML build. The old `Dml` names still work as aliases.

| What you want                       | Command                          |
|-------------------------------------|----------------------------------|
| Fabric + NeoForge, Windows/DirectML | `./gradlew buildWindows`         |
| Fabric + NeoForge, CUDA             | `./gradlew buildCuda`            |
| Fabric + NeoForge, CPU/CoreML       | `./gradlew buildCpu`             |
| Every loader and every variant      | `./gradlew buildRelease`         |
| Fabric only, Windows/DirectML       | `./gradlew buildFabricWindows`   |
| Fabric only, CUDA                   | `./gradlew buildFabricCuda`      |
| Fabric only, CPU/CoreML             | `./gradlew buildFabricCpu`       |
| Every Fabric variant                | `./gradlew buildFabricAll`       |
| NeoForge only, Windows/DirectML     | `./gradlew buildNeoForgeWindows` |
| NeoForge only, CUDA                 | `./gradlew buildNeoForgeCuda`    |
| NeoForge only, CPU/CoreML           | `./gradlew buildNeoForgeCpu`     |
| Every NeoForge variant              | `./gradlew buildNeoForgeAll`     |

The direct property form still works:

```
./gradlew build -PuseDml=true
./gradlew build -PuseCuda=true
./gradlew build -PuseCpu=true
./gradlew :fabric:build -PuseDml=true
./gradlew :neoforge:build -PuseDml=true
```

Aliases kept for existing scripts:

```
./gradlew buildDml
./gradlew buildFabricDml
./gradlew buildNeoForgeDml
./gradlew buildAll
```

Jars land under each loader module:

```
fabric/build/libs/
neoforge/build/libs/
```

`./gradlew collectReleaseJars` copies the distributable ones into:

```
build/release/fabric/
build/release/neoforge/
```

To cut a full release, run both:

```
./gradlew buildRelease collectReleaseJars
```

That produces the six jars that go on a release, and takes a few minutes most of it spent on the two CUDA jars:

```
build/release/fabric/
  Fabric-terra_diffusion-1.1.0-windows+1.21.1.jar        7 MB
  Fabric-terra_diffusion-1.1.0-cpu+1.21.1.jar           95 MB
  Fabric-terra_diffusion-1.1.0-cuda+1.21.1.jar         561 MB
build/release/neoforge/
  NeoForge-terra_diffusion-1.1.0-windows+1.21.1.jar      7 MB
  NeoForge-terra_diffusion-1.1.0-cpu+1.21.1.jar         93 MB
  NeoForge-terra_diffusion-1.1.0-cuda+1.21.1.jar       547 MB
```

The jar name comes from `archives_base_name` and `mod_version` in `gradle.properties`. Note that `mod_id` is separate and deliberately still `terrain-diffusion-mc` — it is the resource namespace baked into world saves, so renaming it would break existing worlds.

### Building onnxruntime with DirectML
*I don't suggest treading here unless you know your shit!*

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — Windows 10 1803 or newer
- Visual Studio 2017 toolchain — *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same
- Python 3.10+
- CMake 3.28 or higher

Keep both VS toolchains current. Full details in the
[ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the
[DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run everything from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and put it in `libs/`.

## Credits and licence

Terrain Diffusion and this mod's entire pipeline are by
[xandergos](https://github.com/xandergos). Original repositories:
[terrain-diffusion](https://github.com/xandergos/terrain-diffusion) and
[terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc).

Terralith by [Stardust Labs](https://modrinth.com/mod/terralith).

MIT, Copyright (c) 2025 Alexander Goslin & Alesrr [LICENSE.txt](LICENSE.txt).

<img width="1736" height="939" alt="Screenshot 2026-08-29 150459" src="https://github.com/user-attachments/assets/601bfe55-e662-4c5a-9589-5b8f445086c8" />
