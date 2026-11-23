package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.compat.DeepWatersCompat;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class DeepWatersGameTests {
    private DeepWatersGameTests() {}

    @GameTest(batch = "deep_waters_round_trip", template = "empty", timeoutTicks = 400)
    public static void deepWatersRoundTrip(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        Block portal = DeepWatersCompat.portalBlock();
        if (portal == null) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Deep Waters not loaded; round trip skipped");
            helper.succeed();
            return;
        }

        ServerLevel overworld = helper.getLevel();
        ServerLevel caves = overworld.getServer().getLevel(DeepWatersCompat.DIMENSION);
        helper.assertTrue(caves != null, "Deep Waters must load its dimension");
        BlockPos local = helper.absolutePos(new BlockPos(7, 4, 7));
        BlockPos entry = new BlockPos(local.getX(), 100, local.getZ());
        // Leave enough water around the planned destination to exercise an underwater arrival.
        for (BlockPos pos : BlockPos.betweenClosed(entry.offset(-8, -3, -8), entry.offset(8, 9, 8))) {
            caves.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
            overworld.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        var portalState = portal.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X);
        PortalBuilder.build(overworld, entry, Direction.Axis.X, 4, 5, Blocks.COBBLED_DEEPSLATE, portalState);
        var type = AeroPortalsApi.findPortalType(overworld.getBlockState(entry));
        helper.assertTrue(type != null && type.id().equals(AeroPortals.id("deep_waters")), "Portal must be registered");

        BlockPos block = entry.offset(1, 1, 1);
        overworld.setBlockAndUpdate(block, Blocks.OBSIDIAN.defaultBlockState());
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(overworld, block, List.of(block),
                new BoundingBox3i(block.getX() - 1, block.getY() - 1, block.getZ() - 1,
                        block.getX() + 1, block.getY() + 1, block.getZ() + 1));
        helper.assertTrue(ship != null, "Test ship must assemble");

        var outbound = type.resolve(overworld, ship, entry);
        helper.assertTrue(outbound != null && outbound.level() == caves && outbound.construction() != null,
                "Outbound portal must plan a matching exit in Deep Waters");
        var exit = outbound.construction().rect();
        helper.assertTrue(exit.width() == 4 && exit.height() == 5, "Exit must match the source size");
        helper.assertTrue(PortalTeleport.dispatchResult(overworld, ship, outbound), "Ship must enter Deep Waters");
        helper.assertTrue(caves.getBlockState(exit.minCorner()).is(portal), "Arrival must build an active Deep Waters portal");
        helper.assertTrue(caves.getBlockState(exit.minCorner().below()).is(Blocks.COBBLED_DEEPSLATE),
                "Arrival must use the mod's frame material");
        var arrived = (ServerSubLevel) SubLevelContainer.getContainer(caves).getSubLevel(ship.getUniqueId());
        helper.assertTrue(arrived != null, "The original ship UUID must arrive");

        var inbound = type.resolve(caves, arrived, exit.minCorner());
        helper.assertTrue(inbound != null && inbound.level() == overworld && inbound.construction() == null,
                "Return portal must link to the existing Overworld portal");
        PortalCooldown.clear();
        helper.assertTrue(PortalTeleport.dispatchResult(caves, arrived, inbound), "The same ship must return");
        helper.assertTrue(SubLevelContainer.getContainer(caves).getSubLevel(ship.getUniqueId()) == null,
                "Return trip must retire the Deep Waters copy");
        helper.succeed();
    }
}
