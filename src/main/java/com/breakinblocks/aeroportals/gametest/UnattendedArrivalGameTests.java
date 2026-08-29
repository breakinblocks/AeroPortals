package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.ShipDirectory;
import com.breakinblocks.aeroportals.portal.ShipRecovery;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class UnattendedArrivalGameTests {

    private static final String EMPTY = "empty";

    private static List<BlockPos> buildCube(GameTestHelper helper, BlockPos localMin, int size) {
        List<BlockPos> world = new ArrayList<>();
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos local = localMin.offset(x, y, z);
                    helper.setBlock(local, obsidian);
                    world.add(helper.absolutePos(local));
                }
            }
        }
        return world;
    }

    private static void prepareNetherZone(ServerLevel nether, int centreX, int centreZ) {
        int floorTop = nether.getMinBuildHeight() + 2;
        int maxY = Math.min(nether.getMaxBuildHeight() - 2, floorTop + 60);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState floor = Blocks.NETHERRACK.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = centreX - 24; x <= centreX + 24; x++) {
            for (int z = centreZ - 24; z <= centreZ + 24; z++) {
                for (int y = nether.getMinBuildHeight() + 1; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState wanted = y <= floorTop ? floor : air;
                    if (nether.getBlockState(cursor) == wanted) continue;
                    nether.setBlock(cursor, wanted, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static String describe(ServerSubLevelContainer container, UUID uuid) {
        ServerSubLevel active = (ServerSubLevel) container.getSubLevel(uuid);
        HoldingSubLevel holding = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
        if (active != null) {
            AABB box = AabbUtil.worldAabb(active);
            return "ACTIVE pose=" + active.logicalPose().position() + " aabb=" + box;
        }
        if (holding != null) {
            return "HOLDING at " + boundsCentre(holding) + " chunk " + holdingChunk(holding) + " pointer=" + holding.pointer();
        }
        return "MISSING";
    }

    private static Vec3 boundsCentre(HoldingSubLevel holding) {
        return new Vec3(
                (holding.data().bounds().minX() + holding.data().bounds().maxX()) / 2.0,
                (holding.data().bounds().minY() + holding.data().bounds().maxY()) / 2.0,
                (holding.data().bounds().minZ() + holding.data().bounds().maxZ()) / 2.0);
    }

    private static ChunkPos holdingChunk(HoldingSubLevel holding) {
        return new ChunkPos(BlockPos.containing(boundsCentre(holding)));
    }

    @GameTest(batch = "unattended_shipSurvivesArrivalWithNoPlayer", template = EMPTY, timeoutTicks = 600)
    public static void unattended_shipSurvivesArrivalWithNoPlayer(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] wantedRef = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int centreX = (int) Math.round(origin.getX() / 8.0);
                    int centreZ = (int) Math.round(origin.getZ() / 8.0);
                    prepareNetherZone(dstLevel, centreX, centreZ);

                    Vec3 wanted = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 20, centreZ + 0.5);
                    wantedRef[0] = wanted;
                    AeroPortals.LOGGER.info("[AeroPortals/test] unattended: sub {} src pose {} -> requested nether {}",
                            uuidRef[0], sub.logicalPose().position(), wanted);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, wanted, false, "test-unattended");
                })
                .thenIdle(2)
                .thenExecute(() -> AeroPortals.LOGGER.info("[AeroPortals/test] unattended @t+2: src={} dst={}",
                        describe(srcContainer, uuidRef[0]), describe(dstContainer, uuidRef[0])))
                .thenExecute(() -> {
                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    if (arrived == null) { helper.fail("ship did not arrive in the nether at all"); return; }
                    Vec3 pose = new Vec3(arrived.logicalPose().position().x(),
                            arrived.logicalPose().position().y(), arrived.logicalPose().position().z());
                    AABB box = AabbUtil.worldAabb(arrived);
                    Vec3 centre = box.getCenter();
                    double poseDrift = pose.distanceTo(wantedRef[0]);
                    double blockDrift = centre.distanceTo(wantedRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] unattended arrival: pose={} (drift {}), blocks centred at {} (drift {})",
                            pose, String.format("%.2f", poseDrift), centre, String.format("%.2f", blockDrift));
                    if (blockDrift > 8.0) {
                        helper.fail("ship blocks landed " + String.format("%.1f", blockDrift)
                                + " blocks from the requested destination " + wantedRef[0] + " (blocks at " + centre + ")");
                    }
                })
                .thenIdle(30)
                .thenExecute(() -> AeroPortals.LOGGER.info("[AeroPortals/test] unattended @t+32: {}", describe(dstContainer, uuidRef[0])))
                .thenIdle(60)
                .thenExecute(() -> AeroPortals.LOGGER.info("[AeroPortals/test] unattended @t+92 (arrival ticket expired): {}",
                        describe(dstContainer, uuidRef[0])))
                .thenIdle(100)
                .thenExecute(() -> {
                    String state = describe(dstContainer, uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] unattended @t+192: {}", state);
                    boolean present = dstContainer.getSubLevel(uuidRef[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]) != null;
                    if (!present) {
                        helper.fail("ship vanished from the nether while unattended (no active sub-level, no holding entry)");
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "unattended_shipSurvivesOccupiedDestinationPlot", template = EMPTY, timeoutTicks = 600)
    public static void unattended_shipSurvivesOccupiedDestinationPlot(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] wantedRef = new Vec3[1];
        int[] blockedIndexRef = new int[]{-1};

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int localPlotX = sub.getPlot().plotPos.x - srcContainer.getOrigin().x;
                    int localPlotZ = sub.getPlot().plotPos.z - srcContainer.getOrigin().y;
                    int index = dstContainer.getIndex(localPlotX, localPlotZ);
                    blockedIndexRef[0] = index;
                    dstContainer.getOccupancy().set(index);
                    AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: src ship uses local plot {},{}; marked that plot taken in the nether",
                            localPlotX, localPlotZ);

                    int centreX = (int) Math.round(origin.getX() / 8.0) - 40;
                    int centreZ = (int) Math.round(origin.getZ() / 8.0) - 40;
                    prepareNetherZone(dstLevel, centreX, centreZ);
                    Vec3 wanted = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 20, centreZ + 0.5);
                    wantedRef[0] = wanted;

                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, wanted, false, "test-occupied-plot");

                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    if (arrived == null) {
                        AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: no active sub in nether right after the move");
                        return;
                    }
                    AABB box = AabbUtil.worldAabb(arrived);
                    AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: same-tick arrival pose={} aabb centre={} (requested {}), drift={}",
                            arrived.logicalPose().position(), box.getCenter(), wanted,
                            String.format("%.2f", box.getCenter().distanceTo(wanted)));

                    Vec3 centre = box.getCenter();
                    int phantomCx = ((int) Math.floor(centre.x)) >> 4;
                    int phantomCz = ((int) Math.floor(centre.z)) >> 4;
                    int realCx = ((int) Math.floor(wanted.x)) >> 4;
                    int realCz = ((int) Math.floor(wanted.z)) >> 4;
                    AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: chunk loaded-enough? bbox chunk [{},{}]={} | landing chunk [{},{}]={}",
                            phantomCx, phantomCz, PhysicsChunkTicketManager.isChunkLoadedEnough(dstLevel, phantomCx, phantomCz),
                            realCx, realCz, PhysicsChunkTicketManager.isChunkLoadedEnough(dstLevel, realCx, realCz));

                    dstContainer.physicsSystem().tick(dstContainer);
                    AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: after one forced physics tick -> {}",
                            describe(dstContainer, uuidRef[0]));
                })
                .thenIdle(1)
                .thenExecute(() -> AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot @t+1: {}", describe(dstContainer, uuidRef[0])))
                .thenIdle(5)
                .thenExecute(() -> {
                    String state = describe(dstContainer, uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot @t+6: {}", state);
                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    if (arrived != null) {
                        Vec3 centre = AabbUtil.worldAabb(arrived).getCenter();
                        double drift = centre.distanceTo(wantedRef[0]);
                        AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: blocks at {} drift={} from requested {}",
                                centre, String.format("%.2f", drift), wantedRef[0]);
                        if (drift > 8.0) {
                            helper.fail("relocated plot put the ship " + String.format("%.0f", drift) + " blocks off target");
                            return;
                        }
                    }
                    HoldingSubLevel holding = dstContainer.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]);
                    if (arrived == null && holding == null) {
                        helper.fail("ship vanished after arriving into a relocated plot");
                        return;
                    }
                    if (holding != null) {
                        Vec3 centre = new Vec3(
                                (holding.data().bounds().minX() + holding.data().bounds().maxX()) / 2.0,
                                (holding.data().bounds().minY() + holding.data().bounds().maxY()) / 2.0,
                                (holding.data().bounds().minZ() + holding.data().bounds().maxZ()) / 2.0);
                        double drift = centre.distanceTo(wantedRef[0]);
                        AeroPortals.LOGGER.info("[AeroPortals/test] occupied-plot: held at {} drift={} from requested {}",
                                centre, String.format("%.2f", drift), wantedRef[0]);
                        if (drift > 8.0) {
                            helper.fail("ship was stored " + String.format("%.0f", drift)
                                    + " blocks away from where it arrived; nobody will ever load that chunk");
                        }
                    }
                })
                .thenExecute(() -> {
                    if (blockedIndexRef[0] >= 0) dstContainer.getOccupancy().clear(blockedIndexRef[0]);
                })
                .thenSucceed();
    }

    @GameTest(batch = "unattended_realPortalWithOccupiedPlot", template = EMPTY, timeoutTicks = 600)
    public static void unattended_realPortalWithOccupiedPlot(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] scaledRef = new Vec3[1];
        int[] blockedIndexRef = new int[]{-1};

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int localPlotX = sub.getPlot().plotPos.x - srcContainer.getOrigin().x;
                    int localPlotZ = sub.getPlot().plotPos.z - srcContainer.getOrigin().y;
                    blockedIndexRef[0] = dstContainer.getIndex(localPlotX, localPlotZ);
                    dstContainer.getOccupancy().set(blockedIndexRef[0]);

                    scaledRef[0] = new Vec3(worldPos.getX() / 8.0, worldPos.getY(), worldPos.getZ() / 8.0);
                    AeroPortals.LOGGER.info("[AeroPortals/test] real-portal: ship at plot {},{} (marked taken in nether); scaled landing ~{}",
                            localPlotX, localPlotZ, scaledRef[0]);

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(15)
                .thenExecute(() -> {
                    boolean inSrc = srcContainer.getSubLevel(uuidRef[0]) != null;
                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    HoldingSubLevel holding = dstContainer.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] real-portal: inSrc={} dst={}", inSrc, describe(dstContainer, uuidRef[0]));

                    if (blockedIndexRef[0] >= 0) dstContainer.getOccupancy().clear(blockedIndexRef[0]);
                    if (inSrc) return;

                    Vec3 where = null;
                    if (arrived != null) {
                        where = AabbUtil.worldAabb(arrived).getCenter();
                    } else if (holding != null) {
                        where = new Vec3(
                                (holding.data().bounds().minX() + holding.data().bounds().maxX()) / 2.0,
                                (holding.data().bounds().minY() + holding.data().bounds().maxY()) / 2.0,
                                (holding.data().bounds().minZ() + holding.data().bounds().maxZ()) / 2.0);
                    }
                    if (where == null) {
                        helper.fail("ship left the overworld and is in neither the nether's live list nor its holding map");
                        return;
                    }
                    double horizontal = Math.hypot(where.x - scaledRef[0].x, where.z - scaledRef[0].z);
                    AeroPortals.LOGGER.info("[AeroPortals/test] real-portal: ship recorded at {} which is {} blocks (horizontal) from the scaled portal point {}",
                            where, String.format("%.1f", horizontal), scaledRef[0]);
                    if (horizontal > 256.0) {
                        helper.fail("ship was recorded " + String.format("%.0f", horizontal)
                                + " blocks from the portal it came out of; players will never load that chunk");
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "unattended_heldShipComesBackWhenChunksLoad", template = EMPTY, timeoutTicks = 900)
    public static void unattended_heldShipComesBackWhenChunksLoad(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] wantedRef = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int centreX = (int) Math.round(origin.getX() / 8.0) + 80;
                    int centreZ = (int) Math.round(origin.getZ() / 8.0) + 80;
                    prepareNetherZone(dstLevel, centreX, centreZ);
                    Vec3 wanted = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 20, centreZ + 0.5);
                    wantedRef[0] = wanted;
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, wanted, false, "test-comeback");
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    AeroPortals.LOGGER.info("[AeroPortals/test] comeback: after 120 ticks unattended -> {}",
                            describe(dstContainer, uuidRef[0]));
                    HoldingSubLevel held = dstContainer.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]);
                    ChunkPos chunkPos = held != null ? holdingChunk(held) : new ChunkPos(BlockPos.containing(wantedRef[0]));
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            ChunkPos near = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
                            dstLevel.getChunkSource().addRegionTicket(TicketType.FORCED, near, 2, near);
                            dstLevel.getChunk(near.x, near.z);
                        }
                    }
                    AeroPortals.LOGGER.info("[AeroPortals/test] comeback: force-loaded 5x5 chunks around {} to stand in for a player arriving", chunkPos);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    String state = describe(dstContainer, uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] comeback: 40 ticks after the chunks came back -> {}", state);
                    if (dstContainer.getSubLevel(uuidRef[0]) == null) {
                        helper.fail("ship did not come back when its chunks were loaded again; state was " + state);
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "unattended_highAltitudeShipThroughRealPortal", template = EMPTY, timeoutTicks = 900)
    public static void unattended_highAltitudeShipThroughRealPortal(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] scaledRef = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos base = helper.absolutePos(new BlockPos(7, 4, 7));
                    BlockPos anchor = new BlockPos(base.getX(), 200, base.getZ());
                    List<BlockPos> shipBlocks = new ArrayList<>();
                    for (int x = 0; x < 5; x++) {
                        for (int y = 0; y < 4; y++) {
                            for (int z = 0; z < 5; z++) {
                                BlockPos pos = anchor.offset(x, y, z);
                                srcLevel.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3);
                                shipBlocks.add(pos);
                            }
                        }
                    }
                    BoundingBox3i bounds = new BoundingBox3i(
                            anchor.getX() - 1, anchor.getY() - 1, anchor.getZ() - 1,
                            anchor.getX() + 6, anchor.getY() + 5, anchor.getZ() + 6);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, anchor, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();
                    scaledRef[0] = new Vec3(anchor.getX() / 8.0, anchor.getY(), anchor.getZ() / 8.0);
                    AeroPortals.LOGGER.info("[AeroPortals/test] high-altitude: ship at {} (plot bounds {}), portal about to be lit",
                            anchor, sub.getPlot().getBoundingBox());
                    PortalBuilder.build(srcLevel, anchor, Direction.Axis.X, 2, 3);
                })
                .thenIdle(15)
                .thenExecute(() -> {
                    boolean inSrc = srcContainer.getSubLevel(uuidRef[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] high-altitude @t+15: inSrc={} dst={}",
                            inSrc, describe(dstContainer, uuidRef[0]));
                    if (inSrc) {
                        AeroPortals.LOGGER.info("[AeroPortals/test] high-altitude: teleport did not happen (ship stayed in the overworld)");
                    }
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    boolean inSrc = srcContainer.getSubLevel(uuidRef[0]) != null;
                    String state = describe(dstContainer, uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] high-altitude @t+135: inSrc={} dst={}", inSrc, state);
                    if (inSrc) return;
                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    HoldingSubLevel holding = dstContainer.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]);
                    if (arrived == null && holding == null) {
                        helper.fail("high-altitude ship left the overworld and is nowhere in the nether");
                        return;
                    }
                    Vec3 where = arrived != null
                            ? AabbUtil.worldAabb(arrived).getCenter()
                            : new Vec3((holding.data().bounds().minX() + holding.data().bounds().maxX()) / 2.0,
                                    (holding.data().bounds().minY() + holding.data().bounds().maxY()) / 2.0,
                                    (holding.data().bounds().minZ() + holding.data().bounds().maxZ()) / 2.0);
                    double horizontal = Math.hypot(where.x - scaledRef[0].x, where.z - scaledRef[0].z);
                    AeroPortals.LOGGER.info("[AeroPortals/test] high-altitude: ship recorded at {} ({} blocks horizontally from the scaled portal point {})",
                            where, String.format("%.1f", horizontal), scaledRef[0]);
                    if (horizontal > 256.0) {
                        helper.fail("high-altitude ship recorded " + String.format("%.0f", horizontal) + " blocks from its portal");
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "voidFall_shipBelowRemovalLimitIsDestroyed", template = EMPTY, timeoutTicks = 600)
    public static void voidFall_shipBelowRemovalLimitIsDestroyed(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(srcLevel);
        if (container == null) { helper.fail("container"); return; }

        UUID[] uuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    Vector3d far = new Vector3d(sub.logicalPose().position().x(), -10_050.0, sub.logicalPose().position().z());
                    container.physicsSystem().getPipeline().teleport(sub, far, sub.logicalPose().orientation());
                    AeroPortals.LOGGER.info("[AeroPortals/test] void-fall: dropped sub {} to y={} (Sable removal floor is -10000)",
                            uuidRef[0], far.y);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    ServerSubLevel still = (ServerSubLevel) container.getSubLevel(uuidRef[0]);
                    HoldingSubLevel holding = container.getHoldingChunkMap().getHoldingSubLevel(uuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] void-fall: after 10 ticks active={} holding={}", still, holding);
                    if (still == null && holding == null) {
                        AeroPortals.LOGGER.info("[AeroPortals/test] void-fall: CONFIRMED - the ship is gone from the level with no holding entry and no drops");
                    } else {
                        AeroPortals.LOGGER.info("[AeroPortals/test] void-fall: ship survived the drop; removal floor did not fire");
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "voidGuard_catchesFallingShip", template = EMPTY, timeoutTicks = 600)
    public static void voidGuard_catchesFallingShip(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel level = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) { helper.fail("container"); return; }

        UUID[] uuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    double belowFloor = level.getMinBuildHeight() - 120.0;
                    container.physicsSystem().getPipeline().teleport(sub,
                            new Vector3d(sub.logicalPose().position().x(), belowFloor, sub.logicalPose().position().z()),
                            sub.logicalPose().orientation());
                    AeroPortals.LOGGER.info("[AeroPortals/test] void-guard: dropped sub {} to y={}", uuidRef[0], belowFloor);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(uuidRef[0]);
                    if (sub == null) {
                        helper.fail("void guard did not catch the ship; it is gone from the level");
                        return;
                    }
                    double y = sub.logicalPose().position().y();
                    AeroPortals.LOGGER.info("[AeroPortals/test] void-guard: ship is at y={} (world floor {})",
                            y, level.getMinBuildHeight());
                    if (y < level.getMinBuildHeight()) {
                        helper.fail("void guard left the ship below the world floor at y=" + y);
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "recovery_bringsBackAStoredShip", template = EMPTY, timeoutTicks = 900)
    public static void recovery_bringsBackAStoredShip(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] targetRef = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int centreX = (int) Math.round(origin.getX() / 8.0) + 120;
                    int centreZ = (int) Math.round(origin.getZ() / 8.0) + 120;
                    prepareNetherZone(dstLevel, centreX, centreZ);
                    Vec3 wanted = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 20, centreZ + 0.5);
                    targetRef[0] = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 12, centreZ + 40.5);
                    prepareNetherZone(dstLevel, centreX, centreZ + 40);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, wanted, false, "test-recovery");
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    List<ShipDirectory.Entry> entries = ShipDirectory.list(dstLevel);
                    ShipDirectory.Entry mine = null;
                    for (ShipDirectory.Entry entry : entries) {
                        if (entry.uuid().equals(uuidRef[0])) mine = entry;
                    }
                    AeroPortals.LOGGER.info("[AeroPortals/test] recovery: directory lists {} ship(s) in the nether; mine={}",
                            entries.size(), mine);
                    if (mine == null) {
                        helper.fail("the ship directory did not list the ship that went through the portal");
                        return;
                    }
                    boolean started = ShipRecovery.start(dstLevel, uuidRef[0], targetRef[0],
                            component -> AeroPortals.LOGGER.info("[AeroPortals/test] recovery says: {}", component.getString()));
                    if (!started) helper.fail("recovery could not be started for a listed ship");
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    ServerSubLevel sub = (ServerSubLevel) dstContainer.getSubLevel(uuidRef[0]);
                    if (sub == null) {
                        helper.fail("ship was not recovered; state is " + describe(dstContainer, uuidRef[0]));
                        return;
                    }
                    Vec3 centre = AabbUtil.worldAabb(sub).getCenter();
                    double distance = Math.hypot(centre.x - targetRef[0].x, centre.z - targetRef[0].z);
                    AeroPortals.LOGGER.info("[AeroPortals/test] recovery: ship now at {} ({} blocks from the requested spot)",
                            centre, String.format("%.1f", distance));
                    if (distance > 16.0) {
                        helper.fail("recovered ship is " + String.format("%.0f", distance) + " blocks from where it was called to");
                    }
                })
                .thenSucceed();
    }

    @GameTest(batch = "unattended_shipPersistsThroughSave", template = EMPTY, timeoutTicks = 600)
    public static void unattended_shipPersistsThroughSave(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] uuidRef = new UUID[1];
        Vec3[] wantedRef = new Vec3[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localMin = new BlockPos(6, 3, 6);
                    List<BlockPos> shipBlocks = buildCube(helper, localMin, 3);
                    BlockPos origin = shipBlocks.get(0);
                    BoundingBox3i bounds = new BoundingBox3i(
                            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                            origin.getX() + 3, origin.getY() + 3, origin.getZ() + 3);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(srcLevel, origin, shipBlocks, bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    uuidRef[0] = sub.getUniqueId();

                    int centreX = (int) Math.round(origin.getX() / 8.0) + 40;
                    int centreZ = (int) Math.round(origin.getZ() / 8.0) + 40;
                    prepareNetherZone(dstLevel, centreX, centreZ);
                    Vec3 wanted = new Vec3(centreX + 0.5, dstLevel.getMinBuildHeight() + 20, centreZ + 0.5);
                    wantedRef[0] = wanted;
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, wanted, false, "test-persist");
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    SubLevelHoldingChunkMap map = dstContainer.getHoldingChunkMap();
                    HoldingSubLevel before = map.getHoldingSubLevel(uuidRef[0]);
                    ChunkPos filedIn = before != null ? holdingChunk(before) : new ChunkPos(BlockPos.containing(wantedRef[0]));
                    AeroPortals.LOGGER.info("[AeroPortals/test] persist: before save state={}", describe(dstContainer, uuidRef[0]));
                    map.saveAll();

                    int pointers = 0;
                    ChunkPos found = null;
                    for (int dx = -3; dx <= 3 && found == null; dx++) {
                        for (int dz = -3; dz <= 3 && found == null; dz++) {
                            ChunkPos near = new ChunkPos(filedIn.x + dx, filedIn.z + dz);
                            SubLevelHoldingChunk onDisk = map.getStorage().attemptLoadHoldingChunk(near);
                            if (onDisk != null && !onDisk.getSubLevelPointers().isEmpty()) {
                                found = near;
                                pointers = onDisk.getSubLevelPointers().size();
                            }
                        }
                    }
                    ChunkPos chunkPos = found != null ? found : filedIn;
                    AeroPortals.LOGGER.info("[AeroPortals/test] persist: stored ship data found on disk at chunk {} -> {} pointer(s) (searched around {})",
                            found, pointers, filedIn);

                    boolean present = dstContainer.getSubLevel(uuidRef[0]) != null
                            || map.getHoldingSubLevel(uuidRef[0]) != null;
                    if (!present && pointers <= 0) {
                        helper.fail("after save the ship is neither loaded, held in memory, nor stored on disk at " + chunkPos);
                        return;
                    }

                    boolean listed = false;
                    for (ShipDirectory.Entry entry : ShipDirectory.list(dstLevel)) {
                        if (entry.uuid().equals(uuidRef[0])) {
                            listed = true;
                            AeroPortals.LOGGER.info("[AeroPortals/test] persist: directory found the ship as {} at {}",
                                    entry.state(), entry.position());
                        }
                    }
                    if (!listed) {
                        helper.fail("the ship directory could not find the ship after it was written to the save");
                    }
                })
                .thenSucceed();
    }
}
