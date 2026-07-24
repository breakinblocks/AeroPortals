package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class SpdStackCompat {
    public static final String MOD_ID = "stackable_planar_dimensions";
    private static final String VARIABLES_CLASS = "net.mcreator.stackableplanardimensions.network.StackablePlanarDimensionsModVariables";
    private static final String MAP_VARIABLES_CLASS = VARIABLES_CLASS + "$MapVariables";
    private static final double ARRIVAL_MARGIN = 2.0;

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static Field dimListField;
    private static Field dimMinField;
    private static Field dimMaxField;
    private static Method mapVariablesGet;
    private static Field enableModField;
    private static Field thresholdField;
    private static Field distanceField;

    private SpdStackCompat() {}

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
            Class<?> variablesClass = Class.forName(VARIABLES_CLASS);
            Field dimList = variablesClass.getField("dimList");
            Field dimMin = variablesClass.getField("dimMin");
            Field dimMax = variablesClass.getField("dimMax");

            Class<?> mapVariablesClass = Class.forName(MAP_VARIABLES_CLASS);
            Method get = mapVariablesClass.getMethod("get", net.minecraft.world.level.LevelAccessor.class);
            Field enableMod = mapVariablesClass.getField("enableMod");
            Field threshold = mapVariablesClass.getField("teleportationThreshold");
            Field distance = mapVariablesClass.getField("teleportationDistance");

            dimListField = dimList;
            dimMinField = dimMin;
            dimMaxField = dimMax;
            mapVariablesGet = get;
            enableModField = enableMod;
            thresholdField = threshold;
            distanceField = distance;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Stackable Planar Dimensions compat initialized");
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Stackable Planar Dimensions loaded but expected class not found ({}); compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Stackable Planar Dimensions internals renamed/missing ({}); compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Stackable Planar Dimensions compat init failed: {}", t.toString());
            return false;
        }
    }

    private record Stack(List<String> dims, double[] mins, double[] maxs, double threshold, double distance) {}

    private static Stack readStack(ServerLevel level) {
        try {
            Object mapVars = mapVariablesGet.invoke(null, level);
            if (mapVars == null || !enableModField.getBoolean(mapVars)) return null;
            List<?> dimListRaw = (List<?>) dimListField.get(null);
            List<?> dimMinRaw = (List<?>) dimMinField.get(null);
            List<?> dimMaxRaw = (List<?>) dimMaxField.get(null);
            if (dimListRaw == null || dimListRaw.size() < 2) return null;
            int n = dimListRaw.size();
            if (dimMinRaw == null || dimMaxRaw == null || dimMinRaw.size() < n || dimMaxRaw.size() < n) return null;

            List<String> dims = new ArrayList<>(n);
            double[] mins = new double[n];
            double[] maxs = new double[n];
            for (int i = 0; i < n; i++) {
                dims.add(String.valueOf(dimListRaw.get(i)).trim());
                mins[i] = parseDouble(dimMinRaw.get(i));
                maxs[i] = parseDouble(dimMaxRaw.get(i));
            }
            return new Stack(dims,
                    mins, maxs,
                    thresholdField.getDouble(mapVars),
                    distanceField.getDouble(mapVars));
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] failed to read Stackable Planar Dimensions stack: {}", e.toString());
            return null;
        }
    }

    private static double parseDouble(Object value) {
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static void scan(ServerLevel level) {
        if (!isAvailable()) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        Stack stack = readStack(level);
        if (stack == null) return;

        int index = stack.dims().indexOf(level.dimension().location().toString());
        if (index < 0) return;

        boolean hasDown = index < stack.dims().size() - 1;
        boolean hasUp = index > 0;
        if (!hasDown && !hasUp) return;

        MinecraftServer server = level.getServer();
        long now = server.getTickCount();

        for (ServerSubLevel sub : List.copyOf(subs)) {
            if (sub.isRemoved()) continue;
            if (PortalCooldown.isOnCooldown(sub.getUniqueId(), now)) continue;

            AABB aabb = AabbUtil.worldAabb(sub);
            if (hasDown && aabb.minY <= stack.mins()[index] + stack.threshold()) {
                travel(level, sub, aabb, stack, index, index + 1, true);
            } else if (hasUp && aabb.maxY >= stack.maxs()[index] - stack.threshold()) {
                travel(level, sub, aabb, stack, index, index - 1, false);
            }
        }
    }

    private static void travel(ServerLevel srcLevel, ServerSubLevel sub, AABB aabb, Stack stack, int srcIndex, int dstIndex, boolean down) {
        String dstId = stack.dims().get(dstIndex);
        ResourceLocation dstLoc = ResourceLocation.tryParse(dstId);
        if (dstLoc == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] dimension stack entry '{}' is not a valid dimension id; skipping", dstId);
            return;
        }
        ServerLevel dstLevel = srcLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dstLoc));
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] dimension stack target {} is not loaded; skipping", dstId);
            return;
        }

        double height = aabb.maxY - aabb.minY;
        double dstMin = stack.mins()[dstIndex];
        double dstMax = stack.maxs()[dstIndex];
        boolean dstHasDown = dstIndex < stack.dims().size() - 1;
        boolean dstHasUp = dstIndex > 0;

        double bottom = down
                ? dstMax - stack.distance()
                : dstMin + stack.distance();

        double lowBound = dstMin + (dstHasDown ? stack.threshold() + ARRIVAL_MARGIN : ARRIVAL_MARGIN);
        double highBound = dstMax - (dstHasUp ? stack.threshold() + ARRIVAL_MARGIN : ARRIVAL_MARGIN) - height;
        if (lowBound > highBound) {
            AeroPortals.LOGGER.warn("[AeroPortals] sub {} ({} blocks tall) does not fit between the travel boundaries of {}; stack teleport skipped",
                    sub.getUniqueId(), height, dstId);
            PortalCooldown.mark(sub.getUniqueId(), srcLevel.getServer().getTickCount());
            return;
        }
        bottom = Math.max(lowBound, Math.min(highBound, bottom));

        Vec3 center = aabb.getCenter();
        Vec3 subPos = subWorldPos(sub);
        double dstCenterY = bottom + (subPos.y - aabb.minY);

        AeroPortals.LOGGER.debug("[AeroPortals] sub {} crossed the {} boundary of {}; travelling to {} (arrival bottom Y {})",
                sub.getUniqueId(), down ? "lower" : "upper", srcLevel.dimension().location(), dstId, bottom);

        PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel,
                new Vec3(center.x, dstCenterY, center.z), false,
                down ? "dimension-stack-down" : "dimension-stack-up");
    }

    private static Vec3 subWorldPos(ServerSubLevel sub) {
        var pose = sub.logicalPose();
        return new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
    }
}
