package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.portal.SableBridge;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class JournalDurabilityGameTests {
    private JournalDurabilityGameTests() {}

    @GameTest(batch = "journal_malformedEntryRemainsRecoverable", template = "empty")
    public static void journal_malformedEntryRemainsRecoverable(GameTestHelper helper) throws IOException {
        GameTestSupport.isolate(helper);
        UUID uuid = UUID.randomUUID();
        Path path = journal(helper.getLevel(), uuid);
        Files.createDirectories(path.getParent());
        byte[] damaged = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00};
        Files.write(path, damaged);
        try {
            TeleportJournal.replayPending(helper.getLevel().getServer());
            helper.assertTrue(Files.exists(path), "Unreadable recovery data must never be deleted");
            helper.assertTrue(java.util.Arrays.equals(damaged, Files.readAllBytes(path)),
                    "Unreadable recovery data must remain unchanged for repair");
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

    @GameTest(batch = "journal_completionRequiresDurableDestination", template = "empty", timeoutTicks = 200)
    public static void journal_completionRequiresDurableDestination(GameTestHelper helper) throws IOException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7));
        UUID uuid = ship.getUniqueId();
        var sourceContainer = SubLevelContainer.getContainer(source);
        var destinationContainer = SubLevelContainer.getContainer(destination);
        sourceContainer.getHoldingChunkMap().saveAll();
        sourceContainer.getHoldingChunkMap().getStorage().flush();
        var originalPointer = ship.getLastSerializationPointer();
        helper.assertTrue(originalPointer != null, "Fixture must start with a saved source ship");
        SubLevelData data = destinationData(ship);
        helper.assertTrue(TeleportJournal.write(source.getServer(), uuid, source.dimension().location(),
                destination.dimension().location(), source.getMinBuildHeight(), data), "Journal prepare must succeed");
        sourceContainer.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        SableBridge.Loaded loaded = SableBridge.reloadInDestination(
                SableBridge.SourceInfo.of(source.dimension(), source.getMinBuildHeight()), destination, destinationContainer, data);
        helper.assertTrue(loaded != null, "Destination must load before completion");
        helper.assertTrue(loaded.sub().getLastSerializationPointer() == null,
                "In-memory destination fixture must start without a durable pointer");
        helper.assertTrue(Files.exists(journal(source, uuid)), "Entry must remain before durable completion");
        helper.assertTrue(TeleportJournal.complete(source.getServer(), uuid, source, destination, loaded.sub()),
                "Completion must durably save destination and retire source");
        var pointer = loaded.sub().getLastSerializationPointer();
        helper.assertTrue(pointer != null, "Completion must create a saved destination pointer");
        SubLevelData stored = destinationContainer.getHoldingChunkMap().getStorage()
                .attemptLoadSubLevel(pointer.chunkPos(), pointer.local());
        helper.assertTrue(stored != null && uuid.equals(stored.uuid()), "Saved destination must contain this ship");
        helper.assertTrue(!Files.exists(journal(source, uuid)), "Verified completed entry should be removed");
        helper.succeed();
    }

    @GameTest(batch = "journal_staleSourcePointerPreventsCompletion", template = "empty", timeoutTicks = 200)
    public static void journal_staleSourcePointerPreventsCompletion(GameTestHelper helper) throws IOException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7));
        UUID uuid = ship.getUniqueId();
        var sourceContainer = SubLevelContainer.getContainer(source);
        var destinationContainer = SubLevelContainer.getContainer(destination);
        sourceContainer.getHoldingChunkMap().saveAll();
        sourceContainer.getHoldingChunkMap().getStorage().flush();
        var originalPointer = ship.getLastSerializationPointer();
        helper.assertTrue(originalPointer != null, "Fixture must start with saved source data");
        SubLevelData data = destinationData(ship);
        helper.assertTrue(TeleportJournal.write(source.getServer(), uuid, source.dimension().location(),
                destination.dimension().location(), source.getMinBuildHeight(), data), "Journal prepare must succeed");
        // Simulate source retirement not being queued, while the original pointer still exists on disk.
        ship.setLastSerializationPointer(null);
        sourceContainer.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        SableBridge.Loaded loaded = SableBridge.reloadInDestination(
                SableBridge.SourceInfo.of(source.dimension(), source.getMinBuildHeight()), destination, destinationContainer, data);
        helper.assertTrue(loaded != null, "Destination must load");
        try {
            helper.assertTrue(!TeleportJournal.complete(source.getServer(), uuid, source, destination, loaded.sub()),
                    "Completion must reject a still-saved original source copy");
            helper.assertTrue(Files.exists(journal(source, uuid)), "Failed retirement must retain recovery data");
        } finally {
            // Repair the deliberately broken fixture through Sable's normal queued-deletion path.
            var restored = SableBridge.reloadInDestination(
                    SableBridge.SourceInfo.of(source.dimension(), source.getMinBuildHeight()), source, sourceContainer,
                    sourceContainer.getHoldingChunkMap().getStorage().attemptLoadSubLevel(originalPointer.chunkPos(), originalPointer.local()));
            if (restored != null) {
                restored.sub().setLastSerializationPointer(originalPointer);
                sourceContainer.removeSubLevel(restored.sub(), SubLevelRemovalReason.REMOVED);
                sourceContainer.getHoldingChunkMap().saveAll();
            }
            TeleportJournal.delete(source.getServer(), uuid);
        }
        helper.succeed();
    }

    @GameTest(batch = "journal_partialGroupCannotComplete", template = "empty", timeoutTicks = 200)
    public static void journal_partialGroupCannotComplete(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevel first = assemble(helper, new BlockPos(4, 4, 7));
        ServerSubLevel second = assemble(helper, new BlockPos(10, 4, 7));
        List<UUID> ids = List.of(first.getUniqueId(), second.getUniqueId());
        ListTag members = new ListTag();
        for (UUID uuid : ids) {
            CompoundTag member = new CompoundTag();
            member.putUUID("uuid", uuid);
            members.add(member);
        }
        for (ServerSubLevel sub : List.of(first, second)) {
            CompoundTag metadata = new CompoundTag();
            metadata.putUUID("group_id", ids.getFirst());
            metadata.put("group_members", members.copy());
            metadata.put("source_snapshot", SubLevelSerializer.toData(sub, List.of()).fullTag().copy());
            helper.assertTrue(TeleportJournal.write(source.getServer(), sub.getUniqueId(), source.dimension().location(),
                    destination.dimension().location(), source.getMinBuildHeight(), destinationData(sub), metadata),
                    "Every group member must be journaled");
        }
        try {
            helper.assertTrue(!TeleportJournal.completeGroup(source.getServer(), List.of(ids.getFirst()), source, destination),
                    "A subset of a prepared group must not complete");
            for (UUID uuid : ids) helper.assertTrue(Files.exists(journal(source, uuid)), "All group entries must remain");
        } finally {
            for (UUID uuid : ids) TeleportJournal.delete(source.getServer(), uuid);
        }
        helper.succeed();
    }

    @GameTest(batch = "journal_pendingCleanupCannotDeleteNewTransfer", template = "empty")
    public static void journal_pendingCleanupCannotDeleteNewTransfer(GameTestHelper helper) throws IOException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7));
        UUID uuid = ship.getUniqueId();
        UUID group = UUID.randomUUID();
        Path markerPath = TeleportJournal.pendingDir(source.getServer()).resolve(group + ".complete");
        Files.createDirectories(markerPath.getParent());
        CompoundTag marker = new CompoundTag();
        marker.putUUID("group_id", group);
        marker.putString("outcome", "destination");
        ListTag members = new ListTag();
        for (UUID memberId : List.of(group, uuid)) {
            CompoundTag member = new CompoundTag();
            member.putUUID("uuid", memberId);
            members.add(member);
        }
        marker.put("members", members);
        NbtIo.writeCompressed(marker, markerPath);
        try {
            helper.assertTrue(!TeleportJournal.write(source.getServer(), uuid, source.dimension().location(),
                            Level.NETHER.location(), source.getMinBuildHeight(), destinationData(ship)),
                    "An older group cleanup marker must block reusing a member's journal filename");
            helper.assertTrue(!Files.exists(journal(source, uuid)), "Rejected preparation must not create a new entry");
        } finally {
            Files.deleteIfExists(markerPath);
        }
        helper.succeed();
    }

    @GameTest(batch = "journal_unloadedDiskSourcePreventsDuplicateRecovery", template = "empty", timeoutTicks = 200)
    public static void journal_unloadedDiskSourcePreventsDuplicateRecovery(GameTestHelper helper)
            throws IOException, ReflectiveOperationException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7));
        UUID uuid = ship.getUniqueId();
        var container = SubLevelContainer.getContainer(source);
        var map = container.getHoldingChunkMap();
        map.saveAll();
        map.getStorage().flush();
        var pointer = ship.getLastSerializationPointer();
        helper.assertTrue(pointer != null, "Fixture must have a saved source pointer");
        helper.assertTrue(TeleportJournal.write(source.getServer(), uuid, source.dimension().location(),
                destination.dimension().location(), source.getMinBuildHeight(), destinationData(ship)),
                "Legacy forward-recovery journal must be prepared");
        container.removeSubLevel(ship, SubLevelRemovalReason.UNLOADED);
        // Simulate startup before this holding chunk has entered the in-memory cache.
        var chunksField = map.getClass().getDeclaredField("loadedHoldingChunks");
        chunksField.setAccessible(true);
        ((java.util.Map<?, ?>) chunksField.get(map)).remove(pointer.chunkPos().toLong());
        helper.assertTrue(container.getSubLevel(uuid) == null && map.getHoldingSubLevel(uuid) == null,
                "Source ship must exist only in persistent storage before replay");
        try {
            TeleportJournal.replayPending(source.getServer());
            helper.assertTrue(container.getSubLevel(uuid) != null, "Recovery must load and preserve the stored source ship");
            helper.assertTrue(SubLevelContainer.getContainer(destination).getSubLevel(uuid) == null,
                    "Recovery must not create a duplicate in destination when source exists on disk");
            helper.assertTrue(!Files.exists(journal(source, uuid)), "Verified source preservation should complete recovery");
        } finally {
            TeleportJournal.delete(source.getServer(), uuid);
        }
        helper.succeed();
    }

    @GameTest(batch = "journal_opposite_copy_does_not_leak_carriers", template = "empty", timeoutTicks = 200)
    public static void journal_opposite_copy_does_not_leak_carriers(GameTestHelper helper) {
        carrierCleanupRecovery(helper, false);
    }

    @GameTest(batch = "journal_same_dimension_rollback_keeps_carriers_on_ship", template = "empty", timeoutTicks = 200)
    public static void journal_same_dimension_rollback_keeps_carriers_on_ship(GameTestHelper helper) {
        carrierCleanupRecovery(helper, true);
    }

    private static void carrierCleanupRecovery(GameTestHelper helper, boolean sameDimension) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = sameDimension ? source : source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Recovery fixture requires its destination dimension");
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7));
        UUID shipId = ship.getUniqueId();
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos block = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        ItemFrame frame = new ItemFrame(source, block.above(), Direction.UP);
        helper.assertTrue(frame.survives(), "Recovery frame fixture must have valid support");
        frame.setItem(new ItemStack(Items.DIAMOND));
        helper.assertTrue(source.addFreshEntity(frame), "Fixture item frame must spawn on the ship");
        UUID frameId = frame.getUUID();
        AABB glueBox = new AABB(block);
        helper.assertTrue(source.addFreshEntity(new SuperGlueEntity(source, glueBox)), "Fixture glue must spawn on the ship");
        try {
            var moved = SableBridge.moveGroup(source, destination,
                    List.of(new SableBridge.Request(ship, new Vec3(13000, 100, 13000))));
            helper.assertTrue(moved.containsKey(shipId), "Fixture transfer must reach destination replay");
            helper.assertTrue(Files.exists(journal(source, shipId)), "Fixture must retain its unfinished journal");
            helper.assertTrue(destination.getEntity(frameId) instanceof ItemFrame,
                    "Fixture must have a carried frame on the unfinished destination copy");
            helper.assertTrue(glueCount(destination) == 1, "Fixture must have one carried glue entity before recovery");

            // A prepared modern journal chooses the source on recovery, even when the destination
            // copy already exists. Its carried entities must be discarded before that copy retires.
            TeleportJournal.replayPending(source.getServer());
            ServerSubLevel restored = (ServerSubLevel) SubLevelContainer.getContainer(source).getSubLevel(shipId);
            helper.assertTrue(restored != null && !restored.isRemoved(), "Recovery must restore the source ship");
            Entity entity = source.getEntity(frameId);
            helper.assertTrue(entity instanceof ItemFrame, "Recovery must preserve the item frame UUID");
            ItemFrame recoveredFrame = (ItemFrame) entity;
            helper.assertTrue(recoveredFrame.getItem().is(Items.DIAMOND), "Recovered frame must retain its contents");
            helper.assertTrue(AabbUtil.plotAabb(restored).inflate(1).contains(recoveredFrame.position()),
                    "Recovered frame must be attached to the restored plot, not kicked into world space");
            helper.assertTrue(glueCount(source) == 1, "Recovered source must contain exactly one glue entity");
            helper.assertTrue(source.getEntitiesOfClass(SuperGlueEntity.class, AabbUtil.plotAabb(restored).inflate(2)).size() == 1,
                    "Recovered glue must remain on the restored ship");
            if (!sameDimension) {
                helper.assertTrue(SubLevelContainer.getContainer(destination).getSubLevel(shipId) == null,
                        "Recovery must retire the opposite ship copy");
                helper.assertTrue(destination.getEntity(frameId) == null,
                        "Retiring the opposite copy must not leak a duplicate frame into that dimension");
                helper.assertTrue(glueCount(destination) == 0,
                        "Retiring the opposite copy must not leak duplicate glue into that dimension");
            }
            helper.assertTrue(!Files.exists(journal(source, shipId)), "Recovered carrier state must complete durably");
        } finally {
            TeleportJournal.delete(source.getServer(), shipId);
        }
        helper.succeed();
    }

    private static int glueCount(ServerLevel level) {
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SuperGlueEntity && !entity.isRemoved()) count++;
        }
        return count;
    }

    private static ServerSubLevel assemble(GameTestHelper helper, BlockPos local) {
        BlockPos pos = helper.absolutePos(local);
        helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), pos, List.of(pos),
                new BoundingBox3i(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
        helper.assertTrue(sub != null, "Journal test ship must assemble");
        return sub;
    }

    private static SubLevelData destinationData(ServerSubLevel ship) {
        SubLevelData data = SubLevelSerializer.toData(ship, List.of());
        var pose = SableNBTUtils.readPose3d(data.fullTag().getCompound("pose"));
        Vec3 target = new Vec3(13000, 64, 13000);
        var bounds = SableNBTUtils.readBoundingBox(data.fullTag().getCompound("world_bounds"));
        bounds.move(target.x - pose.position().x(), target.y - pose.position().y(), target.z - pose.position().z());
        pose.position().set(target.x, target.y, target.z);
        data.fullTag().put("pose", SableNBTUtils.writePose3d(pose));
        data.fullTag().put("world_bounds", SableNBTUtils.writeBoundingBox(bounds));
        return data;
    }

    private static Path journal(ServerLevel level, UUID uuid) {
        return TeleportJournal.pendingDir(level.getServer()).resolve(uuid + ".nbt");
    }
}
