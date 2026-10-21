package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.IrregularImplementsCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.PortalDetector;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.SableBridge;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import com.breakinblocks.aeroportals.portal.TransferRemovalScope;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class EndTravelRegressionGameTests {
    private EndTravelRegressionGameTests() {}

    @GameTest(batch = "artificialEndPortal_transfer", template = "empty", timeoutTicks = 200)
    public static void artificialEndPortalTransfersShip(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(7, 4, 7));
        ServerSubLevel ship = ship(helper, origin, List.of(origin), Blocks.OBSIDIAN);
        Entity portal = artificialPortal(helper, origin, 200);
        if (portal == null) return;
        UUID id = ship.getUniqueId();
        helper.assertTrue(source.getBlockState(origin).isAir(), "The fixture must contain no End Portal blocks");
        helper.assertTrue(IrregularImplementsCompat.findTouching(source, ship, AabbUtil.worldAabb(ship).inflate(1)) != null,
                "A mature artificial portal must detect the ship's blocks");
        PortalDetector.scan(source);
        helper.runAfterDelay(5, () -> {
            ServerLevel end = source.getServer().getLevel(Level.END);
            helper.assertTrue(SubLevelContainer.getContainer(source).getSubLevel(id) == null, "Ship must leave source");
            helper.assertTrue(SubLevelContainer.getContainer(end).getSubLevel(id) != null, "Ship must arrive in the End");
            helper.succeed();
        });
    }

    @GameTest(batch = "artificialEndPortal_disabled", template = "empty")
    public static void artificialEndPortalRespectsReadinessAndDisabledEnd(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(7, 4, 7));
        ServerSubLevel ship = ship(helper, origin, List.of(origin), Blocks.OBSIDIAN);
        Entity portal = artificialPortal(helper, origin, 199);
        if (portal == null) return;
        helper.assertTrue(IrregularImplementsCompat.findTouching(level, ship, AabbUtil.worldAabb(ship).inflate(1)) == null,
                "A portal which is still opening must not carry ships");
        CompoundTag tag = new CompoundTag();
        portal.saveWithoutId(tag);
        tag.putInt("ActionTimer", 200);
        portal.load(tag);
        List<? extends String> disabled = AeroPortalsConfig.DISABLED_TRAVEL_METHODS.get();
        try {
            AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(List.of("end"));
            PortalDetector.scan(level);
            helper.assertTrue(IrregularImplementsCompat.findTouching(level, ship, AabbUtil.worldAabb(ship).inflate(1)) == null,
                    "Disabling End travel must include artificial portals");
            helper.assertTrue(SubLevelContainer.getContainer(level).getSubLevel(ship.getUniqueId()) == ship,
                    "Disabled End travel must leave the source ship intact");
        } finally {
            AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(disabled);
        }
        helper.succeed();
    }

    @GameTest(batch = "quantumBridge_transfer", template = "empty", timeoutTicks = 200)
    public static void quantumBridgeSurvivesTransferAndReload(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.END);
        // No players enter these test dimensions; keep block entities ticking through the round trip.
        source.resetEmptyTime();
        destination.resetEmptyTime();
        Block ring = block("ae2:quantum_ring");
        Block link = block("ae2:quantum_link");
        if (ring == Blocks.AIR) {
            AeroPortals.LOGGER.info("[AeroPortals/test] AE2 absent; skipping Quantum Bridge regression");
            helper.succeed();
            return;
        }
        BlockPos centre = helper.absolutePos(new BlockPos(7, 6, 7));
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = centre.offset(x, 0, z);
                source.setBlockAndUpdate(pos, (x == 0 && z == 0 ? link : ring).defaultBlockState());
                blocks.add(pos);
            }
        }
        source.setBlockAndUpdate(centre.east(2), block("ae2:cable_bus").defaultBlockState());
        source.setBlockAndUpdate(centre.east(3), block("ae2:creative_energy_cell").defaultBlockState());
        source.setBlockAndUpdate(centre.west(2), Blocks.CHEST.defaultBlockState());
        blocks.add(centre.east(2));
        blocks.add(centre.east(3));
        blocks.add(centre.west(2));
        ServerSubLevel ship = assemble(source, centre, blocks);
        BlockEntity cable = source.getBlockEntity(find(ship, block("ae2:cable_bus")));
        Object part = cable.getClass().getMethod("addPart", Class.forName("appeng.api.parts.IPartItem"),
                        Direction.class, Player.class)
                .invoke(cable, BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:fluix_glass_cable")), null, null);
        helper.assertTrue(part != null, "Fixture must have a real ME cable");
        BlockPos triggerPos = find(ship, Blocks.CHEST);
        BlockEntity bridge = source.getBlockEntity(find(ship, link));
        NeighborUpdateChest trigger = new NeighborUpdateChest(triggerPos, source.getBlockState(triggerPos), bridge);
        source.setBlockEntity(trigger);
        UUID id = ship.getUniqueId();
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            assertQuantumBridge(helper, ship);
            PortalTeleport.holdDestinationChunks(destination, new Vec3(100.5, 90, 0.5), 2);
            SableBridge.Moved moved = SableBridge.moveAcrossDimensions(ship, source, destination, new Vec3(100.5, 90, 0.5));
            helper.assertTrue(trigger.called, "The fixture must force a neighbor update after destroying a bridge node");
            helper.assertTrue(moved != null, "Initialized Quantum Bridge must transfer without rolling back");
        }).thenIdle(30).thenExecute(() -> {
            ServerSubLevel arrived = (ServerSubLevel) SubLevelContainer.getContainer(destination).getSubLevel(id);
            helper.assertTrue(arrived != null, "Destination ship must survive its initialization ticks");
            assertQuantumBridge(helper, arrived);
            SableBridge.Moved returned = SableBridge.moveAcrossDimensions(arrived, destination, source, Vec3.atCenterOf(centre));
            helper.assertTrue(returned != null, "Reloaded Quantum Bridge must transfer again");
        }).thenIdle(30).thenExecute(() -> {
            ServerSubLevel returned = (ServerSubLevel) SubLevelContainer.getContainer(source).getSubLevel(id);
            helper.assertTrue(returned != null, "Return ship must survive initialization");
            assertQuantumBridge(helper, returned);
        }).thenSucceed();
    }

    @GameTest(batch = "sourceUnload_failure", template = "empty", timeoutTicks = 200)
    public static void unloadFailureRestoresFreshSourceAndPhysics(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.END);
        BlockPos origin = helper.absolutePos(new BlockPos(7, 4, 7));
        ServerSubLevel ship = ship(helper, origin, List.of(origin, origin.east()), Blocks.CHEST);
        BlockPos chestPos = find(ship, Blocks.CHEST);
        FailingChest failing = new FailingChest(chestPos, source.getBlockState(chestPos));
        failing.setItem(0, new ItemStack(Items.DIAMOND, 7));
        source.setBlockEntity(failing);
        UUID id = ship.getUniqueId();
        SableBridge.Moved moved = SableBridge.moveAcrossDimensions(ship, source, destination, new Vec3(100.5, 90, 0.5));
        helper.assertTrue(moved == null && failing.called, "Injected unload failure must fail the transfer");
        helper.assertTrue(ship.isRemoved(), "Failed unload must still finish removing the original ship");
        helper.assertTrue(!TransferRemovalScope.isRemoving(source, chestPos), "Removal scope must close after failure");
        ServerSubLevel restored = (ServerSubLevel) SubLevelContainer.getContainer(source).getSubLevel(id);
        helper.assertTrue(restored != null && restored != ship && !restored.isRemoved(), "Rollback must load a fresh source copy");
        helper.assertTrue(SubLevelContainer.getContainer(destination).getSubLevel(id) == null, "No destination copy may remain");
        ChestBlockEntity chest = (ChestBlockEntity) source.getBlockEntity(find(restored, Blocks.CHEST));
        boolean cargoFound = false;
        for (BlockPos pos : positions(restored)) {
            if (source.getBlockEntity(pos) instanceof ChestBlockEntity candidate && candidate.getItem(0).getCount() == 7) cargoFound = true;
        }
        helper.assertTrue(chest != null && cargoFound, "Rollback must preserve the saved chest contents");
        helper.assertTrue(!Files.exists(TeleportJournal.pendingDir(source.getServer()).resolve(id + ".nbt")),
                "A durable successful rollback must complete its journal");
        helper.runAfterDelay(5, () -> {
            SableBridge.Moved retried = SableBridge.moveAcrossDimensions(restored, source, destination, new Vec3(100.5, 90, 0.5));
            helper.assertTrue(retried != null, "Restored physics and blocks must support a subsequent successful transfer");
            helper.succeed();
        });
    }

    private static Entity artificialPortal(GameTestHelper helper, BlockPos origin, int timer) {
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("irregular_implements:artificial_end_portal"));
        if (type.isEmpty()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Irregular Implements absent; skipping artificial portal regression");
            helper.succeed();
            return null;
        }
        ServerLevel level = helper.getLevel();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.END_STONE.defaultBlockState());
        for (int i = -1; i <= 1; i++) {
            for (BlockPos edge : List.of(origin.offset(-2, 0, i), origin.offset(2, 0, i), origin.offset(i, 0, -2), origin.offset(i, 0, 2))) {
                level.setBlockAndUpdate(edge, Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(origin.above(4), Blocks.END_STONE.defaultBlockState());
        level.setBlockAndUpdate(origin.above(3), Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.DOWN));
        Entity portal = type.get().create(level);
        portal.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        CompoundTag tag = new CompoundTag();
        portal.saveWithoutId(tag);
        tag.putInt("ActionTimer", timer);
        portal.load(tag);
        helper.assertTrue(level.addFreshEntity(portal), "Artificial portal must spawn");
        return portal;
    }

    private static void assertQuantumBridge(GameTestHelper helper, ServerSubLevel ship) {
        try {
            BlockEntity bridge = ship.getLevel().getBlockEntity(find(ship, block("ae2:quantum_link")));
            Object managedNode = bridge.getClass().getMethod("getMainNode").invoke(bridge);
            Object node = managedNode.getClass().getMethod("getNode").invoke(managedNode);
            AeroPortals.LOGGER.info("[AeroPortals/test] Quantum Bridge in {}: formed={}, powered={}, initialized={}, removed={}",
                    ship.getLevel().dimension().location(), bridge.getClass().getMethod("isFormed").invoke(bridge),
                    bridge.getClass().getMethod("isPowered").invoke(bridge), node != null, bridge.isRemoved());
            helper.assertTrue((boolean) bridge.getClass().getMethod("isFormed").invoke(bridge),
                    "Quantum Bridge must be formed in " + ship.getLevel().dimension().location());
            helper.assertTrue((boolean) bridge.getClass().getMethod("isPowered").invoke(bridge), "Quantum Bridge must be powered");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot inspect Quantum Bridge fixture", ex);
        }
    }

    private static ServerSubLevel ship(GameTestHelper helper, BlockPos origin, List<BlockPos> blocks, Block block) {
        for (BlockPos pos : blocks) helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
        return assemble(helper.getLevel(), origin, blocks);
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos origin, List<BlockPos> blocks) {
        return SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks,
                new BoundingBox3i(origin.getX() - 5, origin.getY() - 2, origin.getZ() - 5,
                        origin.getX() + 5, origin.getY() + 2, origin.getZ() + 5));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private static Iterable<BlockPos> positions(ServerSubLevel ship) {
        var bounds = ship.getPlot().getBoundingBox();
        return BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static BlockPos find(ServerSubLevel ship, Block block) {
        for (BlockPos pos : positions(ship)) if (ship.getLevel().getBlockState(pos).is(block)) return pos.immutable();
        throw new IllegalStateException("Fixture block missing: " + block);
    }

    private static final class FailingChest extends ChestBlockEntity {
        boolean called;

        FailingChest(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public void onChunkUnloaded() {
            super.onChunkUnloaded();
            called = true;
            throw new IllegalStateException("Injected source block entity unload failure");
        }
    }

    private static final class NeighborUpdateChest extends ChestBlockEntity {
        private final BlockEntity bridge;
        boolean called;

        NeighborUpdateChest(BlockPos pos, BlockState state, BlockEntity bridge) {
            super(pos, state);
            this.bridge = bridge;
        }

        @Override
        public void onChunkUnloaded() {
            super.onChunkUnloaded();
            called = true;
            try {
                Object node = bridge.getClass().getMethod("getMainNode").invoke(bridge);
                node.getClass().getMethod("destroy").invoke(node);
                bridge.getClass().getMethod("neighborUpdate", BlockPos.class).invoke(bridge, getBlockPos());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Quantum Bridge recalculated after its grid node was destroyed", ex);
            }
        }
    }
}
