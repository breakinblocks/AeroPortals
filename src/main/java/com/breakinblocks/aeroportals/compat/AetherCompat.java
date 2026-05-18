package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

public final class AetherCompat {
    public static final String MOD_ID = "aether";
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "aether_portal");
    private static final String LEVEL_UTIL_CLASS = "com.aetherteam.aether.world.LevelUtil";
    private static final String PORTAL_FORCER_CLASS = "com.aetherteam.aether.block.portal.AetherPortalForcer";

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Block portalBlock;
    private static volatile Method destinationDimMethod;
    private static volatile Method returnDimMethod;
    private static volatile Constructor<?> portalForcerCtor;
    private static volatile Method findClosestPortalMethod;

    private AetherCompat() {}

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
            Block block = BuiltInRegistries.BLOCK.get(PORTAL_BLOCK_ID);
            if (block == null || block.defaultBlockState().isAir()) {
                AeroPortals.LOGGER.warn("[AeroPortals] Aether loaded but {} not in registry; compat disabled",
                        PORTAL_BLOCK_ID);
                return false;
            }
            Class<?> levelUtil = Class.forName(LEVEL_UTIL_CLASS);
            Method dest = levelUtil.getMethod("destinationDimension");
            Method ret = levelUtil.getMethod("returnDimension");

            Class<?> forcer = Class.forName(PORTAL_FORCER_CLASS);
            Constructor<?> ctor = forcer.getConstructor(ServerLevel.class);
            Method find = forcer.getMethod("findClosestPortalPosition", BlockPos.class, WorldBorder.class);

            portalBlock = block;
            destinationDimMethod = dest;
            returnDimMethod = ret;
            portalForcerCtor = ctor;
            findClosestPortalMethod = find;
            initialized = true;
            AeroPortals.LOGGER.info("[AeroPortals] Aether portal compat initialized (block={}, levelUtil={}, forcer={})",
                    PORTAL_BLOCK_ID, LEVEL_UTIL_CLASS, PORTAL_FORCER_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Aether loaded but expected class not found ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Aether API method renamed/missing ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Aether compat init failed: {}", t.toString());
            return false;
        }
    }

    public static Block portalBlock() {
        if (!isAvailable()) return null;
        return portalBlock;
    }

    public static boolean isPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        return state.is(portalBlock);
    }

    public static ResourceKey<Level> destinationDimension() {
        if (!isAvailable()) return null;
        try {
            @SuppressWarnings("unchecked")
            ResourceKey<Level> key = (ResourceKey<Level>) destinationDimMethod.invoke(null);
            return key;
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to invoke LevelUtil.destinationDimension: {}", e.getMessage());
            return null;
        }
    }

    public static ResourceKey<Level> returnDimension() {
        if (!isAvailable()) return null;
        try {
            @SuppressWarnings("unchecked")
            ResourceKey<Level> key = (ResourceKey<Level>) returnDimMethod.invoke(null);
            return key;
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to invoke LevelUtil.returnDimension: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<BlockPos> findClosestPortalPosition(ServerLevel level, BlockPos searchCenter) {
        if (!isAvailable()) return Optional.empty();
        try {
            Object forcer = portalForcerCtor.newInstance(level);
            return (Optional<BlockPos>) findClosestPortalMethod.invoke(forcer, searchCenter, level.getWorldBorder());
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to invoke AetherPortalForcer.findClosestPortalPosition: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }
}
