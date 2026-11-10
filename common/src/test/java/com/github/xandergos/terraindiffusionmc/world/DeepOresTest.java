package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepOresTest {

    @Test
    void anchorsFromTheWorldsEdgesBecomeVanillasY() {
        JsonElement placement = JsonParser.parseString("""
                [{"type": "minecraft:count", "count": 7},
                 {"type": "minecraft:height_range", "height": {"type": "minecraft:trapezoid",
                    "min_inclusive": {"above_bottom": -80}, "max_inclusive": {"above_bottom": 80}}},
                 {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform",
                    "min_inclusive": {"absolute": 136}, "max_inclusive": {"below_top": 8}}}]""");

        assertTrue(DeepOres.pinToVanilla(placement));
        assertEquals(JsonParser.parseString("""
                [{"type": "minecraft:count", "count": 7},
                 {"type": "minecraft:height_range", "height": {"type": "minecraft:trapezoid",
                    "min_inclusive": {"absolute": -144}, "max_inclusive": {"absolute": 16}}},
                 {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform",
                    "min_inclusive": {"absolute": 136}, "max_inclusive": {"absolute": 311}}}]"""), placement);
    }

    @Test
    void absoluteRangesAreLeftAlone() {
        JsonElement placement = JsonParser.parseString("""
                [{"type": "minecraft:height_range", "height": {"type": "minecraft:uniform",
                    "min_inclusive": {"absolute": -16}, "max_inclusive": {"absolute": 112}}},
                 {"type": "minecraft:biome"}]""");
        JsonElement before = placement.deepCopy();

        assertFalse(DeepOres.pinToVanilla(placement));
        assertEquals(before, placement);
    }
}
