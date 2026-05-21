package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

public final class TelepastriesCompat {
    public static final String MOD_ID = "telepastries";
    private static final String BLOCK_CAKE_BASE_CLASS = "com.mrbysco.telepastries.blocks.cake.BlockCakeBase";

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Class<?> blockCakeBaseClass;
    private static volatile Method getCakeWorldMethod;
    private static volatile Method consumeCakeMethod;

    private TelepastriesCompat() {}

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
            Class<?> baseClass = Class.forName(BLOCK_CAKE_BASE_CLASS);
            Method cakeWorldMethod = baseClass.getMethod("getCakeWorld");
            Method consumeMethod = baseClass.getMethod("consumeCake");

            blockCakeBaseClass = baseClass;
            getCakeWorldMethod = cakeWorldMethod;
            consumeCakeMethod = consumeMethod;
            initialized = true;
            AeroPortals.LOGGER.info("[AeroPortals] TelePastries compat initialized");
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] TelePastries loaded but BlockCakeBase class not found ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] TelePastries BlockCakeBase missing expected method ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] TelePastries compat init failed: {}", t.toString());
            return false;
        }
    }

    public static boolean isTeleCake(BlockState state) {
        if (!isAvailable()) return false;
        return blockCakeBaseClass.isInstance(state.getBlock());
    }

    @SuppressWarnings("unchecked")
    public static ResourceKey<Level> getCakeDestination(BlockState state) {
        if (!isTeleCake(state)) return null;
        try {
            Object key = getCakeWorldMethod.invoke(state.getBlock());
            if (key instanceof ResourceKey<?> rk) {
                return (ResourceKey<Level>) rk;
            }
            return null;
        } catch (Throwable t) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read TelePastries getCakeWorld(): {}", t.toString());
            return null;
        }
    }

    private static boolean shouldConsumeCake(BlockState state) {
        if (!isTeleCake(state)) return true;
        try {
            Object result = consumeCakeMethod.invoke(state.getBlock());
            return result instanceof Boolean b ? b : true;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] failed to read TelePastries consumeCake(): {}", t.toString());
            return true;
        }
    }

    public static void eatSlice(Level level, BlockPos pos, BlockState state, ServerPlayer player) {
        player.awardStat(Stats.EAT_CAKE_SLICE);
        player.getFoodData().eat(2, 0.1F);
        if (!shouldConsumeCake(state)) return;
        if (player.getAbilities().instabuild) return;

        IntegerProperty bitesProp = BlockStateProperties.BITES;
        if (!state.hasProperty(bitesProp)) return;

        int bites = state.getValue(bitesProp);
        level.gameEvent(player, GameEvent.EAT, pos);
        if (bites < 6) {
            level.setBlock(pos, state.setValue(bitesProp, bites + 1), 3);
        } else {
            level.removeBlock(pos, false);
        }
    }
}
