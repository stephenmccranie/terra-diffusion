package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.WorldScaleSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Reuses vanilla's World tab "Customize" button for Terrain Diffusion worlds
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
    @Shadow
    @Final
    private Button customizeTypeButton;

    @Unique
    private CreateWorldScreen terrainDiffusionMc$createWorldScreen;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terrainDiffusionMc$captureCreateWorldScreen(CreateWorldScreen createWorldScreen, CallbackInfo callbackInfo) {
        this.terrainDiffusionMc$createWorldScreen = createWorldScreen;
        createWorldScreen.getUiState().addListener(state -> terrainDiffusionMc$refreshCustomizeButton());
        terrainDiffusionMc$refreshCustomizeButton();
    }

    @Unique
    private void terrainDiffusionMc$refreshCustomizeButton() {
        if (isTerrainDiffusionWorldTypeSelected()) {
            this.customizeTypeButton.active = true;
        }
    }

    @Inject(method = "openPresetEditor", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$openTerrainScaleScreen(CallbackInfo callbackInfo) {
        if (!isTerrainDiffusionWorldTypeSelected()) {
            return;
        }
        Minecraft.getInstance().setScreen(new WorldScaleSettingsScreen(terrainDiffusionMc$createWorldScreen));
        callbackInfo.cancel();
    }

    // Any world whose overworld this mod generates, whichever preset it came from
    @Unique
    private boolean isTerrainDiffusionWorldTypeSelected() {
        return WorldScaleSettingsScreen.hasTerrain(
                terrainDiffusionMc$createWorldScreen.getUiState().getSettings().selectedDimensions());
    }
}
