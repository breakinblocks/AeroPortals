package com.breakinblocks.aeroportals.portal;

import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Allows only the entity currently being transferred to bypass our portal cancellation. */
public final class TransferTravelScope {
    private static final ThreadLocal<Set<UUID>> ALLOWED = new ThreadLocal<>();

    private TransferTravelScope() {}

    public static boolean isAllowed(Entity entity) {
        Set<UUID> allowed = ALLOWED.get();
        return allowed != null && allowed.contains(entity.getUUID());
    }

    public static <T> T allow(Entity entity, Supplier<T> transfer) {
        Set<UUID> allowed = ALLOWED.get();
        if (allowed == null) {
            allowed = new HashSet<>();
            ALLOWED.set(allowed);
        }
        UUID id = entity.getUUID();
        boolean added = allowed.add(id);
        try {
            return transfer.get();
        } finally {
            if (added) allowed.remove(id);
            if (allowed.isEmpty()) ALLOWED.remove();
        }
    }
}
