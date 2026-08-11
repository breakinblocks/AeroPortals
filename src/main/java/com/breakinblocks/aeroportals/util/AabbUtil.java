package com.breakinblocks.aeroportals.util;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.AABB;

public final class AabbUtil {
    private AabbUtil() {}

    public static AABB worldAabb(SubLevel sub) {
        BoundingBox3dc bb = ensureBoundsCurrent(sub);
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
    }

    public static void ensureBoundsCurrent(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            ensureBoundsCurrent(sub);
        }
    }

    public static BoundingBox3dc ensureBoundsCurrent(SubLevel sub) {
        BoundingBox3dc bb = sub.boundingBox();
        if (!isUnset(bb)) return bb;
        if (sub.getPlot().getBoundingBox() == BoundingBox3i.EMPTY) return bb;

        sub.updateBoundingBox();
        bb = sub.boundingBox();
        AeroPortals.LOGGER.debug("[AeroPortals] sub {} had no world bounds yet (assembled this tick); recomputed as {},{},{} -> {},{},{}",
                sub.getUniqueId(), bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
        return bb;
    }

    private static boolean isUnset(BoundingBox3dc bb) {
        return bb.maxX() <= bb.minX() && bb.maxY() <= bb.minY() && bb.maxZ() <= bb.minZ();
    }

    public static AABB plotAabb(SubLevel sub) {
        BoundingBox3ic bb = sub.getPlot().getBoundingBox();
        if (bb == BoundingBox3i.EMPTY) return null;
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
    }
}
