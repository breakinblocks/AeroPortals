package com.breakinblocks.aeroportals.util;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.AABB;

public final class AabbUtil {
    private AabbUtil() {}

    public static AABB worldAabb(SubLevel sub) {
        BoundingBox3dc bb = sub.boundingBox();
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
    }
}
