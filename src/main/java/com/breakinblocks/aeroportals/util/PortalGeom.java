package com.breakinblocks.aeroportals.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.BlockUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.Optional;

public final class PortalGeom {
    private static final int MAX_PORTAL_DIM = 21;

    private PortalGeom() {}

    public static PortalRect measureFromBlock(Level level, BlockPos portalBlock) {
        return measureFromBlock(level, portalBlock, Blocks.NETHER_PORTAL);
    }

    public static PortalRect measureFromBlock(Level level, BlockPos portalBlock, Block portalBlockType) {
        BlockState state = level.getBlockState(portalBlock);
        if (!state.is(portalBlockType)) return null;
        EnumProperty<Direction.Axis> axisProp = portalBlockType == Blocks.NETHER_PORTAL
                ? NetherPortalBlock.AXIS
                : BlockStateProperties.HORIZONTAL_AXIS;
        if (!state.hasProperty(axisProp)) return null;
        Direction.Axis axis = state.getValue(axisProp);

        BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(
                portalBlock,
                axis, MAX_PORTAL_DIM,
                Direction.Axis.Y, MAX_PORTAL_DIM,
                pos -> level.getBlockState(pos).is(portalBlockType)
        );
        return new PortalRect(rect.minCorner.immutable(), axis, rect.axis1Size, rect.axis2Size);
    }

    public static Optional<PortalRect> findExistingPortal(ServerLevel level, BlockPos searchCenter) {
        boolean toNether = level.dimension() == Level.NETHER;
        Optional<BlockPos> portalPos = level.getPortalForcer()
                .findClosestPortalPosition(searchCenter, toNether, level.getWorldBorder());
        return portalPos.map(pos -> measureFromBlock(level, pos));
    }
}
