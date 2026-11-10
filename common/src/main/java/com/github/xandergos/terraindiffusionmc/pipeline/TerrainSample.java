package com.github.xandergos.terraindiffusionmc.pipeline;

public final class TerrainSample {
    public int worldX, worldZ;

    public float elev;

    public float altM;

    // Slope of the land's shape, averaged over BiomeClassifier.LANDFORM_RADIUS; trees and bare rock read the
    // ground's own slope through slopeMedium and slopeBare
    public float slope;

    // Share of sea within the landform's reach
    public float seaShare;

    public float temp;

    // Temperature at the land's averaged surface, which sets the zones of heat and dryness
    public float zoneTemp;

    public float airC;

    public float tReg, relElev, moist, frost;

    public float tStd;

    public float precip;

    public float pCV;

    public float aridity;

    public float treeMoisture;

    public float growingSeason;

    public float effTreeMoisture;

    public float bareThreshold;

    public boolean aboveTreeLine, permanentSnow;

    public boolean treesNone, treesSparse, treesForest, treesDense, treesRainforest;

    public boolean barren;
    public boolean tooArid, tooCold;

    public boolean slopeMedium, slopeBare;
    public boolean hasSnow;

    public boolean isOcean;

    public boolean mountains;

    public boolean lowland;

    public boolean frozen, cold, cool, temperate, warm, hot;
}
