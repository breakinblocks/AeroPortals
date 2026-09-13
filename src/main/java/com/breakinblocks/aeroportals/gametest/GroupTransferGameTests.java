package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import com.breakinblocks.aeroportals.compat.SimulatedRopeCompat;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.SableBridge;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class GroupTransferGameTests {
    private static final FailingCarrier PROBE = new FailingCarrier();

    @GameTest(batch = "group_capacity_rejects_every_member", template = "empty")
    public static void group_capacity_rejects_every_member(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = nether(helper);
        ServerSubLevel first = assemble(helper, new BlockPos(4, 4, 7), Blocks.OBSIDIAN);
        ServerSubLevel second = assemble(helper, new BlockPos(10, 4, 7), Blocks.OBSIDIAN);
        List<SableBridge.Request> requests = requests(first, second);
        helper.assertTrue(SableBridge.canMoveGroup(source, destination, List.of(first, second)),
                "The unmodified destination must accept the fixture group");
        var container = SubLevelContainer.getContainer(destination);
        BitSet occupancy = container.getOccupancy();
        BitSet original = (BitSet) occupancy.clone();
        int capacity = 1 << (container.getLogSideLength() * 2);
        int free = original.nextClearBit(0);
        helper.assertTrue(free < capacity, "Fixture needs one real free plot");
        try {
            occupancy.set(0, capacity);
            occupancy.clear(free);
            helper.assertTrue(SableBridge.moveGroup(source, destination, requests).isEmpty(),
                    "Two-member group must not consume the only free destination plot");
            assertInSource(helper, source, destination, first, second);
        } finally {
            occupancy.clear();
            occupancy.or(original);
        }
        helper.succeed();
    }

    @GameTest(batch = "group_second_replay_failure_rolls_back_all", template = "empty", timeoutTicks = 200)
    public static void group_second_replay_failure_rolls_back_all(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = nether(helper);
        ServerSubLevel first = assemble(helper, new BlockPos(4, 4, 7), Blocks.OBSIDIAN);
        ServerSubLevel second = assemble(helper, new BlockPos(10, 4, 7), Blocks.OBSIDIAN);
        UUID a = first.getUniqueId();
        UUID b = second.getUniqueId();
        AeroPortalsApi.registerCarrier(PROBE);
        PROBE.arm(destination, b);
        PROBE.payloads.put(key(source, a), 17);
        PROBE.payloads.put(key(source, b), 29);
        try {
            helper.assertTrue(SableBridge.moveGroup(source, destination, requests(first, second)).isEmpty(),
                    "Failed group replay must return no successful members");
            helper.assertTrue(PROBE.failures == 1 && PROBE.destinationReplays == 2,
                    "Fixture must reach both destination replays and fail only the second");
            assertInSource(helper, source, destination, first, second);
            helper.assertTrue(Integer.valueOf(17).equals(PROBE.payloads.get(key(source, a))),
                    "First member's destructively captured source payload was lost during rollback");
            helper.assertTrue(Integer.valueOf(29).equals(PROBE.payloads.get(key(source, b))),
                    "Second member's source payload was lost after its destination replay threw");
            helper.assertTrue(!PROBE.payloads.containsKey(key(destination, a))
                            && !PROBE.payloads.containsKey(key(destination, b)),
                    "Partial destination payloads must be discarded before source replay");
            for (UUID id : List.of(a, b)) {
                ServerSubLevel restored = (ServerSubLevel) SubLevelContainer.getContainer(source).getSubLevel(id);
                helper.assertTrue(source.getBlockState(plotBlock(restored)).is(Blocks.OBSIDIAN),
                        "Rollback must restore each actual ship's block snapshot");
            }
        } finally {
            PROBE.disarm();
            // Failed assertions must not contaminate later recovery tests.
            TeleportJournal.delete(source.getServer(), a);
            TeleportJournal.delete(source.getServer(), b);
        }
        helper.succeed();
    }

    @GameTest(batch = "group_rope_partner_cooldown_rejects_all", template = "empty", timeoutTicks = 200)
    public static void group_rope_partner_cooldown_rejects_all(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (!SimulatedRopeCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] SKIP mixed group cooldown: Simulated rope API absent");
            helper.succeed();
            return;
        }
        ServerLevel source = helper.getLevel();
        ServerLevel destination = nether(helper);
        Block connector = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:rope_connector"));
        helper.assertTrue(connector != Blocks.AIR, "Simulated rope connector must be registered");
        ServerSubLevel[] ships = new ServerSubLevel[2];
        helper.startSequence()
                .thenExecute(() -> {
                    ships[0] = assemble(helper, new BlockPos(4, 4, 7), connector);
                    ships[1] = assemble(helper, new BlockPos(10, 4, 7), connector);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(connectRope(source, plotBlock(ships[0]), plotBlock(ships[1])),
                            "Fixture must create a real rope between the ships");
                    helper.assertTrue(PortalTeleport.transferGroup(source, ships[0]).size() == 2,
                            "Portal group must include the remote rope partner");
                    PortalCooldown.clear();
                    PortalCooldown.mark(ships[1].getUniqueId(), source.getServer().getTickCount());
                    try {
                        helper.assertTrue(!PortalCooldown.isOnCooldown(ships[0].getUniqueId(), source.getServer().getTickCount()),
                                "Only the remote partner should be on cooldown");
                        PortalTeleport.teleportToDimension(source, ships[0], destination,
                                new Vec3(20, 100, 20), false, "test:mixed-group-cooldown");
                        assertInSource(helper, source, destination, ships[0], ships[1]);
                        helper.assertTrue(SimulatedRopeCompat.ownedStrand(source.getBlockEntity(plotBlock(ships[0]))) != null,
                                "Rejected travel must leave the source rope connected");
                    } finally {
                        PortalCooldown.clear();
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "group_refused_prepare_keeps_prior_journal", template = "empty")
    public static void group_refused_prepare_keeps_prior_journal(GameTestHelper helper) throws IOException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = nether(helper);
        ServerSubLevel ship = assemble(helper, new BlockPos(7, 4, 7), Blocks.OBSIDIAN);
        UUID uuid = ship.getUniqueId();
        var data = SubLevelSerializer.toData(ship, List.of());
        CompoundTag metadata = new CompoundTag();
        metadata.put("source_snapshot", data.fullTag().copy());
        int regionBits = SubLevelContainer.getContainer(source).getLogPlotSize() + 4;
        metadata.putLong("old_region_min", new BlockPos(ship.getPlot().plotPos.x << regionBits,
                source.getMinBuildHeight(), ship.getPlot().plotPos.z << regionBits).asLong());
        metadata.putInt("region_blocks", 1 << regionBits);
        metadata.putUUID("group_id", uuid);
        CompoundTag member = new CompoundTag();
        member.putUUID("uuid", uuid);
        ListTag members = new ListTag();
        members.add(member);
        metadata.put("group_members", members);
        helper.assertTrue(TeleportJournal.write(source.getServer(), uuid, source.dimension().location(),
                        destination.dimension().location(), source.getMinBuildHeight(), data, metadata),
                "Fixture must create an existing valid recovery journal");
        Path path = TeleportJournal.pendingDir(source.getServer()).resolve(uuid + ".nbt");
        byte[] original = Files.readAllBytes(path);
        try {
            helper.assertTrue(SableBridge.moveGroup(source, destination,
                    List.of(new SableBridge.Request(ship, new Vec3(20, 100, 20)))).isEmpty(),
                    "A pending prior transfer must prevent reusing this ship's journal");
            helper.assertTrue(Files.exists(path) && Arrays.equals(original, Files.readAllBytes(path)),
                    "Refused preparation must not delete or overwrite the prior recovery data");
            helper.assertTrue(SubLevelContainer.getContainer(source).getSubLevel(uuid) == ship && !ship.isRemoved(),
                    "The refused transfer must retain its original source ship");
        } finally {
            TeleportJournal.delete(source.getServer(), uuid);
        }
        helper.succeed();
    }

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(level != null, "Nether must be loaded");
        return level;
    }

    private static List<SableBridge.Request> requests(ServerSubLevel first, ServerSubLevel second) {
        return List.of(new SableBridge.Request(first, new Vec3(20, 100, 20)),
                new SableBridge.Request(second, new Vec3(26, 100, 20)));
    }

    private static ServerSubLevel assemble(GameTestHelper helper, BlockPos local, Block block) {
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

    private static void assertInSource(GameTestHelper helper, ServerLevel source, ServerLevel destination,
                                       ServerSubLevel... ships) {
        for (ServerSubLevel ship : ships) {
            var restored = SubLevelContainer.getContainer(source).getSubLevel(ship.getUniqueId());
            helper.assertTrue(restored != null && !restored.isRemoved(), "Every group member must remain or return to source");
            helper.assertTrue(SubLevelContainer.getContainer(destination).getSubLevel(ship.getUniqueId()) == null,
                    "No group member may remain in the destination after a rejected or rolled-back move");
        }
    }

    private static boolean connectRope(ServerLevel level, BlockPos from, BlockPos to) {
        try {
            Class<?> type = Class.forName("dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior");
            Object behaviourType = type.getField("TYPE").get(null);
            Class<?> base = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour");
            for (Method get : base.getMethods()) {
                if (!get.getName().equals("get") || get.getParameterCount() != 2 || get.getParameterTypes()[0] != BlockEntity.class) continue;
                Object a = get.invoke(null, level.getBlockEntity(from), behaviourType);
                Object b = get.invoke(null, level.getBlockEntity(to), behaviourType);
                return a != null && b != null && (Boolean) type.getMethod("createRope", type, boolean.class).invoke(a, b, true);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not create the fixture rope", ex);
        }
        return false;
    }

    private static String key(ServerLevel level, UUID uuid) {
        return level.dimension().location() + "/" + uuid;
    }

    private static final class FailingCarrier implements TransferCarrier<CompoundTag> {
        private final Map<String, Integer> payloads = new HashMap<>();
        private ServerLevel destination;
        private UUID failOn;
        private boolean active;
        private int failures;
        private int destinationReplays;

        private void arm(ServerLevel destination, UUID failOn) {
            this.destination = destination;
            this.failOn = failOn;
            active = true;
            failures = 0;
            destinationReplays = 0;
            payloads.clear();
        }

        private void disarm() {
            active = false;
            destination = null;
            failOn = null;
            payloads.clear();
        }

        @Override public ResourceLocation id() { return AeroPortals.id("test_group_failure"); }
        @Override public boolean isEnabled() { return active; }

        @Override
        public CompoundTag capture(ServerLevel level, ServerSubLevel sub) {
            Integer value = payloads.remove(key(level, sub.getUniqueId()));
            if (value == null) return null;
            CompoundTag payload = new CompoundTag();
            payload.putInt("value", value);
            return payload;
        }

        @Override
        public void replay(ServerLevel level, ServerSubLevel sub, CompoundTag captured, BlockPos shift) {
            payloads.put(key(level, sub.getUniqueId()), captured.getInt("value"));
            if (level == destination) {
                destinationReplays++;
                if (sub.getUniqueId().equals(failOn) && failures++ == 0) {
                    throw new IllegalStateException("Intentional second-member replay failure after payload restoration");
                }
            }
        }
    }
}
