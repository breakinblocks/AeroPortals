package com.breakinblocks.aeroportals.portal;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record RiderBinding(UUID playerUuid, Vec3 localOffset, float yawDelta, float pitch) {}
