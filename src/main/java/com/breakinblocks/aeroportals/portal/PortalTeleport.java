package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.YawMath;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PortalTeleport {
    private static final int DEST_CHUNK_RADIUS = 2;

    private PortalTeleport() {}

    public static void teleport(ServerLevel srcLevel, ServerSubLevel sub, BlockPos portalBlock) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> dstKey = (srcLevel.dimension() == Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] destination dimension {} not loaded; aborting", dstKey.location());
            return;
        }

        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();

        Pose3dc srcPose = sub.logicalPose();
        Vector3dc srcPosV = srcPose.position();
        Vec3 srcWorld = new Vec3(srcPosV.x(), srcPosV.y(), srcPosV.z());

        double dstX = srcWorld.x * ratio;
        double dstZ = srcWorld.z * ratio;
        Vec3 dstWorld = clampToWorldBorder(dstLevel, new Vec3(dstX, srcWorld.y, dstZ));

        AeroPortals.LOGGER.info("[AeroPortals] teleport: src dim={} pos={} -> dst dim={} pos={} (ratio={}, srcScale={}, dstScale={})",
                srcLevel.dimension().location(), srcWorld,
                dstKey.location(), dstWorld,
                ratio, srcDim.coordinateScale(), dstDim.coordinateScale());

        List<RiderBinding> riders = captureRiders(srcLevel, sub);
        AeroPortals.LOGGER.info("[AeroPortals] captured {} rider(s)", riders.size());

        ensureChunksLoaded(dstLevel, BlockPos.containing(dstWorld));

        ServerSubLevel newSub = SableBridge.moveAcrossDimensions(sub, srcLevel, dstLevel, dstWorld);
        if (newSub == null) {
            AeroPortals.LOGGER.error("[AeroPortals] SableBridge returned null; teleport aborted, riders remain in source dim");
            return;
        }

        Pose3dc newPose = newSub.logicalPose();
        float newYawBase = (float) YawMath.yawFromOrientation(newPose.orientation());
        for (RiderBinding rb : riders) {
            ServerPlayer p = server.getPlayerList().getPlayer(rb.playerUuid());
            if (p == null) {
                AeroPortals.LOGGER.warn("[AeroPortals] rider {} not online post-teleport; skipping", rb.playerUuid());
                continue;
            }
            Vec3 worldFinal = newPose.transformPosition(rb.localOffset());
            float yaw = rb.yawDelta() + newYawBase;
            p.teleportTo(dstLevel, worldFinal.x, worldFinal.y, worldFinal.z,
                    Collections.<RelativeMovement>emptySet(), yaw, rb.pitch());
            AeroPortals.LOGGER.info("[AeroPortals] moved rider {} -> {} yaw={} pitch={}",
                    p.getGameProfile().getName(), worldFinal, yaw, rb.pitch());
        }

        PortalCooldown.mark(newSub.getUniqueId(), server.getTickCount());
        AeroPortals.LOGGER.info("[AeroPortals] teleport complete; cooldown set for sub {}", newSub.getUniqueId());
    }

    private static List<RiderBinding> captureRiders(ServerLevel srcLevel, ServerSubLevel sub) {
        List<RiderBinding> out = new ArrayList<>();
        Pose3dc pose = sub.logicalPose();
        float subYawNow = (float) YawMath.yawFromOrientation(pose.orientation());
        for (ServerPlayer p : srcLevel.players()) {
            SubLevel tracking = Sable.HELPER.getTrackingSubLevel(p);
            if (tracking != sub) continue;
            Vec3 local = pose.transformPositionInverse(p.position());
            float yawDelta = p.getYRot() - subYawNow;
            out.add(new RiderBinding(p.getUUID(), local, yawDelta, p.getXRot()));
            AeroPortals.LOGGER.info("[AeroPortals] capture rider {} local={} yawDelta={}",
                    p.getGameProfile().getName(), local, yawDelta);
        }
        return out;
    }

    private static Vec3 clampToWorldBorder(ServerLevel level, Vec3 pos) {
        var border = level.getWorldBorder();
        double half = border.getSize() / 2.0;
        double cx = border.getCenterX();
        double cz = border.getCenterZ();
        double x = Math.max(cx - half, Math.min(cx + half, pos.x));
        double z = Math.max(cz - half, Math.min(cz + half, pos.z));
        return new Vec3(x, pos.y, z);
    }

    private static void ensureChunksLoaded(ServerLevel level, BlockPos center) {
        int cx = SectionPos.blockToSectionCoord(center.getX());
        int cz = SectionPos.blockToSectionCoord(center.getZ());
        for (int dx = -DEST_CHUNK_RADIUS; dx <= DEST_CHUNK_RADIUS; dx++) {
            for (int dz = -DEST_CHUNK_RADIUS; dz <= DEST_CHUNK_RADIUS; dz++) {
                level.getChunk(cx + dx, cz + dz);
            }
        }
    }
}
