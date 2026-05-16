package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class PortalDetector {
    private PortalDetector() {}

    public static void scan(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        long now = level.getServer().getTickCount();
        double maxVolume = AeroPortalsConfig.MAX_SUBLEVEL_AABB_VOLUME.get();
        boolean verbose = AeroPortalsConfig.VERBOSE_LOGGING.get();

        for (ServerSubLevel sub : subs) {
            if (sub.isRemoved()) continue;
            if (PortalCooldown.isOnCooldown(sub.getUniqueId(), now)) continue;

            AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
            double volume = aabb.getXsize() * aabb.getYsize() * aabb.getZsize();
            if (volume > maxVolume) {
                if (verbose) AeroPortals.LOGGER.debug("[AeroPortals] skipping sub {} (volume {} > max {})", sub.getUniqueId(), volume, maxVolume);
                continue;
            }

            BlockPos portalPos = findPortalBlock(level, aabb);
            if (portalPos == null) continue;

            AeroPortals.LOGGER.info("[AeroPortals] sub {} overlaps portal at {} in dim {}",
                    sub.getUniqueId(), portalPos, level.dimension().location());
            PortalTeleport.teleport(level, sub, portalPos);
        }
    }

    private static BlockPos findPortalBlock(ServerLevel level, AABB aabb) {
        int x0 = (int) Math.floor(aabb.minX);
        int y0 = (int) Math.floor(aabb.minY);
        int z0 = (int) Math.floor(aabb.minZ);
        int x1 = (int) Math.floor(aabb.maxX);
        int y1 = (int) Math.floor(aabb.maxY);
        int z1 = (int) Math.floor(aabb.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) continue;
                    if (level.getBlockState(cursor).is(Blocks.NETHER_PORTAL)) {
                        return cursor.immutable();
                    }
                }
            }
        }
        return null;
    }
}
