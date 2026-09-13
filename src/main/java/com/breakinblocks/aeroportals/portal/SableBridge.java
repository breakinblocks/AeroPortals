package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import com.breakinblocks.aeroportals.api.nbt.NbtFixContext;
import com.breakinblocks.aeroportals.compat.CreateContraptionCompat;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SableBridge {
    private SableBridge() {}

    public record Moved(ServerSubLevel sub, BlockPos shift, BlockPos oldRegionMin, int regionBlocks) {}

    public record SourceInfo(ResourceKey<Level> dimension, int minBuildHeight, Vec3 worldTranslation,
                             BlockPos regionMin, int regionBlocks) {
        public static SourceInfo of(ResourceKey<Level> dimension, int minBuildHeight) {
            return new SourceInfo(dimension, minBuildHeight, Vec3.ZERO, null, 0);
        }
    }

    public record Request(ServerSubLevel sub, Vec3 destination) {}

    private record Prepared(Request request, CompoundTag source, SubLevelData destination,
                            SourceInfo sourceInfo, Map<TransferCarrier<?>, Object> carriers,
                            CreateContraptionCompat.KineticSnapshot kinetics, CompoundTag metadata) {}

    public static Moved moveAcrossDimensions(ServerSubLevel sub, ServerLevel source, ServerLevel destination, Vec3 position) {
        Map<UUID, Moved> moved = moveGroup(source, destination, List.of(new Request(sub, position)));
        if (moved.isEmpty()) return null;
        TeleportJournal.completeGroup(source.getServer(), moved.keySet(), source, destination);
        return moved.get(sub.getUniqueId());
    }

    public static boolean canMoveGroup(ServerLevel source, ServerLevel destination, List<ServerSubLevel> group) {
        ServerSubLevelContainer src = SubLevelContainer.getContainer(source);
        ServerSubLevelContainer dst = SubLevelContainer.getContainer(destination);
        if (src == null || dst == null || group.isEmpty()) return false;
        if (group.stream().map(ServerSubLevel::getUniqueId).distinct().count() != group.size()) return false;
        int capacity = 1 << (dst.getLogSideLength() * 2);
        int available = capacity - dst.getOccupancy().cardinality() + (src == dst ? group.size() : 0);
        if (available < group.size()) return false;
        for (ServerSubLevel sub : group) {
            if (sub.isRemoved() || src.getSubLevel(sub.getUniqueId()) != sub) return false;
            BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
            if (bounds != BoundingBox3i.EMPTY && source.getSectionIndex(bounds.maxY())
                    - source.getSectionIndex(bounds.minY()) + 1 > destination.getSectionsCount()) return false;
        }
        try {
            for (TransferCarrier<?> carrier : AeroPortalsApi.carriers()) carrier.validateGroup(source, group, destination);
        } catch (RuntimeException ex) {
            AeroPortals.LOGGER.warn("[AeroPortals] transfer group rejected: {}", ex.getMessage());
            return false;
        }
        return true;
    }

    /** The server thread reserves the whole group by completing preflight before any mutation. */
    public static Map<UUID, Moved> moveGroup(ServerLevel source, ServerLevel destination, List<Request> requests) {
        List<ServerSubLevel> group = requests.stream().map(Request::sub).toList();
        if (!canMoveGroup(source, destination, group)) return Map.of();
        ServerSubLevelContainer src = SubLevelContainer.getContainer(source);
        ServerSubLevelContainer dst = SubLevelContainer.getContainer(destination);
        List<UUID> ids = group.stream().map(ServerSubLevel::getUniqueId).toList();
        List<Prepared> prepared = new ArrayList<>();
        Map<UUID, Moved> moved = new LinkedHashMap<>();
        Set<UUID> journalsWritten = new java.util.HashSet<>();
        boolean removed = false;
        boolean preparationRollbackComplete = true;
        try {
            for (Request request : requests) {
                ServerSubLevel sub = request.sub();
                int regionBits = src.getLogPlotSize() + 4;
                BlockPos region = new BlockPos(sub.getPlot().plotPos.x << regionBits,
                        source.getMinBuildHeight(), sub.getPlot().plotPos.z << regionBits);
                Map<TransferCarrier<?>, Object> carried = captureCarriers(source, sub);
                try {
                    CreateContraptionCompat.KineticSnapshot kinetics = CreateContraptionCompat.collectKinetics(source, sub);
                    SubLevelData data = SubLevelSerializer.toData(sub, ids);
                    CompoundTag tag = data.fullTag();
                    stripKineticState(tag.getCompound("plot"), kinetics.kineticPositions());
                    CompoundTag original = tag.copy();
                    Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
                    Vec3 translation = request.destination().subtract(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()));
                    pose.position().set(request.destination().x, request.destination().y, request.destination().z);
                    tag.put("pose", SableNBTUtils.writePose3d(pose));
                    if (tag.contains("world_bounds")) {
                        BoundingBox3d bounds = SableNBTUtils.readBoundingBox(tag.getCompound("world_bounds"));
                        bounds.move(translation.x, translation.y, translation.z);
                        tag.put("world_bounds", SableNBTUtils.writeBoundingBox(bounds));
                    }
                    CompoundTag metadata = new CompoundTag();
                    metadata.put("source_snapshot", original.copy());
                    metadata.put("carriers", serializeCarriers(carried));
                    metadata.putLong("old_region_min", region.asLong());
                    metadata.putInt("region_blocks", 1 << regionBits);
                    metadata.putLongArray("generator_positions", kinetics.generatorPositions().stream().mapToLong(Long::longValue).toArray());
                    ListTag worldTranslation = new ListTag();
                    worldTranslation.add(DoubleTag.valueOf(translation.x));
                    worldTranslation.add(DoubleTag.valueOf(translation.y));
                    worldTranslation.add(DoubleTag.valueOf(translation.z));
                    metadata.put("world_translation", worldTranslation);
                    metadata.putUUID("group_id", ids.getFirst());
                    ListTag members = new ListTag();
                    for (UUID id : ids) { CompoundTag member = new CompoundTag(); member.putUUID("uuid", id); members.add(member); }
                    metadata.put("group_members", members);
                    prepared.add(new Prepared(request, original, data,
                            new SourceInfo(source.dimension(), source.getMinBuildHeight(), translation, region, 1 << regionBits),
                            carried, kinetics, metadata));
                } catch (RuntimeException ex) {
                    preparationRollbackComplete = replayCarriersBestEffort(source, sub, carried, BlockPos.ZERO);
                    throw ex;
                }
            }
            for (Prepared item : prepared) {
                UUID uuid = item.request().sub().getUniqueId();
                if (!TeleportJournal.write(source.getServer(), uuid,
                        source.dimension().location(), destination.dimension().location(), source.getMinBuildHeight(),
                        item.destination(), item.metadata())) throw new IllegalStateException("Recovery journal could not be written");
                journalsWritten.add(uuid);
            }
            removed = true;
            for (ServerSubLevel sub : group) src.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            for (Prepared item : prepared) {
                Loaded loaded = reloadInDestination(item.sourceInfo(), destination, dst, item.destination());
                if (loaded == null) throw new IllegalStateException("Destination rejected " + item.destination().uuid());
                moved.put(item.destination().uuid(), new Moved(loaded.sub(), loaded.shift(), item.sourceInfo().regionMin(), item.sourceInfo().regionBlocks()));
            }
            // Every endpoint exists before any carrier reconnects a multi-ship network.
            for (Prepared item : prepared) {
                Moved result = moved.get(item.destination().uuid());
                replayCarriers(destination, result.sub(), item.carriers(), result.shift());
                CreateContraptionCompat.reactivateGenerators(destination, item.kinetics().generatorPositions(), result.shift());
            }
            return moved;
        } catch (RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] group transfer failed; restoring all source ships", ex);
            boolean rollbackComplete = preparationRollbackComplete
                    && (!(ex instanceof CarrierCaptureException captureFailure) || captureFailure.restored);
            if (!removed) {
                for (Prepared item : prepared) {
                    rollbackComplete &= replayCarriersBestEffort(source, item.request().sub(), item.carriers(), BlockPos.ZERO);
                }
                if (rollbackComplete) {
                    // A refused write can mean this ship has an older recovery transaction.
                    // Only this attempt's successful writes belong to its cancellation cleanup.
                    for (UUID uuid : journalsWritten) TeleportJournal.delete(source.getServer(), uuid);
                } else {
                    AeroPortals.LOGGER.error("[AeroPortals] source carrier rollback incomplete; retaining available group journals for recovery");
                }
                return Map.of();
            }
            List<ServerSubLevel> partialDestination = new ArrayList<>();
            for (UUID id : ids) {
                ServerSubLevel loaded = (ServerSubLevel) dst.getSubLevel(id);
                if (loaded != null && !loaded.isRemoved() && loaded != group.stream().filter(g -> g.getUniqueId().equals(id)).findFirst().orElse(null)) {
                    partialDestination.add(loaded);
                }
            }
            // Networks may refer to every member. Discard replayed entities/glue while all
            // destination endpoints still exist, then remove every partial load independently.
            for (ServerSubLevel loaded : partialDestination) {
                rollbackComplete &= discardCarriersBestEffort(destination, loaded);
            }
            for (ServerSubLevel loaded : partialDestination) {
                try {
                    dst.removeSubLevel(loaded, SubLevelRemovalReason.REMOVED);
                } catch (RuntimeException cleanupError) {
                    rollbackComplete = false;
                    AeroPortals.LOGGER.error("[AeroPortals] could not remove partial destination ship {}; continuing group rollback",
                            loaded.getUniqueId(), cleanupError);
                }
            }
            Map<UUID, Loaded> restored = new LinkedHashMap<>();
            for (Prepared item : prepared) {
                ServerSubLevel original = item.request().sub();
                if (!original.isRemoved() && src.getSubLevel(original.getUniqueId()) == original) {
                    restored.put(original.getUniqueId(), new Loaded(original, BlockPos.ZERO));
                    continue;
                }
                try {
                    if (src.getSubLevel(original.getUniqueId()) != null) {
                        throw new IllegalStateException("A partial destination with this UUID still occupies the source container");
                    }
                    Loaded result = reloadInDestination(new SourceInfo(source.dimension(), source.getMinBuildHeight(), Vec3.ZERO,
                            item.sourceInfo().regionMin(), item.sourceInfo().regionBlocks()), source, src,
                            SubLevelSerializer.fromData(item.source().copy()));
                    if (result == null) throw new IllegalStateException("Source rejected the saved ship");
                    restored.put(original.getUniqueId(), result);
                } catch (RuntimeException restoreError) {
                    rollbackComplete = false;
                    AeroPortals.LOGGER.error("[AeroPortals] could not restore source ship {}; continuing other group members",
                            original.getUniqueId(), restoreError);
                }
            }
            for (Prepared item : prepared) {
                Loaded result = restored.get(item.destination().uuid());
                if (result == null) continue;
                rollbackComplete &= replayCarriersBestEffort(source, result.sub(), item.carriers(), result.shift());
                try {
                    CreateContraptionCompat.reactivateGenerators(source, item.kinetics().generatorPositions(), result.shift());
                } catch (RuntimeException kineticError) {
                    rollbackComplete = false;
                    AeroPortals.LOGGER.error("[AeroPortals] could not reactivate restored generators for {}", result.sub().getUniqueId(), kineticError);
                }
            }
            if (rollbackComplete) {
                try {
                    TeleportJournal.rollbackGroup(source.getServer(), ids, source, destination);
                } catch (RuntimeException saveError) {
                    AeroPortals.LOGGER.error("[AeroPortals] could not durably finish group rollback; retaining journals", saveError);
                }
            } else {
                AeroPortals.LOGGER.error("[AeroPortals] group rollback incomplete; retaining all journals for recovery");
            }
            return Map.of();
        }
    }

    private static CompoundTag serializeCarriers(Map<TransferCarrier<?>, Object> carried) {
        CompoundTag tags = new CompoundTag();
        carried.forEach((carrier, value) -> tags.put(carrier.id().toString(), serializeCarrier(carrier, value)));
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static <T> CompoundTag serializeCarrier(TransferCarrier<T> carrier, Object value) {
        return carrier.serialize((T) value);
    }

    public static boolean replayJournalCarriers(ServerLevel level, ServerSubLevel sub, CompoundTag metadata, BlockPos shift) {
        CompoundTag tags = metadata.getCompound("carriers");
        Map<TransferCarrier<?>, Object> carried = new LinkedHashMap<>();
        try {
            for (String id : tags.getAllKeys()) {
                TransferCarrier<?> carrier = AeroPortalsApi.carriers().stream()
                        .filter(candidate -> candidate.id().toString().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Missing recovery carrier " + id));
                carried.put(carrier, carrier.deserialize(tags.getCompound(id)));
            }
            replayCarriers(level, sub, carried, shift);
            Set<Long> generators = new java.util.HashSet<>();
            for (long position : metadata.getLongArray("generator_positions")) generators.add(position);
            CreateContraptionCompat.reactivateGenerators(level, generators, shift);
            return true;
        } catch (RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] carrier recovery failed for {}", sub.getUniqueId(), ex);
            return false;
        }
    }

    private static Map<TransferCarrier<?>, Object> captureCarriers(ServerLevel srcLevel, ServerSubLevel sub) {
        List<TransferCarrier<?>> carriers = AeroPortalsApi.carriers();
        if (carriers.isEmpty()) return Map.of();
        Map<TransferCarrier<?>, Object> captured = new LinkedHashMap<>(carriers.size());
        for (TransferCarrier<?> carrier : carriers) {
            try {
                Object value = carrier.capture(srcLevel, sub);
                if (value != null) captured.put(carrier, value);
            } catch (RuntimeException e) {
                boolean restored = replayCarriersBestEffort(srcLevel, sub, captured, BlockPos.ZERO);
                throw new CarrierCaptureException(carrier.id().toString(), e, restored);
            }
        }
        return captured;
    }

    private static final class CarrierCaptureException extends IllegalStateException {
        private final boolean restored;

        private CarrierCaptureException(String carrier, RuntimeException cause, boolean restored) {
            super("Transfer carrier capture failed: " + carrier, cause);
            this.restored = restored;
        }
    }

    private static boolean discardCarriersBestEffort(ServerLevel level, ServerSubLevel sub) {
        boolean complete = true;
        for (TransferCarrier<?> carrier : AeroPortalsApi.carriers()) {
            try {
                carrier.discard(level, sub);
            } catch (RuntimeException ex) {
                complete = false;
                AeroPortals.LOGGER.error("[AeroPortals] could not discard destination carrier {} for {}; continuing cleanup",
                        carrier.id(), sub.getUniqueId(), ex);
            }
        }
        return complete;
    }

    private static boolean replayCarriersBestEffort(ServerLevel level, ServerSubLevel sub,
                                                   Map<TransferCarrier<?>, Object> captured, BlockPos shift) {
        boolean complete = true;
        List<Map.Entry<TransferCarrier<?>, Object>> entries = new ArrayList<>(captured.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<TransferCarrier<?>, Object> entry : entries) {
            try {
                replayOne(entry.getKey(), level, sub, entry.getValue(), shift);
            } catch (RuntimeException ex) {
                complete = false;
                AeroPortals.LOGGER.error("[AeroPortals] could not replay source carrier {} for {}; continuing rollback",
                        entry.getKey().id(), sub.getUniqueId(), ex);
            }
        }
        return complete;
    }

    private static void replayCarriers(ServerLevel level, ServerSubLevel newSub, Map<TransferCarrier<?>, Object> captured, BlockPos shift) {
        if (captured.isEmpty()) return;
        List<Map.Entry<TransferCarrier<?>, Object>> entries = new ArrayList<>(captured.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<TransferCarrier<?>, Object> entry : entries) {
            try {
                replayOne(entry.getKey(), level, newSub, entry.getValue(), shift);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Transfer carrier replay failed: " + entry.getKey().id(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void replayOne(TransferCarrier<T> carrier, ServerLevel level, ServerSubLevel newSub, Object captured, BlockPos shift) {
        carrier.replay(level, newSub, (T) captured, shift);
    }

    public record Loaded(ServerSubLevel sub, BlockPos shift) {}

    public static Loaded reloadInDestination(SourceInfo source, ServerLevel dstLevel,
                                             ServerSubLevelContainer dstContainer, SubLevelData data) {
        Loaded loaded = tryLoad(source, dstLevel, dstContainer, data);
        if (loaded != null) {
            rebuildPhysicsData(dstLevel, loaded.sub(), dstContainer);
        }
        return loaded;
    }

    // Without this replay the MassTracker stays empty after fullyLoad and the sub is auto-removed next tick.
    private static void rebuildPhysicsData(ServerLevel level, ServerSubLevel sub, ServerSubLevelContainer dstContainer) {
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        if (bounds == BoundingBox3i.EMPTY) {
            AeroPortals.LOGGER.warn("[AeroPortals] SableBridge: plot bounds empty after fullyLoad for sub {}; physics rebuild skipped", sub.getUniqueId());
            return;
        }

        SubLevelPhysicsSystem physics = dstContainer.physicsSystem();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int blocksProcessed = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    LevelChunk chunk = level.getChunkAt(cursor);
                    int sectionIdx = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(sectionIdx);
                    SectionPos sectionPos = SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(sectionIdx));
                    int localX = x & SectionPos.SECTION_MASK;
                    int localZ = z & SectionPos.SECTION_MASK;
                    physics.handleBlockChange(sectionPos, section, localX, y & 15, localZ, air, state);
                    blocksProcessed++;
                }
            }
        }
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: rebuilt physics data for sub {} ({} blocks); mass.isInvalid={} mass.value={}",
                sub.getUniqueId(), blocksProcessed,
                sub.getMassTracker().isInvalid(), sub.getMassTracker().getMass());
    }

    private static Loaded tryLoad(SourceInfo source, ServerLevel dstLevel,
                                  ServerSubLevelContainer dstContainer, SubLevelData data) {
        CompoundTag tag = data.fullTag();
        CompoundTag plotTag = tag.getCompound("plot");
        int origPlotX = plotTag.getInt("plot_x");
        int origPlotZ = plotTag.getInt("plot_z");

        int dstSectionCount = dstLevel.getSectionsCount();
        int sectionShift = 0;
        int[] span = sectionSpan(plotTag);
        if (span != null) {
            int spanSections = span[1] - span[0] + 1;
            if (spanSections > dstSectionCount) {
                AeroPortals.LOGGER.error("[AeroPortals] SableBridge: sub {} spans {} chunk sections but destination {} only has {}; aborting move so it can stay where it is",
                        data.uuid(), spanSections, dstLevel.dimension().location(), dstSectionCount);
                return null;
            }
            sectionShift = Math.max(0, span[1] - (dstSectionCount - 1));
        }

        int[] plot = findFreePlot(dstContainer, origPlotX, origPlotZ);
        if (plot == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination container has no free plot; aborting load for sub {}", data.uuid());
            return null;
        }

        int shift = dstContainer.getLogPlotSize() + 4;
        int deltaX = (plot[0] - origPlotX) << shift;
        int deltaZ = (plot[1] - origPlotZ) << shift;
        int deltaY = dstLevel.getMinBuildHeight() - source.minBuildHeight() - (sectionShift << 4);

        if (deltaX != 0 || deltaZ != 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: original plot {},{} occupied in destination; relocating sub {} to free plot {},{} (block shift {},{})",
                    origPlotX, origPlotZ, data.uuid(), plot[0], plot[1], deltaX, deltaZ);
            plotTag.putInt("plot_x", plot[0]);
            plotTag.putInt("plot_z", plot[1]);
        }
        if (sectionShift > 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: sub {} sits {} section(s) above destination {} height; shifting plot content down to fit",
                    data.uuid(), sectionShift, dstLevel.dimension().location());
            shiftSectionKeys(plotTag, sectionShift);
        }
        if (deltaX != 0 || deltaY != 0 || deltaZ != 0) {
            if (deltaY != 0) {
                AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: plot content Y shift for sub {} is {} (min-height delta {}, section shift {})",
                        data.uuid(), deltaY, dstLevel.getMinBuildHeight() - source.minBuildHeight(), sectionShift);
                stripHeightmaps(plotTag);
            }
            offsetPlotCoordinates(plotTag, deltaX, deltaY, deltaZ);
            offsetPoseRotationPoint(tag, deltaX, deltaY, deltaZ);
        }

        applyNbtFixers(plotTag, new NbtFixContext(
                data.uuid(), source.dimension(), dstLevel.dimension(), new BlockPos(deltaX, deltaY, deltaZ),
                source.worldTranslation(), source.regionMin(), source.regionBlocks()));

        ServerSubLevel loaded = SubLevelSerializer.fullyLoad(dstLevel, data);
        if (loaded == null) return null;
        return new Loaded(loaded, new BlockPos(deltaX, deltaY, deltaZ));
    }

    private static void offsetPoseRotationPoint(CompoundTag tag, int deltaX, int deltaY, int deltaZ) {
        Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
        pose.rotationPoint().add(deltaX, deltaY, deltaZ);
        tag.put("pose", SableNBTUtils.writePose3d(pose));
    }

    private static void applyNbtFixers(CompoundTag plotTag, NbtFixContext context) {
        if (!AeroPortalsApi.hasNbtFixers()) return;
        if (!context.moved() && !context.dimensionChanged()) return;
        int visited = 0;
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            ListTag blockEntities = chunks.getCompound(key).getList("block_entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < blockEntities.size(); i++) {
                AeroPortalsApi.applyNbtFixers(blockEntities.getCompound(i), context);
                visited++;
            }
        }
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: ran NBT fixers over {} block entit(ies) for sub {} (shift {}, {} -> {})",
                visited, context.subUuid(), context.plotShift(),
                context.srcDimensionId(), context.dstDimensionId());
    }

    private static int[] sectionSpan(CompoundTag plotTag) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            CompoundTag sections = chunks.getCompound(key).getCompound("sections");
            for (String sectionKey : sections.getAllKeys()) {
                int idx;
                try {
                    idx = Integer.parseInt(sectionKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                min = Math.min(min, idx);
                max = Math.max(max, idx);
            }
        }
        return min == Integer.MAX_VALUE ? null : new int[]{min, max};
    }

    private static void shiftSectionKeys(CompoundTag plotTag, int sectionShift) {
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            CompoundTag chunkTag = chunks.getCompound(key);
            CompoundTag sections = chunkTag.getCompound("sections");
            CompoundTag renumbered = new CompoundTag();
            for (String sectionKey : sections.getAllKeys()) {
                int idx;
                try {
                    idx = Integer.parseInt(sectionKey);
                } catch (NumberFormatException e) {
                    renumbered.put(sectionKey, sections.get(sectionKey));
                    continue;
                }
                renumbered.put(String.valueOf(idx - sectionShift), sections.get(sectionKey));
            }
            chunkTag.put("sections", renumbered);
        }
    }

    private static void stripHeightmaps(CompoundTag plotTag) {
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            chunks.getCompound(key).remove("heightmaps");
        }
    }

    private static final String[] KINETIC_KEYS = {"Speed", "Source", "Network", "NeedsSpeedUpdate"};

    private static void stripKineticState(CompoundTag plotTag, Set<Long> kineticPositions) {
        if (kineticPositions.isEmpty()) return;
        int stripped = 0;
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            ListTag blockEntities = chunks.getCompound(key).getList("block_entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < blockEntities.size(); i++) {
                CompoundTag be = blockEntities.getCompound(i);
                long pos = BlockPos.asLong(be.getInt("x"), be.getInt("y"), be.getInt("z"));
                if (!kineticPositions.contains(pos)) continue;
                for (String kineticKey : KINETIC_KEYS) {
                    be.remove(kineticKey);
                }
                for (String childKey : be.getAllKeys()) {
                    if (be.get(childKey) instanceof CompoundTag child
                            && child.contains("Speed")
                            && (child.contains("Source") || child.contains("Network"))) {
                        for (String kineticKey : KINETIC_KEYS) {
                            child.remove(kineticKey);
                        }
                    }
                }
                stripped++;
            }
        }
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: stripped kinetic state from {} of {} block entit(ies) so rotation networks rebuild cold",
                stripped, kineticPositions.size());
    }

    private static void offsetPlotCoordinates(CompoundTag plotTag, int deltaX, int deltaY, int deltaZ) {
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            CompoundTag chunkTag = chunks.getCompound(key);
            offsetCoordinateList(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), deltaX, deltaY, deltaZ);
            offsetCoordinateList(chunkTag.getList("block_ticks", Tag.TAG_COMPOUND), deltaX, deltaY, deltaZ);
            offsetCoordinateList(chunkTag.getList("fluid_ticks", Tag.TAG_COMPOUND), deltaX, deltaY, deltaZ);
        }
    }

    private static void offsetCoordinateList(ListTag list, int deltaX, int deltaY, int deltaZ) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.contains("x", Tag.TAG_INT)) entry.putInt("x", entry.getInt("x") + deltaX);
            if (entry.contains("y", Tag.TAG_INT)) entry.putInt("y", entry.getInt("y") + deltaY);
            if (entry.contains("z", Tag.TAG_INT)) entry.putInt("z", entry.getInt("z") + deltaZ);
            if (entry.contains("Controller", Tag.TAG_INT_ARRAY)) {
                int[] controller = entry.getIntArray("Controller");
                if (controller.length == 3) {
                    entry.putIntArray("Controller", new int[]{
                            controller[0] + deltaX, controller[1] + deltaY, controller[2] + deltaZ});
                }
            }
        }
    }

    private static int[] findFreePlot(ServerSubLevelContainer container, int preferX, int preferZ) {
        BitSet occupancy = container.getOccupancy();
        int sideLength = 1 << container.getLogSideLength();

        if (isPlotFree(container, occupancy, sideLength, preferX, preferZ)) {
            return new int[]{preferX, preferZ};
        }
        for (int z = 0; z < sideLength; z++) {
            for (int x = 0; x < sideLength; x++) {
                if (isPlotFree(container, occupancy, sideLength, x, z)) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    private static boolean isPlotFree(ServerSubLevelContainer container, BitSet occupancy, int sideLength, int x, int z) {
        if (x < 0 || x >= sideLength || z < 0 || z >= sideLength) {
            return false;
        }
        return !occupancy.get(container.getIndex(x, z));
    }
}
