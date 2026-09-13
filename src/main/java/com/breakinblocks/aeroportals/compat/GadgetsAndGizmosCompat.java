package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adapter for the linker tracker NBT/API shared by Gadgets & Gizmos 1.1.3 and 1.2.2. */
public final class GadgetsAndGizmosCompat implements TransferCarrier<CompoundTag> {
    private static final String TRACKER = "com.rieno.gadgetsandgizmos.content.ContraptionNetworkLinkerTracker";
    private static final String ORIGIN = "AeroPortalsOrigin";

    @Override
    public ResourceLocation id() {
        return AeroPortals.id("gadgets_and_gizmos_links");
    }

    @Override
    public boolean isEnabled() {
        return ModList.get().isLoaded("createthrusters");
    }

    @Override
    public void discard(ServerLevel level, ServerSubLevel sub) {
        // This tracker is global. Its record already belongs to the recovered target copy.
    }

    @Override
    public void validateGroup(ServerLevel src, List<ServerSubLevel> group, ServerLevel dst) {
        Set<UUID> owners = new HashSet<>();
        group.forEach(sub -> owners.add(sub.getUniqueId()));
        for (Tag entry : snapshot(src).getList("Linkers", Tag.TAG_COMPOUND)) {
            CompoundTag linker = (CompoundTag) entry;
            if (!src.dimension().location().toString().equals(linker.getString("Dimension"))) continue;
            List<CompoundTag> endpoints = endpoints(linker);
            boolean anyMoving = endpoints.stream().anyMatch(endpoint -> belongs(endpoint, owners));
            if (!anyMoving) continue;
            if (src != dst && endpoints.stream().anyMatch(endpoint -> !belongs(endpoint, owners))) {
                throw new IllegalStateException("Gadgets & Gizmos linker crosses the transfer group; move its controller and all targets together");
            }
            for (CompoundTag endpoint : endpoints) {
                if (endpoint.hasUUID("CurrentSubLevelId") && !endpoint.contains("CurrentLocalPos", Tag.TAG_LONG)) {
                    throw new IllegalStateException("Gadgets & Gizmos endpoint has no saved position");
                }
            }
        }
    }

    @Override
    public CompoundTag capture(ServerLevel level, ServerSubLevel sub) {
        ListTag linkers = new ListTag();
        for (Tag entry : snapshot(level).getList("Linkers", Tag.TAG_COMPOUND)) {
            CompoundTag linker = (CompoundTag) entry;
            if (!level.dimension().location().toString().equals(linker.getString("Dimension"))) continue;
            if (endpoints(linker).stream().noneMatch(endpoint -> belongs(endpoint, Set.of(sub.getUniqueId())))) continue;
            CompoundTag saved = linker.copy();
            for (CompoundTag endpoint : endpoints(saved)) {
                if (endpoint.hasUUID("CurrentSubLevelId")) {
                    SubLevel owner = owner(level, endpoint.getUUID("CurrentSubLevelId"));
                    endpoint.putLong(ORIGIN, origin(level, owner).asLong());
                }
            }
            linkers.add(saved);
        }
        if (linkers.isEmpty()) return null;
        CompoundTag payload = new CompoundTag();
        payload.put("Linkers", linkers);
        return payload;
    }

