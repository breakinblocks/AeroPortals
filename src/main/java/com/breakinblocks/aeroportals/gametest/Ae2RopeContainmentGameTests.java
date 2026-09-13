package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.Ae2SpatialCompat;
import com.breakinblocks.aeroportals.compat.SimulatedRopeCompat;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class Ae2RopeContainmentGameTests {
    @GameTest(batch = "ae2Spatial_requiresEveryRopePartnerInside", template = "empty", timeoutTicks = 100)
    public static void ae2Spatial_requiresEveryRopePartnerInside(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        Ae2SpatialCompat.clear();
        if (!SimulatedRopeCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Simulated absent; skipping AE2 rope containment test");
            helper.succeed();
            return;
        }
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "test destination must exist");
        Block connector = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:rope_connector"));
        helper.assertTrue(connector != Blocks.AIR, "rope connectors must be registered");
        ServerSubLevel[] ships = new ServerSubLevel[3];
        helper.startSequence()
                .thenExecute(() -> {
                    for (int i = 0; i < ships.length; i++) {
                        BlockPos origin = helper.absolutePos(new BlockPos(2 + 6 * i, 4, 7));
                        source.setBlockAndUpdate(origin, connector.defaultBlockState());
                        ships[i] = SubLevelAssemblyHelper.assembleBlocks(source, origin, List.of(origin),
                                new BoundingBox3i(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                                        origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1));
                        helper.assertTrue(ships[i] != null, "test ships must assemble");
                    }
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    try {
                        BlockPos plotA = connector(source, ships[0], connector);
                        BlockPos plotB = connector(source, ships[1], connector);
                        BlockPos plotC = connector(source, ships[2], connector);
                        tie(source, plotA, plotB);
                        helper.assertTrue(!AabbUtil.worldAabb(ships[0]).intersects(AabbUtil.worldAabb(ships[1])),
                                "rope fixture must connect spatially separate ships");
                        var expanded = SimulatedRopeCompat.withRopePartners(source, List.of(ships[0]));
                        helper.assertTrue(expanded.size() == 2 && expanded.contains(ships[1]),
                                "rope expansion from one ship must discover its separate partner");
                        helper.assertTrue(PortalTeleport.transferGroup(source, ships[0]).contains(ships[1]),
                                "the transfer must include the remote rope partner");
                        AABB narrow = AabbUtil.worldAabb(ships[0]).inflate(1);
                        Vec3 shift = new Vec3(80, 0, 80);
                        queue(source, destination, narrow, shift);
                        helper.assertTrue(pendingCount() == 0,
                                "AE2 must not queue a ship when its rope partner is outside the pylon region");

                        AABB both = AabbUtil.worldAabb(ships[0]).minmax(AabbUtil.worldAabb(ships[1])).inflate(1);
                        queue(source, destination, both, shift);
                        helper.assertTrue(pendingCount() == 1,
                                "a fully contained rope group must queue once, not once per ship");
                        tie(source, plotB, plotC);
                        helper.assertTrue(PortalTeleport.transferGroup(source, ships[0]).size() == 3,
                                "the queued group must now have an additional rope partner");
                        process(source.getServer());
                        helper.assertTrue(pendingCount() == 1,
                                "retry must stop when the complete transfer group changed after containment was checked");
                        for (ServerSubLevel ship : ships) {
                            UUID id = ship.getUniqueId();
                            helper.assertTrue(SubLevelContainer.getContainer(source).getSubLevel(id) != null,
                                    "changed rope groups must remain in the source");
                            helper.assertTrue(SubLevelContainer.getContainer(destination).getSubLevel(id) == null,
                                    "an unvalidated rope partner must not be pulled into storage");
                        }
                    } catch (ReflectiveOperationException failure) {
                        helper.fail("rope containment setup failed: " + failure);
                    } finally {
                        Ae2SpatialCompat.clear();
                        for (ServerLevel level : List.of(source, destination)) {
                            var container = SubLevelContainer.getContainer(level);
                            for (ServerSubLevel ship : ships) {
                                var remaining = container.getSubLevel(ship.getUniqueId());
                                if (remaining != null) container.removeSubLevel(remaining, SubLevelRemovalReason.REMOVED);
                            }
                        }
                    }
                })
                .thenSucceed();
    }

    private static BlockPos connector(ServerLevel level, ServerSubLevel ship, Block block) throws ReflectiveOperationException {
        Method method = PortalGameTests.class.getDeclaredMethod("findBlockInPlot", ServerLevel.class, ServerSubLevel.class, Block.class);
        method.setAccessible(true);
        return (BlockPos) method.invoke(null, level, ship, block);
    }

    private static void tie(ServerLevel level, BlockPos first, BlockPos second) throws ReflectiveOperationException {
        Method method = PortalGameTests.class.getDeclaredMethod("connectRope", ServerLevel.class, BlockPos.class, BlockPos.class);
        method.setAccessible(true);
        if (!(Boolean) method.invoke(null, level, first, second)) throw new IllegalStateException("could not connect test rope");
    }

    private static void queue(ServerLevel source, ServerLevel destination, AABB region, Vec3 shift) throws ReflectiveOperationException {
        Method method = Ae2SpatialCompat.class.getDeclaredMethod("queueTransition", ServerLevel.class, ServerLevel.class,
                AABB.class, AABB.class, Vec3.class, int.class);
        method.setAccessible(true);
        method.invoke(null, source, destination, region, region.move(shift), shift, 101);
    }

    private static int pendingCount() throws ReflectiveOperationException {
        Field field = Ae2SpatialCompat.class.getDeclaredField("pendingMoves");
        field.setAccessible(true);
        return ((List<?>) field.get(null)).size();
    }

    private static void process(MinecraftServer server) throws ReflectiveOperationException {
        Method method = Ae2SpatialCompat.class.getDeclaredMethod("processPending", MinecraftServer.class, long.class);
        method.setAccessible(true);
        method.invoke(null, server, (long) server.getTickCount());
    }
}
