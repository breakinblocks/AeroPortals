package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

public final class CreateEnderGatewayCompat {
    public static final String MOD_ID = "createendergateway";
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "gateway_portal");
    private static final String GATEWAY_BE_CLASS = "io.github.ayohee.createendergateway.content.blockentity.GatewayBlockEntity";

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static volatile Block portalBlock;
    private static volatile Class<?> gatewayBeClass;
    private static volatile Method getLinkedPos;

    private CreateEnderGatewayCompat() {}

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
                AeroPortals.LOGGER.warn("[AeroPortals] Create: Ender Gateway loaded but {} not in registry; compat disabled", PORTAL_BLOCK_ID);
                return false;
            }
            Class<?> beClass = Class.forName(GATEWAY_BE_CLASS);
            Method linkedPos = beClass.getMethod("getLinkedPos");

            portalBlock = block;
            gatewayBeClass = beClass;
            getLinkedPos = linkedPos;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Create: Ender Gateway compat initialized (block={})", PORTAL_BLOCK_ID);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Create: Ender Gateway loaded but expected class not found ({}); compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Create: Ender Gateway API method renamed/missing ({}); compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Create: Ender Gateway compat init failed: {}", t.toString());
            return false;
        }
    }

    public static boolean isPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        return state.is(portalBlock);
    }

    public static Block portalBlock() {
        return portalBlock;
    }

    public static Optional<BlockPos> readLinkedPos(ServerLevel level, BlockPos pos) {
        if (!isAvailable()) return Optional.empty();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !gatewayBeClass.isInstance(be)) return Optional.empty();
        try {
            Object linked = getLinkedPos.invoke(be);
            return linked instanceof BlockPos linkedPos ? Optional.of(linkedPos) : Optional.empty();
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read ender gateway link at {}: {}", pos, e.getMessage());
            return Optional.empty();
        }
    }
}
