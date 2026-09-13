package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.GadgetsAndGizmosCompat;
import com.breakinblocks.aeroportals.portal.SableBridge;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class GadgetsAndGizmosGameTests {
    private static final String TRACKER = "com.rieno.gadgetsandgizmos.content.ContraptionNetworkLinkerTracker";

    @GameTest(batch = "gadgets_linker_tracker_transfer", template = "empty", timeoutTicks = 200)
    public static void gadgets_linker_tracker_transfer(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        GadgetsAndGizmosCompat carrier = new GadgetsAndGizmosCompat();
        if (!carrier.isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Gadgets & Gizmos tracker: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerLevel dst = src.getServer().getLevel(Level.NETHER);
        BlockPos world = helper.absolutePos(new BlockPos(4, 3, 4));
        src.setBlockAndUpdate(world, Blocks.STONE.defaultBlockState());
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(src, world, List.of(world),
                new BoundingBox3i(world.getX() - 1, world.getY() - 1, world.getZ() - 1,
                        world.getX() + 1, world.getY() + 1, world.getZ() + 1));
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos original = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        UUID linkerId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        CompoundTag linker = new CompoundTag();
        linker.putUUID("LinkerId", linkerId);
        linker.putString("Dimension", src.dimension().location().toString());
        CompoundTag target = new CompoundTag();
        target.putUUID("CurrentSubLevelId", ship.getUniqueId());
        target.putLong("CurrentLocalPos", original.asLong());
        target.putUUID("TrackingPointId", pointId);
        target.putString("NodeId", "aeroportals-transfer-test");
        target.putString("BlockId", "minecraft:stone");
        target.putString("Scope", "block");
        ListTag targets = new ListTag();
        targets.add(target);
        linker.put("Targets", targets);
        SavedData tracker = install(src, linkerId, linker);
        try {
            CompoundTag captured = carrier.serialize(carrier.capture(src, ship));
            SableBridge.Moved moved = SableBridge.moveAcrossDimensions(ship, src, dst, new Vec3(25, 100, 25));
            helper.assertTrue(moved != null, "Linked ship failed to move");
            carrier.replay(dst, moved.sub(), carrier.deserialize(captured), moved.shift());
            CompoundTag restored = find(tracker.save(new CompoundTag(), dst.registryAccess()), linkerId);
            helper.assertTrue(restored.getString("Dimension").equals(dst.dimension().location().toString()),
                    "Linker tracker still points at the source dimension");
            CompoundTag endpoint = restored.getList("Targets", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertTrue(BlockPos.of(endpoint.getLong("CurrentLocalPos")).equals(original.offset(moved.shift())),
                    "Linker endpoint was not rebased to its destination plot");
            helper.assertTrue(endpoint.getString("NodeId").equals("aeroportals-transfer-test"),
                    "Old node ID was lost before authoritative item reconciliation");
            var point = SubLevelTrackingPointSavedData.getOrLoad(dst).getTrackingPoint(pointId);
            helper.assertTrue(point != null && point.inSubLevel() && ship.getUniqueId().equals(point.subLevelID()),
                    "Destination tracking point was not rebuilt for the transferred ship");
            helper.succeed();
        } finally {
            records(tracker).remove(linkerId);
            tracker.setDirty();
            SubLevelTrackingPointSavedData.getOrLoad(src).removeTrackingPoint(pointId);
            SubLevelTrackingPointSavedData.getOrLoad(dst).removeTrackingPoint(pointId);
        }
    }

    private static SavedData install(ServerLevel level, UUID id, CompoundTag record) throws Exception {
        Class<?> type = Class.forName(TRACKER);
        SavedData tracker = (SavedData) type.getMethod("get", MinecraftServer.class).invoke(null, level.getServer());
        Class<?> recordType = Class.forName(TRACKER + "$LinkerRecord");
        Method load = recordType.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        records(tracker).put(id, load.invoke(null, record));
        tracker.setDirty();
        return tracker;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> records(SavedData tracker) throws Exception {
        Field field = tracker.getClass().getDeclaredField("trackedLinkers");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(tracker);
    }

    private static CompoundTag find(CompoundTag snapshot, UUID id) {
        for (Tag tag : snapshot.getList("Linkers", Tag.TAG_COMPOUND)) {
            CompoundTag linker = (CompoundTag) tag;
            if (linker.hasUUID("LinkerId") && linker.getUUID("LinkerId").equals(id)) return linker;
        }
        throw new IllegalStateException("Transferred linker disappeared from the tracker");
    }
}
