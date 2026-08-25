package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.util.Optional;

public final class ArsNouveauCompat {
    public static final String MOD_ID = "ars_nouveau";
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "portal");
    private static final String PORTAL_TILE_CLASS = "com.hollingsworth.arsnouveau.common.block.tile.PortalTile";

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Block portalBlock;
    private static volatile Class<?> portalTileClass;
    private static volatile Field warpPosField;
    private static volatile Field dimIdField;
    private static volatile Field rotationVecField;

    private ArsNouveauCompat() {}

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
                AeroPortals.LOGGER.warn("[AeroPortals] Ars Nouveau loaded but portal block {} not in registry; compat disabled",
                        PORTAL_BLOCK_ID);
                return false;
            }
            Class<?> cls = Class.forName(PORTAL_TILE_CLASS);
            Field warp = cls.getField("warpPos");
            Field dim = cls.getField("dimID");
            Field rot = cls.getField("rotationVec");

            portalBlock = block;
            portalTileClass = cls;
            warpPosField = warp;
            dimIdField = dim;
            rotationVecField = rot;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Ars Nouveau portal compat initialized (block={}, tile={})",
                    PORTAL_BLOCK_ID, PORTAL_TILE_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Ars Nouveau loaded but PortalTile class not found ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (NoSuchFieldException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Ars Nouveau PortalTile field renamed/missing ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Ars Nouveau compat init failed: {}", t.toString());
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

    public record Destination(ResourceKey<Level> dim, BlockPos warpPos, Vec2 rotation) {}

    public static Optional<Destination> readDestination(Level level, BlockPos pos) {
        if (!isAvailable()) return Optional.empty();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !portalTileClass.isInstance(be)) return Optional.empty();
        try {
            BlockPos warp = (BlockPos) warpPosField.get(be);
            String dimId = (String) dimIdField.get(be);
            Vec2 rot = (Vec2) rotationVecField.get(be);
            if (warp == null || dimId == null || dimId.isEmpty()) return Optional.empty();
            ResourceLocation dimRl;
            try {
                dimRl = ResourceLocation.parse(dimId);
            } catch (RuntimeException badId) {
                AeroPortals.LOGGER.warn("[AeroPortals] AN portal at {} has malformed dimID '{}': {}",
                        pos, dimId, badId.getMessage());
                return Optional.empty();
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimRl);
            return Optional.of(new Destination(dimKey, warp, rot != null ? rot : Vec2.ZERO));
        } catch (IllegalAccessException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read AN portal destination at {}: {}",
                    pos, e.getMessage());
            return Optional.empty();
        }
    }
}
