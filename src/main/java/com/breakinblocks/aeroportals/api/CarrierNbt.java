package com.breakinblocks.aeroportals.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

/** Journal codec for the standard carrier payloads. Custom payloads supply their own codec. */
public final class CarrierNbt {
    private CarrierNbt() {}

    public static CompoundTag encode(Object value) {
        CompoundTag result = new CompoundTag();
        if (value instanceof String text) {
            result.putString("kind", "string");
            result.putString("value", text);
        } else if (value instanceof CompoundTag tag) {
            result.putString("kind", "nbt");
            result.put("value", tag.copy());
        } else if (value instanceof BlockPos pos) {
            result.putString("kind", "pos");
            result.putLong("value", pos.asLong());
        } else if (value instanceof AABB box) {
            result.putString("kind", "box");
            result.putDouble("x0", box.minX); result.putDouble("y0", box.minY); result.putDouble("z0", box.minZ);
            result.putDouble("x1", box.maxX); result.putDouble("y1", box.maxY); result.putDouble("z1", box.maxZ);
        } else if (value instanceof List<?> list) {
            result.putString("kind", "list");
            ListTag values = new ListTag();
            for (Object item : list) values.add(encode(item));
            result.put("value", values);
        } else {
            throw new IllegalArgumentException("Carrier payload needs an NBT codec: " + value.getClass().getName());
        }
        return result;
    }

    public static Object decode(CompoundTag tag) {
        return switch (tag.getString("kind")) {
            case "string" -> tag.getString("value");
            case "nbt" -> tag.getCompound("value").copy();
            case "pos" -> BlockPos.of(tag.getLong("value"));
            case "box" -> new AABB(tag.getDouble("x0"), tag.getDouble("y0"), tag.getDouble("z0"),
                    tag.getDouble("x1"), tag.getDouble("y1"), tag.getDouble("z1"));
            case "list" -> {
                List<Object> values = new ArrayList<>();
                for (Tag value : tag.getList("value", Tag.TAG_COMPOUND)) values.add(decode((CompoundTag) value));
                yield values;
            }
            default -> throw new IllegalArgumentException("Unknown carrier payload kind: " + tag.getString("kind"));
        };
    }
}
