package com.breakinblocks.aeroportals.mixin;

import com.breakinblocks.aeroportals.portal.TransferRemovalScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.blockentity.qnb.QuantumBridgeBlockEntity", remap = false)
public abstract class QuantumBridgeBlockEntityMixin {
    @Inject(method = "neighborUpdate", at = @At("HEAD"), cancellable = true)
    private void aeroportals$skipUnloadingCluster(BlockPos fromPos, CallbackInfo ci) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        if (TransferRemovalScope.isRemoving(blockEntity.getLevel(), blockEntity.getBlockPos())) ci.cancel();
    }
}
