package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.UUID;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class SimulatedRopeFixup {
    private SimulatedRopeFixup() {}

    @SubscribeEvent
    public static void onSubLevelTransfer(SubLevelTransferEvent event) {
        if (!SimulatedRopeCompat.isAvailable()) return;

        ServerSubLevel sub = event.newSub();
        ServerLevel level = event.dstLevel();
        UUID ownId = event.subUuid();

        for (var chunkHolder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                Object strand = SimulatedRopeCompat.ownedStrand(be);
                if (strand == null) continue;
                repair(level, strand, ownId, event);
            }
        }
    }

    private static void repair(ServerLevel level, Object strand, UUID ownId, SubLevelTransferEvent event) {
        boolean changed = false;
        for (SimulatedRopeCompat.AttachmentView attachment : SimulatedRopeCompat.attachmentsOf(strand)) {
            UUID attachedTo = attachment.subLevelId();
            if (attachedTo == null || attachedTo.equals(ownId)) continue;

            BlockPos remapped = event.remapPlotPos(attachment.blockPos());
            if (remapped.equals(attachment.blockPos())) continue;
            if (SimulatedRopeCompat.moveAttachment(level, strand, attachment, remapped)) {
                AeroPortals.LOGGER.debug("[AeroPortals] rope end on sub {} followed it to {} (was {})",
                        attachedTo, remapped, attachment.blockPos());
                changed = true;
            }
        }
        if (changed) {
            SimulatedRopeCompat.reattachConstraints(level, strand);
        }
    }
}
