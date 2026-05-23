package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.Comparator;
import java.util.Optional;

public final class DeeperAndDarkerCompat {
    public static final String MOD_ID = "deeperdarker";
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "otherside_portal");
    private static final ResourceLocation OTHERSIDE_DIM_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "otherside");
    private static final ResourceLocation OTHERSIDE_PORTAL_POI_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "otherside_portal");
    private static final int POI_SEARCH_RADIUS = 128;

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Block portalBlock;
    private static volatile ResourceKey<Level> othersideKey;
    private static volatile ResourceKey<PoiType> portalPoiKey;

    private DeeperAndDarkerCompat() {}

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
                AeroPortals.LOGGER.warn("[AeroPortals] Deeper and Darker loaded but {} not in registry; compat disabled",
                        PORTAL_BLOCK_ID);
                return false;
            }
            PoiType poi = BuiltInRegistries.POINT_OF_INTEREST_TYPE.get(OTHERSIDE_PORTAL_POI_ID);
            if (poi == null) {
                AeroPortals.LOGGER.warn("[AeroPortals] Deeper and Darker loaded but POI {} not in registry; compat disabled",
                        OTHERSIDE_PORTAL_POI_ID);
                return false;
            }

            portalBlock = block;
            othersideKey = ResourceKey.create(Registries.DIMENSION, OTHERSIDE_DIM_ID);
            portalPoiKey = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, OTHERSIDE_PORTAL_POI_ID);
            initialized = true;
            AeroPortals.LOGGER.info("[AeroPortals] Deeper and Darker portal compat initialized (block={}, dim={}, poi={})",
                    PORTAL_BLOCK_ID, OTHERSIDE_DIM_ID, OTHERSIDE_PORTAL_POI_ID);
            return true;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Deeper and Darker compat init failed: {}", t.toString());
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
        return othersideKey;
    }

    public static ResourceKey<Level> returnDimension() {
        if (!isAvailable()) return null;
        return Level.OVERWORLD;
    }

    public static Optional<BlockPos> findClosestPortalPosition(ServerLevel level, BlockPos searchCenter) {
        if (!isAvailable()) return Optional.empty();
        PoiManager poiManager = level.getPoiManager();
        poiManager.ensureLoadedAndValid(level, searchCenter, POI_SEARCH_RADIUS);
        return poiManager.getInSquare(
                        holder -> holder.is(portalPoiKey),
                        searchCenter, POI_SEARCH_RADIUS, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(level.getWorldBorder()::isWithinBounds)
                .filter(p -> level.getBlockState(p).is(portalBlock))
                .min(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(searchCenter)).thenComparingInt(Vec3i::getY));
    }
}
