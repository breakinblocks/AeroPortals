package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.List;

public final class SableBridge {
    private SableBridge() {}

    public static ServerSubLevel moveAcrossDimensions(
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

        SubLevelData data = SubLevelSerializer.toData(src, List.of());
        AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: snapshotted sub uuid={} bounds={}", data.uuid(), data.bounds());

        CompoundTag tag = data.fullTag();
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

        ServerSubLevel loaded = reloadInDestination(srcLevel.getMinBuildHeight(), dstLevel, dstContainer, data);
        if (loaded == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: fullyLoad failed in destination; leaving journal entry for recovery on next start");
        } else {
            AeroPortals.LOGGER.debug("[AeroPortals] SableBridge: loaded into {} at {}",
                    dstLevel.dimension().location(), loaded.logicalPose().position());
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
        }
        return loaded;
    }

    public static ServerSubLevel reloadInDestination(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        ServerSubLevel loaded = tryLoad(srcMinBuildHeight, dstLevel, dstContainer, data);
        if (loaded != null) {
            rebuildPhysicsData(dstLevel, loaded, dstContainer);
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

    private static ServerSubLevel tryLoad(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        CompoundTag tag = data.fullTag();
        CompoundTag plotTag = tag.getCompound("plot");
        int origPlotX = plotTag.getInt("plot_x");
        int origPlotZ = plotTag.getInt("plot_z");

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

        return SubLevelSerializer.fullyLoad(dstLevel, data);
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
