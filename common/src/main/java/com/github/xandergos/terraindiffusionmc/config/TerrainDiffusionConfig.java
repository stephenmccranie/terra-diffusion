package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "terrain-diffusion-mc.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();

    // The bundled file holds every key's default; the copy in the config directory overrides it
    static {
        Path configPath = PlatformPaths.configDir().resolve(FILE_NAME);
        try (InputStream defaults = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaults == null) {
                throw new IllegalStateException("Bundled config " + RESOURCE_PATH + " is missing");
            }
            PROPERTIES.load(defaults);
            Set<String> known = Set.copyOf(PROPERTIES.stringPropertyNames());
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream overrides = Files.newInputStream(configPath)) {
                    PROPERTIES.load(overrides);
                }
                Set<String> unread = new TreeSet<>(PROPERTIES.stringPropertyNames());
                unread.removeAll(known);
                if (!unread.isEmpty()) {
                    throw new IllegalStateException(configPath + " sets keys this build does not read: " + unread
                            + ". Remove them.");
                }
            } else {
                try (InputStream copy = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
                    Files.copy(copy, configPath);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + configPath, e);
        }
    }

    private TerrainDiffusionConfig() {
    }

    // Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU)
    public static String inferenceDevice() {
        String device = readString("inference.device");
        // The CPU build has no dedicated GPU provider, so "gpu" there means try CoreML, then the CPU
        return "cpu".equals(BUILD_VARIANT) && "gpu".equals(device) ? "auto" : device;
    }

    // Whether to offload inactive models from VRAM between pipeline stages
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models");
    }

    // TCP port for the local terrain explorer HTTP server
    public static int explorerPort() {
        return readInt("explorer.port");
    }

    // Whether to validate SHA-256 for pre-existing local model files before use
    public static boolean validateModel() {
        return readBoolean("validate_model");
    }

    // Initial coarse-pixel radius for spawn land search (NxN region centered at origin)
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size");
    }

    // Maximum coarse-pixel region size for spawn land search before giving up
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size");
    }

    public static boolean snowDepthScaling() {
        return readBoolean("snow.depth_scaling");
    }

    public static int snowMaxLayersOverVegetation() {
        return Math.max(1, Math.min(8, readInt("snow.max_layers_over_vegetation")));
    }

    public static boolean terralithEnabled() {
        return readBoolean("terralith.enabled");
    }

    public static boolean terralithInjectSurfaceRules() {
        return readBoolean("terralith.inject_surface_rules");
    }

    // Decoded model windows kept in memory, per tensor
    public static int tileCacheMb() {
        return Math.max(16, readInt("tile_cache_mb"));
    }

    // Whether to adapt TFMG's oil and striated ore generation to this dimension
    public static boolean tfmgEnabled() {
        return readBoolean("tfmg.enabled");
    }

    // Y the oil deposit and oil well features start from
    public static int tfmgOilY() {
        return readInt("tfmg.oil_y");
    }

    // Block count of a striated ore body
    public static int tfmgStriatedOreSize() {
        return readInt("tfmg.striated_ore_size");
    }

    // One placement in this many produces a deposit
    public static int tfmgDepositRarity() {
        return readInt("tfmg.oil_deposit_rarity");
    }

    // Upper bound on the pockets a single deposit placement scatters
    public static int tfmgDepositBlobs() {
        return readInt("tfmg.oil_deposit_blobs");
    }

    // Upper bound on the height of one oil pocket, in blocks
    public static int tfmgDepositHeight() {
        return readInt("tfmg.oil_deposit_height");
    }

    // How far pockets of one deposit wander from the placement
    public static int tfmgDepositSpread() {
        return readInt("tfmg.oil_deposit_spread");
    }

    // Where an oil well's column stops, relative to the surface
    public static int tfmgWellSurfaceOffset() {
        return readInt("tfmg.oil_well_surface_offset");
    }

    // Radius of the slick an oil well leaves at the surface
    public static int tfmgWellPoolRadius() {
        return readInt("tfmg.oil_well_pool_radius");
    }

    // Tile side length in blocks: a power of two, and at least a chunk
    private static int tileSizeCache;

    public static int tileSize() {
        int cached = tileSizeCache;
        if (cached > 0) {
            return cached;
        }
        int size = readInt("tile_size");
        if (size < 16 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("tile_size must be a power of two of at least 16; got: " + size);
        }
        tileSizeCache = size;
        return size;
    }

    private static String value(String key) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Config key " + key + " has no bundled default");
        }
        return value.trim();
    }

    private static String readString(String key) {
        return value(key).toLowerCase();
    }

    private static boolean readBoolean(String key) {
        String value = readString(key);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false; got: " + value);
        }
        return value.equals("true");
    }

    private static int readInt(String key) {
        try {
            return Integer.parseInt(value(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number; got: " + value(key), e);
        }
    }

    private static float readFloat(String key) {
        try {
            return Float.parseFloat(value(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number; got: " + value(key), e);
        }
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