    @Override
    public void replay(ServerLevel level, ServerSubLevel sub, CompoundTag captured, BlockPos plotShift) {
        try {
            Access access = access();
            SavedData tracker = (SavedData) access.get().invoke(null, level.getServer());
            @SuppressWarnings("unchecked")
            Map<UUID, Object> records = (Map<UUID, Object>) access.records().get(tracker);
            for (Tag entry : captured.getList("Linkers", Tag.TAG_COMPOUND)) {
                CompoundTag linker = ((CompoundTag) entry).copy();
                linker.putString("Dimension", level.dimension().location().toString());
                for (CompoundTag endpoint : endpoints(linker)) {
                    if (!endpoint.hasUUID("CurrentSubLevelId")) continue;
                    SubLevel owner = owner(level, endpoint.getUUID("CurrentSubLevelId"));
                    BlockPos oldOrigin = BlockPos.of(endpoint.getLong(ORIGIN));
                    BlockPos pos = BlockPos.of(endpoint.getLong("CurrentLocalPos"))
                            .subtract(oldOrigin).offset(origin(level, owner));
                    endpoint.putLong("CurrentLocalPos", pos.asLong());
                    endpoint.putString("Dimension", level.dimension().location().toString());
                    Vector3d anchor = owner.logicalPose().transformPosition(new Vector3d(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    endpoint.putDouble("AnchorX", anchor.x);
                    endpoint.putDouble("AnchorY", anchor.y);
                    endpoint.putDouble("AnchorZ", anchor.z);
                    endpoint.remove(ORIGIN);
                }
                Object record = access.loadRecord().invoke(null, linker);
                if (record == null) throw new IllegalStateException("Gadgets & Gizmos rejected a saved linker");
                // Keep original node IDs/aliases. Reconciliation uses them to rewrite the authoritative
                // linker item/SQLite payload instead of treating the remapped targets as new links.
                for (Object target : (List<?>) access.targets().get(record)) {
                    access.writePoint().invoke(target, level);
                }
                records.put(linker.getUUID("LinkerId"), record);
                // Tracking point IDs belong to this global linker record. Keep each point only in
                // its current dimension, including when replay is undoing a partially completed move.
                for (Tag targetTag : linker.getList("Targets", Tag.TAG_COMPOUND)) {
                    CompoundTag target = (CompoundTag) targetTag;
                    if (!target.hasUUID("TrackingPointId")) continue;
                    UUID point = target.getUUID("TrackingPointId");
                    for (ServerLevel other : level.getServer().getAllLevels()) {
                        if (other == level) continue;
                        var data = SubLevelTrackingPointSavedData.getOrLoad(other);
                        if (data.getTrackingPoint(point) != null) data.removeTrackingPoint(point);
                    }
                }
            }
            if (access.invalidateIndex() != null) access.invalidateIndex().invoke(tracker);
            if (access.requestReconciliation() != null) access.requestReconciliation().invoke(tracker);
            tracker.setDirty();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not restore Gadgets & Gizmos links", e);
        }
    }

    @Override
    public CompoundTag serialize(CompoundTag payload) {
        return payload.copy();
    }

    @Override
    public CompoundTag deserialize(CompoundTag tag) {
        return tag.copy();
    }

    private static boolean belongs(CompoundTag endpoint, Set<UUID> owners) {
        return endpoint.hasUUID("CurrentSubLevelId") && owners.contains(endpoint.getUUID("CurrentSubLevelId"));
    }

    private static List<CompoundTag> endpoints(CompoundTag linker) {
        List<CompoundTag> result = new ArrayList<>();
        if (linker.contains("Controller", Tag.TAG_COMPOUND)) result.add(linker.getCompound("Controller"));
        for (Tag target : linker.getList("Targets", Tag.TAG_COMPOUND)) result.add((CompoundTag) target);
        return result;
    }

    private static SubLevel owner(ServerLevel level, UUID uuid) {
        var container = SubLevelContainer.getContainer(level);
        SubLevel owner = container == null ? null : container.getSubLevel(uuid);
        if (owner == null || owner.isRemoved()) {
            throw new IllegalStateException("Gadgets & Gizmos endpoint ship has not been restored: " + uuid);
        }
        return owner;
    }

    private static BlockPos origin(ServerLevel level, SubLevel sub) {
        int bits = SubLevelContainer.getContainer(level).getLogPlotSize() + 4;
        // Pose pivots include vertical section compaction without depending on content bounds,
        // which another carrier may expand while disassembling a connected ship's contraptions.
        return new BlockPos(sub.getPlot().plotPos.x << bits, (int) Math.floor(sub.logicalPose().rotationPoint().y()),
                sub.getPlot().plotPos.z << bits);
    }

    private static CompoundTag snapshot(ServerLevel level) {
        try {
            Access access = access();
            SavedData tracker = (SavedData) access.get().invoke(null, level.getServer());
            return tracker.save(new CompoundTag(), level.registryAccess());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Gadgets & Gizmos compatibility is unavailable; transfer cannot preserve its links", e);
        }
    }

    private static Access access() throws ReflectiveOperationException {
        Class<?> tracker = Class.forName(TRACKER);
        Class<?> record = Class.forName(TRACKER + "$LinkerRecord");
        Class<?> target = Class.forName(TRACKER + "$TargetRecord");
        Field records = tracker.getDeclaredField("trackedLinkers");
        Field targets = record.getDeclaredField("targets");
        Method load = record.getDeclaredMethod("load", CompoundTag.class);
        Method write = target.getDeclaredMethod("writeTrackingPoint", ServerLevel.class);
        records.setAccessible(true);
        targets.setAccessible(true);
        load.setAccessible(true);
        write.setAccessible(true);
        Method invalidate = null;
        Method reconcile = null;
        try {
            tracker.getDeclaredField("targetsByNodeId");
            invalidate = tracker.getDeclaredMethod("invalidateTargetIndex");
            invalidate.setAccessible(true);
            reconcile = tracker.getMethod("requestReconciliation");
        } catch (NoSuchFieldException legacyTracker) {
            // 1.1.3 has no secondary index and reconciles every server tick.
        }
        return new Access(tracker.getMethod("get", MinecraftServer.class), records, targets, load, write,
                invalidate, reconcile);
    }

    private record Access(Method get, Field records, Field targets, Method loadRecord, Method writePoint,
                          Method invalidateIndex, Method requestReconciliation) {}
}
