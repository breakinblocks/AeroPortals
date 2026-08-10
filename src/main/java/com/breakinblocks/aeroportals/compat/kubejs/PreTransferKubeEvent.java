package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.api.SubLevelPreTransferEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PreTransferKubeEvent implements KubeEvent {
    private final SubLevelPreTransferEvent parent;

    public PreTransferKubeEvent(SubLevelPreTransferEvent parent) {
        this.parent = parent;
    }

    public ServerSubLevel getSub() {
        return this.parent.sub();
    }

    public String getSubId() {
        return this.parent.sub().getUniqueId().toString();
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

    public List<ServerSubLevel> getChain() {
        return this.parent.chain();
    }

    public int getChainSize() {
        return this.parent.chain().size();
    }

    public String getLabel() {
        return this.parent.label();
    }

    public Vec3 getDestination() {
        return this.parent.destination();
    }

    public void setDestination(Vec3 destination) {
        this.parent.setDestination(destination);
    }

    public void setDestination(double x, double y, double z) {
        this.parent.setDestination(new Vec3(x, y, z));
    }

    public void offsetDestination(double x, double y, double z) {
        this.parent.setDestination(this.parent.destination().add(x, y, z));
    }
}
