package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.api.PortalScanPlan;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalBlockSearch;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

public final class PortalDetector {
    private PortalDetector() {}

    public static void scan(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        PortalScanPlan plan = AeroPortalsApi.scanPlan();
        if (plan.isEmpty()) return;

        long now = level.getServer().getTickCount();
        double maxVolume = AeroPortalsConfig.MAX_SUBLEVEL_AABB_VOLUME.get();

        for (ServerSubLevel sub : subs) {
            if (sub.isRemoved()) continue;

            UUID id = sub.getUniqueId();
            if (PortalCooldown.isOnCooldown(id, now)) continue;

            AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
            double volume = aabb.getXsize() * aabb.getYsize() * aabb.getZsize();
            if (volume > maxVolume) {
                AeroPortals.LOGGER.debug("[AeroPortals] skipping sub {} (volume {} > max {})", id, volume, maxVolume);
                continue;
            }

            PortalBlockSearch.Hit hit = PortalBlockSearch.find(level, aabb, plan);
            if (hit == null) {
                PortalCooldown.noteAwayFromPortal(id, aabb.getCenter());
                continue;
            }
            if (PortalCooldown.isSuppressedUntilLeftPortal(id)) continue;

            dispatch(level, sub, hit);
            PortalCooldown.mark(id, now);
        }
    }

    private static void dispatch(ServerLevel level, ServerSubLevel sub, PortalBlockSearch.Hit hit) {
        AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps {} portal at {} in dim {}",
                sub.getUniqueId(), hit.type().id(), hit.pos(), level.dimension().location());

        PortalDestination destination;
        try {
            destination = hit.type().resolve(level, sub, hit.pos());
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] portal type {} threw while resolving a destination at {}",
                    hit.type().id(), hit.pos(), e);
            return;
        }
        if (destination == null) {
            AeroPortals.LOGGER.debug("[AeroPortals] portal type {} at {} produced no destination; skipping",
                    hit.type().id(), hit.pos());
            return;
        }
        PortalTeleport.dispatch(level, sub, destination);
    }
}
