package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public final class CreateContraptionCompat {
    private static final boolean CREATE_LOADED = ModList.get().isLoaded("create");

    private CreateContraptionCompat() {}

    public static List<BlockPos> disassembleAssemblies(ServerLevel level, ServerSubLevel sub) {
        if (!CREATE_LOADED) return List.of();
        return Impl.disassembleAssemblies(level, sub);
    }

    public static List<AABB> captureGlue(ServerLevel level, ServerSubLevel sub) {
        if (!CREATE_LOADED) return List.of();
        return Impl.captureGlue(level, sub);
    }

    public static void replayGlue(ServerLevel level, List<AABB> glueBoxes, BlockPos shift) {
        if (!CREATE_LOADED || glueBoxes.isEmpty()) return;
        Impl.replayGlue(level, glueBoxes, shift);
    }

    public static void reassemble(ServerLevel level, List<BlockPos> bearingPositions, BlockPos shift) {
        if (!CREATE_LOADED || bearingPositions.isEmpty()) return;
        Impl.reassemble(level, bearingPositions, shift);
    }

    private static final class Impl {
        static List<BlockPos> disassembleAssemblies(ServerLevel level, ServerSubLevel sub) {
            List<BlockEntity> candidates = new ArrayList<>();
            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = level.getChunk(holder.getPos().x, holder.getPos().z);
                candidates.addAll(chunk.getBlockEntities().values());
            }

            List<BlockPos> toReassemble = new ArrayList<>();
            for (BlockEntity be : candidates) {
                try {
                    if (be instanceof MechanicalBearingBlockEntity bearing) {
                        boolean wasRunning = bearing.isRunning();
                        if (!wasRunning && bearing.getMovedContraption() == null) continue;
                        bearing.disassemble();
                        AeroPortals.LOGGER.debug("[AeroPortals] disassembled bearing {} at {} pre-teleport (wasRunning={}, contraptionCleared={})",
                                be.getType(), be.getBlockPos(), wasRunning, bearing.getMovedContraption() == null);
                        if (wasRunning && bearing.getMovedContraption() == null) {
                            toReassemble.add(be.getBlockPos());
                        }
                    } else if (be instanceof ClockworkBearingBlockEntity clock) {
                        if (!clock.isRunning()) continue;
                        clock.disassemble();
                        AeroPortals.LOGGER.debug("[AeroPortals] disassembled clockwork bearing at {} pre-teleport", be.getBlockPos());
                        toReassemble.add(be.getBlockPos());
                    } else if (be instanceof LinearActuatorBlockEntity actuator) {
                        if (!actuator.running) continue;
                        actuator.disassemble();
                        AeroPortals.LOGGER.debug("[AeroPortals] disassembled linear actuator {} at {} pre-teleport", be.getType(), be.getBlockPos());
                    }
                } catch (RuntimeException e) {
                    AeroPortals.LOGGER.error("[AeroPortals] failed to disassemble contraption controller {} at {} pre-teleport",
                            be.getType(), be.getBlockPos(), e);
                }
            }
            return toReassemble;
        }

        static List<AABB> captureGlue(ServerLevel level, ServerSubLevel sub) {
            AABB plotAabb = AabbUtil.plotAabb(sub);
            if (plotAabb == null) return List.of();
            List<AABB> boxes = new ArrayList<>();
            for (SuperGlueEntity glue : level.getEntitiesOfClass(SuperGlueEntity.class, plotAabb.inflate(2.0))) {
                boxes.add(glue.getBoundingBox());
                glue.discard();
            }
            if (!boxes.isEmpty()) {
                AeroPortals.LOGGER.debug("[AeroPortals] captured {} super glue box(es) from sub {} pre-teleport", boxes.size(), sub.getUniqueId());
            }
            return boxes;
        }

        static void replayGlue(ServerLevel level, List<AABB> glueBoxes, BlockPos shift) {
            for (AABB box : glueBoxes) {
                AABB moved = box.move(shift.getX(), shift.getY(), shift.getZ());
                level.addFreshEntity(new SuperGlueEntity(level, moved));
            }
            AeroPortals.LOGGER.debug("[AeroPortals] replayed {} super glue box(es) post-teleport (shift {})", glueBoxes.size(), shift);
        }

        static void reassemble(ServerLevel level, List<BlockPos> bearingPositions, BlockPos shift) {
            for (BlockPos pos : bearingPositions) {
                BlockPos target = pos.offset(shift);
                BlockEntity be = level.getBlockEntity(target);
                try {
                    if (be instanceof MechanicalBearingBlockEntity bearing) {
                        bearing.assemble();
                        AeroPortals.LOGGER.debug("[AeroPortals] reassembled bearing {} at {} post-teleport (running={})",
                                be.getType(), target, bearing.isRunning());
                    } else if (be instanceof ClockworkBearingBlockEntity clock) {
                        clock.assemble();
                        AeroPortals.LOGGER.debug("[AeroPortals] reassembled clockwork bearing at {} post-teleport (running={})",
                                target, clock.isRunning());
                    } else {
                        AeroPortals.LOGGER.warn("[AeroPortals] expected a bearing at {} post-teleport but found {}; leaving disassembled",
                                target, be == null ? "no block entity" : be.getType());
                    }
                } catch (RuntimeException e) {
                    AeroPortals.LOGGER.error("[AeroPortals] failed to reassemble bearing at {} post-teleport; blocks are preserved but the bearing must be restarted manually",
                            target, e);
                }
            }
        }
    }
}
