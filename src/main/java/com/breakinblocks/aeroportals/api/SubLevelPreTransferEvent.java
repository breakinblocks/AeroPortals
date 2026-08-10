package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.List;
import java.util.Objects;

public final class SubLevelPreTransferEvent extends Event implements ICancellableEvent {
    private final ServerSubLevel sub;
    private final ServerLevel srcLevel;
    private final ServerLevel dstLevel;
    private final List<ServerSubLevel> chain;
    private final String label;
    private final Vec3 originalDestination;
    private Vec3 destination;
    private String cancelReason;

    public SubLevelPreTransferEvent(ServerSubLevel sub, ServerLevel srcLevel, ServerLevel dstLevel,
                                    List<ServerSubLevel> chain, Vec3 destination, String label) {
        this.sub = sub;
        this.srcLevel = srcLevel;
        this.dstLevel = dstLevel;
        this.chain = List.copyOf(chain);
        this.originalDestination = destination;
        this.destination = destination;
        this.label = label;
    }

    public ServerSubLevel sub() {
        return this.sub;
    }

    public ServerLevel srcLevel() {
        return this.srcLevel;
    }

    public ServerLevel dstLevel() {
        return this.dstLevel;
    }

    public List<ServerSubLevel> chain() {
        return this.chain;
    }

    public String label() {
        return this.label;
    }

    public Vec3 originalDestination() {
        return this.originalDestination;
    }

    public Vec3 destination() {
        return this.destination;
    }

    public void setDestination(Vec3 destination) {
        this.destination = Objects.requireNonNull(destination, "destination");
    }

    public String cancelReason() {
        return this.cancelReason;
    }

    public void cancel(String reason) {
        this.cancelReason = reason;
        setCanceled(true);
    }
}
