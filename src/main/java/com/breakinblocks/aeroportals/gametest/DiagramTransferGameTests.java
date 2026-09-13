package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class DiagramTransferGameTests {
    private DiagramTransferGameTests() {}

    @GameTest(batch = "diagram_preservesMountAndConfiguration", template = "empty", timeoutTicks = 200)
    public static void diagram_preservesMountAndConfiguration(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (!ModList.get().isLoaded("simulated")) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Simulated absent; skipping diagram transfer test");
            helper.succeed();
            return;
        }
        ServerLevel source = helper.getLevel();
        ServerLevel destination = source.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination != null, "Nether must be loaded");
        ServerSubLevelContainer destinationContainer = SubLevelContainer.getContainer(destination);
        UUID[] shipId = new UUID[1];
        UUID[] diagramId = new UUID[1];
        CompoundTag[] saved = new CompoundTag[1];
        BlockPos[] expectedMount = new BlockPos[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos world = helper.absolutePos(new BlockPos(7, 4, 7));
                    source.setBlockAndUpdate(world, Blocks.OBSIDIAN.defaultBlockState());
                    ServerSubLevel ship = assemble(source, world);
                    helper.assertTrue(ship != null, "Diagram support ship must assemble");
                    shipId[0] = ship.getUniqueId();
                    BlockPos oldSupport = support(ship);
                    HangingEntity diagram = diagram(source, oldSupport.above());
                    helper.assertTrue(diagram.survives(), "Diagram fixture must have solid support");
                    CompoundTag data = new CompoundTag();
                    diagram.save(data);
                    CompoundTag config = data.getCompound("Config");
                    config.putBoolean("display_center_of_mass", true);
                    config.putBoolean("merge_forces", true);
                    config.putDouble("yaw", 37.5);
                    config.putDouble("pitch", -12.25);
                    diagram.load(data);
                    saved[0] = new CompoundTag();
                    diagram.save(saved[0]);
                    diagramId[0] = diagram.getUUID();
                    helper.assertTrue(source.addFreshEntity(diagram), "Source must accept the diagram");

                    BlockPos occupied = new BlockPos(12700, 64, 12700);
                    destination.setBlockAndUpdate(occupied, Blocks.OBSIDIAN.defaultBlockState());
                    helper.assertTrue(assemble(destination, occupied) != null, "Destination plot must be occupied");
                    PortalTeleport.teleportToDimension(source, ship, destination,
                            new Vec3(12800, 64, 12800), false, "test:diagram");
                    ServerSubLevel arrived = (ServerSubLevel) destinationContainer.getSubLevel(shipId[0]);
                    helper.assertTrue(arrived != null, "Diagram support ship must arrive");
                    expectedMount[0] = support(arrived).above();
                    helper.assertTrue(!expectedMount[0].equals(oldSupport.above()),
                            "Fixture must relocate the diagram plot position");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    Entity arrived = destination.getEntity(diagramId[0]);
                    helper.assertTrue(arrived instanceof HangingEntity, "Diagram must arrive in the destination");
                    helper.assertTrue(source.getEntity(diagramId[0]) == null, "Source must not retain a diagram copy");
                    CompoundTag actual = new CompoundTag();
                    arrived.save(actual);
                    helper.assertTrue(actual.getCompound("Config").equals(saved[0].getCompound("Config")),
                            "Diagram display settings and notes must survive transfer");
                    helper.assertTrue(actual.getByte("Facing") == saved[0].getByte("Facing")
                                    && actual.getByte("Orientation") == saved[0].getByte("Orientation")
                                    && actual.getInt("Size") == saved[0].getInt("Size"),
                            "Diagram facing, orientation and size must survive transfer");
                    BlockPos mount = new BlockPos(actual.getInt("TileX"), actual.getInt("TileY"), actual.getInt("TileZ"));
                    helper.assertTrue(mount.equals(expectedMount[0]), "Diagram mount must follow its relocated support");
                    helper.assertTrue(((HangingEntity) arrived).survives(), "Transferred diagram must remain supported");
                })
                .thenSucceed();
    }

    private static HangingEntity diagram(ServerLevel level, BlockPos mount) {
        try {
            return (HangingEntity) Class.forName("dev.simulated_team.simulated.content.entities.diagram.DiagramEntity")
                    .getConstructor(Level.class, BlockPos.class, Direction.class, Direction.class)
                    .newInstance(level, mount, Direction.UP, Direction.NORTH);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot create Simulated contraption diagram", ex);
        }
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos pos) {
        return SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos),
                new BoundingBox3i(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
    }

    private static BlockPos support(ServerSubLevel sub) {
        var bounds = sub.getPlot().getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (sub.getLevel().getBlockState(pos).is(Blocks.OBSIDIAN)) return pos.immutable();
        }
        throw new IllegalStateException("Diagram support disappeared from " + sub.getUniqueId());
    }
}
