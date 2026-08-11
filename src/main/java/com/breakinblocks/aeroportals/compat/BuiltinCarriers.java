package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public final class BuiltinCarriers {
    private BuiltinCarriers() {}

    private static final TagKey<EntityType<?>> WALL_ENTITIES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("sable", "wall_entities"));

    public static void register() {
        AeroPortalsApi.registerCarrier(new CreateAssemblyCarrier());
        AeroPortalsApi.registerCarrier(new SuperGlueCarrier());
        AeroPortalsApi.registerCarrier(new HoneyGlueCarrier());
        AeroPortalsApi.registerCarrier(new WallEntityCarrier());
    }

    private static final class WallEntityCarrier implements TransferCarrier<List<CompoundTag>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("wall_entities");
        }

        @Override
        public List<CompoundTag> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            AABB plotAabb = AabbUtil.plotAabb(sub);
            if (plotAabb == null) return null;

            List<CompoundTag> saved = new ArrayList<>();
            for (Entity e : srcLevel.getEntities((Entity) null, plotAabb.inflate(1.0),
                    e -> e.getType().is(WALL_ENTITIES))) {
                CompoundTag tag = new CompoundTag();
                if (!e.save(tag)) continue;
                saved.add(tag);
                e.discard();
            }
            if (saved.isEmpty()) return null;
            AeroPortals.LOGGER.debug("[AeroPortals] captured {} wall entity/entities from sub {} pre-teleport",
                    saved.size(), sub.getUniqueId());
            return saved;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<CompoundTag> captured, BlockPos plotShift) {
            for (CompoundTag tag : captured) {
                shift(tag, plotShift);
                Entity restored = EntityType.loadEntityRecursive(tag, dstLevel, e -> e);
                if (restored == null) {
                    AeroPortals.LOGGER.warn("[AeroPortals] wall entity {} could not be recreated after the move", tag.getString("id"));
                    continue;
                }
                if (!dstLevel.addFreshEntity(restored)) {
                    AeroPortals.LOGGER.warn("[AeroPortals] wall entity {} was rejected by the destination level", restored.getType());
                    continue;
                }
                PortalTeleport.lastMovedEntities.put(restored.getUUID(), restored);
            }
            AeroPortals.LOGGER.debug("[AeroPortals] replayed {} wall entity/entities post-teleport (shift {})",
                    captured.size(), plotShift);
        }

        private static void shift(CompoundTag tag, BlockPos plotShift) {
            ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() == 3) {
                ListTag moved = new ListTag();
                moved.add(DoubleTag.valueOf(pos.getDouble(0) + plotShift.getX()));
                moved.add(DoubleTag.valueOf(pos.getDouble(1) + plotShift.getY()));
                moved.add(DoubleTag.valueOf(pos.getDouble(2) + plotShift.getZ()));
                tag.put("Pos", moved);
            }
            if (tag.contains("TileX")) {
                tag.putInt("TileX", tag.getInt("TileX") + plotShift.getX());
                tag.putInt("TileY", tag.getInt("TileY") + plotShift.getY());
                tag.putInt("TileZ", tag.getInt("TileZ") + plotShift.getZ());
            }
            if (tag.contains("block_pos")) {
                int[] blockPos = tag.getIntArray("block_pos");
                if (blockPos.length == 3) {
                    tag.putIntArray("block_pos", new int[]{
                            blockPos[0] + plotShift.getX(),
                            blockPos[1] + plotShift.getY(),
                            blockPos[2] + plotShift.getZ()});
                }
            }
        }
    }

    private static final class CreateAssemblyCarrier implements TransferCarrier<List<BlockPos>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("create_assemblies");
        }

        @Override
        public boolean isEnabled() {
            return ModList.get().isLoaded("create");
        }

        @Override
        public List<BlockPos> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<BlockPos> bearings = CreateContraptionCompat.disassembleAssemblies(srcLevel, sub);
            return bearings.isEmpty() ? null : bearings;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<BlockPos> captured, BlockPos plotShift) {
            CreateContraptionCompat.reassemble(dstLevel, captured, plotShift);
        }
    }

    private static final class SuperGlueCarrier implements TransferCarrier<List<AABB>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("create_super_glue");
        }

        @Override
        public boolean isEnabled() {
            return ModList.get().isLoaded("create");
        }

        @Override
        public List<AABB> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<AABB> boxes = CreateContraptionCompat.captureGlue(srcLevel, sub);
            return boxes.isEmpty() ? null : boxes;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<AABB> captured, BlockPos plotShift) {
            CreateContraptionCompat.replayGlue(dstLevel, captured, plotShift);
        }
    }

    private static final class HoneyGlueCarrier implements TransferCarrier<List<AABB>> {
        @Override
        public ResourceLocation id() {
            return AeroPortals.id("simulated_honey_glue");
        }

        @Override
        public boolean isEnabled() {
            return SimulatedCompat.isAvailable();
        }

        @Override
        public List<AABB> capture(ServerLevel srcLevel, ServerSubLevel sub) {
            List<AABB> boxes = SimulatedCompat.captureHoneyGlue(srcLevel, sub);
            return boxes.isEmpty() ? null : boxes;
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<AABB> captured, BlockPos plotShift) {
            SimulatedCompat.replayHoneyGlue(dstLevel, captured, plotShift);
        }
    }
}
