package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Method;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class AeronauticsBalloonFixup {
    private static final String MOD_ID = "aeronautics";
    private static final String GAS_PROVIDER_CLASS = "dev.eriksonn.aeronautics.content.blocks.hot_air.BlockEntityLiftingGasProvider";

    private static volatile boolean attempted = false;
    private static volatile Class<?> gasProviderClass = null;
    private static volatile Method tryCreateBalloon = null;

    private AeronauticsBalloonFixup() {}

    @SubscribeEvent
    public static void onSubLevelTransfer(SubLevelTransferEvent event) {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        if (!resolveReflectionTargets()) return;

        ServerSubLevel sub = event.newSub();
        int rebuilt = 0;
        for (var chunkHolder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!gasProviderClass.isInstance(be)) continue;
                try {
                    tryCreateBalloon.invoke(be);
                    rebuilt++;
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    AeroPortals.LOGGER.error("[AeroPortals] balloon rebuild failed @ {}", be.getBlockPos(), ex);
                }
            }
        }
        if (rebuilt > 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] asked {} Aeronautics gas provider(s) to rebuild their balloon on sub {}",
                    rebuilt, event.subUuid());
        }
    }

    private static boolean resolveReflectionTargets() {
        if (attempted) return gasProviderClass != null;
        synchronized (AeronauticsBalloonFixup.class) {
            if (attempted) return gasProviderClass != null;
            attempted = true;
            try {
                Class<?> cls = Class.forName(GAS_PROVIDER_CLASS);
                Method method = cls.getMethod("tryCreateBalloon");
                tryCreateBalloon = method;
                gasProviderClass = cls;
                return true;
            } catch (ClassNotFoundException e) {
                AeroPortals.LOGGER.debug("[AeroPortals] Aeronautics lifting-gas class not present; skipping balloon fixup");
                return false;
            } catch (NoSuchMethodException e) {
                AeroPortals.LOGGER.warn("[AeroPortals] Aeronautics tryCreateBalloon() not found on {} (Aeronautics API changed?)",
                        GAS_PROVIDER_CLASS);
                return false;
            }
        }
    }
}
