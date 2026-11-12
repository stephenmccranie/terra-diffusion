package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.ColumnFloors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every-layer placement finds a column's n-th floor by scanning down from its surface, anew for every try and layer, and
 * stops only after a round of tries finds no floor, each of them scanning to the bottom of the world. Nothing changes
 * the world while it picks positions, so each column it tries is scanned once, as deep as the call needs.
 */
@Mixin(CountOnEveryLayerPlacement.class)
public abstract class CountOnEveryLayerPlacementMixin {
    @WrapOperation(method = "getPositions", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/placement/CountOnEveryLayerPlacement;findOnGroundYPosition(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;IIII)I"))
    private int terrainDiffusion$floor(PlacementContext context, int x, int surface, int z, int n, Operation<Integer> scan,
                                       @Share("columns") LocalRef<ColumnFloors[]> columns) {
        ColumnFloors[] floors = columns.get();
        if (floors == null) columns.set(floors = new ColumnFloors[256]);
        // A call tries columns of one 16-block square, so their low four bits of x and z tell them apart
        int i = (z & 15) << 4 | (x & 15);
        if (floors[i] == null) floors[i] = new ColumnFloors(context.getLevel(), x, surface, z);
        return floors[i].floor(n);
    }
}
