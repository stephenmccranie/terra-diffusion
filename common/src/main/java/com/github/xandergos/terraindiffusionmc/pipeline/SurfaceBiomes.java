package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

// Every biome the surface of a tile can be: the id tiles carry, the biome it stands for, and how the explorer
// draws it
public final class SurfaceBiomes {
    public record Entry(short id, String location, int grass, int display, boolean water) {
        public boolean terralith() {
            return location.startsWith(TerralithCompat.NAMESPACE + ":");
        }

        // "Snowy Plains" for minecraft:snowy_plains
        public String title() {
            StringBuilder out = new StringBuilder();
            for (String word : location.substring(location.indexOf(':') + 1).split("_")) {
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
            return out.toString();
        }
    }

    private static final Map<Short, Entry> ENTRIES = new LinkedHashMap<>();

    public static final short PLAINS = land(1, "minecraft:plains", 0x91BD59, 0x91BD59);
    public static final short SNOWY_PLAINS = land(3, "minecraft:snowy_plains", 0x80B497, 0xF1F5F8);
    public static final short DESERT = land(5, "minecraft:desert", 0xBFB755, 0xE3D49A);
    public static final short SWAMP = land(6, "minecraft:swamp", 0x6A7039, 0x6A7039);
    public static final short RIVER = water(7, "minecraft:river", 0x8EB971, 0x4C8FD6);
    public static final short FOREST = land(8, "minecraft:forest", 0x79C05A, 0x79C05A);
    public static final short FROZEN_RIVER = water(11, "minecraft:frozen_river", 0x80B497, 0x9DB7D6);
    public static final short TAIGA = land(15, "minecraft:taiga", 0x86B783, 0x86B783);
    public static final short SNOWY_TAIGA = land(16, "minecraft:snowy_taiga", 0x80B497, 0xC9DCD6);
    public static final short SAVANNA = land(17, "minecraft:savanna", 0xBFB755, 0xBFB755);
    public static final short WINDSWEPT_HILLS = land(19, "minecraft:windswept_hills", 0x8AB689, 0x8AB689);
    public static final short JUNGLE = land(23, "minecraft:jungle", 0x59C93C, 0x59C93C);
    public static final short BADLANDS = land(26, "minecraft:badlands", 0x90814D, 0xD9822B);
    public static final short WOODED_BADLANDS = land(27, "minecraft:wooded_badlands", 0x90814D, 0x9C7A44);
    public static final short ERODED_BADLANDS = land(28, "minecraft:eroded_badlands", 0x90814D, 0xC9693A);
    public static final short MEADOW = land(29, "minecraft:meadow", 0x83BB6D, 0x83BB6D);
    public static final short GROVE = land(31, "minecraft:grove", 0x80B497, 0xC9DCD6);
    public static final short SNOWY_SLOPES = land(32, "minecraft:snowy_slopes", 0x80B497, 0xF1F5F8);
    public static final short FROZEN_PEAKS = land(33, "minecraft:frozen_peaks", 0x80B497, 0xF1F5F8);
    public static final short STONY_PEAKS = land(35, "minecraft:stony_peaks", 0x9ABE4B, 0x9ABE4B);
    public static final short BEACH = land(38, "minecraft:beach", 0x91BD59, 0xE8D9A0);
    public static final short SNOWY_BEACH = land(39, "minecraft:snowy_beach", 0x83B593, 0xEDE8D4);
    public static final short STONY_SHORE = land(40, "minecraft:stony_shore", 0x8AB689, 0x9A9A8E);
    public static final short WARM_OCEAN = water(41, "minecraft:warm_ocean", 0x8EB971, 0x2F8FC0);
    public static final short LUKEWARM_OCEAN = water(42, "minecraft:lukewarm_ocean", 0x8EB971, 0x3384C0);
    public static final short DEEP_LUKEWARM_OCEAN = water(43, "minecraft:deep_lukewarm_ocean", 0x8EB971, 0x226FA8);
    public static final short OCEAN = water(44, "minecraft:ocean", 0x8EB971, 0x3A76B8);
    public static final short DEEP_OCEAN = water(45, "minecraft:deep_ocean", 0x8EB971, 0x28609E);
    public static final short COLD_OCEAN = water(46, "minecraft:cold_ocean", 0x8EB971, 0x3B6A9E);
    public static final short DEEP_COLD_OCEAN = water(47, "minecraft:deep_cold_ocean", 0x8EB971, 0x2A5686);
    public static final short FROZEN_OCEAN = water(48, "minecraft:frozen_ocean", 0x80B497, 0x9DB7D6);
    public static final short DEEP_FROZEN_OCEAN = water(49, "minecraft:deep_frozen_ocean", 0x80B497, 0x7F9CBE);

