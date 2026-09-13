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

    public record Plan(PortalRect rect, Block frame, BlockState portal) {
        public boolean canBuild(ServerLevel level) {
            Direction horizontal = Direction.get(Direction.AxisDirection.POSITIVE, rect.axis());
            Direction perpendicular = horizontal.getClockWise();
            for (int i = -1; i <= rect.width(); i++) {
                for (int j = -1; j <= rect.height(); j++) {
                    for (int p = -1; p <= 1; p++) {
                        BlockPos pos = rect.minCorner().relative(horizontal, i).above(j).relative(perpendicular, p);
                        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
                        BlockState existing = level.getBlockState(pos);
                        if (existing.getDestroySpeed(level, pos) >= 0) continue;
                        BlockState expected = p != 0 ? Blocks.AIR.defaultBlockState()
                                : i == -1 || i == rect.width() || j == -1 || j == rect.height()
                                ? frame.defaultBlockState() : portal;
                        if (!existing.equals(expected)) return false;
                    }
                }
            }
            return true;
        }

        public PortalRect build(ServerLevel level) {
            return PortalBuilder.build(level, rect.minCorner(), rect.axis(), rect.width(), rect.height(), frame, portal);
        }
    }

    public static Plan plan(ServerLevel level, BlockPos requested, Direction.Axis axis, int width, int height) {
        return plan(level, requested, axis, width, height, Blocks.OBSIDIAN,
                Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis));
    }

    public static Plan plan(ServerLevel level, BlockPos requested, Direction.Axis axis, int width, int height,
                            Block frame, BlockState portal) {
        int y = Mth.clamp(requested.getY(), level.getMinBuildHeight() + 5, level.getMaxBuildHeight() - height - 5);
        return new Plan(new PortalRect(new BlockPos(requested.getX(), y, requested.getZ()), axis, width, height), frame, portal);
    }

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

        // Recheck immediately before mutation; planning itself never changes the destination.
        if (!new Plan(new PortalRect(minCorner, axis, width, height), frameBlock, portalState).canBuild(level)) return null;

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
                    if (existing.getDestroySpeed(level, cursor) < 0) continue;
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
