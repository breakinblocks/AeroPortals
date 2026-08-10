package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

import java.util.List;

public final class BuiltinCarriers {
    private BuiltinCarriers() {}

    public static void register() {
        AeroPortalsApi.registerCarrier(new CreateAssemblyCarrier());
        AeroPortalsApi.registerCarrier(new SuperGlueCarrier());
        AeroPortalsApi.registerCarrier(new HoneyGlueCarrier());
    }

    private static final class CreateAssemblyCarrier implements TransferCarrier<List<BlockPos>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("create_assemblies");
        }

        @Override
        public boolean isEnabled() {
            return ModList.get().isLoaded("create");
        }

        @Override
        public List<BlockPos> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<BlockPos> bearings = CreateContraptionCompat.disassembleAssemblies(srcLevel, sub);
            return bearings.isEmpty() ? null : bearings;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<BlockPos> captured, BlockPos plotShift) {
            CreateContraptionCompat.reassemble(dstLevel, captured, plotShift);
        }
    }

    private static final class SuperGlueCarrier implements TransferCarrier<List<AABB>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("create_super_glue");
        }

        @Override
        public boolean isEnabled() {
            return ModList.get().isLoaded("create");
        }

        @Override
        public List<AABB> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<AABB> boxes = CreateContraptionCompat.captureGlue(srcLevel, sub);
            return boxes.isEmpty() ? null : boxes;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<AABB> captured, BlockPos plotShift) {
            CreateContraptionCompat.replayGlue(dstLevel, captured, plotShift);
        }
    }

    private static final class HoneyGlueCarrier implements TransferCarrier<List<AABB>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("simulated_honey_glue");
        }

        @Override
        public boolean isEnabled() {
            return SimulatedCompat.isAvailable();
        }

        @Override
        public List<AABB> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<AABB> boxes = SimulatedCompat.captureHoneyGlue(srcLevel, sub);
            return boxes.isEmpty() ? null : boxes;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<AABB> captured, BlockPos plotShift) {
            SimulatedCompat.replayHoneyGlue(dstLevel, captured, plotShift);
        }
    }
}
