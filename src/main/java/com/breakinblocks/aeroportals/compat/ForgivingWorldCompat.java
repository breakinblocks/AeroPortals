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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class ForgivingWorldCompat {
    public static final String MOD_ID = "forgivingworld";
    private static final String MOD_CLASS = "com.forgivingworld.ForgivingWorldMod";
    private static final String CONFIG_CLASS = "com.forgivingworld.config.CommonConfiguration";
    private static final String DIMENSION_DATA_CLASS = "com.forgivingworld.config.DimensionData";
    private static final double ARRIVAL_MARGIN = 2.0;

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static Object cupboardConfig;
    private static Method getCommonConfig;
    private static Field connectionsField;
    private static Field toField;
    private static Field xMultField;
    private static Field zMultField;
    private static Field aboveYField;
    private static Field belowYField;
    private static Field teleportToYField;
    private static Field yspawnField;

    private ForgivingWorldCompat() {}

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
            Class<?> modClass = Class.forName(MOD_CLASS);
            Object config = modClass.getField("config").get(null);
            Method getCommon = config.getClass().getMethod("getCommonConfig");

            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Field connections = configClass.getField("dimensionConnections");

            Class<?> dataClass = Class.forName(DIMENSION_DATA_CLASS);
            Field to = dataClass.getField("to");
            Field xMult = dataClass.getField("xMult");
            Field zMult = dataClass.getField("zMult");
            Field aboveY = dataClass.getField("aboveY");
            Field belowY = dataClass.getField("belowY");
            Field teleportToY = dataClass.getField("teleportToYlevel");
            Field yspawn = dataClass.getField("yspawn");

            cupboardConfig = config;
            getCommonConfig = getCommon;
            connectionsField = connections;
            toField = to;
            xMultField = xMult;
            zMultField = zMult;
            aboveYField = aboveY;
            belowYField = belowY;
            teleportToYField = teleportToY;
            yspawnField = yspawn;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Forgiving World compat initialized");
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Forgiving World loaded but expected class not found ({}); compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Forgiving World internals renamed/missing ({}); compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Forgiving World compat init failed: {}", t.toString());
            return false;
        }
    }

    private record Link(ResourceLocation to, double xMult, double zMult, int aboveY, int belowY, int teleportToY, String spawnType) {}

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, List<Object>> connections() {
        try {
            Object common = getCommonConfig.invoke(cupboardConfig);
            return (Map<ResourceLocation, List<Object>>) connectionsField.get(common);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] failed to read Forgiving World config: {}", e.toString());
            return null;
        }
    }

    private static Link readLink(Object data) {
        try {
            return new Link(
                    (ResourceLocation) toField.get(data),
                    xMultField.getDouble(data),
                    zMultField.getDouble(data),
                    aboveYField.getInt(data),
                    belowYField.getInt(data),
                    teleportToYField.getInt(data),
                    String.valueOf(yspawnField.get(data)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] failed to read Forgiving World dimension link: {}", e.toString());
            return null;
        }
    }

    public static void scan(ServerLevel level) {
        if (!isAvailable()) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        Map<ResourceLocation, List<Object>> connections = connections();
        if (connections == null) return;
        List<Object> links = connections.get(level.dimension().location());
        if (links == null || links.isEmpty()) return;

        long now = level.getServer().getTickCount();
        for (ServerSubLevel sub : List.copyOf(subs)) {
            if (sub.isRemoved()) continue;
            if (PortalCooldown.isOnCooldown(sub.getUniqueId(), now)) continue;

            AABB aabb = AabbUtil.worldAabb(sub);
            for (Object raw : links) {
                Link link = readLink(raw);
                if (link == null || link.to() == null) continue;
                boolean down = link.belowY() != Integer.MIN_VALUE && aabb.minY < link.belowY();
                boolean up = link.aboveY() != Integer.MAX_VALUE && aabb.maxY > link.aboveY();
                if (!down && !up) continue;
                travel(level, sub, aabb, connections, link, down);
                break;
            }
        }
    }

    private static void travel(ServerLevel srcLevel, ServerSubLevel sub, AABB aabb,
                               Map<ResourceLocation, List<Object>> connections, Link link, boolean down) {
        ServerLevel dstLevel = srcLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, link.to()));
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] Forgiving World link targets unknown dimension {}; skipping", link.to());
            return;
        }

        Vec3 center = aabb.getCenter();
        double dstX = center.x * link.xMult();
        double dstZ = center.z * link.zMult();
        double height = aabb.maxY - aabb.minY;

        double bottom = "GROUND".equals(link.spawnType())
                ? PortalTeleport.loadedSurfaceY(dstLevel, (int) Math.floor(dstX), (int) Math.floor(dstZ)) + 1
                : link.teleportToY();

        double lowBound = dstLevel.getMinBuildHeight() + ARRIVAL_MARGIN;
        double highBound = dstLevel.getMaxBuildHeight() - ARRIVAL_MARGIN - height;
        List<Object> dstLinks = connections.get(link.to());
        if (dstLinks != null) {
            for (Object raw : dstLinks) {
                Link dstLink = readLink(raw);
                if (dstLink == null) continue;
                if (dstLink.belowY() != Integer.MIN_VALUE) {
                    lowBound = Math.max(lowBound, dstLink.belowY() + ARRIVAL_MARGIN);
                }
                if (dstLink.aboveY() != Integer.MAX_VALUE) {
                    highBound = Math.min(highBound, dstLink.aboveY() - ARRIVAL_MARGIN - height);
                }
            }
        }
        if (lowBound > highBound) {
            AeroPortals.LOGGER.warn("[AeroPortals] sub {} ({} blocks tall) does not fit between the travel boundaries of {}; stack teleport skipped",
                    sub.getUniqueId(), height, link.to());
            PortalCooldown.mark(sub.getUniqueId(), srcLevel.getServer().getTickCount());
            return;
        }
        bottom = Math.max(lowBound, Math.min(highBound, bottom));

        Vec3 subPos = subWorldPos(sub);
        double dstCenterY = bottom + (subPos.y - aabb.minY);

        AeroPortals.LOGGER.debug("[AeroPortals] sub {} crossed the {} boundary of {} (Forgiving World); travelling to {} (arrival bottom Y {})",
                sub.getUniqueId(), down ? "lower" : "upper", srcLevel.dimension().location(), link.to(), bottom);

        PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel,
                new Vec3(dstX, dstCenterY, dstZ), false,
                down ? "forgivingworld-down" : "forgivingworld-up");
    }

    private static Vec3 subWorldPos(ServerSubLevel sub) {
        var pose = sub.logicalPose();
        return new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
    }
}
