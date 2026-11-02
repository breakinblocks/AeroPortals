package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

public final class TwilightForestCompat {
    public static final String MOD_ID = "twilightforest";
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "twilight_forest"));
    public static final Component UNWORTHY = Component.translatable("misc.twilightforest.portal_unworthy");
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "twilight_portal");
    private static final String CONFIG_CLASS = "twilightforest.config.TFConfig";

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Block portalBlock;
    private static volatile Method lockingAdvancementMethod;

    private TwilightForestCompat() {}

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
        Block block = BuiltInRegistries.BLOCK.get(PORTAL_BLOCK_ID);
        if (block == null || block.defaultBlockState().isAir()) {
            AeroPortals.LOGGER.warn("[AeroPortals] Twilight Forest loaded but {} not in registry; compat disabled", PORTAL_BLOCK_ID);
            return false;
        }
        if (!(block instanceof Portal)) {
            AeroPortals.LOGGER.warn("[AeroPortals] Twilight Forest portal block {} is no longer a vanilla portal; compat disabled",
                    PORTAL_BLOCK_ID);
            return false;
        }
        try {
            lockingAdvancementMethod = Class.forName(CONFIG_CLASS).getMethod("getPortalLockingAdvancement", Player.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Twilight Forest portal advancement lock not found ({}); ships will ignore it",
                    e.toString());
        }
        portalBlock = block;
        initialized = true;
        AeroPortals.LOGGER.debug("[AeroPortals] Twilight Forest portal compat initialized (block={})", PORTAL_BLOCK_ID);
        return true;
    }

    public static Block portalBlock() {
        if (!isAvailable()) return null;
        return portalBlock;
    }

    public static boolean isPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        return state == portalBlock.defaultBlockState();
    }

    public static boolean isAnyPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        return state.is(portalBlock);
    }

    public static DimensionTransition destination(ServerLevel srcLevel, BlockPos portalPos) {
        if (!isAvailable()) return null;
        Marker standIn = EntityType.MARKER.create(srcLevel);
        if (standIn == null) return null;
        standIn.moveTo(Vec3.atBottomCenterOf(portalPos));
        return ((Portal) portalBlock).getPortalDestination(srcLevel, standIn, portalPos);
    }

    public static boolean isLockedOutOfPortal(ServerPlayer player) {
        if (!isAvailable() || lockingAdvancementMethod == null) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        ResourceLocation lock;
        try {
            lock = (ResourceLocation) lockingAdvancementMethod.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read the Twilight Forest portal advancement lock: {}", e.toString());
            return false;
        }
        if (lock == null) return false;
        AdvancementHolder advancement = player.server.getAdvancements().get(lock);
        return advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
