package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.DriveByWireCompat;
import com.breakinblocks.aeroportals.portal.SableBridge;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.List;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class DriveByWireGameTests {
    @GameTest(batch = "wire_transfer_preserves_connections", template = "empty", timeoutTicks = 200)
    public static void wire_transfer_preserves_connections(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        DriveByWireCompat carrier = new DriveByWireCompat();
        if (!carrier.isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Drive By Wire transfer: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerLevel dst = src.getServer().getLevel(Level.NETHER);
        ServerSubLevel ship = ship(helper);
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos from = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        BlockPos to = from.east();
        Class<?> api = Class.forName("edn.stratodonut.drivebywire.wire.WireNetworkManager");
        Method create = api.getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class);
        Method has = api.getMethod("hasConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class);
        create.invoke(null, src, from, to, Direction.UP, "world");
        CompoundTag saved = carrier.serialize(carrier.capture(src, ship));
        helper.assertTrue((Boolean) has.invoke(null, src, from, to, Direction.UP, "world"), "Capture must be non-destructive");
        SableBridge.Moved moved = SableBridge.moveAcrossDimensions(ship, src, dst, new Vec3(20, 100, 20));
        helper.assertTrue(moved != null, "Wire-bearing ship failed to move");
        helper.assertTrue(!(Boolean) has.invoke(null, src, from, to, Direction.UP, "world"), "Source wire remains after ship removal");
        helper.assertTrue((Boolean) has.invoke(null, dst, from.offset(moved.shift()), to.offset(moved.shift()), Direction.UP, "world"),
                "Wire endpoints were not remapped to the destination plot");
        carrier.replay(dst, moved.sub(), carrier.deserialize(saved), moved.shift());
        helper.assertTrue((Boolean) has.invoke(null, dst, from.offset(moved.shift()), to.offset(moved.shift()), Direction.UP, "world"),
                "Journal replay lost an existing wire");
        Object manager = api.getMethod("get", Level.class).invoke(null, dst);
        int signal = (Integer) api.getMethod("getSignalAt", BlockPos.class, Direction.class)
                .invoke(manager, to.offset(moved.shift()), Direction.UP);
        helper.assertTrue(signal == 15, "Restored wire exists but does not deliver its source signal");
        helper.succeed();
    }

    @GameTest(batch = "wire_external_endpoint_veto", template = "empty")
    public static void wire_external_endpoint_veto(GameTestHelper helper) throws Exception {
        GameTestSupport.isolate(helper);
        DriveByWireCompat carrier = new DriveByWireCompat();
        if (!carrier.isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP Drive By Wire external endpoint: optional mod absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerSubLevel ship = ship(helper);
        var bounds = ship.getPlot().getBoundingBox();
        BlockPos from = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        Class.forName("edn.stratodonut.drivebywire.wire.WireNetworkManager")
                .getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class)
                .invoke(null, src, from, helper.absolutePos(new BlockPos(2, 2, 2)), Direction.UP, "world");
        boolean vetoed = false;
        try {
            carrier.validateGroup(src, List.of(ship), src.getServer().getLevel(Level.NETHER));
        } catch (IllegalStateException expected) {
            vetoed = true;
        }
        helper.assertTrue(vetoed, "A cross-dimension wire would silently lose its world endpoint");
        helper.assertTrue(!ship.isRemoved(), "Preflight changed the source ship");
        helper.succeed();
    }

    private static ServerSubLevel ship(GameTestHelper helper) {
        BlockPos from = helper.absolutePos(new BlockPos(4, 3, 4));
        helper.getLevel().setBlockAndUpdate(from, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(from.east(), Blocks.STONE.defaultBlockState());
        return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), from, List.of(from, from.east()),
                new BoundingBox3i(from.getX() - 1, from.getY() - 1, from.getZ() - 1,
                        from.getX() + 2, from.getY() + 1, from.getZ() + 1));
    }
}
