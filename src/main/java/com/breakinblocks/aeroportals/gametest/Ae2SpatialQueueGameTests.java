package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.api.SubLevelPreTransferEvent;
import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.Ae2SpatialCompat;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class Ae2SpatialQueueGameTests {
    @GameTest(batch = "ae2Spatial_retryPreservesRapidSwapOrder", template = "empty")
    public static void ae2Spatial_retryPreservesRapidSwapOrder(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.isolate(helper);
        Ae2SpatialCompat.clear();
        ServerLevel world = helper.getLevel();
        ServerLevel cell = world.getServer().getLevel(Level.NETHER);
        helper.assertTrue(cell != null, "test destination must exist");
        // Keep both sides within valid build heights and ordinary physics coordinate precision.
        BlockPos origin = new BlockPos(12000, 100, 12000);
        world.setBlockAndUpdate(origin, Blocks.OBSIDIAN.defaultBlockState());
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(world, origin, List.of(origin),
                new BoundingBox3i(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                        origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1));
        helper.assertTrue(sub != null, "test ship must assemble");
        UUID id = sub.getUniqueId();
        Vec3 start = position(sub);
        Vec3 shift = new Vec3(80, 0, 80);
        AABB worldRegion = AabbUtil.worldAabb(sub).inflate(2);
        AABB cellRegion = worldRegion.move(shift);
        long now = world.getServer().getTickCount();
        VetoProbe probe = new VetoProbe(id);
        NeoForge.EVENT_BUS.register(probe);
        try {
            PortalCooldown.mark(id, now);
            queue(world, cell, worldRegion, cellRegion, shift);
            process(world.getServer(), now);
            helper.assertTrue(probe.labels.equals(List.of("ae2-spatial-store")),
                    "spatial swaps must reach preflight even during portal cooldown");
            helper.assertTrue(pendingCount() == 1, "a vetoed store must remain queued");
            helper.assertTrue(SubLevelContainer.getContainer(world).getSubLevel(id) != null,
                    "vetoed store must leave the ship in its source");

            // AE2 swaps again before the first store succeeds. This must enqueue recall of that same ship.
            queue(world, cell, worldRegion, cellRegion, shift);
            helper.assertTrue(pendingCount() == 2, "a second swap must retain its own immutable operation");
            probe.cancel = false;
            process(world.getServer(), now + 20);
            helper.assertTrue(probe.labels.equals(List.of("ae2-spatial-store", "ae2-spatial-store", "ae2-spatial-recall")),
                    "retry must store then recall, without storing the original source twice");
            helper.assertTrue(probe.destinations.equals(List.of(start.add(shift), start.add(shift), start)),
                    "each queued attempt must request its exact captured destination: " + probe.destinations);
            helper.assertTrue(pendingCount() == 0, "both successful operations must leave the queue");
            ServerSubLevel returned = (ServerSubLevel) SubLevelContainer.getContainer(world).getSubLevel(id);
            helper.assertTrue(returned != null, "rapid store/recall must return the ship despite fresh portal cooldown");
            helper.assertTrue(SubLevelContainer.getContainer(cell).getSubLevel(id) == null,
                    "rapid recall must not leave the ship in storage");
            Vec3 actual = position(returned);
            AeroPortals.LOGGER.info("[AeroPortals/test] AE2 queued recall expected={} actual={} squared-error={}",
                    start, actual, actual.distanceToSqr(start));
            helper.assertTrue(actual.distanceToSqr(start) < 0.001,
                    "queued recall must preserve the originally captured destination; expected " + start + ", actual " + actual);
        } finally {
            NeoForge.EVENT_BUS.unregister(probe);
            Ae2SpatialCompat.clear();
            for (ServerLevel level : List.of(world, cell)) {
                var container = SubLevelContainer.getContainer(level);
                var remaining = container.getSubLevel(id);
                if (remaining != null) container.removeSubLevel(remaining, SubLevelRemovalReason.REMOVED);
            }
        }
        helper.succeed();
    }

    private static void queue(ServerLevel world, ServerLevel cell, AABB worldRegion, AABB cellRegion, Vec3 shift)
            throws ReflectiveOperationException {
        Method method = Ae2SpatialCompat.class.getDeclaredMethod("queueTransition", ServerLevel.class, ServerLevel.class,
                AABB.class, AABB.class, Vec3.class, int.class);
        method.setAccessible(true);
        method.invoke(null, world, cell, worldRegion, cellRegion, shift, 100);
    }

    private static void process(MinecraftServer server, long now) throws ReflectiveOperationException {
        Method method = Ae2SpatialCompat.class.getDeclaredMethod("processPending", MinecraftServer.class, long.class);
        method.setAccessible(true);
        method.invoke(null, server, now);
    }

    private static int pendingCount() throws ReflectiveOperationException {
        Field field = Ae2SpatialCompat.class.getDeclaredField("pendingMoves");
        field.setAccessible(true);
        return ((List<?>) field.get(null)).size();
    }

    private static Vec3 position(ServerSubLevel sub) {
        var position = sub.logicalPose().position();
        return new Vec3(position.x(), position.y(), position.z());
    }

    public static final class VetoProbe {
        private final UUID ship;
        final List<String> labels = new ArrayList<>();
        final List<Vec3> destinations = new ArrayList<>();
        boolean cancel = true;

        VetoProbe(UUID ship) {
            this.ship = ship;
        }

        @SubscribeEvent
        public void onTransfer(SubLevelPreTransferEvent event) {
            if (!event.sub().getUniqueId().equals(ship)) return;
            labels.add(event.label());
            destinations.add(event.originalDestination());
            if (cancel) event.cancel("queue retry probe");
        }
    }
}
