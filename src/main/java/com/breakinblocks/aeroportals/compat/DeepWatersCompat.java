package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;
import java.util.Optional;

public final class DeepWatersCompat {
    private static final ResourceLocation PORTAL_ID = ResourceLocation.fromNamespaceAndPath("deepwaters", "endlesscaves_portal");
    private static final ResourceKey<PoiType> PORTAL_POI = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, PORTAL_ID);
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("deepwaters", "endlesscaves"));

    private DeepWatersCompat() {}

    public static Block portalBlock() {
        return BuiltInRegistries.BLOCK.getOptional(PORTAL_ID)
                .filter(block -> block.defaultBlockState().hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
                .orElse(null);
    }

    public static boolean isPortalBlock(BlockState state) {
        return state.getBlockHolder().is(PORTAL_ID);
    }

    public static Optional<BlockPos> findClosestPortalPosition(ServerLevel level, BlockPos center) {
        int radius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        PoiManager pois = level.getPoiManager();
        pois.ensureLoadedAndValid(level, center, radius);
        return pois.getInSquare(holder -> holder.is(PORTAL_POI), center, radius, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(level.getWorldBorder()::isWithinBounds)
                .filter(pos -> isPortalBlock(level.getBlockState(pos)))
                .min(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(center)).thenComparingInt(Vec3i::getY));
    }
}
