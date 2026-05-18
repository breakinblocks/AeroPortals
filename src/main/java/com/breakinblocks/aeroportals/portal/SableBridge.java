package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class SableBridge {
    private static final int MAX_PLOT_SCAN = 256;

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
        AeroPortals.LOGGER.info("[AeroPortals] SableBridge: snapshotted sub uuid={} bounds={}", data.uuid(), data.bounds());

        CompoundTag tag = data.fullTag();
        CompoundTag poseTag = tag.getCompound("pose");
        Pose3d pose = SableNBTUtils.readPose3d(poseTag);
        pose.position().set(dstWorldPos.x, dstWorldPos.y, dstWorldPos.z);
        tag.put("pose", SableNBTUtils.writePose3d(pose));

        TeleportJournal.write(
                srcLevel.getServer(), data.uuid(),
                srcLevel.dimension().location(), dstLevel.dimension().location(), data);

        srcContainer.removeSubLevel(src, SubLevelRemovalReason.REMOVED);
        AeroPortals.LOGGER.info("[AeroPortals] SableBridge: removed source sub-level");

        ServerSubLevel loaded = tryLoad(dstLevel, dstContainer, data);
        if (loaded == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: fullyLoad failed in destination; leaving journal entry for recovery on next start");
        } else {
            AeroPortals.LOGGER.info("[AeroPortals] SableBridge: loaded into {} at {}",
                    dstLevel.dimension().location(), loaded.logicalPose().position());
            rebuildPhysicsData(dstLevel, loaded, dstContainer);
            TeleportJournal.delete(srcLevel.getServer(), data.uuid());
        }
        return loaded;
    }

    // Replays each loaded block through the physics system; fullyLoad's bulk write bypasses the
    // per-block change event, leaving the MassTracker empty and the sub gets auto-removed next tick.
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
        AeroPortals.LOGGER.info("[AeroPortals] SableBridge: rebuilt physics data for sub {} ({} blocks); mass.isInvalid={} mass.value={}",
                sub.getUniqueId(), blocksProcessed,
                sub.getMassTracker().isInvalid(), sub.getMassTracker().getMass());
    }

    private static ServerSubLevel tryLoad(ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        try {
            ServerSubLevel loaded = SubLevelSerializer.fullyLoad(dstLevel, data);
            if (loaded != null) return loaded;
        } catch (IllegalArgumentException collision) {
            AeroPortals.LOGGER.warn("[AeroPortals] SableBridge: destination plot collision ({}). Scanning for a free slot.", collision.getMessage());
        }

        CompoundTag tag = data.fullTag();
        CompoundTag plotTag = tag.getCompound("plot");
        int origPlotX = plotTag.getInt("plot_x");
        int origPlotZ = plotTag.getInt("plot_z");

        for (int dz = 0; dz < MAX_PLOT_SCAN; dz++) {
            for (int dx = 0; dx < MAX_PLOT_SCAN; dx++) {
                int candidateX = origPlotX + dx;
                int candidateZ = origPlotZ + dz;
                SubLevel existing = dstContainer.getSubLevel(candidateX, candidateZ);
                if (existing != null) continue;
                plotTag.putInt("plot_x", candidateX);
                plotTag.putInt("plot_z", candidateZ);
                try {
                    ServerSubLevel loaded = SubLevelSerializer.fullyLoad(dstLevel, data);
                    if (loaded != null) return loaded;
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }
}
