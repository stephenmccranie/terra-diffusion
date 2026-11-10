package com.github.xandergos.terraindiffusionmc.infinitetensor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskTileCacheTest {

    @Test
    void aCacheFromOtherModelsIsRefusedAndKept(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("manifest.txt"), "models=old");
        Path window = root.resolve("window.bin");
        Files.writeString(window, "terrain");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DiskTileCache.open(root, "models=new"));
        assertTrue(refused.getMessage().contains("models=old") && refused.getMessage().contains("models=new"));
        assertTrue(Files.exists(window));
        assertNull(DiskTileCache.openReadOnly(root, "models=new"));
    }

    @Test
    void aCacheFromTheSameModelsOpens(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("manifest.txt"), "models=same");

        DiskTileCache.open(root, "models=same").close();
        DiskTileCache.open(root.resolve("fresh"), "models=same").close();
    }
}
