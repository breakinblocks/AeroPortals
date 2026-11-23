package com.breakinblocks.aeroportals.portal;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Captures the NBT passed to Sable storage during a journaled level save. */
public final class JournalSaveCapture {
    private static final ThreadLocal<JournalSaveCapture> ACTIVE = new ThreadLocal<>();

    private final SubLevelStorage storage;
    private final Set<UUID> members;
    private final Map<UUID, CompoundTag> snapshots = new HashMap<>();

    private JournalSaveCapture(SubLevelStorage storage, Set<UUID> members) {
        this.storage = storage;
        this.members = members;
    }

    static Map<UUID, CompoundTag> during(SubLevelStorage storage, Set<UUID> members, Runnable save) {
        JournalSaveCapture previous = ACTIVE.get();
        JournalSaveCapture capture = new JournalSaveCapture(storage, members);
        ACTIVE.set(capture);
        try {
            save.run();
            return capture.snapshots;
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    public static void record(SubLevelStorage storage, SubLevelData data) {
        JournalSaveCapture capture = ACTIVE.get();
        if (capture != null && capture.storage == storage && data != null && capture.members.contains(data.uuid())) {
            capture.snapshots.put(data.uuid(), data.fullTag().copy());
        }
    }
}
