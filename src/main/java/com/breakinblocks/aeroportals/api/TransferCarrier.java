package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public interface TransferCarrier<T> {
    ResourceLocation id();

    T capture(ServerLevel srcLevel, ServerSubLevel sub);

    void replay(ServerLevel dstLevel, ServerSubLevel newSub, T captured, BlockPos plotShift);

    default boolean isEnabled() {
        return true;
    }
}
