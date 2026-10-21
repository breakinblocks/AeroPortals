package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.TravelMethods;
import com.breakinblocks.aeroportals.util.PortalBlockSearch;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

public final class IrregularImplementsCompat {
    private static final ResourceLocation PORTAL = ResourceLocation.fromNamespaceAndPath(
            "irregular_implements", "artificial_end_portal");
    private static Method actionTimer;
    private static boolean unavailable;

    private IrregularImplementsCompat() {}

    public static BlockPos findTouching(ServerLevel level, SubLevel sub, AABB searchArea) {
        if (unavailable || !ModList.get().isLoaded(PORTAL.getNamespace()) || !TravelMethods.isEnabled("end")) return null;

        for (Entity entity : level.getEntities((Entity) null, searchArea,
                entity -> !entity.isRemoved() && PORTAL.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))) {
            try {
                if (actionTimer == null) actionTimer = entity.getClass().getMethod("getActionTimer");
                if ((int) actionTimer.invoke(entity) < 200) continue;
            } catch (ReflectiveOperationException ex) {
                unavailable = true;
                AeroPortals.LOGGER.warn("[AeroPortals] cannot read Irregular Implements portal readiness", ex);
                return null;
            }
            // The artificial portal covers a horizontal 3x3 area, without any portal blocks.
            BlockPos centre = entity.blockPosition();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (PortalBlockSearch.touches(level, sub, centre.offset(x, 0, z))) return centre;
                }
            }
        }
        return null;
    }
}
