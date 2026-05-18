package com.breakinblocks.aeroportals.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class PortalBuilder {
    private PortalBuilder() {}

    public static PortalRect build(ServerLevel level, BlockPos requestedMinCorner, Direction.Axis axis, int width, int height) {
        return build(level, requestedMinCorner, axis, width, height,
                Blocks.OBSIDIAN, Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis));
    }

    public static PortalRect build(
            ServerLevel level,
            BlockPos requestedMinCorner,
            Direction.Axis axis,
            int width,
            int height,
            Block frameBlock,
            BlockState portalState) {
        int safeMinY = level.getMinBuildHeight() + 5;
        int safeMaxY = level.getMaxBuildHeight() - height - 5;
        int y = Mth.clamp(requestedMinCorner.getY(), safeMinY, safeMaxY);
        BlockPos minCorner = new BlockPos(requestedMinCorner.getX(), y, requestedMinCorner.getZ());

        Direction horizontal = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        Direction perpendicular = horizontal.getClockWise();
        int hx = horizontal.getStepX();
        int hz = horizontal.getStepZ();
        int px = perpendicular.getStepX();
        int pz = perpendicular.getStepZ();

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState frame = frameBlock.defaultBlockState();
        BlockState portal = portalState;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = -1; i <= width; i++) {
            for (int j = -1; j <= height; j++) {
                for (int p = -1; p <= 1; p++) {
                    cursor.set(
                            minCorner.getX() + hx * i + px * p,
                            minCorner.getY() + j,
                            minCorner.getZ() + hz * i + pz * p
                    );
                    BlockState existing = level.getBlockState(cursor);
                    if (existing.is(Blocks.BEDROCK)) continue;
                    if (existing.isAir()) continue;
                    level.setBlock(cursor, air, 3);
                }
            }
        }

        for (int i = -1; i <= width; i++) {
            for (int j = -1; j <= height; j++) {
                if (i != -1 && i != width && j != -1 && j != height) continue;
                cursor.set(
                        minCorner.getX() + hx * i,
                        minCorner.getY() + j,
                        minCorner.getZ() + hz * i
                );
                level.setBlock(cursor, frame, 3);
            }
        }

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                cursor.set(
                        minCorner.getX() + hx * i,
                        minCorner.getY() + j,
                        minCorner.getZ() + hz * i
                );
                level.setBlock(cursor, portal, 18);
            }
        }

        return new PortalRect(minCorner, axis, width, height);
    }
}
