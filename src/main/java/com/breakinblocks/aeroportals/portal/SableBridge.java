package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.CreateContraptionCompat;
import com.breakinblocks.aeroportals.compat.SimulatedCompat;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SableBridge {
    private SableBridge() {}

    public record Moved(ServerSubLevel sub, BlockPos shift, BlockPos oldRegionMin, int regionBlocks) {}

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

        int regionBits = srcContainer.getLogPlotSize() + 4;
        ChunkPos oldPlotPos = src.getPlot().plotPos;
        BlockPos oldRegionMin = new BlockPos(oldPlotPos.x << regionBits, srcLevel.getMinBuildHeight(), oldPlotPos.z << regionBits);
        int regionBlocks = 1 << regionBits;

        List<BlockPos> assembledBearings = CreateContraptionCompat.disassembleAssemblies(srcLevel, src);
        List<AABB> superGlue = CreateContraptionCompat.captureGlue(srcLevel, src);
        List<AABB> honeyGlue = SimulatedCompat.captureHoneyGlue(srcLevel, src);
        CreateContraptionCompat.KineticSnapshot kinetics = CreateContraptionCompat.collectKinetics(srcLevel, src);

        SubLevelData data = SubLevelSerializer.toData(src, List.of());
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: snapshotted sub uuid={} bounds={}", data.uuid(), data.bounds());

        CompoundTag tag = data.fullTag();
        stripKineticState(tag.getCompound("plot"), kinetics.kineticPositions());
        CompoundTag sourceSnapshot = tag.copy();

        CompoundTag poseTag = tag.getCompound("pose");
        Pose3d pose = SableNBTUtils.readPose3d(poseTag);
        pose.position().set(dstWorldPos.x, dstWorldPos.y, dstWorldPos.z);
        tag.put("pose", SableNBTUtils.writePose3d(pose));

        TeleportJournal.write(
                srcLevel.getServer(), data.uuid(),
                srcLevel.dimension().location(), dstLevel.dimension().location(),
                srcLevel.getMinBuildHeight(), data);

        srcContainer.removeSubLevel(src, SubLevelRemovalReason.REMOVED);
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: removed source sub-level");

        Loaded loaded;
        try {
            loaded = reloadInDestination(srcLevel.getMinBuildHeight(), dstLevel, dstContainer, data);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination load threw for sub {}; will restore to source", data.uuid(), e);
            loaded = null;
        }

        if (loaded != null) {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: loaded into {} at {}",
                    dstLevel.dimension().location(), loaded.sub().logicalPose().position());
            CreateContraptionCompat.replayGlue(dstLevel, superGlue, loaded.shift());
            SimulatedCompat.replayHoneyGlue(dstLevel, honeyGlue, loaded.shift());
            CreateContraptionCompat.reassemble(dstLevel, assembledBearings, loaded.shift());
            CreateContraptionCompat.reactivateGenerators(dstLevel, kinetics.generatorPositions(), loaded.shift());
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
            return new Moved(loaded.sub(), loaded.shift(), oldRegionMin, regionBlocks);
        }

        if (restoreToSource(srcLevel, srcContainer, sourceSnapshot, data.uuid(), assembledBearings, superGlue, honeyGlue, kinetics)) {
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
        } else {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination load AND source restore failed for sub {}; left in journal for recovery on next start", data.uuid());
        }
        return null;
    }

    private static boolean restoreToSource(ServerLevel srcLevel, ServerSubLevelContainer srcContainer, CompoundTag sourceSnapshot, UUID uuid,
                                           List<BlockPos> assembledBearings, List<AABB> superGlue, List<AABB> honeyGlue,
                                           CreateContraptionCompat.KineticSnapshot kinetics) {
        SubLevelData restoreData = SubLevelSerializer.fromData(sourceSnapshot);
        if (restoreData == null) return false;
        Loaded restored;
        try {
            restored = reloadInDestination(srcLevel.getMinBuildHeight(), srcLevel, srcContainer, restoreData);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: source restore threw for sub {}", uuid, e);
            return false;
        }
        if (restored == null) return false;
        CreateContraptionCompat.replayGlue(srcLevel, superGlue, restored.shift());
        SimulatedCompat.replayHoneyGlue(srcLevel, honeyGlue, restored.shift());
        CreateContraptionCompat.reassemble(srcLevel, assembledBearings, restored.shift());
        CreateContraptionCompat.reactivateGenerators(srcLevel, kinetics.generatorPositions(), restored.shift());
        AeroPortals.LOGGER.warn("[AeroPortals] SableBridge: destination load failed; restored sub {} to source {} (teleport cancelled)",
                uuid, srcLevel.dimension().location());
        return true;
    }

    public record Loaded(ServerSubLevel sub, BlockPos shift) {}

    public static Loaded reloadInDestination(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        Loaded loaded = tryLoad(srcMinBuildHeight, dstLevel, dstContainer, data);
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

    private static Loaded tryLoad(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        CompoundTag tag = data.fullTag();
        CompoundTag plotTag = tag.getCompound("plot");
        int origPlotX = plotTag.getInt("plot_x");
        int origPlotZ = plotTag.getInt("plot_z");

        int dstSectionCount = dstLevel.getSectionsCount();
        if (!sectionsFitDestination(plotTag, dstSectionCount)) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: sub {} is too tall for destination {} ({} sections); aborting move so it can stay where it is",
                    data.uuid(), dstLevel.dimension().location(), dstSectionCount);
            return null;
        }

        int[] plot = findFreePlot(dstContainer, origPlotX, origPlotZ);
        if (plot == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: destination container has no free plot; aborting load for sub {}", data.uuid());
            return null;
        }

        int shift = dstContainer.getLogPlotSize() + 4;
        int deltaX = (plot[0] - origPlotX) << shift;
        int deltaZ = (plot[1] - origPlotZ) << shift;
        int deltaY = dstLevel.getMinBuildHeight() - srcMinBuildHeight;

        if (deltaX != 0 || deltaZ != 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: original plot {},{} occupied in destination; relocating sub {} to free plot {},{} (block shift {},{})",
                    origPlotX, origPlotZ, data.uuid(), plot[0], plot[1], deltaX, deltaZ);
            plotTag.putInt("plot_x", plot[0]);
            plotTag.putInt("plot_z", plot[1]);
        }
        if (deltaX != 0 || deltaY != 0 || deltaZ != 0) {
            if (deltaY != 0) {
                AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: dimension min-height differs (src {} -> dst {}); shifting block-entity coordinates by {} in Y",
                        srcMinBuildHeight, dstLevel.getMinBuildHeight(), deltaY);
            }
            offsetPlotCoordinates(plotTag, deltaX, deltaY, deltaZ);
        }

        ServerSubLevel loaded = SubLevelSerializer.fullyLoad(dstLevel, data);
        if (loaded == null) return null;
        return new Loaded(loaded, new BlockPos(deltaX, deltaY, deltaZ));
    }

    private static boolean sectionsFitDestination(CompoundTag plotTag, int dstSectionCount) {
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
                if (idx < 0 || idx >= dstSectionCount) {
                    return false;
                }
            }
        }
        return true;
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
