package com.breakinblocks.aeroportals.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record PortalDestination(ServerLevel level, Vec3 subWorldPos, boolean validateLanding, String label) {
    public PortalDestination {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(subWorldPos, "subWorldPos");
        if (label == null || label.isBlank()) {
            label = "addon";
        }
    }

    public static PortalDestination of(ServerLevel level, Vec3 subWorldPos, String label) {
        return new PortalDestination(level, subWorldPos, true, label);
    }

    public static PortalDestination of(ServerLevel level, Vec3 subWorldPos, boolean validateLanding, String label) {
        return new PortalDestination(level, subWorldPos, validateLanding, label);
    }
}
