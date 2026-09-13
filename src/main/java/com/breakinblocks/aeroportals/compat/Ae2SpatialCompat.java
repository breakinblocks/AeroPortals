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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
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
    private static final List<QueuedMove> pendingMoves = new ArrayList<>();
    private static MinecraftServer activeServer;
    private static boolean seeded;
    private static long lastErrorLogTick = Long.MIN_VALUE;

    private record ShipSnapshot(UUID id, Vec3 position, AABB bounds) {
        ShipSnapshot translated(Vec3 shift) {
            return new ShipSnapshot(id, position.add(shift), bounds.move(shift));
        }
    }

    private record GroupSnapshot(ResourceKey<Level> dimension, UUID rootId, List<ShipSnapshot> ships) {
        GroupSnapshot {
            ships = List.copyOf(ships);
        }

        Set<UUID> ids() {
            Set<UUID> ids = new HashSet<>();
            for (ShipSnapshot ship : ships) ids.add(ship.id());
            return ids;
        }

        GroupSnapshot translated(ResourceKey<Level> destination, Vec3 shift) {
            return new GroupSnapshot(destination, rootId, ships.stream().map(ship -> ship.translated(shift)).toList());
        }

        Vec3 rootPosition() {
            return ships.stream().filter(ship -> ship.id().equals(rootId)).findFirst().orElseThrow().position();
        }
    }

    private record SpatialMove(GroupSnapshot source, GroupSnapshot destination, String label) {}

    private static final class QueuedMove {
        final SpatialMove move;
        long retryAt;

        QueuedMove(SpatialMove move) {
            this.move = move;
        }
    }

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
        if (activeServer != server) {
            clear();
            activeServer = server;
        }
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
                if (handleTransition(server, plot, transition)) seenTransitions.put(id, ts);
            }
            seenTransitions.keySet().retainAll(present);
        } catch (Throwable t) {
            long now = server.getTickCount();
            if (lastErrorLogTick == Long.MIN_VALUE || now - lastErrorLogTick >= ERROR_LOG_INTERVAL_TICKS) {
                lastErrorLogTick = now;
                AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial poll failed: {}", t.toString());
            }
        }
        if (TravelMethods.isEnabled(TravelMethods.AE2_SPATIAL)) processPending(server, server.getTickCount());
    }

    public static void clear() {
        seenTransitions.clear();
        pendingMoves.clear();
        seeded = false;
        activeServer = null;
        lastErrorLogTick = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (activeServer == event.getServer()) clear();
    }

    private static boolean handleTransition(MinecraftServer server, Object plot, Object transition) throws ReflectiveOperationException {
        if (!TravelMethods.isEnabled(TravelMethods.AE2_SPATIAL)) return true;

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
            return false;
        }

        BlockPos interiorMin = resolveInteriorMin(min, max, size);
        if (interiorMin == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial transition on plot {}: region {}..{} does not match plot size {}; skipping", plotId, min, max, size);
            return true;
        }

        AABB worldRegion = regionAabb(interiorMin, size);
        AABB plotRegion = regionAabb(origin, size);

        Vec3 storeShift = new Vec3(
                origin.getX() - interiorMin.getX(),
                origin.getY() - interiorMin.getY(),
                origin.getZ() - interiorMin.getZ());

        queueTransition(worldLevel, spatialLevel, worldRegion, plotRegion, storeShift, plotId);
        return true;
    }

    private static void queueTransition(ServerLevel worldLevel, ServerLevel spatialLevel,
                                        AABB worldRegion, AABB plotRegion, Vec3 storeShift, int plotId) {
        List<GroupSnapshot> groups = new ArrayList<>();
        groups.addAll(snapshotGroups(worldLevel));
        groups.addAll(snapshotGroups(spatialLevel));
        // A later region swap sees the result of earlier queued moves, even if they are still retrying.
        for (QueuedMove queued : pendingMoves) {
            GroupSnapshot destination = queued.move.destination();
            Set<UUID> ids = destination.ids();
            groups.removeIf(group -> group.ships().stream().anyMatch(ship -> ids.contains(ship.id())));
            groups.add(destination);
        }
        List<GroupSnapshot> storing = containedGroups(groups, worldLevel.dimension(), worldRegion, plotId);
        List<GroupSnapshot> recalling = containedGroups(groups, spatialLevel.dimension(), plotRegion, plotId);
        List<QueuedMove> captured = new ArrayList<>();
        for (GroupSnapshot group : storing) {
            captured.add(new QueuedMove(new SpatialMove(group,
                    group.translated(spatialLevel.dimension(), storeShift), "ae2-spatial-store")));
        }
        for (GroupSnapshot group : recalling) {
            captured.add(new QueuedMove(new SpatialMove(group,
                    group.translated(worldLevel.dimension(), storeShift.scale(-1)), "ae2-spatial-recall")));
        }
        pendingMoves.addAll(captured);
        AeroPortals.LOGGER.debug("[AeroPortals] AE2 spatial transition on plot {}: queued {} store(s), {} recall(s)",
                plotId, storing.size(), recalling.size());
    }

    private static List<GroupSnapshot> snapshotGroups(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();
        List<GroupSnapshot> groups = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (ServerSubLevel sub : List.copyOf(container.getAllSubLevels())) {
            if (sub.isRemoved() || claimed.contains(sub.getUniqueId())) continue;
            Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(sub);
            List<ShipSnapshot> ships = new ArrayList<>();
            for (ServerSubLevel member : chain) {
                if (member.isRemoved()) continue;
                claimed.add(member.getUniqueId());
                ships.add(new ShipSnapshot(member.getUniqueId(), subPos(member), AabbUtil.worldAabb(member)));
            }
            if (!ships.isEmpty()) {
                Set<UUID> ids = new HashSet<>();
                for (ShipSnapshot ship : ships) ids.add(ship.id());
                Iterator<GroupSnapshot> previous = groups.iterator();
                while (previous.hasNext()) {
                    GroupSnapshot group = previous.next();
                    if (group.ships().stream().noneMatch(ship -> ids.contains(ship.id()))) continue;
                    for (ShipSnapshot ship : group.ships()) {
                        if (ids.add(ship.id())) ships.add(ship);
                    }
                    previous.remove();
                }
                groups.add(new GroupSnapshot(level.dimension(), sub.getUniqueId(), ships));
            }
        }
        return groups;
    }

    private static void processPending(MinecraftServer server, long now) {
        Set<UUID> blocked = new HashSet<>();
        Iterator<QueuedMove> it = pendingMoves.iterator();
        while (it.hasNext()) {
            QueuedMove queued = it.next();
            SpatialMove move = queued.move;
            Set<UUID> ids = move.source().ids();
            if (ids.stream().anyMatch(blocked::contains) || now < queued.retryAt) {
                blocked.addAll(ids);
                continue;
            }
            boolean succeeded = false;
            try {
                ServerLevel src = server.getLevel(move.source().dimension());
                ServerLevel dst = server.getLevel(move.destination().dimension());
                ServerSubLevelContainer container = src == null ? null : SubLevelContainer.getContainer(src);
                ServerSubLevel sub = container == null ? null : (ServerSubLevel) container.getSubLevel(move.source().rootId());
                if (sub != null && !sub.isRemoved() && dst != null) {
                    Set<UUID> currentIds = new HashSet<>();
                    for (ServerSubLevel member : SubLevelHelper.getLoadingDependencyChain(sub)) {
                        if (!member.isRemoved()) currentIds.add(member.getUniqueId());
                    }
                    if (ids.equals(currentIds)) {
                        succeeded = PortalTeleport.teleportSpatial(src, sub, dst, move.destination().rootPosition(), move.label());
                        if (succeeded) {
                            for (UUID id : ids) scheduleSyntheticSettles(dst, id);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                AeroPortals.LOGGER.warn("[AeroPortals] AE2 spatial move for {} failed; retaining it for retry", move.source().rootId(), failure);
            }
            if (succeeded) {
                it.remove();
            } else {
                queued.retryAt = now + 20;
                blocked.addAll(ids);
            }
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

    private static List<GroupSnapshot> containedGroups(List<GroupSnapshot> groups, ResourceKey<Level> dimension,
                                                      AABB region, int plotId) {
        AABB tolerant = region.inflate(0.5);
        List<GroupSnapshot> result = new ArrayList<>();
        for (GroupSnapshot group : groups) {
            if (!group.dimension().equals(dimension)) continue;
            if (group.ships().stream().noneMatch(ship -> ship.bounds().intersects(region))) continue;
            if (group.ships().stream().anyMatch(ship -> !containsAabb(tolerant, ship.bounds()))) {
                AeroPortals.LOGGER.warn("[AeroPortals] sub {} overlaps AE2 spatial region for plot {} but its chain is not fully inside; leaving it behind",
                        group.rootId(), plotId);
                continue;
            }
            result.add(group);
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
