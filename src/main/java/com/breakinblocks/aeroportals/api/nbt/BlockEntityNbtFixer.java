package com.breakinblocks.aeroportals.api.nbt;

import net.minecraft.nbt.CompoundTag;

@FunctionalInterface
public interface BlockEntityNbtFixer {
    void fix(CompoundTag tag, NbtFixContext context);
}
