package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public interface AeroPortalType {
    ResourceLocation id();

    boolean matches(BlockState state);

    PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos);

    default int priority() {
        return 0;
    }

    default boolean isEnabled() {
        return true;
    }
}
