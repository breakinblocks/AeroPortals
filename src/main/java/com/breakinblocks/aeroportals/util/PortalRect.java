package com.breakinblocks.aeroportals.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public record PortalRect(BlockPos minCorner, Direction.Axis axis, int width, int height) {
    public Vec3 centerWorld() {
        double cx = minCorner.getX() + 0.5;
        double cy = minCorner.getY() + height / 2.0;
        double cz = minCorner.getZ() + 0.5;
        if (axis == Direction.Axis.X) {
            cx = minCorner.getX() + width / 2.0;
        } else {
            cz = minCorner.getZ() + width / 2.0;
        }
        return new Vec3(cx, cy, cz);
    }
}
