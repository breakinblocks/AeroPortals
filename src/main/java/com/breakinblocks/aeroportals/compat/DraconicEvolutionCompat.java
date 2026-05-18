package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class DraconicEvolutionCompat {
    public static final String MOD_ID = "draconicevolution";
    private static final ResourceLocation PORTAL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "portal");
    private static final String TILE_PORTAL_CLASS = "com.brandon3055.draconicevolution.blocks.tileentity.TilePortal";
    private static final String DISLOCATOR_CLASS = "com.brandon3055.draconicevolution.items.tools.Dislocator";
    private static final String TARGET_POS_CLASS = "com.brandon3055.brandonscore.utils.TargetPos";
    private static final String VECTOR3_CLASS = "codechicken.lib.vec.Vector3";

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static volatile Block portalBlock;
    private static volatile Class<?> tilePortalClass;
    private static volatile Method tilePortalGetController;
    private static volatile Field receptacleItemHandler;
    private static volatile Method itemHandlerGetStackInSlot;
    private static volatile Class<?> dislocatorClass;
    private static volatile Method dislocatorGetTargetPos;
    private static volatile Method targetPosDimension;
    private static volatile Method targetPosGetPos;
    private static volatile Field vector3X;
    private static volatile Field vector3Y;
    private static volatile Field vector3Z;

    private DraconicEvolutionCompat() {}

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
                AeroPortals.LOGGER.warn("[AeroPortals] DE loaded but {} not in registry; compat disabled", PORTAL_BLOCK_ID);
                return false;
            }

            Class<?> tilePortal = Class.forName(TILE_PORTAL_CLASS);
            Method getController = tilePortal.getMethod("getController");

            Class<?> receptacleClass = getController.getReturnType();
            Field itemHandler = receptacleClass.getField("itemHandler");
            Method getStackInSlot = itemHandler.getType().getMethod("getStackInSlot", int.class);

            Class<?> dislocator = Class.forName(DISLOCATOR_CLASS);
            Method getTargetPos = dislocator.getMethod("getTargetPos", ItemStack.class, Level.class);

            Class<?> targetPos = Class.forName(TARGET_POS_CLASS);
            Method dim = targetPos.getMethod("dimension");
            Method pos = targetPos.getMethod("pos");

            Class<?> vector3 = Class.forName(VECTOR3_CLASS);
            Field vx = vector3.getField("x");
            Field vy = vector3.getField("y");
            Field vz = vector3.getField("z");

            portalBlock = block;
            tilePortalClass = tilePortal;
            tilePortalGetController = getController;
            receptacleItemHandler = itemHandler;
            itemHandlerGetStackInSlot = getStackInSlot;
            dislocatorClass = dislocator;
            dislocatorGetTargetPos = getTargetPos;
            targetPosDimension = dim;
            targetPosGetPos = pos;
            vector3X = vx;
            vector3Y = vy;
            vector3Z = vz;
            initialized = true;
            AeroPortals.LOGGER.info("[AeroPortals] Draconic Evolution compat initialized (block={})", PORTAL_BLOCK_ID);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] DE loaded but expected class not found ({}); compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] DE API method/field renamed/missing ({}); compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] DE compat init failed: {}", t.toString());
            return false;
        }
    }

    public static boolean isPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        return state.is(portalBlock);
    }

    public record Destination(ResourceKey<Level> dim, BlockPos pos) {}

    public static Optional<Destination> readDestination(Level level, BlockPos portalPos) {
        if (!isAvailable()) return Optional.empty();
        BlockEntity be = level.getBlockEntity(portalPos);
        if (be == null || !tilePortalClass.isInstance(be)) return Optional.empty();
        try {
            Object receptacle = tilePortalGetController.invoke(be);
            if (receptacle == null) return Optional.empty();
            Object itemHandler = receptacleItemHandler.get(receptacle);
            if (itemHandler == null) return Optional.empty();
            ItemStack stack = (ItemStack) itemHandlerGetStackInSlot.invoke(itemHandler, 0);
            if (stack == null || stack.isEmpty()) return Optional.empty();
            if (!dislocatorClass.isInstance(stack.getItem())) return Optional.empty();

            Object targetPos = dislocatorGetTargetPos.invoke(stack.getItem(), stack, level);
            if (targetPos == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            ResourceKey<Level> dim = (ResourceKey<Level>) targetPosDimension.invoke(targetPos);
            Object vector3 = targetPosGetPos.invoke(targetPos);
            if (dim == null || vector3 == null) return Optional.empty();
            double x = vector3X.getDouble(vector3);
            double y = vector3Y.getDouble(vector3);
            double z = vector3Z.getDouble(vector3);
            return Optional.of(new Destination(dim, BlockPos.containing(x, y, z)));
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read DE portal destination at {}: {}", portalPos, e.getMessage());
            return Optional.empty();
        }
    }
}
