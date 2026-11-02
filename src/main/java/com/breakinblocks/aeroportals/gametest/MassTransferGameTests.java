package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3dc;

import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class MassTransferGameTests {
    private static final String EMPTY = "empty";
    private static final double TOLERANCE = 1.0e-6;

    private MassTransferGameTests() {}

    @GameTest(batch = "mass_roundTripKeepsShipMass", template = EMPTY, timeoutTicks = 300)
    public static void mass_roundTripKeepsShipMass(GameTestHelper helper) {
        roundTrip(helper, false);
    }

    @GameTest(batch = "mass_relocatedPlotKeepsShipMass", template = EMPTY, timeoutTicks = 300)
    public static void mass_relocatedPlotKeepsShipMass(GameTestHelper helper) {
        roundTrip(helper, true);
    }

    private static void roundTrip(GameTestHelper helper, boolean occupyDestinationPlot) {
        GameTestSupport.isolate(helper);
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("Nether not loaded");
            return;
        }
        ServerSubLevelContainer overworldContainer = SubLevelContainer.getContainer(overworld);
        ServerSubLevelContainer netherContainer = SubLevelContainer.getContainer(nether);
        if (overworldContainer == null || netherContainer == null) {
            helper.fail("Sable containers missing");
            return;
        }

        UUID[] id = new UUID[1];
        double[] expected = new double[1];
        Vec3[] home = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
                    BlockPos localA = new BlockPos(7, 4, 7);
                    BlockPos localB = localA.east();
                    helper.setBlock(localA, obsidian);
                    helper.setBlock(localB, obsidian);
                    BlockPos a = helper.absolutePos(localA);
                    BlockPos b = helper.absolutePos(localB);
                    BoundingBox3i bounds = new BoundingBox3i(
                            Math.min(a.getX(), b.getX()) - 1, a.getY() - 1, Math.min(a.getZ(), b.getZ()) - 1,
                            Math.max(a.getX(), b.getX()) + 1, a.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
                    ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(overworld, a, List.of(a, b), bounds);
                    if (ship == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    id[0] = ship.getUniqueId();
                    expected[0] = scannedMass(ship);
                    if (expected[0] <= 0.0) {
                        helper.fail("fixture ship has no block mass");
                        return;
                    }
                    assertMass(helper, ship, expected[0], "before transfer");
                    Vector3dc pose = ship.logicalPose().position();
                    home[0] = new Vec3(pose.x(), pose.y(), pose.z());

                    int occupiedIndex = -1;
                    if (occupyDestinationPlot) {
                        int localPlotX = ship.getPlot().plotPos.x - netherContainer.getOrigin().x;
                        int localPlotZ = ship.getPlot().plotPos.z - netherContainer.getOrigin().y;
                        occupiedIndex = netherContainer.getIndex(localPlotX, localPlotZ);
                        if (netherContainer.getOccupancy().get(occupiedIndex)) {
                            occupiedIndex = -1;
                        } else {
                            netherContainer.getOccupancy().set(occupiedIndex);
                        }
                    }

                    Vec3 destination = new Vec3(a.getX() / 8.0 + 0.5, 64.5, a.getZ() / 8.0 + 0.5);
                    clearCube(nether, BlockPos.containing(destination), 4);
                    try {
                        PortalTeleport.teleportToDimension(overworld, ship, nether, destination, false, "test:mass_out");
                    } finally {
                        if (occupiedIndex >= 0) netherContainer.getOccupancy().clear(occupiedIndex);
                    }

                    ServerSubLevel arrived = active(netherContainer, id[0]);
                    if (arrived == null) {
                        helper.fail("ship did not arrive in the Nether");
                        return;
                    }
                    if (occupyDestinationPlot && arrived.getPlot().plotPos.equals(ship.getPlot().plotPos)) {
                        helper.fail("fixture did not force a plot relocation");
                        return;
                    }
                    assertMass(helper, arrived, expected[0], "immediately after transfer");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    ServerSubLevel arrived = active(netherContainer, id[0]);
                    if (arrived == null) {
                        helper.fail("ship left the Nether before the return trip");
                        return;
                    }
                    assertMass(helper, arrived, expected[0], "20 ticks after transfer");

                    PortalCooldown.clear();
                    PortalTeleport.teleportToDimension(nether, arrived, overworld, home[0], false, "test:mass_back");
                    ServerSubLevel returned = active(overworldContainer, id[0]);
                    if (returned == null) {
                        helper.fail("ship did not return to the Overworld");
                        return;
                    }
                    assertMass(helper, returned, expected[0], "immediately after return");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    ServerSubLevel returned = active(overworldContainer, id[0]);
                    if (returned == null) {
                        helper.fail("ship left the Overworld after the return trip");
                        return;
                    }
                    assertMass(helper, returned, expected[0], "20 ticks after return");
                })
                .thenSucceed();
    }

    private static ServerSubLevel active(ServerSubLevelContainer container, UUID id) {
        return id != null && container.getSubLevel(id) instanceof ServerSubLevel sub ? sub : null;
    }

    private static double scannedMass(ServerSubLevel ship) {
        return MassTracker.build(ship.getLevel(), ship.getPlot().getBoundingBox()).getMass();
    }

    private static void assertMass(GameTestHelper helper, ServerSubLevel ship, double expected, String stage) {
        double self = ship.getSelfMassTracker().getMass();
        double merged = ship.getMassTracker().getMass();
        double scanned = scannedMass(ship);
        AeroPortals.LOGGER.info("[AeroPortals/test] mass {}: self={} merged={} scanned={} expected={}",
                stage, self, merged, scanned, expected);
        helper.assertTrue(Math.abs(scanned - expected) < TOLERANCE,
                stage + ": ship blocks changed, scanned mass " + scanned + " expected " + expected);
        helper.assertTrue(Math.abs(self - expected) < TOLERANCE,
                stage + ": self mass " + self + " expected " + expected);
        helper.assertTrue(Math.abs(merged - expected) < TOLERANCE,
                stage + ": merged mass " + merged + " expected " + expected);
    }

    private static void clearCube(ServerLevel level, BlockPos centre, int radius) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    cursor.setWithOffset(centre, x, y, z);
                    if (level.getBlockState(cursor) != air) level.setBlock(cursor, air, Block.UPDATE_CLIENTS);
                }
            }
        }
    }
}
