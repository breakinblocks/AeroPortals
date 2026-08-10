package com.breakinblocks.aeroportals.api.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public final class NbtFixers {
    private NbtFixers() {}

    public static BlockEntityNbtFixer blockPos(String... keys) {
        String[] copy = keys.clone();
        return (tag, ctx) -> {
            if (!ctx.moved()) return;
            for (String key : copy) {
                Tag value = tag.get(key);
                Tag fixed = shiftPosTag(value, ctx);
                if (fixed != null) tag.put(key, fixed);
            }
        };
    }

    public static BlockEntityNbtFixer dimensionId(String... keys) {
        String[] copy = keys.clone();
        return (tag, ctx) -> {
            if (!ctx.dimensionChanged()) return;
            String srcFull = ctx.srcDimensionId().toString();
            String srcPath = ctx.srcDimensionId().getPath();
            String dstFull = ctx.dstDimensionId().toString();
            String dstPath = ctx.dstDimensionId().getPath();
            for (String key : copy) {
                if (!tag.contains(key, Tag.TAG_STRING)) continue;
                String current = tag.getString(key);
                if (current.equals(srcFull)) {
                    tag.putString(key, dstFull);
                } else if (current.equals(srcPath)) {
                    tag.putString(key, dstPath);
                }
            }
        };
    }

    public static BlockEntityNbtFixer nested(String path, BlockEntityNbtFixer inner) {
        String[] parts = path.split("\\.");
        return (tag, ctx) -> {
            CompoundTag cursor = tag;
            for (String part : parts) {
                if (!cursor.contains(part, Tag.TAG_COMPOUND)) return;
                cursor = cursor.getCompound(part);
            }
            inner.fix(cursor, ctx);
        };
    }

    public static BlockEntityNbtFixer each(String listKey, BlockEntityNbtFixer inner) {
        return (tag, ctx) -> {
            if (!tag.contains(listKey, Tag.TAG_LIST)) return;
            ListTag list = tag.getList(listKey, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                inner.fix(list.getCompound(i), ctx);
            }
        };
    }

    public static BlockEntityNbtFixer all(BlockEntityNbtFixer... fixers) {
        BlockEntityNbtFixer[] copy = fixers.clone();
        return (tag, ctx) -> {
            for (BlockEntityNbtFixer fixer : copy) {
                fixer.fix(tag, ctx);
            }
        };
    }

    public static BlockEntityNbtFixer clearKeys(String... keys) {
        String[] copy = keys.clone();
        return (tag, ctx) -> {
            for (String key : copy) {
                tag.remove(key);
            }
        };
    }

    private static Tag shiftPosTag(Tag value, NbtFixContext ctx) {
        if (value == null) return null;
        BlockPos shift = ctx.plotShift();

        if (value instanceof IntArrayTag array) {
            int[] data = array.getAsIntArray();
            if (data.length != 3) return null;
            return new IntArrayTag(new int[]{
                    data[0] + shift.getX(), data[1] + shift.getY(), data[2] + shift.getZ()});
        }
        if (value instanceof LongTag packed) {
            return LongTag.valueOf(BlockPos.of(packed.getAsLong()).offset(shift).asLong());
        }
        if (value instanceof CompoundTag compound) {
            if (compound.contains("X", Tag.TAG_INT) && compound.contains("Y", Tag.TAG_INT) && compound.contains("Z", Tag.TAG_INT)) {
                CompoundTag out = compound.copy();
                out.putInt("X", compound.getInt("X") + shift.getX());
                out.putInt("Y", compound.getInt("Y") + shift.getY());
                out.putInt("Z", compound.getInt("Z") + shift.getZ());
                return out;
            }
            if (compound.contains("x", Tag.TAG_INT) && compound.contains("y", Tag.TAG_INT) && compound.contains("z", Tag.TAG_INT)) {
                CompoundTag out = compound.copy();
                out.putInt("x", compound.getInt("x") + shift.getX());
                out.putInt("y", compound.getInt("y") + shift.getY());
                out.putInt("z", compound.getInt("z") + shift.getZ());
                return out;
            }
            return null;
        }
        if (value instanceof ListTag list) {
            ListTag out = new ListTag();
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                Tag fixed = shiftPosTag(element, ctx);
                if (fixed != null) {
                    out.add(fixed);
                    changed = true;
                } else {
                    out.add(element);
                }
            }
            return changed ? out : null;
        }
        if (value instanceof StringTag) {
            return null;
        }
        return null;
    }
}