    // This mod's own
    public static final short FOREST_SPARSE = land(108, "terrain-diffusion-mc:forest_sparse", 0x79C05A, 0x79C05A);
    public static final short TAIGA_SPARSE = land(115, "terrain-diffusion-mc:taiga_sparse", 0x86B783, 0x86B783);
    public static final short SNOWY_TAIGA_SPARSE = land(116, "terrain-diffusion-mc:snowy_taiga_sparse", 0x80B497, 0xC9DCD6);
    public static final short GROVE_DRY = land(131, "terrain-diffusion-mc:grove_dry", 0xBFB755, 0xBFB755);

    // Terralith's, placed only in a world that has them all
    public static final short DEEP_WARM_OCEAN = water(200, "terralith:deep_warm_ocean", 0x8EB971, 0x1E6FA6);
    public static final short GRAVEL_BEACH = land(201, "terralith:gravel_beach", 0x91BD59, 0x91BD59);
    public static final short WARM_RIVER = water(202, "terralith:warm_river", 0x8EB971, 0x4C8FD6);
    public static final short FROZEN_CLIFFS = land(210, "terralith:frozen_cliffs", 0x80B497, 0xA9C8F0);
    public static final short GRANITE_CLIFFS = land(211, "terralith:granite_cliffs", 0x92B77A, 0x92B77A);
    public static final short WHITE_CLIFFS = land(212, "terralith:white_cliffs", 0x92B77A, 0x92B77A);
    public static final short YOSEMITE_CLIFFS = land(213, "terralith:yosemite_cliffs", 0x85B979, 0x85B979);
    public static final short BASALT_CLIFFS = land(214, "terralith:basalt_cliffs", 0x95B872, 0x5B5B60);
    public static final short STONY_SPIRES = land(215, "terralith:stony_spires", 0x98BB63, 0x98BB63);
    public static final short WINDSWEPT_SPIRES = land(216, "terralith:windswept_spires", 0x8AB689, 0x8AB689);
    public static final short DESERT_SPIRES = land(217, "terralith:desert_spires", 0x77B338, 0xE3D49A);
    public static final short DESERT_CANYON = land(218, "terralith:desert_canyon", 0xBFB755, 0xE3D49A);
    public static final short BRYCE_CANYON = land(219, "terralith:bryce_canyon", 0x90814D, 0xB8683E);
    public static final short AMETHYST_CANYON = land(220, "terralith:amethyst_canyon", 0x70D484, 0x70D484);
    public static final short GLACIAL_CHASM = land(221, "terralith:glacial_chasm", 0x80B497, 0xA9C8F0);
    public static final short EMERALD_PEAKS = land(233, "terralith:emerald_peaks", 0x6783AF, 0xF1F5F8);
    public static final short ROCKY_MOUNTAINS = land(235, "terralith:rocky_mountains", 0x8EB681, 0x8EB681);
    public static final short PAINTED_MOUNTAINS = land(236, "terralith:painted_mountains", 0x9ABE4B, 0xC77A55);
    public static final short HAZE_MOUNTAIN = land(237, "terralith:haze_mountain", 0x9FA885, 0x9FA885);
    public static final short JUNGLE_MOUNTAINS = land(238, "terralith:jungle_mountains", 0x72AA06, 0x72AA06);
    public static final short ALPINE_GROVE = land(239, "terralith:alpine_grove", 0x80B497, 0xC9DCD6);
    public static final short CLOUD_FOREST = land(240, "terralith:cloud_forest", 0x5C9A70, 0x5C9A70);
    public static final short YELLOWSTONE = land(241, "terralith:yellowstone", 0xC6D097, 0xC6D097);
    public static final short ALPINE_HIGHLANDS = land(250, "terralith:alpine_highlands", 0x99B678, 0x99B678);
    public static final short TEMPERATE_HIGHLANDS = land(251, "terralith:temperate_highlands", 0xA4BB58, 0xA4BB58);
    public static final short FORESTED_HIGHLANDS = land(252, "terralith:forested_highlands", 0x93B67E, 0x93B67E);
    public static final short ARID_HIGHLANDS = land(253, "terralith:arid_highlands", 0xB2B952, 0xB2B952);
    public static final short HIGHLANDS = land(254, "terralith:highlands", 0x8FB87A, 0x8FB87A);
    public static final short BLOOMING_PLATEAU = land(255, "terralith:blooming_plateau", 0x83BB6D, 0x83BB6D);
    public static final short SHIELD = land(256, "terralith:shield", 0x70A57B, 0x70A57B);
    public static final short SHIELD_CLEARING = land(257, "terralith:shield_clearing", 0x99B67C, 0x99B67C);
    public static final short SNOWY_SHIELD = land(258, "terralith:snowy_shield", 0x70A57B, 0xC9DCD6);
    public static final short SIBERIAN_GROVE = land(259, "terralith:siberian_grove", 0x4D836D, 0xC9DCD6);
    public static final short ROCKY_SHRUBLAND = land(260, "terralith:rocky_shrubland", 0x937554, 0xDCD6CC);
    public static final short SAVANNA_SLOPES = land(270, "terralith:savanna_slopes", 0xBFB755, 0xC9B26A);
    public static final short SAVANNA_BADLANDS = land(271, "terralith:savanna_badlands", 0xBFB755, 0xC9B26A);
    public static final short ASHEN_SAVANNA = land(272, "terralith:ashen_savanna", 0xC0BBAC, 0xB5B0A4);
    public static final short FRACTURED_SAVANNA = land(273, "terralith:fractured_savanna", 0xBFB755, 0xBFB755);
    public static final short BRUSHLAND = land(274, "terralith:brushland", 0xA7BB4E, 0xA7BB4E);
    public static final short SHRUBLAND = land(275, "terralith:shrubland", 0xAE9967, 0xAE9967);
    public static final short HOT_SHRUBLAND = land(276, "terralith:hot_shrubland", 0xC3AF67, 0xC3AF67);
    public static final short COLD_SHRUBLAND = land(277, "terralith:cold_shrubland", 0x937554, 0xDCD6CC);
    public static final short STEPPE = land(278, "terralith:steppe", 0x99B67C, 0x99B67C);
    public static final short ANCIENT_SANDS = land(290, "terralith:ancient_sands", 0xBFB755, 0xD8B77A);
    public static final short LUSH_DESERT = land(291, "terralith:lush_desert", 0xDCAC56, 0xD9B071);
    public static final short DESERT_OASIS = land(292, "terralith:desert_oasis", 0x77B338, 0x9CC25A);
    public static final short RED_OASIS = land(293, "terralith:red_oasis", 0x77B338, 0x9CC25A);
    public static final short SANDSTONE_VALLEY = land(294, "terralith:sandstone_valley", 0x77B338, 0xE3D49A);
    public static final short WHITE_MESA = land(295, "terralith:white_mesa", 0x90814D, 0xD8C8B8);
    public static final short WARPED_MESA = land(296, "terralith:warped_mesa", 0xBEAFDD, 0xA78BD0);
    public static final short GRAVEL_DESERT = land(297, "terralith:gravel_desert", 0x86B68C, 0xF1F5F8);
    public static final short SNOWY_BADLANDS = land(298, "terralith:snowy_badlands", 0x80B497, 0xE6D6CF);
    public static final short TROPICAL_JUNGLE = land(310, "terralith:tropical_jungle", 0x89DD4E, 0x89DD4E);
    public static final short ROCKY_JUNGLE = land(311, "terralith:rocky_jungle", 0x72AA06, 0x72AA06);
    public static final short AMETHYST_RAINFOREST = land(312, "terralith:amethyst_rainforest", 0x70D484, 0x70D484);
    public static final short YOSEMITE_LOWLANDS = land(320, "terralith:yosemite_lowlands", 0x598347, 0x598347);
    public static final short LUSH_VALLEY = land(321, "terralith:lush_valley", 0x70A57B, 0x70A57B);
    public static final short VALLEY_CLEARING = land(322, "terralith:valley_clearing", 0x99B67C, 0x99B67C);
    public static final short BIRCH_TAIGA = land(323, "terralith:birch_taiga", 0x8DC876, 0x8DC876);
    public static final short LAVENDER_FOREST = land(324, "terralith:lavender_forest", 0x9BE0BC, 0x9BE0BC);
    public static final short LAVENDER_VALLEY = land(325, "terralith:lavender_valley", 0x9BE0BC, 0x9BE0BC);
    public static final short SAKURA_GROVE = land(326, "terralith:sakura_grove", 0x79CF85, 0x79CF85);
    public static final short SAKURA_VALLEY = land(327, "terralith:sakura_valley", 0x79CF85, 0x79CF85);
    public static final short MOONLIGHT_GROVE = land(328, "terralith:moonlight_grove", 0x9EB2E1, 0x9EB2E1);
    public static final short MOONLIGHT_VALLEY = land(329, "terralith:moonlight_valley", 0x9EB2E1, 0x9EB2E1);
    public static final short BLOOMING_VALLEY = land(330, "terralith:blooming_valley", 0x79C05A, 0x79C05A);
    public static final short ORCHID_SWAMP = land(331, "terralith:orchid_swamp", 0x5F9B76, 0x5F9B76);
    public static final short SIBERIAN_TAIGA = land(340, "terralith:siberian_taiga", 0x629E77, 0xC9DCD6);
    public static final short WINTRY_FOREST = land(341, "terralith:wintry_forest", 0x80B497, 0xC9DCD6);
    public static final short WINTRY_LOWLANDS = land(342, "terralith:wintry_lowlands", 0x80B497, 0xF1F5F8);
    public static final short SNOWY_MAPLE_FOREST = land(343, "terralith:snowy_maple_forest", 0x85B590, 0xC9DCD6);
    public static final short SNOWY_CHERRY_GROVE = land(344, "terralith:snowy_cherry_grove", 0xB6DB61, 0xF1F5F8);
    public static final short ICE_MARSH = land(345, "terralith:ice_marsh", 0x6A7039, 0xF1F5F8);

    private SurfaceBiomes() {
    }

    private static short land(int id, String location, int grass, int display) {
        return add(new Entry((short) id, location, grass, display, false));
    }

    private static short water(int id, String location, int grass, int display) {
        return add(new Entry((short) id, location, grass, display, true));
    }

    private static short add(Entry entry) {
        if (ENTRIES.put(entry.id(), entry) != null) {
            throw new IllegalStateException("Two biomes share id " + entry.id());
        }
        return entry.id();
    }

    public static Collection<Entry> all() {
        return ENTRIES.values();
    }

    public static Entry of(short id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("No biome has id " + id);
        }
        return entry;
    }
}
