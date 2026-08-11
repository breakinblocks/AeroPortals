package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.TravelMethods;
import com.breakinblocks.aeroportals.portal.EntityRiderBinding;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.RiderBinding;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Ae2SpatialCompat {
    public static final String MOD_ID = "ae2";
    private static final String PLOT_MANAGER_CLASS = "appeng.spatial.SpatialStoragePlotManager";
    private static final String PLOT_CLASS = "appeng.spatial.SpatialStoragePlot";
    private static final String TRANSITION_CLASS = "appeng.spatial.TransitionInfo";
    private static final long ERROR_LOG_INTERVAL_TICKS = 1200L;

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static Object plotManager;
    private static Method managerGetPlots;
    private static Method managerGetLevel;
    private static Method plotGetId;
    private static Method plotGetSize;
    private static Method plotGetOrigin;
    private static Method plotGetLastTransition;
    private static Method transitionGetWorldId;
    private static Method transitionGetMin;
    private static Method transitionGetMax;
    private static Method transitionGetTimestamp;

    private static final Map<Integer, Instant> seenTransitions = new HashMap<>();
    private static boolean seeded;
    private static long lastErrorLogTick = Long.MIN_VALUE;

    private Ae2SpatialCompat() {}

    public static boolean isAvailable() {
        if (initialized) return true;
        if (initAttempted) return false;
        if (!ModList.get().isLoaded(MOD_ID)) {
            initAttempted = true;
            return false;
        }
        return tryInit();
    }

    private static synchronized boolean tryInit() {
        if (initAttempted) return initialized;
        initAttempted = true;
        try {
            Class<?> managerClass = Class.forName(PLOT_MANAGER_CLASS);
            Field instanceField = managerClass.getField("INSTANCE");
            Object manager = instanceField.get(null);
            Method getPlots = managerClass.getMethod("getPlots");
            Method getLevel = managerClass.getMethod("getLevel");

            Class<?> plotClass = Class.forName(PLOT_CLASS);
            Method getId = plotClass.getMethod("getId");
            Method getSize = plotClass.getMethod("getSize");
            Method getOrigin = plotClass.getMethod("getOrigin");
            Method getLastTransition = plotClass.getMethod("getLastTransition");

            Class<?> transitionClass = Class.forName(TRANSITION_CLASS);
            Method getWorldId = transitionClass.getMethod("getWorldId");
            Method getMin = transitionClass.getMethod("getMin");
            Method getMax = transitionClass.getMethod("getMax");
            Method getTimestamp = transitionClass.getMethod("getTimestamp");

            plotManager = manager;
            managerGetPlots = getPlots;
            managerGetLevel = getLevel;
            plotGetId = getId;
            plotGetSize = getSize;
            plotGetOrigin = getOrigin;
            plotGetLastTransition = getLastTransition;
            transitionGetWorldId = getWorldId;
            transitionGetMin = getMin;
            transitionGetMax = getMax;
            transitionGetTimestamp = getTimestamp;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] AE2 spatial storage compat initialized");
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 loaded but expected class not found ({}); spatial compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial API method/field renamed/missing ({}); spatial compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial compat init failed: {}", t.toString());
            return false;
        }
    }

    public static void tick(MinecraftServer server) {
        if (!isAvailable()) return;
        try {
            List<?> plots = (List<?>) managerGetPlots.invoke(plotManager);
            if (!seeded) {
                for (Object plot : plots) {
                    Object transition = plotGetLastTransition.invoke(plot);
                    if (transition == null) continue;
                    seenTransitions.put((Integer) plotGetId.invoke(plot),
                            (Instant) transitionGetTimestamp.invoke(transition));
                }
                seeded = true;
                return;
            }

            Set<Integer> present = new HashSet<>();
            for (Object plot : plots) {
                int id = (Integer) plotGetId.invoke(plot);
                present.add(id);
                Object transition = plotGetLastTransition.invoke(plot);
                if (transition == null) continue;
                Instant ts = (Instant) transitionGetTimestamp.invoke(transition);
                if (ts.equals(seenTransitions.get(id))) continue;
                seenTransitions.put(id, ts);
                handleTransition(server, plot, transition);
            }
            seenTransitions.keySet().retainAll(present);
        } catch (Throwable t) {
            long now = server.getTickCount();
            if (now - lastErrorLogTick >= ERROR_LOG_INTERVAL_TICKS) {
                lastErrorLogTick = now;
                AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial poll failed: {}", t.toString());
            }
        }
    }

    private static void handleTransition(MinecraftServer server, Object plot, Object transition) throws ReflectiveOperationException {
        if (!TravelMethods.isEnabled(TravelMethods.AE2_SPATIAL)) return;

        int plotId = (Integer) plotGetId.invoke(plot);
        ResourceLocation worldId = (ResourceLocation) transitionGetWorldId.invoke(transition);
        BlockPos min = (BlockPos) transitionGetMin.invoke(transition);
        BlockPos max = (BlockPos) transitionGetMax.invoke(transition);
        BlockPos size = (BlockPos) plotGetSize.invoke(plot);
        BlockPos origin = (BlockPos) plotGetOrigin.invoke(plot);

        ServerLevel worldLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, worldId));
        ServerLevel spatialLevel = (ServerLevel) managerGetLevel.invoke(plotManager);
        if (worldLevel == null || spatialLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial transition on plot {} references unavailable level (world={}); skipping", plotId, worldId);
            return;
        }

        BlockPos interiorMin = resolveInteriorMin(min, max, size);
        if (interiorMin == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial transition on plot {}: region {}..{} does not match plot size {}; skipping", plotId, min, max, size);
            return;
        }

        AABB worldRegion = regionAabb(interiorMin, size);
        AABB plotRegion = regionAabb(origin, size);

        List<ServerSubLevel> storing = containedSubs(worldLevel, worldRegion, plotId);
        List<ServerSubLevel> recalling = containedSubs(spatialLevel, plotRegion, plotId);
        if (storing.isEmpty() && recalling.isEmpty()) return;

        Vec3 storeShift = new Vec3(
                origin.getX() - interiorMin.getX(),
                origin.getY() - interiorMin.getY(),
                origin.getZ() - interiorMin.getZ());

        AeroPortals.LOGGER.debug("[AeroPortals] AE2 spatial transition on plot {} ({} -> cell): storing {} sub(s), recalling {} sub(s)",
                plotId, worldId, storing.size(), recalling.size());

        for (ServerSubLevel sub : storing) {
            UUID id = sub.getUniqueId();
            PortalTeleport.teleportToDimension(worldLevel, sub, spatialLevel, subPos(sub).add(storeShift), false, "ae2-spatial-store");
            scheduleSyntheticSettles(spatialLevel, id);
        }
        for (ServerSubLevel sub : recalling) {
            UUID id = sub.getUniqueId();
            PortalTeleport.teleportToDimension(spatialLevel, sub, worldLevel, subPos(sub).subtract(storeShift), false, "ae2-spatial-recall");
            scheduleSyntheticSettles(worldLevel, id);
        }
    }

    private static BlockPos resolveInteriorMin(BlockPos min, BlockPos max, BlockPos size) {
        if (max.getX() - min.getX() - 1 == size.getX()
                && max.getY() - min.getY() - 1 == size.getY()
                && max.getZ() - min.getZ() - 1 == size.getZ()) {
            return min.offset(1, 1, 1);
        }
        if (max.getX() - min.getX() + 1 == size.getX()
                && max.getY() - min.getY() + 1 == size.getY()
                && max.getZ() - min.getZ() + 1 == size.getZ()) {
            return min;
        }
        return null;
    }

    private static AABB regionAabb(BlockPos minCorner, BlockPos size) {
        return new AABB(
                minCorner.getX(), minCorner.getY(), minCorner.getZ(),
                minCorner.getX() + size.getX(), minCorner.getY() + size.getY(), minCorner.getZ() + size.getZ());
    }

    private static List<ServerSubLevel> containedSubs(ServerLevel level, AABB region, int plotId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();
        AABB tolerant = region.inflate(0.5);
        List<ServerSubLevel> result = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved() || claimed.contains(sub.getUniqueId())) continue;
            if (!AabbUtil.worldAabb(sub).intersects(region)) continue;
            Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(sub);
            boolean allContained = true;
            for (ServerSubLevel member : chain) {
                if (member.isRemoved()) continue;
                claimed.add(member.getUniqueId());
                if (!containsAabb(tolerant, AabbUtil.worldAabb(member))) {
                    allContained = false;
                }
            }
            if (!allContained) {
                AeroPortals.LOGGER.warn("[AeroPortals] sub {} overlaps AE2 spatial region for plot {} but its chain is not fully inside; leaving it behind",
                        sub.getUniqueId(), plotId);
                continue;
            }
            result.add(sub);
        }
        return result;
    }

    private static boolean containsAabb(AABB outer, AABB inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    private static void scheduleSyntheticSettles(ServerLevel dstLevel, UUID subId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(dstLevel);
        if (container == null) return;
        ServerSubLevel newSub = null;
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.getUniqueId().equals(subId) && !sub.isRemoved()) {
                newSub = sub;
                break;
            }
        }
        if (newSub == null) return;

        Vec3 subPos = subPos(newSub);
        AABB deckArea = AabbUtil.worldAabb(newSub).inflate(1.5);
        List<RiderBinding> riders = new ArrayList<>();
        List<EntityRiderBinding> entityRiders = new ArrayList<>();
        for (ServerPlayer p : dstLevel.players()) {
            if (!deckArea.contains(p.position())) continue;
            riders.add(new RiderBinding(p.getUUID(), p.position().subtract(subPos), 0.0f, p.getXRot()));
        }
        for (Entity e : dstLevel.getEntities((Entity) null, deckArea)) {
            if (e instanceof Player) continue;
            if (EntitySubLevelUtil.shouldKick(e)) continue;
            entityRiders.add(new EntityRiderBinding(e.getUUID(), e.position().subtract(subPos), 0.0f, e.getXRot()));
        }
        PortalTeleport.DeferredRiderSettles.schedule(dstLevel.getServer().getTickCount(), dstLevel, newSub, riders, entityRiders);
    }

    private static Vec3 subPos(ServerSubLevel sub) {
        Pose3dc pose = sub.logicalPose();
        return new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
    }
}
