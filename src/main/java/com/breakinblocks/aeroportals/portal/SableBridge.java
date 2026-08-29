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

    public static Moved moveAcrossDimensions(
            ServerSubLevel src,
            ServerLevel srcLevel,
            ServerLevel dstLevel,
            Vec3 dstWorldPos) {

        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: missing container src={} dst={}", srcContainer, dstContainer);
            return null;
        }

        BoundingBox3ic contentBounds = src.getPlot().getBoundingBox();
        if (contentBounds != BoundingBox3i.EMPTY) {
            int spanSections = srcLevel.getSectionIndex(contentBounds.maxY()) - srcLevel.getSectionIndex(contentBounds.minY()) + 1;
            int dstSectionCount = dstLevel.getSectionsCount();
            if (spanSections > dstSectionCount) {
                AeroPortals.LOGGER.error("[AeroPortals] SableBridge: sub {} spans {} chunk sections but destination {} only has {}; aborting move so it can stay where it is",
                        src.getUniqueId(), spanSections, dstLevel.dimension().location(), dstSectionCount);
                return null;
            }
        }
        if (srcContainer != dstContainer && findFreePlot(dstContainer, 0, 0) == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination container has no free plot; aborting move for sub {}", src.getUniqueId());
            return null;
        }

        int regionBits = srcContainer.getLogPlotSize() + 4;
        ChunkPos oldPlotPos = src.getPlot().plotPos;
        BlockPos oldRegionMin = new BlockPos(oldPlotPos.x << regionBits, srcLevel.getMinBuildHeight(), oldPlotPos.z << regionBits);
        int regionBlocks = 1 << regionBits;

        Map<TransferCarrier<?>, Object> carried = captureCarriers(srcLevel, src);
        CreateContraptionCompat.KineticSnapshot kinetics = CreateContraptionCompat.collectKinetics(srcLevel, src);

        SubLevelData data = SubLevelSerializer.toData(src, List.of());
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: snapshotted sub uuid={} bounds={}", data.uuid(), data.bounds());

        CompoundTag tag = data.fullTag();
        stripKineticState(tag.getCompound("plot"), kinetics.kineticPositions());
        CompoundTag sourceSnapshot = tag.copy();

        CompoundTag poseTag = tag.getCompound("pose");
        Pose3d pose = SableNBTUtils.readPose3d(poseTag);
        Vec3 srcWorldPos = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
        pose.position().set(dstWorldPos.x, dstWorldPos.y, dstWorldPos.z);
        tag.put("pose", SableNBTUtils.writePose3d(pose));

        Vec3 worldTranslation = dstWorldPos.subtract(srcWorldPos);
        if (tag.contains("world_bounds")) {
            BoundingBox3d worldBounds = SableNBTUtils.readBoundingBox(tag.getCompound("world_bounds"));
            worldBounds.move(worldTranslation.x, worldTranslation.y, worldTranslation.z);
            tag.put("world_bounds", SableNBTUtils.writeBoundingBox(worldBounds));
        }

        TeleportJournal.write(
                srcLevel.getServer(), data.uuid(),
                srcLevel.dimension().location(), dstLevel.dimension().location(),
                srcLevel.getMinBuildHeight(), data);

        srcContainer.removeSubLevel(src, SubLevelRemovalReason.REMOVED);
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: removed source sub-level");

        SourceInfo sourceInfo = new SourceInfo(srcLevel.dimension(), srcLevel.getMinBuildHeight(),
                worldTranslation, oldRegionMin, regionBlocks);

        Loaded loaded;
        try {
            loaded = reloadInDestination(sourceInfo, dstLevel, dstContainer, data);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination load threw for sub {}; will restore to source", data.uuid(), e);
            loaded = null;
        }

        if (loaded != null) {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: loaded into {} at {}",
                    dstLevel.dimension().location(), loaded.sub().logicalPose().position());
            replayCarriers(dstLevel, loaded.sub(), carried, loaded.shift());
            CreateContraptionCompat.reactivateGenerators(dstLevel, kinetics.generatorPositions(), loaded.shift());
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
            return new Moved(loaded.sub(), loaded.shift(), oldRegionMin, regionBlocks);
        }

        if (restoreToSource(srcLevel, srcContainer, sourceSnapshot, data.uuid(), oldRegionMin, regionBlocks, carried, kinetics)) {
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
        } else {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination load AND source restore failed for sub {}; left in journal for recovery on next start", data.uuid());
        }
        return null;
    }

    private static boolean restoreToSource(ServerLevel srcLevel, ServerSubLevelContainer srcContainer, CompoundTag sourceSnapshot, UUID uuid,
                                           BlockPos oldRegionMin, int regionBlocks,
                                           Map<TransferCarrier<?>, Object> carried,
                                           CreateContraptionCompat.KineticSnapshot kinetics) {
        SubLevelData restoreData = SubLevelSerializer.fromData(sourceSnapshot);
        if (restoreData == null) return false;
        SourceInfo sourceInfo = new SourceInfo(srcLevel.dimension(), srcLevel.getMinBuildHeight(),
                Vec3.ZERO, oldRegionMin, regionBlocks);
        Loaded restored;
        try {
            restored = reloadInDestination(sourceInfo, srcLevel, srcContainer, restoreData);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: source restore threw for sub {}", uuid, e);
            return false;
        }
        if (restored == null) return false;
        replayCarriers(srcLevel, restored.sub(), carried, restored.shift());
        CreateContraptionCompat.reactivateGenerators(srcLevel, kinetics.generatorPositions(), restored.shift());
        AeroPortals.LOGGER.warn("[AeroPortals] SableBridge: destination load failed; restored sub {} to source {} (teleport cancelled)",
                uuid, srcLevel.dimension().location());
        return true;
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
                AeroPortals.LOGGER.error("[AeroPortals] transfer carrier {} threw during capture of sub {}",
                        carrier.id(), sub.getUniqueId(), e);
            }
        }
        return captured;
    }

    private static void replayCarriers(ServerLevel level, ServerSubLevel newSub, Map<TransferCarrier<?>, Object> captured, BlockPos shift) {
        if (captured.isEmpty()) return;
        List<Map.Entry<TransferCarrier<?>, Object>> entries = new ArrayList<>(captured.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<TransferCarrier<?>, Object> entry : entries) {
            try {
                replayOne(entry.getKey(), level, newSub, entry.getValue(), shift);
            } catch (RuntimeException e) {
                AeroPortals.LOGGER.error("[AeroPortals] transfer carrier {} threw during replay on sub {}",
                        entry.getKey().id(), newSub.getUniqueId(), e);
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
