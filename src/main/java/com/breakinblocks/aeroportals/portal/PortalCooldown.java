package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.config.AeroPortalsConfig;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalCooldown {
    private static final ConcurrentHashMap<UUID, Long> LAST_TELEPORT_TICK = new ConcurrentHashMap<>();
    private static final Set<UUID> SUPPRESSED_UNTIL_LEFT_PORTAL = ConcurrentHashMap.newKeySet();

    private PortalCooldown() {}

    public static boolean isOnCooldown(UUID subLevelUuid, long currentTick) {
        Long last = LAST_TELEPORT_TICK.get(subLevelUuid);
        if (last == null) return false;
        return (currentTick - last) < AeroPortalsConfig.PORTAL_COOLDOWN_TICKS.get();
    }

    public static void mark(UUID subLevelUuid, long currentTick) {
        LAST_TELEPORT_TICK.put(subLevelUuid, currentTick);
    }

    public static void suppressUntilLeftPortal(UUID subLevelUuid) {
        SUPPRESSED_UNTIL_LEFT_PORTAL.add(subLevelUuid);
    }

    public static boolean isSuppressedUntilLeftPortal(UUID subLevelUuid) {
        return SUPPRESSED_UNTIL_LEFT_PORTAL.contains(subLevelUuid);
    }

    public static void clearSuppression(UUID subLevelUuid) {
        SUPPRESSED_UNTIL_LEFT_PORTAL.remove(subLevelUuid);
    }

    public static void clear() {
        LAST_TELEPORT_TICK.clear();
        SUPPRESSED_UNTIL_LEFT_PORTAL.clear();
    }
}
