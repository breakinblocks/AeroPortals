package com.breakinblocks.aeroportals.api.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record NbtFixContext(UUID subUuid,
                            ResourceKey<Level> srcDimension,
                            ResourceKey<Level> dstDimension,
                            BlockPos plotShift,
                            Vec3 worldTranslation,
                            BlockPos srcRegionMin,
                            int srcRegionBlocks) {

    public static NbtFixContext simple(UUID subUuid, ResourceKey<Level> srcDimension,
                                       ResourceKey<Level> dstDimension, BlockPos plotShift) {
        return new NbtFixContext(subUuid, srcDimension, dstDimension, plotShift, Vec3.ZERO, null, 0);
    }

    public boolean dimensionChanged() {
        return !this.srcDimension.equals(this.dstDimension);
    }

    public boolean moved() {
        return this.plotShift.getX() != 0 || this.plotShift.getY() != 0 || this.plotShift.getZ() != 0;
    }

    public BlockPos shift(BlockPos pos) {
        return pos.offset(this.plotShift);
    }

    public boolean insideSourcePlot(BlockPos pos) {
        if (this.srcRegionMin == null || this.srcRegionBlocks <= 0) return true;
        int dx = pos.getX() - this.srcRegionMin.getX();
        int dz = pos.getZ() - this.srcRegionMin.getZ();
        return dx >= 0 && dx < this.srcRegionBlocks && dz >= 0 && dz < this.srcRegionBlocks;
    }

    public ResourceLocation srcDimensionId() {
        return this.srcDimension.location();
    }

    public ResourceLocation dstDimensionId() {
        return this.dstDimension.location();
    }
}
