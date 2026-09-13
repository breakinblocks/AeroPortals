package com.breakinblocks.aeroportals.api;

import net.minecraft.server.level.ServerLevel;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record PortalDestination(ServerLevel level, Vec3 subWorldPos, boolean validateLanding, String label,
                                PortalBuilder.Plan construction, Runnable arrivalAction) {
    public PortalDestination(ServerLevel level, Vec3 position, boolean validateLanding, String label) {
        this(level, position, validateLanding, label, null, null);
    }

    public PortalDestination withConstruction(PortalBuilder.Plan plan) {
        return new PortalDestination(level, subWorldPos, validateLanding, label, plan, arrivalAction);
    }

    public PortalDestination withArrivalAction(Runnable action) {
        return new PortalDestination(level, subWorldPos, validateLanding, label, construction, action);
    }

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
