package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.DriveBySableCompat;
import com.breakinblocks.aeroportals.portal.SableBridge;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class DriveBySableGameTests {
    private static final String MANAGER = "edn.lakeopossmc.drivebysable.cable.CableNetworkManager";

    @GameTest(batch = "cable_transfer_preserves_connections", template = "empty", timeoutTicks = 200)
    public static void cable_transfer_preserves_connections(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        DriveBySableCompat carrier = new DriveBySableCompat();
        if (!carrier.isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Drive-By-Sable transfer: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerLevel dst = src.getServer().getLevel(Level.NETHER);
        ServerSubLevel ship = ship(helper);
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos from = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        BlockPos to = from.east();
        Object created = create(src, from, to);
        helper.assertTrue("OK".equals(created.toString()), "Fixture cable must connect: " + created);
        merge(src, connection(from, to, "throttle"));
        helper.assertTrue(has(src, from, to, "throttle"), "Fixture module cable must connect");
        CompoundTag saved = carrier.serialize(carrier.capture(src, ship));
        helper.assertTrue(has(src, from, to, ""), "Capture must be non-destructive");
        SableBridge.Moved moved = SableBridge.moveAcrossDimensions(ship, src, dst, new Vec3(20, 100, 20));
        helper.assertTrue(moved != null, "Cable-bearing ship failed to move");
        helper.assertTrue(!has(src, from, to, ""), "Source cable remains after ship removal");
        BlockPos movedFrom = from.offset(moved.shift());
        BlockPos movedTo = to.offset(moved.shift());
        helper.assertTrue(has(dst, movedFrom, movedTo, ""), "Cable endpoints were not remapped to the destination plot");
        helper.assertTrue(has(dst, movedFrom, movedTo, "throttle"), "Module cable lost its sink channel");
        carrier.replay(dst, moved.sub(), carrier.deserialize(saved), moved.shift());
        helper.assertTrue(has(dst, movedFrom, movedTo, "") && has(dst, movedFrom, movedTo, "throttle"),
                "Journal replay lost an existing cable");
        Class<?> api = Class.forName(MANAGER);
        Object network = api.getMethod("get", Level.class).invoke(null, dst);
        api.getMethod("flushPendingGraphRebuild", Level.class).invoke(network, dst);
        int faceSignal = (Integer) api.getMethod("getSignalAt", BlockPos.class, Direction.class)
                .invoke(network, movedTo, Direction.UP);
        int moduleSignal = (Integer) api.getMethod("getModuleSinkSignal", BlockPos.class, String.class)
                .invoke(network, movedTo, "throttle");
        helper.assertTrue(faceSignal == 15, "Restored cable exists but does not deliver its source signal: " + faceSignal);
        helper.assertTrue(moduleSignal == 15, "Restored module cable exists but does not deliver its source signal: " + moduleSignal);
        helper.succeed();
    }

    @GameTest(batch = "cable_external_endpoint_veto", template = "empty")
    public static void cable_external_endpoint_veto(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        DriveBySableCompat carrier = new DriveBySableCompat();
        if (!carrier.isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Drive-By-Sable external endpoint: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerSubLevel ship = ship(helper);
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos from = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        create(src, from, helper.absolutePos(new BlockPos(2, 2, 2)));
        boolean vetoed = false;
        try {
            carrier.validateGroup(src, List.of(ship), src.getServer().getLevel(Level.NETHER));
        } catch (IllegalStateException expected) {
            vetoed = true;
        }
        helper.assertTrue(vetoed, "A cross-dimension cable would lose its world endpoint");
        helper.assertTrue(!ship.isRemoved(), "Preflight changed the source ship");
        helper.succeed();
    }

    @GameTest(batch = "cable_between_ships_moves_with_group", template = "empty", timeoutTicks = 200)
    public static void cable_between_ships_moves_with_group(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        if (!new DriveBySableCompat().isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Drive-By-Sable ship group: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerLevel dst = src.getServer().getLevel(Level.NETHER);
        ServerSubLevel first = single(helper, new BlockPos(4, 4, 7), Blocks.REDSTONE_BLOCK);
        ServerSubLevel second = single(helper, new BlockPos(10, 4, 7), Blocks.STONE);
        BlockPos from = plotBlock(first);
        BlockPos to = plotBlock(second);
        Object created = create(src, from, to);
        helper.assertTrue("OK".equals(created.toString()), "Fixture cross-ship cable must connect: " + created);
        helper.assertTrue(!SableBridge.canMoveGroup(src, dst, List.of(first)),
                "Moving one end of a cross-ship cable must be rejected");
        ModConfigSpec.BooleanValue forbid = forbidCrossLevel();
        forbid.set(true);
        try {
            helper.assertTrue(!SableBridge.canMoveGroup(src, dst, List.of(first, second)),
                    "A cross-ship cable must hold the group back while cross-level cables are disabled");
        } finally {
            forbid.set(false);
        }
        Map<UUID, SableBridge.Moved> moved = SableBridge.moveGroup(src, dst, List.of(
                new SableBridge.Request(first, new Vec3(20, 100, 20)),
                new SableBridge.Request(second, new Vec3(26, 100, 20))));
        helper.assertTrue(moved.size() == 2, "Both cabled ships must transfer together");
        TeleportJournal.completeGroup(src.getServer(), moved.keySet(), src, dst);
        BlockPos movedFrom = from.offset(moved.get(first.getUniqueId()).shift());
        BlockPos movedTo = to.offset(moved.get(second.getUniqueId()).shift());
        helper.assertTrue(!has(src, from, to, ""), "Source cable remains after the group left");
        helper.assertTrue(has(dst, movedFrom, movedTo, ""), "Cross-ship cable did not follow both ships");
        helper.succeed();
    }

    private static Object create(ServerLevel level, BlockPos source, BlockPos sink) throws ReflectiveOperationException {
        return Class.forName(MANAGER)
                .getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class)
                .invoke(null, level, source, sink, Direction.UP, "world");
    }

    private static boolean has(ServerLevel level, BlockPos source, BlockPos sink, String sinkChannel) throws ReflectiveOperationException {
        return (Boolean) Class.forName(MANAGER)
                .getMethod("hasConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class, String.class)
                .invoke(null, level, source, sink, Direction.UP, "world", sinkChannel);
    }

    private static void merge(ServerLevel level, CompoundTag connection) throws ReflectiveOperationException {
        Class<?> api = Class.forName(MANAGER);
        ListTag connections = new ListTag();
        connections.add(connection);
        CompoundTag payload = new CompoundTag();
        payload.put("Connections", connections);
        Object network = api.getMethod("get", Level.class).invoke(null, level);
        api.getMethod("mergeSavedConnections", Level.class, CompoundTag.class).invoke(network, level, payload);
    }

    private static CompoundTag connection(BlockPos source, BlockPos sink, String sinkChannel) {
        CompoundTag connection = new CompoundTag();
        connection.putLong("Source", source.asLong());
        connection.putLong("Sink", sink.asLong());
        connection.putByte("Direction", (byte) Direction.UP.get3DDataValue());
        connection.putString("Channel", "world");
        connection.putString("SinkChannel", sinkChannel);
        return connection;
    }

    private static ModConfigSpec.BooleanValue forbidCrossLevel() throws ReflectiveOperationException {
        Object config = Class.forName("edn.lakeopossmc.drivebysable.CableConfig").getField("CONFIG").get(null);
        return (ModConfigSpec.BooleanValue) config.getClass().getField("forbidCrossLevelConnections").get(config);
    }

    private static ServerSubLevel ship(GameTestHelper helper) {
        BlockPos from = helper.absolutePos(new BlockPos(4, 3, 4));
        helper.getLevel().setBlockAndUpdate(from, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(from.east(), Blocks.STONE.defaultBlockState());
        return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), from, List.of(from, from.east()),
                new BoundingBox3i(from.getX() - 1, from.getY() - 1, from.getZ() - 1,
                        from.getX() + 2, from.getY() + 1, from.getZ() + 1));
    }

    private static ServerSubLevel single(GameTestHelper helper, BlockPos local, Block block) {
        BlockPos world = helper.absolutePos(local);
        helper.setBlock(local, block.defaultBlockState());
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), world, List.of(world),
                new BoundingBox3i(world.getX() - 1, world.getY() - 1, world.getZ() - 1,
                        world.getX() + 1, world.getY() + 1, world.getZ() + 1));
        helper.assertTrue(ship != null, "Fixture ship assembly must succeed");
        return ship;
    }

    private static BlockPos plotBlock(ServerSubLevel ship) {
        var bounds = ship.getPlot().getBoundingBox();
        return new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
    }
}
