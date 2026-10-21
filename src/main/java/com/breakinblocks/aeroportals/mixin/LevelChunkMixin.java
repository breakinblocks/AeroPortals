package com.breakinblocks.aeroportals.mixin;

import com.breakinblocks.aeroportals.portal.TransferRemovalScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.function.Consumer;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @WrapOperation(method = "clearAllBlockEntities", at = @At(value = "INVOKE",
            target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V", ordinal = 0))
    private void aeroportals$finishUnloading(Collection<BlockEntity> entities, Consumer<BlockEntity> callback,
                                            Operation<Void> original) {
        original.call(entities, (Consumer<BlockEntity>) blockEntity -> TransferRemovalScope.unload(blockEntity, callback));
    }

    @WrapOperation(method = "clearAllBlockEntities", at = @At(value = "INVOKE",
            target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V", ordinal = 1))
    private void aeroportals$finishRemoval(Collection<BlockEntity> entities, Consumer<BlockEntity> callback,
                                          Operation<Void> original) {
        original.call(entities, (Consumer<BlockEntity>) blockEntity -> TransferRemovalScope.unload(blockEntity, callback));
    }
}
