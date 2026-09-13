package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class SwivelTransferGameTests {
    private SwivelTransferGameTests() {}

    @GameTest(batch = "swivelAssembly_survivesDimensionTransfer", template = "empty", timeoutTicks = 200)
    public static void swivelAssembly_survivesDimensionTransfer(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (!ModList.get().isLoaded("simulated")) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Simulated absent; skipping swivel assembly test");
            helper.succeed();
            return;
        }
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(source);
        ServerSubLevelContainer destinationContainer = SubLevelContainer.getContainer(destination);
        Block bearingBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        helper.assertTrue(bearingBlock != Blocks.AIR, "Simulated swivel bearing must be registered");
        UUID[] ships = new UUID[2];
        BlockPos[] oldPlatePos = new BlockPos[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos bearingWorld = helper.absolutePos(new BlockPos(7, 4, 7));
                    source.setBlockAndUpdate(bearingWorld, bearingBlock.defaultBlockState()
                            .setValue(BlockStateProperties.FACING, Direction.UP));
                    ServerSubLevel base = assemble(source, bearingWorld);
                    helper.assertTrue(base != null, "Bearing ship should assemble");
                    ships[0] = base.getUniqueId();

                    // Occupy the first destination plot so both connection ends need remapping.
                    BlockPos occupied = new BlockPos(12500, 64, 12500);
                    destination.setBlockAndUpdate(occupied, Blocks.OBSIDIAN.defaultBlockState());
                    helper.assertTrue(assemble(destination, occupied) != null, "Destination plot should be occupied");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    BlockEntity bearing = bearing(sourceContainer, ships[0], bearingBlock);
                    invoke(bearing, "assemble");
                    ships[1] = (UUID) invoke(bearing, "getSubLevelID");
                    oldPlatePos[0] = (BlockPos) invoke(bearing, "getPlatePos");
                    helper.assertTrue(ships[1] != null && oldPlatePos[0] != null,
                            "Simulated should assemble a real plate ship");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    BlockEntity bearing = bearing(sourceContainer, ships[0], bearingBlock);
                    assertConstraint(helper, bearing);
                    ServerSubLevel base = (ServerSubLevel) sourceContainer.getSubLevel(ships[0]);
                    PortalTeleport.teleportToDimension(source, base, destination,
                            new Vec3(12600, 64, 12600), false, "test:swivel");

                    helper.assertTrue(destinationContainer.getSubLevel(ships[0]) != null
                                    && destinationContainer.getSubLevel(ships[1]) != null,
                            "Both swivel-connected ships must arrive");
                    BlockEntity arrivedBearing = bearing(destinationContainer, ships[0], bearingBlock);
                    BlockPos arrivedPlate = (BlockPos) invoke(arrivedBearing, "getPlatePos");
                    helper.assertTrue(!oldPlatePos[0].equals(arrivedPlate), "Fixture must change the plate plot position");
                    BlockEntity plate = destination.getBlockEntity(arrivedPlate);
                    helper.assertTrue(plate != null, "Bearing must point to the destination plate");
                    CompoundTag plateData = plate.saveWithFullMetadata(destination.registryAccess());
                    helper.assertTrue(NbtUtils.readBlockPos(plateData, "ParentPos").orElseThrow()
                                    .equals(arrivedBearing.getBlockPos()),
                            "Plate must point back to the destination bearing");
                    helper.assertTrue(ships[0].equals(plateData.getUUID("ParentSubLevelId")),
                            "Plate must retain the parent ship identity");
                    assertConstraint(helper, arrivedBearing);
                })
                .thenIdle(5)
                .thenExecute(() -> assertConstraint(helper, bearing(destinationContainer, ships[0], bearingBlock)))
                .thenSucceed();
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos pos) {
        return SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos),
                new BoundingBox3i(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
    }

    private static BlockEntity bearing(ServerSubLevelContainer container, UUID ship, Block block) {
        ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(ship);
        if (sub == null) throw new IllegalStateException("Swivel ship disappeared: " + ship);
        for (var holder : sub.getPlot().getLoadedChunks()) {
            for (BlockEntity entity : holder.getChunk().getBlockEntities().values()) {
                if (entity.getBlockState().is(block)) return entity;
            }
        }
        throw new IllegalStateException("Swivel bearing disappeared from " + ship);
    }

    private static Object invoke(BlockEntity bearing, String method) {
        try {
            return bearing.getClass().getMethod(method).invoke(bearing);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot call Simulated " + method, ex);
        }
    }

    private static void assertConstraint(GameTestHelper helper, BlockEntity bearing) {
        try {
            Field field = bearing.getClass().getDeclaredField("handle");
            field.setAccessible(true);
            PhysicsConstraintHandle handle = (PhysicsConstraintHandle) field.get(bearing);
            helper.assertTrue(handle != null, "Swivel must have a physics constraint");
            helper.assertTrue(handle.isValid(),
                    "Swivel physics constraint must remain valid");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot inspect Simulated swivel constraint", ex);
        }
    }
}
