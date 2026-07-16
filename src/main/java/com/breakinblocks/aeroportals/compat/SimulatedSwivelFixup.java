package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Method;
import java.util.UUID;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class SimulatedSwivelFixup {
    private static final String SIMULATED_MOD_ID = "simulated";
    private static final String BEARING_BE_CLASS_NAME = "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity";

    private static volatile boolean attempted = false;
    private static volatile Class<?> bearingClass = null;
    private static volatile Method getPlatePos = null;
    private static volatile Method setPlatePos = null;
    private static volatile Method getSubLevelID = null;
    private static volatile Method reattachConstraint = null;

    private SimulatedSwivelFixup() {}

    @SubscribeEvent
    public static void onSubLevelTransfer(SubLevelTransferEvent event) {
        if (!ModList.get().isLoaded(SIMULATED_MOD_ID)) return;
        if (!resolveReflectionTargets()) return;

        ServerSubLevel sub = event.newSub();
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(event.dstLevel());
        int updated = 0;
        for (var chunkHolder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!bearingClass.isInstance(be)) continue;
                if (fixupBearing(be, event, dstContainer)) updated++;
            }
        }
        if (updated > 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] remapped {} Simulated swivel-bearing plate position(s) on sub {}",
                    updated, event.subUuid());
        }
    }

    private static boolean fixupBearing(BlockEntity be, SubLevelTransferEvent event, ServerSubLevelContainer dstContainer) {
        try {
            BlockPos platePos = (BlockPos) getPlatePos.invoke(be);
            if (platePos == null) return false;
            BlockPos remapped = event.remapPlotPos(platePos);
            boolean changed = !remapped.equals(platePos);
            if (changed) {
                setPlatePos.invoke(be, remapped);
                be.setChanged();
                AeroPortals.LOGGER.debug("[AeroPortals] SwivelBearingBlockEntity @ {} platePos: {} -> {}",
                        be.getBlockPos(), platePos, remapped);
            }

            UUID attachedId = (UUID) getSubLevelID.invoke(be);
            if (attachedId != null && dstContainer != null) {
                SubLevel attached = dstContainer.getSubLevel(attachedId);
                if (attached != null) {
                    reattachConstraint.invoke(be, attached, true);
                } else {
                    AeroPortals.LOGGER.warn("[AeroPortals] swivel bearing @ {} references attached sub {} not present in {}; constraint not reattached",
                            be.getBlockPos(), attachedId, event.dstLevel().dimension().location());
                }
            }
            return changed;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] swivel bearing fixup failed @ {}", be.getBlockPos(), ex);
            return false;
        }
    }

    private static boolean resolveReflectionTargets() {
        if (attempted) return bearingClass != null;
        synchronized (SimulatedSwivelFixup.class) {
            if (attempted) return bearingClass != null;
            attempted = true;
            try {
                Class<?> cls = Class.forName(BEARING_BE_CLASS_NAME);
                Method getPlate = cls.getMethod("getPlatePos");
                Method setPlate = cls.getMethod("setPlatePos", BlockPos.class);
                Method getSubId = cls.getMethod("getSubLevelID");
                Method reattach = cls.getMethod("reattachConstraint", SubLevel.class, boolean.class);
                getPlatePos = getPlate;
                setPlatePos = setPlate;
                getSubLevelID = getSubId;
                reattachConstraint = reattach;
                bearingClass = cls;
                return true;
            } catch (ClassNotFoundException e) {
                AeroPortals.LOGGER.debug("[AeroPortals] Simulated swivel-bearing class not present; skipping fixup");
                return false;
            } catch (NoSuchMethodException e) {
                AeroPortals.LOGGER.warn("[AeroPortals] Simulated swivel-bearing method not found ({}); Simulated API changed?", e.getMessage());
                return false;
            }
        }
    }
}
