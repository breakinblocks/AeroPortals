package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalCooldown {
    private static final double REARM_DISTANCE_SQR = 8.0 * 8.0;

    private static final ConcurrentHashMap<UUID, Long> LAST_TELEPORT_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Vec3> ARRIVAL_POS = new ConcurrentHashMap<>();

    private PortalCooldown() {}

    public static boolean isOnCooldown(UUID subLevelUuid, long currentTick) {
        Long last = LAST_TELEPORT_TICK.get(subLevelUuid);
        if (last == null) return false;
        return (currentTick - last) < AeroPortalsConfig.PORTAL_COOLDOWN_TICKS.get();
    }

    public static void mark(UUID subLevelUuid, long currentTick) {
        LAST_TELEPORT_TICK.put(subLevelUuid, currentTick);
    }

    public static void suppressUntilLeftPortal(UUID subLevelUuid, Vec3 arrivalPos) {
        ARRIVAL_POS.put(subLevelUuid, arrivalPos);
    }

    public static boolean isSuppressedUntilLeftPortal(UUID subLevelUuid) {
        return ARRIVAL_POS.containsKey(subLevelUuid);
    }

    public static void noteAwayFromPortal(UUID subLevelUuid, Vec3 currentPos) {
        Vec3 arrival = ARRIVAL_POS.get(subLevelUuid);
        if (arrival != null && currentPos.distanceToSqr(arrival) > REARM_DISTANCE_SQR) {
            ARRIVAL_POS.remove(subLevelUuid);
        }
    }

    public static void clear() {
        LAST_TELEPORT_TICK.clear();
        ARRIVAL_POS.clear();
    }
}
