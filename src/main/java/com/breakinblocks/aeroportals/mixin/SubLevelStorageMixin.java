package com.breakinblocks.aeroportals.mixin;

import com.breakinblocks.aeroportals.portal.JournalSaveCapture;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SubLevelStorage.class, remap = false)
abstract class SubLevelStorageMixin {
    @Inject(method = "attemptSaveSubLevel(Lnet/minecraft/world/level/ChunkPos;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;", at = @At("HEAD"))
    private void aeroportals$captureNewSnapshot(ChunkPos pos, SubLevelData data,
                                               CallbackInfoReturnable<GlobalSavedSubLevelPointer> ci) {
        JournalSaveCapture.record((SubLevelStorage) (Object) this, data);
    }

    @Inject(method = "attemptSaveSubLevel(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)V", at = @At("HEAD"))
    private void aeroportals$captureUpdatedSnapshot(GlobalSavedSubLevelPointer pointer, SubLevelData data, CallbackInfo ci) {
        JournalSaveCapture.record((SubLevelStorage) (Object) this, data);
    }
}
