package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.AeronauticsBalloonFixup;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class BalloonTransferGameTests {
    private static final String GAS_PROVIDER = "dev.eriksonn.aeronautics.content.blocks.hot_air.BlockEntityLiftingGasProvider";

    @GameTest(batch = "balloon_gas_survives_transfer", template = "empty", timeoutTicks = 400)
    public static void balloon_gas_survives_transfer(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (!new AeronauticsBalloonFixup().isEnabled()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP balloon transfer: Aeronautics absent");
            helper.succeed();
            return;
        }
        ServerLevel src = helper.getLevel();
        ServerLevel dst = src.getServer().getLevel(Level.NETHER);
        UUID[] id = new UUID[1];
        double[] before = new double[1];

        helper.startSequence()
                .thenExecute(() -> {
                    ServerSubLevel ship = balloonShip(helper);
                    helper.assertTrue(ship != null, "Balloon ship failed to assemble");
                    id[0] = ship.getUniqueId();
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    ServerSubLevel ship = (ServerSubLevel) SubLevelContainer.getContainer(src).getSubLevel(id[0]);
                    helper.assertTrue(ship != null, "Balloon ship disappeared before the transfer");
                    before[0] = gas(ship);
                    helper.assertTrue(before[0] > 1.0, "Burner never filled the balloon in the source dimension: " + before[0]);
                    for (BlockPos pos : BlockPos.betweenClosed(-12, 88, -12, 12, 112, 12)) {
                        dst.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    PortalTeleport.teleportToDimension(src, ship, dst, new Vec3(0.5, 100.0, 0.5), false, "balloon-gas");
                    ServerSubLevel moved = (ServerSubLevel) SubLevelContainer.getContainer(dst).getSubLevel(id[0]);
                    helper.assertTrue(moved != null, "Balloon ship did not arrive in the Nether");
                    double after = gas(moved);
                    AeroPortals.LOGGER.info("[AeroPortals/test] balloon gas before={} after={}", before[0], after);
                    helper.assertTrue(after >= before[0] * 0.95,
                            "Balloon arrived with " + after + " of its " + before[0] + " hot air");
                })
                .thenSucceed();
    }

    private static ServerSubLevel balloonShip(GameTestHelper helper) {
        Block burner = block("aeronautics:adjustable_burner");
        Block envelope = block("aeronautics:white_envelope");
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                boolean centre = x == 7 && z == 7;
                place(helper, blocks, new BlockPos(x, 1, z), centre ? Blocks.REDSTONE_BLOCK : Blocks.STONE);
                place(helper, blocks, new BlockPos(x, 7, z), envelope);
                boolean wall = x == 5 || x == 9 || z == 5 || z == 9;
                if (!wall) continue;
                for (int y = 2; y <= 6; y++) place(helper, blocks, new BlockPos(x, y, z), envelope);
            }
        }
        place(helper, blocks, new BlockPos(7, 2, 7), burner);
        BlockPos anchor = helper.absolutePos(new BlockPos(7, 2, 7));
        BlockPos min = helper.absolutePos(new BlockPos(4, 0, 4));
        BlockPos max = helper.absolutePos(new BlockPos(10, 8, 10));
        return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), anchor, blocks,
                new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
    }

    private static void place(GameTestHelper helper, List<BlockPos> blocks, BlockPos local, Block block) {
        helper.setBlock(local, block.defaultBlockState());
        blocks.add(helper.absolutePos(local));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException("Missing block " + id));
    }

    private static double gas(ServerSubLevel ship) {
        try {
            Class<?> provider = Class.forName(GAS_PROVIDER);
            for (var holder : ship.getPlot().getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!provider.isInstance(be)) continue;
                    Object balloon = provider.getMethod("getBalloon").invoke(be);
                    if (balloon == null) return 0.0;
                    double total = 0.0;
                    for (Object gas : (List<?>) balloon.getClass().getMethod("getLiftingGasHolders").invoke(balloon)) {
                        Object data = gas.getClass().getMethod("data").invoke(gas);
                        total += data.getClass().getField("amount").getDouble(data);
                    }
                    return total;
                }
            }
            return 0.0;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read balloon gas", e);
        }
    }
}
