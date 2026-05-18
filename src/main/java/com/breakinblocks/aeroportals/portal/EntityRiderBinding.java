package com.breakinblocks.aeroportals.portal;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record EntityRiderBinding(UUID entityUuid, Vec3 localOffset, float yawDelta, float pitch) {}
