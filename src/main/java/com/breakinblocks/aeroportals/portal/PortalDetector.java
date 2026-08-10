package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
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

        for (ServerSubLevel sub : subs) {
            if (sub.isRemoved()) continue;

            AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
            double volume = aabb.getXsize() * aabb.getYsize() * aabb.getZsize();
            if (volume > maxVolume) {
                AeroPortals.LOGGER.debug("[AeroPortals] skipping sub {} (volume {} > max {})", sub.getUniqueId(), volume, maxVolume);
                continue;
            }

            PortalHit hit = findPortalBlock(level, aabb);
            if (hit == null) {
                PortalCooldown.noteAwayFromPortal(sub.getUniqueId(), aabb.getCenter());
                continue;
            }
            if (PortalCooldown.isOnCooldown(sub.getUniqueId(), now)) continue;
            if (PortalCooldown.isSuppressedUntilLeftPortal(sub.getUniqueId())) continue;

            dispatch(level, sub, hit);
            PortalCooldown.mark(sub.getUniqueId(), now);
        }
    }

    private static void dispatch(ServerLevel level, ServerSubLevel sub, PortalHit hit) {
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

    private record PortalHit(BlockPos pos, AeroPortalType type) {}

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
                    AeroPortalType type = AeroPortalsApi.findPortalType(state);
                    if (type != null) return new PortalHit(cursor.immutable(), type);
                }
            }
        }
        return null;
    }
}
