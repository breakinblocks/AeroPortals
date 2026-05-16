package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Encapsulates the Sable-specific cross-dimension move. Sable exposes no direct
 * cross-dimension API; we use the serialize → remove-source → fullyLoad-in-target
 * pattern. See CLAUDE.md ("Sable Architecture > Cross-dimension move pattern") for the
 * full explanation.
 */
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

        srcContainer.removeSubLevel(src, SubLevelRemovalReason.REMOVED);
        AeroPortals.LOGGER.info("[AeroPortals] SableBridge: removed source sub-level");

        ServerSubLevel loaded = tryLoad(dstLevel, dstContainer, data);
        if (loaded == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge: fullyLoad failed in destination");
        } else {
            AeroPortals.LOGGER.info("[AeroPortals] SableBridge: loaded into {} at {}",
                    dstLevel.dimension().location(), loaded.logicalPose().position());
        }
        return loaded;
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
                    // try next slot
                }
            }
        }
        return null;
    }
}
