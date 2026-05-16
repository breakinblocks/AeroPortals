package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.config.AeroPortalsConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalCooldown {
    private static final ConcurrentHashMap<UUID, Long> LAST_TELEPORT_TICK = new ConcurrentHashMap<>();

    private PortalCooldown() {}

    public static boolean isOnCooldown(UUID subLevelUuid, long currentTick) {
        Long last = LAST_TELEPORT_TICK.get(subLevelUuid);
        if (last == null) return false;
        return (currentTick - last) < AeroPortalsConfig.PORTAL_COOLDOWN_TICKS.get();
    }

    public static void mark(UUID subLevelUuid, long currentTick) {
        LAST_TELEPORT_TICK.put(subLevelUuid, currentTick);
    }

    public static void clear() {
        LAST_TELEPORT_TICK.clear();
    }
}
