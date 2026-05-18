package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EndPortalLanding {
    public static final BlockPos PLATFORM_CENTRE = new BlockPos(100, 49, 0);
    public static final int LANDING_Y = PLATFORM_CENTRE.getY() + 1;
    public static final int PLATFORM_HALF = 2;

    private EndPortalLanding() {}

    public static Vec3 landingPosition(ServerSubLevel sub) {
        AABB aabb = AabbUtil.worldAabb(sub);
        Vec3 subPos = new Vec3(
                sub.logicalPose().position().x(),
                sub.logicalPose().position().y(),
                sub.logicalPose().position().z());
        double minYOffsetFromPose = aabb.minY - subPos.y;
        double dstY = LANDING_Y - minYOffsetFromPose;
        return new Vec3(PLATFORM_CENTRE.getX() + 0.5, dstY, PLATFORM_CENTRE.getZ() + 0.5);
    }

    public static boolean ensurePlatform(ServerLevel endLevel) {
        boolean alreadyComplete = true;
        for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) {
            for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
                BlockPos p = PLATFORM_CENTRE.offset(dx, 0, dz);
                if (!endLevel.getBlockState(p).is(Blocks.OBSIDIAN)) {
                    alreadyComplete = false;
                    break;
                }
            }
            if (!alreadyComplete) break;
        }
        if (alreadyComplete) {
            AeroPortals.LOGGER.debug("[AeroPortals] End platform already present at {}", PLATFORM_CENTRE);
            return false;
        }

        int placed = 0;
        for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) {
            for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
                BlockPos p = PLATFORM_CENTRE.offset(dx, 0, dz);
                if (endLevel.getBlockState(p).is(Blocks.OBSIDIAN)) continue;
                endLevel.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), 3);
                placed++;
            }
        }
        AeroPortals.LOGGER.info("[AeroPortals] built End obsidian platform at {} ({} block(s) placed)",
                PLATFORM_CENTRE, placed);
        return placed > 0;
    }
}
