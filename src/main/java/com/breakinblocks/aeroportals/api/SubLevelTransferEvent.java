package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

import java.util.List;
import java.util.UUID;

public final class SubLevelTransferEvent extends Event {
    private final UUID subUuid;
    private final ServerSubLevel newSub;
    private final ServerLevel srcLevel;
    private final ServerLevel dstLevel;
    private final Vec3 translation;
    private final BlockPos plotShift;
    private final List<PlotMove> chainPlotMoves;

    public SubLevelTransferEvent(UUID subUuid, ServerSubLevel newSub, ServerLevel srcLevel, ServerLevel dstLevel,
                                 Vec3 translation, BlockPos plotShift, List<PlotMove> chainPlotMoves) {
        this.subUuid = subUuid;
        this.newSub = newSub;
        this.srcLevel = srcLevel;
        this.dstLevel = dstLevel;
        this.translation = translation;
        this.plotShift = plotShift;
        this.chainPlotMoves = List.copyOf(chainPlotMoves);
    }

    public UUID subUuid() {
        return this.subUuid;
    }

    public ServerSubLevel newSub() {
        return this.newSub;
    }

    public ServerLevel srcLevel() {
        return this.srcLevel;
    }

    public ServerLevel dstLevel() {
        return this.dstLevel;
    }

    public Vec3 translation() {
        return this.translation;
    }

    public BlockPos plotShift() {
        return this.plotShift;
    }

    public List<PlotMove> chainPlotMoves() {
        return this.chainPlotMoves;
    }

    public BlockPos remapPlotPos(BlockPos oldPos) {
        if (oldPos == null) return null;
        for (PlotMove move : this.chainPlotMoves) {
            if (move.containedOldPos(oldPos)) {
                return oldPos.offset(move.shift());
            }
        }
        return oldPos;
    }

    public record PlotMove(UUID uuid, BlockPos oldRegionMin, int regionBlocks, BlockPos shift) {
        public boolean containedOldPos(BlockPos pos) {
            int dx = pos.getX() - this.oldRegionMin.getX();
            int dz = pos.getZ() - this.oldRegionMin.getZ();
            return dx >= 0 && dx < this.regionBlocks && dz >= 0 && dz < this.regionBlocks;
        }
    }
}
