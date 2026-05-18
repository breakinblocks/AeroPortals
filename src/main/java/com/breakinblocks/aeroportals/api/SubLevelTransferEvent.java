package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

import java.util.UUID;

public final class SubLevelTransferEvent extends Event {
    private final UUID subUuid;
    private final ServerSubLevel newSub;
    private final ServerLevel srcLevel;
    private final ServerLevel dstLevel;
    private final Vec3 translation;

    public SubLevelTransferEvent(UUID subUuid, ServerSubLevel newSub, ServerLevel srcLevel, ServerLevel dstLevel, Vec3 translation) {
        this.subUuid = subUuid;
        this.newSub = newSub;
        this.srcLevel = srcLevel;
        this.dstLevel = dstLevel;
        this.translation = translation;
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
}
