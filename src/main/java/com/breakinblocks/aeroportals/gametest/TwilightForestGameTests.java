package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.compat.TwilightForestCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.PortalDetector;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class TwilightForestGameTests {
    private static final String EMPTY = "empty";
    private static final int EXIT_POOL_Y = 100;

    private TwilightForestGameTests() {}

    @GameTest(batch = "twilight_matchesOnlyTwoWayPortal", template = EMPTY)
    public static void twilight_matchesOnlyTwoWayPortal(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        helper.assertFalse(TwilightForestCompat.isPortalBlock(Blocks.NETHER_PORTAL.defaultBlockState()),
                "a nether portal is not a Twilight portal");
        helper.assertFalse(TwilightForestCompat.isPortalBlock(Blocks.WATER.defaultBlockState()),
                "an unlit pool is not a Twilight portal");

        Vec3 dive = PortalDestination.UPWARD_EXIT.apply(new Vec3(1.0, -5.0, 2.0));
        helper.assertTrue(dive.equals(new Vec3(1.0, 5.0, 2.0)), "downward entry must leave upward, got " + dive);
        Vec3 climb = PortalDestination.UPWARD_EXIT.apply(new Vec3(1.0, 3.0, 2.0));
        helper.assertTrue(climb.equals(new Vec3(1.0, 3.0, 2.0)), "upward entry must be left alone, got " + climb);

        if (!TwilightForestCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Twilight Forest not loaded; portal state checks skipped");
            helper.succeed();
            return;
        }
        Block portal = TwilightForestCompat.portalBlock();
        helper.assertTrue(TwilightForestCompat.isPortalBlock(portal.defaultBlockState()), "a lit Twilight pool must carry ships");
        helper.assertTrue(oneWay(portal) != null, "Twilight portal lost its one-way state");
        helper.assertFalse(TwilightForestCompat.isPortalBlock(oneWay(portal)),
                "a one-way exit pool sends nobody and must not carry ships");
        helper.assertTrue(TwilightForestCompat.isAnyPortalBlock(oneWay(portal)), "a one-way pool is still a pool when landing");
        helper.succeed();
    }

    @GameTest(batch = "twilight_shipDivesInAndRisesOut", template = EMPTY, timeoutTicks = 400)
    public static void twilight_shipDivesInAndRisesOut(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (!TwilightForestCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Twilight Forest not loaded; end-to-end test skipped");
            helper.succeed();
            return;
        }
        ServerLevel overworld = helper.getLevel();
        ServerLevel twilight = overworld.getServer().getLevel(TwilightForestCompat.DIMENSION);
        if (twilight == null) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Twilight Forest dimension not in this world; end-to-end test skipped");
            helper.succeed();
            return;
        }
        ServerSubLevelContainer overworldContainer = SubLevelContainer.getContainer(overworld);
        ServerSubLevelContainer twilightContainer = SubLevelContainer.getContainer(twilight);
        if (overworldContainer == null || twilightContainer == null) {
            helper.fail("Sable containers missing");
            return;
        }

        Block portal = TwilightForestCompat.portalBlock();
        UUID[] id = new UUID[1];
        BlockPos[] exitPool = new BlockPos[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos entryPool = buildPool(overworld, helper.absolutePos(new BlockPos(6, 2, 6)), portal);
                    exitPool[0] = buildPool(twilight,
                            new BlockPos(entryPool.getX() * 8, EXIT_POOL_Y, entryPool.getZ() * 8), portal);

                    BlockPos local = new BlockPos(6, 3, 6);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
                    BlockPos block = helper.absolutePos(local);
                    BoundingBox3i bounds = new BoundingBox3i(block.getX() - 1, block.getY() - 1, block.getZ() - 1,
                            block.getX() + 1, block.getY() + 1, block.getZ() + 1);
                    ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(overworld, block, List.of(block), bounds);
                    if (ship == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    id[0] = ship.getUniqueId();
                    overworldContainer.physicsSystem().getPipeline()
                            .addLinearAndAngularVelocity(ship, new Vector3d(0.0, -8.0, 0.0), new Vector3d());

                    PortalDetector.scan(overworld);

                    helper.assertTrue(overworldContainer.getSubLevel(id[0]) == null, "ship stayed in the Overworld");
                    ServerSubLevel arrived = twilightContainer.getSubLevel(id[0]) instanceof ServerSubLevel s ? s : null;
                    helper.assertTrue(arrived != null, "ship did not arrive in the Twilight Forest");
                    AABB box = AabbUtil.worldAabb(arrived);
                    AeroPortals.LOGGER.info("[AeroPortals/test] twilight arrival box={} exit pool={}", box, exitPool[0]);
                    double poolCentreX = exitPool[0].getX() + 1.0;
                    double poolCentreZ = exitPool[0].getZ() + 1.0;
                    helper.assertTrue(Math.abs(box.getCenter().x - poolCentreX) <= 1.0
                                    && Math.abs(box.getCenter().z - poolCentreZ) <= 1.0,
                            "ship should arrive over the linked pool at " + exitPool[0] + ", box " + box);
                    helper.assertTrue(box.minY >= EXIT_POOL_Y + 1 - 0.01 && box.minY <= EXIT_POOL_Y + 3,
                            "ship should arrive just above the pool surface, box " + box);
                })
                .thenIdle(22)
                .thenExecute(() -> {
                    if (AeroPortalsConfig.CLEAR_VELOCITY_ON_ARRIVAL.get()) return;
                    ServerSubLevel arrived = twilightContainer.getSubLevel(id[0]) instanceof ServerSubLevel s ? s : null;
                    helper.assertTrue(arrived != null, "ship left the Twilight Forest during the hold");
                    Vector3d velocity = twilightContainer.physicsSystem().getPipeline()
                            .getLinearVelocity(arrived, new Vector3d());
                    AeroPortals.LOGGER.info("[AeroPortals/test] twilight velocity after hold = {}", velocity);
                    helper.assertTrue(velocity.y > 1.0, "the dive should come back out as a climb, velocity " + velocity);
                })
                .thenSucceed();
    }

    private static BlockPos buildPool(ServerLevel level, BlockPos corner, Block portal) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-3, -1, -3), corner.offset(4, 8, 4))) {
            level.setBlock(pos, air, Block.UPDATE_CLIENTS);
        }
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-2, -1, -2), corner.offset(3, -1, 3))) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-2, 0, -2), corner.offset(3, 0, 3))) {
            level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(1, 0, 1))) {
            level.setBlock(pos, portal.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return corner.immutable();
    }

    private static BlockState oneWay(Block portal) {
        Property<?> property = portal.getStateDefinition().getProperty("is_one_way");
        return property instanceof BooleanProperty flag ? portal.defaultBlockState().setValue(flag, true) : null;
    }
}
