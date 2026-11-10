package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.BeardifierAccessor;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/** Skips structure blending only where every existing contribution is exactly zero. */
public final class BoundedBeardifier extends Beardifier {
    private static final int INFLUENCE_RADIUS = 12;
    private final long minX, minY, minZ, maxX, maxY, maxZ;

    public static BoundedBeardifier of(Beardifier original) {
        var access = (BeardifierAccessor) original;
        return new BoundedBeardifier(access.terrainDiffusion$pieces(), access.terrainDiffusion$junctions());
    }

    public BoundedBeardifier(ObjectListIterator<Rigid> pieces, ObjectListIterator<JigsawJunction> junctions) {
        super(pieces, junctions);
        if (pieces.nextIndex() != 0 || junctions.nextIndex() != 0)
            throw new IllegalArgumentException("Structure bounds require fresh iterators");
        long x0 = Long.MAX_VALUE, y0 = Long.MAX_VALUE, z0 = Long.MAX_VALUE;
        long x1 = Long.MIN_VALUE, y1 = Long.MIN_VALUE, z1 = Long.MIN_VALUE;
        // Beard kernels have radius 12. Bury has radius 6, with at most 2x axis scaling.
        // Use one conservative union; retain original evaluation order inside it.
        while (pieces.hasNext()) {
            Rigid piece = pieces.next();
            if (piece.terrainAdjustment() == TerrainAdjustment.NONE) continue;
            var box = piece.box();
            long floor = (long) box.minY() + piece.groundLevelDelta();
            x0 = Math.min(x0, (long) box.minX() - INFLUENCE_RADIUS);
            x1 = Math.max(x1, (long) box.maxX() + INFLUENCE_RADIUS);
            z0 = Math.min(z0, (long) box.minZ() - INFLUENCE_RADIUS);
            z1 = Math.max(z1, (long) box.maxZ() + INFLUENCE_RADIUS);
            y0 = Math.min(y0, Math.min(box.minY(), floor) - INFLUENCE_RADIUS);
            y1 = Math.max(y1, Math.max(box.maxY(), floor) + INFLUENCE_RADIUS);
        }
        pieces.back(Integer.MAX_VALUE);
        while (junctions.hasNext()) {
            var junction = junctions.next();
            x0 = Math.min(x0, (long) junction.getSourceX() - INFLUENCE_RADIUS);
            x1 = Math.max(x1, (long) junction.getSourceX() + INFLUENCE_RADIUS);
            y0 = Math.min(y0, (long) junction.getSourceGroundY() - INFLUENCE_RADIUS);
            y1 = Math.max(y1, (long) junction.getSourceGroundY() + INFLUENCE_RADIUS);
            z0 = Math.min(z0, (long) junction.getSourceZ() - INFLUENCE_RADIUS);
            z1 = Math.max(z1, (long) junction.getSourceZ() + INFLUENCE_RADIUS);
        }
        junctions.back(Integer.MAX_VALUE);
        minX = x0; maxX = x1;
        minY = y0; maxY = y1;
        minZ = z0; maxZ = z1;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int y = pos.blockY();
        if (y < minY || y > maxY) return 0.0;
        int x = pos.blockX(), z = pos.blockZ();
        if (x < minX || x > maxX || z < minZ || z > maxZ) return 0.0;
        return super.compute(pos);
    }

    public long maxInfluenceY() {
        return maxY;
    }
}
