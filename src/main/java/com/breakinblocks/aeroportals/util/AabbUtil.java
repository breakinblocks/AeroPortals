package com.breakinblocks.aeroportals.util;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.AABB;

public final class AabbUtil {
    private AabbUtil() {}

    public static AABB worldAabb(SubLevel sub) {
        BoundingBox3dc bb = sub.boundingBox();
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
    }

    public static AABB plotAabb(SubLevel sub) {
        BoundingBox3ic bb = sub.getPlot().getBoundingBox();
        if (bb == BoundingBox3i.EMPTY) return null;
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
    }
}
