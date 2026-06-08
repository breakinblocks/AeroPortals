package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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

            AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
            double volume = aabb.getXsize() * aabb.getYsize() * aabb.getZsize();
            if (volume > maxVolume) {
                if (verbose) AeroPortals.LOGGER.debug("[AeroPortals] skipping sub {} (volume {} > max {})", sub.getUniqueId(), volume, maxVolume);
                continue;
            }

            PortalHit hit = findPortalBlock(level, aabb);
            if (hit == null) {
                PortalCooldown.clearSuppression(sub.getUniqueId());
                continue;
            }
            if (PortalCooldown.isOnCooldown(sub.getUniqueId(), now)) continue;
            if (PortalCooldown.isSuppressedUntilLeftPortal(sub.getUniqueId())) continue;

            switch (hit.kind()) {
                case NETHER -> {
                    PortalRect srcRect = PortalGeom.measureFromBlock(level, hit.pos());
                    if (srcRect == null) {
                        AeroPortals.LOGGER.warn("[AeroPortals] nether portal block at {} but rect measurement failed; skipping",
                                hit.pos());
                        continue;
                    }
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps nether portal at {} (axis={} {}x{}) in dim {}",
                            sub.getUniqueId(), srcRect.minCorner(), srcRect.axis(), srcRect.width(), srcRect.height(),
                            level.dimension().location());
                    PortalTeleport.teleport(level, sub, srcRect);
                }
                case END -> {
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps end portal at {} in dim {}",
                            sub.getUniqueId(), hit.pos(), level.dimension().location());
                    PortalTeleport.teleportEnd(level, sub, hit.pos());
                }
                case ARS_NOUVEAU -> {
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps ars-nouveau portal at {} in dim {}",
                            sub.getUniqueId(), hit.pos(), level.dimension().location());
                    PortalTeleport.teleportArsNouveau(level, sub, hit.pos());
                }
                case AETHER -> {
                    PortalRect srcRect = PortalGeom.measureFromBlock(level, hit.pos(),
                            AetherCompat.portalBlock());
                    if (srcRect == null) {
                        AeroPortals.LOGGER.warn("[AeroPortals] aether portal block at {} but rect measurement failed; skipping",
                                hit.pos());
                        continue;
                    }
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps aether portal at {} (axis={} {}x{}) in dim {}",
                            sub.getUniqueId(), srcRect.minCorner(), srcRect.axis(), srcRect.width(), srcRect.height(),
                            level.dimension().location());
                    PortalTeleport.teleportAether(level, sub, srcRect);
                }
                case DRACONIC -> {
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps draconic portal at {} in dim {}",
                            sub.getUniqueId(), hit.pos(), level.dimension().location());
                    PortalTeleport.teleportDraconic(level, sub, hit.pos());
                }
                case DEEPER_DARKER -> {
                    PortalRect srcRect = PortalGeom.measureFromBlock(level, hit.pos(),
                            DeeperAndDarkerCompat.portalBlock());
                    if (srcRect == null) {
                        AeroPortals.LOGGER.warn("[AeroPortals] deeperdarker portal block at {} but rect measurement failed; skipping",
                                hit.pos());
                        continue;
                    }
                    AeroPortals.LOGGER.debug("[AeroPortals] sub {} overlaps deeperdarker portal at {} (axis={} {}x{}) in dim {}",
                            sub.getUniqueId(), srcRect.minCorner(), srcRect.axis(), srcRect.width(), srcRect.height(),
                            level.dimension().location());
                    PortalTeleport.teleportDeeperDarker(level, sub, srcRect);
                }
            }
        }
    }

    private record PortalHit(BlockPos pos, PortalKind kind) {}

    private static PortalHit findPortalBlock(ServerLevel level, AABB aabb) {
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
                    BlockState state = level.getBlockState(cursor);
                    PortalKind kind = PortalKind.ofBlock(state);
                    if (kind != null) return new PortalHit(cursor.immutable(), kind);
                }
            }
        }
        return null;
    }
}
