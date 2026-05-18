package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Field;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class SimulatedDockingFixup {
    private static final String SIMULATED_MOD_ID = "simulated";
    private static final String DOCKING_BE_CLASS_NAME = "dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity";
    private static final String OTHER_POS_FIELD = "otherConnectorPosition";

    private static volatile boolean attempted = false;
    private static volatile Class<?> dockingClass = null;
    private static volatile Field otherPosField = null;

    private SimulatedDockingFixup() {}

    @SubscribeEvent
    public static void onSubLevelTransfer(SubLevelTransferEvent event) {
        if (!ModList.get().isLoaded(SIMULATED_MOD_ID)) return;
        if (!resolveReflectionTargets()) return;

        ServerSubLevel sub = event.newSub();
        BlockPos translation = BlockPos.containing(event.translation());
        int updated = translateDockingPositions(sub, translation);
        if (updated > 0) {
            AeroPortals.LOGGER.info("[AeroPortals] translated {} Simulated docking-connector position(s) on sub {}",
                    updated, event.subUuid());
        }
    }

    private static boolean resolveReflectionTargets() {
        if (attempted) return dockingClass != null && otherPosField != null;
        synchronized (SimulatedDockingFixup.class) {
            if (attempted) return dockingClass != null && otherPosField != null;
            attempted = true;
            try {
                Class<?> cls = Class.forName(DOCKING_BE_CLASS_NAME);
                Field field = cls.getField(OTHER_POS_FIELD);
                dockingClass = cls;
                otherPosField = field;
                return true;
            } catch (ClassNotFoundException e) {
                AeroPortals.LOGGER.debug("[AeroPortals] Simulated docking-connector class not present; skipping fixup");
                return false;
            } catch (NoSuchFieldException e) {
                AeroPortals.LOGGER.warn("[AeroPortals] Simulated docking-connector field '{}' not found on {} (Simulated API changed?)",
                        OTHER_POS_FIELD, DOCKING_BE_CLASS_NAME);
                return false;
            }
        }
    }

    private static int translateDockingPositions(ServerSubLevel sub, BlockPos translation) {
        int updated = 0;
        for (var chunkHolder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!dockingClass.isInstance(be)) continue;
                try {
                    BlockPos current = (BlockPos) otherPosField.get(be);
                    if (current == null) continue;
                    BlockPos translated = current.offset(translation);
                    otherPosField.set(be, translated);
                    be.setChanged();
                    AeroPortals.LOGGER.info("[AeroPortals] DockingConnectorBlockEntity @ {} otherConnectorPosition: {} -> {}",
                            be.getBlockPos(), current, translated);
                    updated++;
                } catch (IllegalAccessException ex) {
                    AeroPortals.LOGGER.error("[AeroPortals] failed to access {}: {}", OTHER_POS_FIELD, ex.getMessage());
                }
            }
        }
        return updated;
    }
}
