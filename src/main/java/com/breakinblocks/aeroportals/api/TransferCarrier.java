package com.breakinblocks.aeroportals.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.List;

public interface TransferCarrier<T> {
    ResourceLocation id();

    T capture(ServerLevel srcLevel, ServerSubLevel sub);

    void replay(ServerLevel dstLevel, ServerSubLevel newSub, T captured, BlockPos plotShift);

    /** Removes carried state from a retired copy without replaying it; external-state snapshots may override with a no-op. */
    default void discard(ServerLevel level, ServerSubLevel sub) {
        capture(level, sub);
    }

    default void validateGroup(ServerLevel source, List<ServerSubLevel> group, ServerLevel destination) {}

    default CompoundTag serialize(T captured) {
        return CarrierNbt.encode(captured);
    }

    @SuppressWarnings("unchecked")
    default T deserialize(CompoundTag tag) {
        return (T) CarrierNbt.decode(tag);
    }

    default boolean isEnabled() {
        return true;
    }
}
