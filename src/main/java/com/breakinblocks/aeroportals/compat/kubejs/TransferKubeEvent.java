package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public class TransferKubeEvent implements KubeEvent {
    private final SubLevelTransferEvent parent;

    public TransferKubeEvent(SubLevelTransferEvent parent) {
        this.parent = parent;
    }

    public ServerSubLevel getSub() {
        return this.parent.newSub();
    }

    public String getSubId() {
        return this.parent.subUuid().toString();
    }

    public ServerLevel getSrcLevel() {
        return this.parent.srcLevel();
    }

    public ServerLevel getDstLevel() {
        return this.parent.dstLevel();
    }

    public String getSrcDimension() {
        return this.parent.srcLevel().dimension().location().toString();
    }

    public String getDstDimension() {
        return this.parent.dstLevel().dimension().location().toString();
    }

    public Vec3 getTranslation() {
        return this.parent.translation();
    }

    public BlockPos getPlotShift() {
        return this.parent.plotShift();
    }

    public BlockPos remapPlotPos(BlockPos pos) {
        return this.parent.remapPlotPos(pos);
    }
}
