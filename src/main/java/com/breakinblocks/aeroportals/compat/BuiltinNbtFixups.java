package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.nbt.BlockEntityNbtFixer;
import com.breakinblocks.aeroportals.api.nbt.NbtFixContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.ModList;

import java.util.Set;

import static com.breakinblocks.aeroportals.api.nbt.NbtFixers.blockPos;
import static com.breakinblocks.aeroportals.api.nbt.NbtFixers.nested;

public final class BuiltinNbtFixups {
    private BuiltinNbtFixups() {}

    public static void register() {
        if (ModList.get().isLoaded("create")) {
            registerCreate();
        }
        if (ModList.get().isLoaded("simulated")) {
            registerSimulated();
        }
    }

    private static void registerCreate() {
        AeroPortalsApi.registerNbtFixer(
                Set.of("create:item_vault", "create:fluid_tank"),
                blockPos("LastKnownPos"));
        AeroPortalsApi.registerNbtFixer(
                Set.of("create:rope_pulley", "create:elevator_pulley"),
                blockPos("MirrorChildren"));
        AeroPortalsApi.registerNbtFixer("create:powered_shaft", blockPos("EnginePos"));
        AeroPortalsApi.registerNbtFixer(
                Set.of("create:drill", "create:saw"),
                blockPos("Breaking"));
        AeroPortalsApi.registerNbtFixer("create:display_link", displayLink());
    }

    private static void registerSimulated() {
        AeroPortalsApi.registerNbtFixer("simulated:spring", blockPos("Goal"));
        AeroPortalsApi.registerNbtFixer("simulated:swivel_bearing_link_block", blockPos("ParentPos"));
        AeroPortalsApi.registerNbtFixer(
                Set.of("simulated:rope_connector", "simulated:rope_winch"),
                ropeStrand());
    }

    private static BlockEntityNbtFixer displayLink() {
        return nested("components.create:click_to_link_data", (tag, ctx) -> {
            if (!tag.contains("selected_pos", Tag.TAG_INT_ARRAY)) return;
            int[] data = tag.getIntArray("selected_pos");
            if (data.length != 3) return;

            BlockPos target = new BlockPos(data[0], data[1], data[2]);
            if (!ctx.insideSourcePlot(target)) {
                AeroPortals.LOGGER.debug("[AeroPortals] display link target {} is outside the moving plot; leaving it alone", target);
                return;
            }

            BlockPos shifted = ctx.shift(target);
            tag.putIntArray("selected_pos", new int[]{shifted.getX(), shifted.getY(), shifted.getZ()});

            if (tag.contains("selected_dim", Tag.TAG_STRING)) {
                String current = tag.getString("selected_dim");
                String srcFull = ctx.srcDimensionId().toString();
                String srcPath = ctx.srcDimensionId().getPath();
                if (current.equals(srcFull)) {
                    tag.putString("selected_dim", ctx.dstDimensionId().toString());
                } else if (current.equals(srcPath)) {
                    tag.putString("selected_dim", ctx.dstDimensionId().getPath());
                }
            }
        });
    }

    private static BlockEntityNbtFixer ropeStrand() {
        return (tag, ctx) -> {
            if (!tag.contains("Strand", Tag.TAG_COMPOUND)) return;
            CompoundTag strand = tag.getCompound("Strand");
            shiftOwnAttachments(strand, ctx);
            translatePoints(strand, ctx);
        };
    }

    private static void shiftOwnAttachments(CompoundTag strand, NbtFixContext ctx) {
        if (!ctx.moved() || !strand.contains("attachments", Tag.TAG_LIST)) return;
        String ownId = ctx.subUuid().toString();
        ListTag attachments = strand.getList("attachments", Tag.TAG_COMPOUND);
        for (int i = 0; i < attachments.size(); i++) {
            CompoundTag attachment = attachments.getCompound(i);
            if (!ownId.equals(attachment.getString("subLevelID"))) continue;
            if (!attachment.contains("blockAttachment", Tag.TAG_INT_ARRAY)) continue;
            int[] data = attachment.getIntArray("blockAttachment");
            if (data.length != 3) continue;
            BlockPos shifted = ctx.shift(new BlockPos(data[0], data[1], data[2]));
            attachment.putIntArray("blockAttachment", new int[]{shifted.getX(), shifted.getY(), shifted.getZ()});
        }
    }

    private static void translatePoints(CompoundTag strand, NbtFixContext ctx) {
        if (!strand.contains("points", Tag.TAG_LIST)) return;
        ListTag points = strand.getList("points", Tag.TAG_LIST);
        for (int i = 0; i < points.size(); i++) {
            ListTag point = points.getList(i);
            if (point.size() < 3) continue;
            point.setTag(0, DoubleTag.valueOf(point.getDouble(0) + ctx.worldTranslation().x));
            point.setTag(1, DoubleTag.valueOf(point.getDouble(1) + ctx.worldTranslation().y));
            point.setTag(2, DoubleTag.valueOf(point.getDouble(2) + ctx.worldTranslation().z));
        }
    }
}
