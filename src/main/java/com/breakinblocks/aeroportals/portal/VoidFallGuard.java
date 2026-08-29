package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoidFallGuard {
    private static final long RESCUE_INTERVAL_TICKS = 40L;
    private static final double RIDER_MARGIN = 1.5;

    private static final Map<UUID, Long> LAST_RESCUE = new ConcurrentHashMap<>();

    private VoidFallGuard() {}

    public static void scan(ServerLevel level) {
        if (!AeroPortalsConfig.CATCH_FALLING_SHIPS.get()) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        double catchY = level.getMinBuildHeight() - AeroPortalsConfig.CATCH_SHIPS_BELOW_FLOOR.get();
        long now = level.getServer().getTickCount();

        for (ServerSubLevel sub : List.copyOf(subs)) {
            if (sub.isRemoved()) continue;
            AABB aabb = AabbUtil.worldAabb(sub);
            if (aabb.maxY > catchY) continue;

            Long last = LAST_RESCUE.get(sub.getUniqueId());
            if (last != null && now - last < RESCUE_INTERVAL_TICKS) continue;
            LAST_RESCUE.put(sub.getUniqueId(), now);
            rescue(level, container, sub, aabb);
        }
    }

    private static void rescue(ServerLevel level, ServerSubLevelContainer container, ServerSubLevel sub, AABB aabb) {
        Vec3 subPos = subWorldPos(sub);
        double height = aabb.getYsize();
        int surface = PortalTeleport.loadedSurfaceY(level, (int) Math.floor(aabb.getCenter().x), (int) Math.floor(aabb.getCenter().z));
        double bottom = Math.max(surface + 1.0, level.getMinBuildHeight() + 8.0);
        double maxBottom = level.getMaxBuildHeight() - height - 1.0;
        if (bottom > maxBottom) bottom = maxBottom;

        Vec3 target = new Vec3(subPos.x, bottom + (subPos.y - aabb.minY), subPos.z);
        target = PortalTeleport.raiseLandingUntilClear(level, sub, target);

        List<RescuedRider> riders = captureRiders(level, aabb);
        Vec3 translation = target.subtract(subPos);

        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(sub);
        pipeline.teleport(sub, new Vector3d(target.x, target.y, target.z), sub.logicalPose().orientation());
        PortalTeleport.holdDestinationChunks(level, target);
        PortalTeleport.forceClientSync(level, sub, true);

        for (RescuedRider rider : riders) {
            Entity entity = level.getEntity(rider.entityId());
            if (entity == null || !entity.isAlive()) continue;
            Vec3 to = rider.position().add(translation);
            entity.teleportTo(to.x, to.y, to.z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.hurtMarked = true;
            entity.fallDistance = 0.0f;
            if (entity instanceof ServerPlayer player) {
                player.displayClientMessage(Component.literal(
                        "Your airship was falling out of the world and has been set down safely."), false);
            }
        }

        AeroPortals.LOGGER.warn("[AeroPortals] caught airship {} falling out of {} at {}; it would have been destroyed below y={}. Set it down at {} with {} passenger(s)",
                sub.getUniqueId(), level.dimension().location(), subPos,
                level.getMinBuildHeight() - AeroPortalsConfig.CATCH_SHIPS_BELOW_FLOOR.get(), target, riders.size());
    }

    private record RescuedRider(UUID entityId, Vec3 position) {}

    private static List<RescuedRider> captureRiders(ServerLevel level, AABB aabb) {
        List<RescuedRider> riders = new ArrayList<>();
        AABB search = aabb.inflate(RIDER_MARGIN);
        for (Entity entity : level.getEntities((Entity) null, search, e -> !e.isRemoved())) {
            if (entity.isPassenger()) continue;
            riders.add(new RescuedRider(entity.getUUID(), entity.position()));
        }
        return riders;
    }

    private static Vec3 subWorldPos(ServerSubLevel sub) {
        var pose = sub.logicalPose();
        return new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
    }

    public static void clear() {
        LAST_RESCUE.clear();
    }
}
