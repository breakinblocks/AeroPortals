package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import com.breakinblocks.aeroportals.commands.AeroPortalsCommands;
import com.breakinblocks.aeroportals.events.VanillaPortalCanceller;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.ArsNouveauCompat;
import com.breakinblocks.aeroportals.compat.DraconicEvolutionCompat;
import com.breakinblocks.aeroportals.compat.TelepastriesCompat;
import com.breakinblocks.aeroportals.compat.TropicraftCompat;
import com.breakinblocks.aeroportals.portal.EndPortalLanding;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import com.breakinblocks.aeroportals.portal.PortalDetector;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import org.joml.Vector3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.breakinblocks.aeroportals.portal.SableBridge;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class PortalGameTests {

    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public static void portalRect_centerWorld_xAxis2x3(GameTestHelper helper) {
        PortalRect rect = new PortalRect(new BlockPos(10, 20, 30), Direction.Axis.X, 2, 3);
        Vec3 center = rect.centerWorld();
        helper.assertValueEqual(11.0, center.x, "center.x");
        helper.assertValueEqual(21.5, center.y, "center.y");
        helper.assertValueEqual(30.5, center.z, "center.z");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalRect_centerWorld_zAxis2x3(GameTestHelper helper) {
        PortalRect rect = new PortalRect(new BlockPos(10, 20, 30), Direction.Axis.Z, 2, 3);
        Vec3 center = rect.centerWorld();
        helper.assertValueEqual(10.5, center.x, "center.x");
        helper.assertValueEqual(21.5, center.y, "center.y");
        helper.assertValueEqual(31.0, center.z, "center.z");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalRect_centerWorld_1x1(GameTestHelper helper) {
        PortalRect rect = new PortalRect(new BlockPos(0, 0, 0), Direction.Axis.X, 1, 1);
        Vec3 center = rect.centerWorld();
        helper.assertValueEqual(0.5, center.x, "center.x");
        helper.assertValueEqual(0.5, center.y, "center.y");
        helper.assertValueEqual(0.5, center.z, "center.z");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalBuilder_xAxis_placesObsidianFrame(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 8);
        PortalBuilder.build(helper.getLevel(), helper.absolutePos(local), Direction.Axis.X, 2, 3);

        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(-1, -1, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(2, -1, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(-1, 3, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(2, 3, 0));

        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(-1, 1, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(2, 1, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(0, -1, 0));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(1, 3, 0));

        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalBuilder_xAxis_placesPortalBlocksWithAxis(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 8);
        PortalBuilder.build(helper.getLevel(), helper.absolutePos(local), Direction.Axis.X, 2, 3);

        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(0, 0, 0));
        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(1, 0, 0));
        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(0, 2, 0));
        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(1, 2, 0));

        helper.assertBlockProperty(local.offset(0, 0, 0), NetherPortalBlock.AXIS, Direction.Axis.X);
        helper.assertBlockProperty(local.offset(1, 1, 0), NetherPortalBlock.AXIS, Direction.Axis.X);

        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalBuilder_zAxis_placesFrameAlongZ(GameTestHelper helper) {
        BlockPos local = new BlockPos(8, 2, 2);
        PortalBuilder.build(helper.getLevel(), helper.absolutePos(local), Direction.Axis.Z, 2, 3);

        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(0, -1, -1));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(0, -1, 2));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(0, 3, -1));
        helper.assertBlockPresent(Blocks.OBSIDIAN, local.offset(0, 3, 2));

        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(0, 0, 0));
        helper.assertBlockPresent(Blocks.NETHER_PORTAL, local.offset(0, 0, 1));
        helper.assertBlockProperty(local.offset(0, 0, 0), NetherPortalBlock.AXIS, Direction.Axis.Z);

        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalBuilder_clearsExistingBlocksInArea(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 8);

        helper.setBlock(local.offset(0, 0, -1), Blocks.STONE.defaultBlockState());
        helper.setBlock(local.offset(0, 0, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(local.offset(1, 1, -1), Blocks.STONE.defaultBlockState());

        PortalBuilder.build(helper.getLevel(), helper.absolutePos(local), Direction.Axis.X, 2, 3);

        helper.assertBlockPresent(Blocks.AIR, local.offset(0, 0, -1));
        helper.assertBlockPresent(Blocks.AIR, local.offset(0, 0, 1));
        helper.assertBlockPresent(Blocks.AIR, local.offset(1, 1, -1));

        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalGeom_measureFromBlock_xAxis_3x4(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 8);
        BlockPos world = helper.absolutePos(local);
        PortalBuilder.build(helper.getLevel(), world, Direction.Axis.X, 3, 4);

        PortalRect rect = PortalGeom.measureFromBlock(helper.getLevel(), world);
        helper.assertTrue(rect != null, "measureFromBlock should not return null");
        helper.assertTrue(rect.axis() == Direction.Axis.X, "axis should be X");
        helper.assertValueEqual(3, rect.width(), "width");
        helper.assertValueEqual(4, rect.height(), "height");

        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void portalGeom_measureFromBlock_zAxis_2x3(GameTestHelper helper) {
        BlockPos local = new BlockPos(8, 2, 2);
        BlockPos world = helper.absolutePos(local);
        PortalBuilder.build(helper.getLevel(), world, Direction.Axis.Z, 2, 3);

        PortalRect rect = PortalGeom.measureFromBlock(helper.getLevel(), world);
        helper.assertTrue(rect != null, "measureFromBlock should not return null");
        helper.assertTrue(rect.axis() == Direction.Axis.Z, "axis should be Z");
        helper.assertValueEqual(2, rect.width(), "width");
        helper.assertValueEqual(3, rect.height(), "height");

        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_subLevelInPortal_teleportsToNether(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        MinecraftServer server = srcLevel.getServer();
        ServerLevel dstLevel = server.getLevel(Level.NETHER);
        if (dstLevel == null) {
            helper.fail("Nether dimension must be loaded for cross-dim test");
            return;
        }

        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) {
            helper.fail("SubLevel containers must exist for both dimensions");
            return;
        }

        int dstSubsBefore = dstContainer.getAllSubLevels().size();
        UUID[] subUuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

                    BoundingBox3i assembleBounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1
                    );
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), assembleBounds
                    );
                    if (sub == null) {
                        helper.fail("SubLevel was not assembled");
                        return;
                    }
                    subUuidRef[0] = sub.getUniqueId();

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    PortalDetector.scan(srcLevel);
                })
                .thenExecute(() -> {
                    UUID subUuid = subUuidRef[0];
                    ServerSubLevelContainer dstContainerNow = SubLevelContainer.getContainer(dstLevel);
                    if (dstContainerNow == null) {
                        helper.fail("dstContainer null at verify time");
                        return;
                    }

                    SubLevel srcLookup = srcContainer.getSubLevel(subUuid);
                    SubLevel dstLookup = dstContainerNow.getSubLevel(subUuid);
                    HoldingSubLevel dstHolding = dstContainerNow.getHoldingChunkMap().getHoldingSubLevel(subUuid);

                    AeroPortals.LOGGER.info(
                            "[AeroPortals/test] verify: srcLookup={}, dstLookup={}, dstHolding={}, dstAll.size={}",
                            srcLookup, dstLookup, dstHolding,
                            dstContainerNow.getAllSubLevels().size());

                    if (srcLookup != null) {
                        helper.fail("SubLevel should be removed from source (UUID lookup returned non-null)");
                        return;
                    }

                    if (dstLookup == null && dstHolding == null) {
                        helper.fail("SubLevel UUID should exist in destination dim (active or holding); neither found");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_destinationPortalMatchesSourceDimensions(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        MinecraftServer server = srcLevel.getServer();
        ServerLevel dstLevel = server.getLevel(Level.NETHER);
        if (dstLevel == null) {
            helper.fail("Nether dimension must be loaded");
            return;
        }

        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) {
            helper.fail("SubLevel containers must exist for both dimensions");
            return;
        }

        final int sourceWidth = 4;
        final int sourceHeight = 5;
        UUID[] subUuidRef = new UUID[1];
        BlockPos[] srcPortalMinRef = new BlockPos[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    srcPortalMinRef[0] = worldPos;
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

                    BoundingBox3i assembleBounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1
                    );
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), assembleBounds
                    );
                    if (sub == null) {
                        helper.fail("SubLevel was not assembled");
                        return;
                    }
                    subUuidRef[0] = sub.getUniqueId();

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, sourceWidth, sourceHeight);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    BlockPos srcPortalMin = srcPortalMinRef[0];
                    double ratio = 1.0 / 8.0;
                    int searchX = (int) Math.round((srcPortalMin.getX() + sourceWidth / 2.0) * ratio);
                    int searchZ = (int) Math.round((srcPortalMin.getZ() + 0.5) * ratio);
                    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                    PortalRect matchingRect = null;
                    Set<BlockPos> measuredMinCorners = new HashSet<>();
                    PortalRect lastSeen = null;
                    for (int dx = -15; dx <= 15 && matchingRect == null; dx++) {
                        for (int y = 0; y < 128 && matchingRect == null; y++) {
                            for (int dz = -15; dz <= 15 && matchingRect == null; dz++) {
                                cursor.set(searchX + dx, y, searchZ + dz);
                                if (!dstLevel.getBlockState(cursor).is(Blocks.NETHER_PORTAL)) continue;
                                PortalRect r = PortalGeom.measureFromBlock(dstLevel, cursor);
                                if (r == null || !measuredMinCorners.add(r.minCorner())) continue;
                                lastSeen = r;
                                AeroPortals.LOGGER.info("[AeroPortals/test] examined portal at {} (axis={} {}x{})",
                                        r.minCorner(), r.axis(), r.width(), r.height());
                                if (r.axis() == Direction.Axis.X
                                        && r.width() == sourceWidth
                                        && r.height() == sourceHeight) {
                                    matchingRect = r;
                                }
                            }
                        }
                    }

                    if (matchingRect == null) {
                        helper.fail("no destination portal matching source " + sourceWidth + "x" + sourceHeight
                                + " axis=X found within radius; lastSeen=" + lastSeen);
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void portalDetector_noPortalNearby_doesNotTeleport(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) {
            helper.fail("srcContainer null");
            return;
        }
        UUID[] subUuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1
                    );
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds
                    );
                    if (sub == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    subUuidRef[0] = sub.getUniqueId();
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    SubLevel stillThere = srcContainer.getSubLevel(subUuidRef[0]);
                    if (stillThere == null) {
                        helper.fail("SubLevel should still be in source dim (no portal nearby), but was removed");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void portalCooldown_marksAndExpiresAfterConfiguredTicks(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        PortalCooldown.clear();

        helper.assertTrue(!PortalCooldown.isOnCooldown(uuid, 100L),
                "fresh UUID should not be on cooldown");

        PortalCooldown.mark(uuid, 100L);
        helper.assertTrue(PortalCooldown.isOnCooldown(uuid, 100L),
                "should be on cooldown immediately after mark");
        helper.assertTrue(PortalCooldown.isOnCooldown(uuid, 199L),
                "should still be on cooldown 99 ticks later (default 200)");
        helper.assertTrue(!PortalCooldown.isOnCooldown(uuid, 100L + 200L),
                "should NOT be on cooldown 200 ticks later (>= threshold)");
        helper.assertTrue(!PortalCooldown.isOnCooldown(uuid, 100L + 300L),
                "should NOT be on cooldown well past threshold");

        UUID otherUuid = UUID.randomUUID();
        helper.assertTrue(!PortalCooldown.isOnCooldown(otherUuid, 100L),
                "different UUID should not be on cooldown");

        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_armorStandRider_transfersToNether(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        UUID[] standUuidRef = new UUID[1];
        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    ArmorStand stand = new ArmorStand(srcLevel, worldPos.getX() + 0.5, worldPos.getY() + 1.0, worldPos.getZ() + 0.5);
                    stand.setItemSlot(EquipmentSlot.HEAD,
                            new ItemStack(Items.DIAMOND_HELMET));
                    srcLevel.addFreshEntity(stand);
                    standUuidRef[0] = stand.getUUID();

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    Entity inSrc = srcLevel.getEntity(standUuidRef[0]);
                    Entity inDst = dstLevel.getEntity(standUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] armor stand: src={}, dst={}", inSrc, inDst);

                    if (inSrc != null) {
                        helper.fail("armor stand should be removed from src dim, but still present");
                        return;
                    }
                    if (inDst == null) {
                        helper.fail("armor stand should exist in dst dim, but not found");
                        return;
                    }
                    if (!(inDst instanceof ArmorStand standDst)) {
                        helper.fail("entity in dst is not an ArmorStand: " + inDst.getType());
                        return;
                    }
                    ItemStack helm = standDst.getItemBySlot(EquipmentSlot.HEAD);
                    if (!helm.is(Items.DIAMOND_HELMET)) {
                        helper.fail("armor stand's helmet was not preserved: " + helm);
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_cowOnSubLevel_doesNotTransfer(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        UUID[] cowUuidRef = new UUID[1];

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

                    Entity cow = EntityType.COW.create(srcLevel);
                    if (cow == null) { helper.fail("could not create cow"); return; }
                    cow.setPos(worldPos.getX() + 0.5, worldPos.getY() + 1.0, worldPos.getZ() + 0.5);
                    srcLevel.addFreshEntity(cow);
                    cowUuidRef[0] = cow.getUUID();

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    Entity moved = PortalTeleport.lastMovedEntities.get(cowUuidRef[0]);
                    boolean cancelled = VanillaPortalCanceller.cancelledFor.contains(cowUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] cow: moved-by-us={} cancelled-by-us={}", moved, cancelled);

                    if (moved != null) {
                        helper.fail("our system transferred a cow (not in retain tag), but should have ignored it: " + moved);
                        return;
                    }
                    if (cancelled) {
                        helper.fail("VanillaPortalCanceller fired for cow (should only fire for retain-tag entities)");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void vanillaPortalRaceSuppressed_cancelHandlerFires(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        UUID[] standUuidRef = new UUID[1];

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

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);

                    ArmorStand stand = new ArmorStand(srcLevel,
                            worldPos.getX() + 0.5, worldPos.getY() + 1.0, worldPos.getZ() + 0.5);
                    srcLevel.addFreshEntity(stand);
                    standUuidRef[0] = stand.getUUID();
                    VanillaPortalCanceller.cancelledFor.remove(standUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] spawned armor stand uuid={} (no portalCooldown override)",
                            standUuidRef[0]);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    boolean wasCancelled = VanillaPortalCanceller.cancelledFor.contains(standUuidRef[0]);
                    Entity moved = PortalTeleport.lastMovedEntities.get(standUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] race-suppress: wasCancelled={} moved={}",
                            wasCancelled, moved);

                    if (!wasCancelled) {
                        helper.fail("VanillaPortalCanceller did not record a cancel for this entity - vanilla likely portaled it before we could intervene");
                        return;
                    }
                    if (moved == null) {
                        helper.fail("after cancel, our scan/replay did not transfer the armor stand");
                        return;
                    }
                    if (moved.level() != dstLevel) {
                        helper.fail("transferred armor stand's level is not dst: " + moved.level());
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void deferredClientSync_retriesFireAfterTeleport(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }

        int[] startFireCountRef = new int[1];

        helper.startSequence()
                .thenExecute(() -> {
                    startFireCountRef[0] = PortalTeleport.DeferredClientSyncs.fireCount.get();

                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenIdle(35)
                .thenExecute(() -> {
                    int fired = PortalTeleport.DeferredClientSyncs.fireCount.get() - startFireCountRef[0];
                    AeroPortals.LOGGER.info("[AeroPortals/test] deferred syncs fired this test: {}", fired);
                    if (fired < 2) {
                        helper.fail("expected at least 2 deferred forceClientSync retries (10t and 30t after teleport), got " + fired);
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_dependencyChain_movesTogether(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] aUuid = new UUID[1];
        UUID[] bUuid = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos localA = new BlockPos(5, 4, 7);
                    BlockPos worldA = helper.absolutePos(localA);
                    BlockPos localB = new BlockPos(9, 4, 7);
                    BlockPos worldB = helper.absolutePos(localB);

                    helper.setBlock(localA, Blocks.OBSIDIAN.defaultBlockState());
                    helper.setBlock(localB, Blocks.OBSIDIAN.defaultBlockState());

                    BoundingBox3i boundsA = new BoundingBox3i(
                            worldA.getX() - 1, worldA.getY() - 1, worldA.getZ() - 1,
                            worldA.getX() + 1, worldA.getY() + 1, worldA.getZ() + 1);
                    BoundingBox3i boundsB = new BoundingBox3i(
                            worldB.getX() - 1, worldB.getY() - 1, worldB.getZ() - 1,
                            worldB.getX() + 1, worldB.getY() + 1, worldB.getZ() + 1);

                    ServerSubLevel subA = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldA, List.of(worldA), boundsA);
                    ServerSubLevel subB = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldB, List.of(worldB), boundsB);
                    if (subA == null || subB == null) { helper.fail("assemble failed"); return; }
                    aUuid[0] = subA.getUniqueId();
                    bUuid[0] = subB.getUniqueId();

                    srcContainer.physicsSystem().getPipeline().teleport(subB,
                            new Vector3d(worldA.getX() + 0.5, worldA.getY() + 0.5, worldA.getZ() + 0.5),
                            subB.logicalPose().orientation());

                    PortalBuilder.build(srcLevel, worldA, Direction.Axis.X, 2, 3);
                    AeroPortals.LOGGER.info("[AeroPortals/test] chain setup: subA={} subB={} (B teleported onto A)",
                            aUuid[0], bUuid[0]);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    ServerSubLevel subA = (ServerSubLevel) srcContainer.getSubLevel(aUuid[0]);
                    if (subA == null) {
                        AeroPortals.LOGGER.info("[AeroPortals/test] chain check: subA already gone from src; chain probably ran already");
                    } else {
                        Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(subA);
                        List<UUID> chainUuids = chain.stream().map(SubLevel::getUniqueId).toList();
                        AeroPortals.LOGGER.info("[AeroPortals/test] chain enumeration for subA: size={} uuids={}",
                                chain.size(), chainUuids);
                        if (chain.size() < 2) {
                            helper.fail("expected chain size >= 2 for overlapping SubLevels, got " + chain.size());
                            return;
                        }
                    }
                    PortalDetector.scan(srcLevel);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    boolean aInSrc = srcContainer.getSubLevel(aUuid[0]) != null;
                    boolean bInSrc = srcContainer.getSubLevel(bUuid[0]) != null;
                    boolean aInDst = dstContainer.getSubLevel(aUuid[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(aUuid[0]) != null;
                    boolean bInDst = dstContainer.getSubLevel(bUuid[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(bUuid[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] chain verify: A src={} dst={} | B src={} dst={}",
                            aInSrc, aInDst, bInSrc, bInDst);

                    if (aInSrc) { helper.fail("sub A still in src dim after scan"); return; }
                    if (bInSrc) { helper.fail("sub B still in src dim (chain was not followed)"); return; }
                    if (!aInDst) { helper.fail("sub A did not arrive in dst dim"); return; }
                    if (!bInDst) { helper.fail("sub B did not arrive in dst dim (chain follow failed)"); return; }
                })
                .thenSucceed();
    }

    public static final class TransferEventRecorder {
        
        public record Captured(UUID subUuid, Vec3 translation, ServerLevel src, ServerLevel dst, SubLevelTransferEvent event) {}
        public static final ConcurrentHashMap<UUID, Captured> captured =
                new ConcurrentHashMap<>();
        public static final AtomicInteger count = new AtomicInteger(0);

        public static void reset() {
            captured.clear();
            count.set(0);
        }

        @SubscribeEvent
        public static void onTransfer(SubLevelTransferEvent event) {
            count.incrementAndGet();
            captured.put(event.subUuid(), new Captured(event.subUuid(), event.translation(), event.srcLevel(), event.dstLevel(), event));
            AeroPortals.LOGGER.info("[AeroPortals/test] TransferEventRecorder captured: sub={} translation={} {}->{}",
                    event.subUuid(), event.translation(),
                    event.srcLevel().dimension().location(), event.dstLevel().dimension().location());
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void subLevelTransferEvent_firesWithCorrectTranslation(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        UUID[] subUuidRef = new UUID[1];
        Vec3[] srcPoseRef = new Vec3[1];

        TransferEventRecorder.reset();
        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

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
                    subUuidRef[0] = sub.getUniqueId();
                    org.joml.Vector3dc p = sub.logicalPose().position();
                    srcPoseRef[0] = new Vec3(p.x(), p.y(), p.z());

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    try {
                        TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(subUuidRef[0]);
                        if (captured == null) {
                            helper.fail("SubLevelTransferEvent did not fire for sub " + subUuidRef[0]
                                    + " (overall count=" + TransferEventRecorder.count.get() + ")");
                            return;
                        }
                        if (captured.src() != srcLevel) {
                            helper.fail("event srcLevel mismatch: " + captured.src());
                            return;
                        }
                        if (captured.dst() != dstLevel) {
                            helper.fail("event dstLevel mismatch: " + captured.dst());
                            return;
                        }
                        Vec3 translation = captured.translation();
                        if (translation == null) { helper.fail("translation null"); return; }
                        
                        
                        
                        if (Math.abs(srcPoseRef[0].x) > 0.01 && Math.abs(translation.x) < 0.001) {
                            helper.fail("expected non-zero x translation across dim, got " + translation);
                            return;
                        }
                    } finally {
                        NeoForge.EVENT_BUS.unregister(TransferEventRecorder.class);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void journal_successfulTeleportClearsEntry(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenExecute(() -> {
                    Path entry = TeleportJournal.pendingDir(srcLevel.getServer())
                            .resolve(subUuidRef[0] + ".nbt");
                    boolean exists = Files.exists(entry);
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal entry for sub {} exists post-teleport: {}",
                            subUuidRef[0], exists);
                    if (exists) {
                        helper.fail("journal entry should have been cleared after successful teleport, but " + entry + " still exists");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void journal_recoversLostSubLevelOnReplay(GameTestHelper helper) {
        
        
        
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }
        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    
                    SubLevelData data = SubLevelSerializer.toData(sub, List.of());

                    
                    TeleportJournal.write(
                            srcLevel.getServer(), data.uuid(),
                            srcLevel.dimension().location(), dstLevel.dimension().location(),
                            srcLevel.getMinBuildHeight(), data);

                    
                    srcContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal-recover setup: wrote entry, removed src; not loading dst");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    boolean inSrc = srcContainer.getSubLevel(subUuidRef[0]) != null;
                    boolean inDst = dstContainer.getSubLevel(subUuidRef[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(subUuidRef[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal-recover pre-replay: inSrc={} inDst={}", inSrc, inDst);
                    if (inSrc || inDst) {
                        helper.fail("setup invariant broken: sub should be absent from both sides before replay");
                        return;
                    }

                    TeleportJournal.replayPending(srcLevel.getServer());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    boolean inDstAfter = dstContainer.getSubLevel(subUuidRef[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(subUuidRef[0]) != null;
                    Path entry = TeleportJournal.pendingDir(srcLevel.getServer())
                            .resolve(subUuidRef[0] + ".nbt");
                    boolean entryExists = Files.exists(entry);
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal-recover post-replay: inDst={} entryExists={}",
                            inDstAfter, entryExists);

                    if (!inDstAfter) {
                        helper.fail("after replayPending, lost sub should be recovered into dst dim");
                        return;
                    }
                    if (entryExists) {
                        helper.fail("journal entry should be cleared after successful recovery");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void journal_replayClearsStaleEntryWhenSubAlreadyInSrc(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }
        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    SubLevelData data = SubLevelSerializer.toData(sub, List.of());
                    
                    TeleportJournal.write(
                            srcLevel.getServer(), data.uuid(),
                            srcLevel.dimension().location(), dstLevel.dimension().location(),
                            srcLevel.getMinBuildHeight(), data);
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal-stale-src setup: wrote entry, sub still in src");
                })
                .thenIdle(2)
                .thenExecute(() -> TeleportJournal.replayPending(srcLevel.getServer()))
                .thenIdle(2)
                .thenExecute(() -> {
                    boolean stillInSrc = srcContainer.getSubLevel(subUuidRef[0]) != null;
                    Path entry = TeleportJournal.pendingDir(srcLevel.getServer())
                            .resolve(subUuidRef[0] + ".nbt");
                    boolean entryExists = Files.exists(entry);
                    AeroPortals.LOGGER.info("[AeroPortals/test] journal-stale-src post-replay: stillInSrc={} entryExists={}",
                            stillInSrc, entryExists);

                    if (!stillInSrc) {
                        helper.fail("sub should remain in src (replay must not move it from src to dst)");
                        return;
                    }
                    if (entryExists) {
                        helper.fail("stale journal entry should be cleared");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endPortal_overworldToEnd_landsSubOnPlatform_buildingItIfMissing(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel endLevel = srcLevel.getServer().getLevel(Level.END);
        if (endLevel == null) { helper.fail("End not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer endContainer = SubLevelContainer.getContainer(endLevel);
        if (srcContainer == null || endContainer == null) { helper.fail("containers"); return; }

        
        for (int dx = -EndPortalLanding.PLATFORM_HALF; dx <= EndPortalLanding.PLATFORM_HALF; dx++) {
            for (int dz = -EndPortalLanding.PLATFORM_HALF; dz <= EndPortalLanding.PLATFORM_HALF; dz++) {
                endLevel.setBlock(EndPortalLanding.PLATFORM_CENTRE.offset(dx, 0, dz),
                        Blocks.AIR.defaultBlockState(), 3);
            }
        }

        UUID[] subUuidRef = new UUID[1];
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
                    subUuidRef[0] = sub.getUniqueId();

                    
                    helper.setBlock(local, Blocks.END_PORTAL.defaultBlockState());
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenIdle(2)
                .thenExecute(() -> {
                    UUID id = subUuidRef[0];
                    boolean inSrc = srcContainer.getSubLevel(id) != null;
                    ServerSubLevel inEnd = (ServerSubLevel) endContainer.getSubLevel(id);
                    boolean inEndHolding = endContainer.getHoldingChunkMap().getHoldingSubLevel(id) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] end-OW->End: inSrc={} inEnd={} inEndHolding={}",
                            inSrc, inEnd, inEndHolding);

                    if (inSrc) { helper.fail("sub should be removed from src dim"); return; }
                    if (inEnd == null && !inEndHolding) {
                        helper.fail("sub should be present in End dim (active or holding)");
                        return;
                    }

                    
                    int obsidianBlocks = 0;
                    for (int dx = -EndPortalLanding.PLATFORM_HALF; dx <= EndPortalLanding.PLATFORM_HALF; dx++) {
                        for (int dz = -EndPortalLanding.PLATFORM_HALF; dz <= EndPortalLanding.PLATFORM_HALF; dz++) {
                            if (endLevel.getBlockState(EndPortalLanding.PLATFORM_CENTRE.offset(dx, 0, dz))
                                    .is(Blocks.OBSIDIAN)) {
                                obsidianBlocks++;
                            }
                        }
                    }
                    int expected = (2 * EndPortalLanding.PLATFORM_HALF + 1) * (2 * EndPortalLanding.PLATFORM_HALF + 1);
                    AeroPortals.LOGGER.info("[AeroPortals/test] end-OW->End: platform obsidian blocks = {}/{}",
                            obsidianBlocks, expected);
                    if (obsidianBlocks != expected) {
                        helper.fail("platform not fully built: " + obsidianBlocks + "/" + expected + " obsidian blocks at "
                                + EndPortalLanding.PLATFORM_CENTRE);
                        return;
                    }

                    if (inEnd != null) {
                        
                        double px = inEnd.logicalPose().position().x();
                        double pz = inEnd.logicalPose().position().z();
                        AeroPortals.LOGGER.info("[AeroPortals/test] end-OW->End: sub pose at ({}, _, {})", px, pz);
                        if (Math.abs(px - (EndPortalLanding.PLATFORM_CENTRE.getX() + 0.5)) > 0.01) {
                            helper.fail("sub X not centred over platform: " + px); return;
                        }
                        if (Math.abs(pz - (EndPortalLanding.PLATFORM_CENTRE.getZ() + 0.5)) > 0.01) {
                            helper.fail("sub Z not centred over platform: " + pz); return;
                        }
                        
                        AABB aabb = AabbUtil.worldAabb(inEnd);
                        AeroPortals.LOGGER.info("[AeroPortals/test] end-OW->End: sub aabb.minY = {} (expected {})",
                                aabb.minY, EndPortalLanding.LANDING_Y);
                        if (Math.abs(aabb.minY - EndPortalLanding.LANDING_Y) > 0.1) {
                            helper.fail("sub bottom not at LANDING_Y=" + EndPortalLanding.LANDING_Y
                                    + ", got aabb.minY=" + aabb.minY);
                            return;
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void endPortalLanding_ensurePlatform_isIdempotent(GameTestHelper helper) {
        
        
        
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        if (endLevel == null) { helper.fail("End not loaded"); return; }

        EndPortalLanding.ensurePlatform(endLevel);
        boolean rebuilt = EndPortalLanding.ensurePlatform(endLevel);
        if (rebuilt) {
            helper.fail("ensurePlatform should return false on second call (platform already complete)");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void endPortalLanding_landingPosition_putsLowestBlockAtTopOfPlatform(GameTestHelper helper) {
        
        ServerLevel srcLevel = helper.getLevel();
        BlockPos local = new BlockPos(7, 4, 7);
        BlockPos worldPos = helper.absolutePos(local);
        helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

        BoundingBox3i bounds = new BoundingBox3i(
                worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                srcLevel, worldPos, List.of(worldPos), bounds);
        if (sub == null) { helper.fail("assemble failed"); return; }

        
        
        Vec3 landing = EndPortalLanding.landingPosition(sub);
        AABB aabb = AabbUtil.worldAabb(sub);
        double poseY = sub.logicalPose().position().y();
        double offset = aabb.minY - poseY;
        double expectedDstY = EndPortalLanding.LANDING_Y - offset;
        AeroPortals.LOGGER.info("[AeroPortals/test] landingPosition: aabb.minY={} poseY={} offset={} expectedDstY={} actualY={}",
                aabb.minY, poseY, offset, expectedDstY, landing.y);

        if (Math.abs(landing.x - (EndPortalLanding.PLATFORM_CENTRE.getX() + 0.5)) > 1e-9) {
            helper.fail("landing.x should be platform centre + 0.5, got " + landing.x); return;
        }
        if (Math.abs(landing.z - (EndPortalLanding.PLATFORM_CENTRE.getZ() + 0.5)) > 1e-9) {
            helper.fail("landing.z should be platform centre + 0.5, got " + landing.z); return;
        }
        if (Math.abs(landing.y - expectedDstY) > 1e-9) {
            helper.fail("landing.y should be " + expectedDstY + ", got " + landing.y); return;
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void endPortalLanding_ensurePlatform_buildsWhenMissing(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        if (endLevel == null) { helper.fail("End not loaded"); return; }

        
        for (int dx = -EndPortalLanding.PLATFORM_HALF; dx <= EndPortalLanding.PLATFORM_HALF; dx++) {
            for (int dz = -EndPortalLanding.PLATFORM_HALF; dz <= EndPortalLanding.PLATFORM_HALF; dz++) {
                endLevel.setBlock(EndPortalLanding.PLATFORM_CENTRE.offset(dx, 0, dz),
                        Blocks.AIR.defaultBlockState(), 3);
            }
        }

        boolean built = EndPortalLanding.ensurePlatform(endLevel);
        if (!built) {
            helper.fail("ensurePlatform should return true when platform was missing");
            return;
        }

        int obsidian = 0;
        for (int dx = -EndPortalLanding.PLATFORM_HALF; dx <= EndPortalLanding.PLATFORM_HALF; dx++) {
            for (int dz = -EndPortalLanding.PLATFORM_HALF; dz <= EndPortalLanding.PLATFORM_HALF; dz++) {
                if (endLevel.getBlockState(EndPortalLanding.PLATFORM_CENTRE.offset(dx, 0, dz))
                        .is(Blocks.OBSIDIAN)) {
                    obsidian++;
                }
            }
        }
        int expected = (2 * EndPortalLanding.PLATFORM_HALF + 1) * (2 * EndPortalLanding.PLATFORM_HALF + 1);
        if (obsidian != expected) {
            helper.fail("expected " + expected + " obsidian blocks, got " + obsidian);
            return;
        }
        helper.succeed();
    }
    @GameTest(template = EMPTY)
    public static void arsNouveauCompat_noOpsCleanlyWhenAbsent(GameTestHelper helper) {
        boolean available = ArsNouveauCompat.isAvailable();
        AeroPortals.LOGGER.info("[AeroPortals/test] AN compat isAvailable={} (AN loaded in this run: {})",
                available, ModList.get().isLoaded(ArsNouveauCompat.MOD_ID));

        
        if (AeroPortalsApi.isPortalBlock(Blocks.AIR.defaultBlockState())) {
            helper.fail("AeroPortalsApi.isPortalBlock should be false for AIR");
            return;
        }
        if (AeroPortalsApi.isPortalBlock(Blocks.OBSIDIAN.defaultBlockState())) {
            helper.fail("AeroPortalsApi.isPortalBlock should be false for OBSIDIAN");
            return;
        }

        
        BlockPos local = new BlockPos(7, 4, 7);
        BlockPos world = helper.absolutePos(local);
        helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
        if (ArsNouveauCompat.readDestination(helper.getLevel(), world).isPresent()) {
            helper.fail("readDestination on non-AN BlockEntity should be empty");
            return;
        }

        
        if (ArsNouveauCompat.isPortalBlock(Blocks.NETHER_PORTAL.defaultBlockState())) {
            helper.fail("isPortalBlock should be false for vanilla NETHER_PORTAL");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_arsNouveauPortal_landsSubAtConfiguredWarpPos(GameTestHelper helper) {
        
        if (!ArsNouveauCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] AN not loaded - skipping AN end-to-end teleport test");
            helper.succeed();
            return;
        }

        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        
        Block portalBlock =
                BuiltInRegistries.BLOCK.get(
                        ResourceLocation.fromNamespaceAndPath("ars_nouveau", "portal"));
        if (portalBlock == null) { helper.fail("AN portal block not in registry"); return; }

        UUID[] subUuidRef = new UUID[1];
        BlockPos[] warpPosRef = new BlockPos[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    
                    helper.setBlock(local, portalBlock.defaultBlockState());

                    
                    BlockPos warpPos = new BlockPos(1000, 80, 1000);
                    warpPosRef[0] = warpPos;
                    BlockEntity be = srcLevel.getBlockEntity(worldPos);
                    if (be == null) { helper.fail("AN portal BE not present after setBlock"); return; }
                    try {
                        Class<?> cls = Class.forName("com.hollingsworth.arsnouveau.common.block.tile.PortalTile");
                        cls.getField("warpPos").set(be, warpPos);
                        cls.getField("dimID").set(be, "minecraft:the_nether");
                        cls.getField("rotationVec").set(be, new Vec2(0f, 0f));
                    } catch (ReflectiveOperationException e) {
                        helper.fail("failed to configure AN portal BE: " + e.getMessage());
                        return;
                    }
                    AeroPortals.LOGGER.info("[AeroPortals/test] AN portal configured at {} -> dim minecraft:the_nether warpPos={}",
                            worldPos, warpPos);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenIdle(2)
                .thenExecute(() -> {
                    UUID id = subUuidRef[0];
                    boolean inSrc = srcContainer.getSubLevel(id) != null;
                    ServerSubLevel inDst = (ServerSubLevel) dstContainer.getSubLevel(id);
                    boolean inDstHolding = dstContainer.getHoldingChunkMap().getHoldingSubLevel(id) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] AN-portal teleport: inSrc={} inDst={} inDstHolding={}",
                            inSrc, inDst, inDstHolding);

                    if (inSrc) { helper.fail("sub should be removed from src dim"); return; }
                    if (inDst == null && !inDstHolding) {
                        helper.fail("sub should be present in AN dest dim (active or holding)");
                        return;
                    }

                    if (inDst != null) {
                        BlockPos warpPos = warpPosRef[0];
                        double px = inDst.logicalPose().position().x();
                        double pz = inDst.logicalPose().position().z();
                        AeroPortals.LOGGER.info("[AeroPortals/test] AN-portal teleport: sub pose at ({}, _, {})", px, pz);
                        if (Math.abs(px - (warpPos.getX() + 0.5)) > 0.01) {
                            helper.fail("sub X not centred over warpPos: " + px); return;
                        }
                        if (Math.abs(pz - (warpPos.getZ() + 0.5)) > 0.01) {
                            helper.fail("sub Z not centred over warpPos: " + pz); return;
                        }
                        AABB aabb = AabbUtil.worldAabb(inDst);
                        double expectedMinY = warpPos.getY() + 1;
                        AeroPortals.LOGGER.info("[AeroPortals/test] AN-portal teleport: aabb.minY={} expectedTopOfWarpBlock={}",
                                aabb.minY, expectedMinY);
                        if (Math.abs(aabb.minY - expectedMinY) > 0.1) {
                            helper.fail("sub bottom not above warpPos.y+1, got aabb.minY=" + aabb.minY);
                            return;
                        }
                    }
                })
                .thenSucceed();
    }
    @GameTest(template = EMPTY)
    public static void aetherCompat_noOpsCleanlyWhenAbsent(GameTestHelper helper) {
        boolean available = AetherCompat.isAvailable();
        AeroPortals.LOGGER.info("[AeroPortals/test] Aether compat isAvailable={} (Aether loaded in this run: {})",
                available, ModList.get().isLoaded(AetherCompat.MOD_ID));

        if (AetherCompat.isPortalBlock(Blocks.NETHER_PORTAL.defaultBlockState())) {
            helper.fail("Aether.isPortalBlock should be false for vanilla NETHER_PORTAL");
            return;
        }
        if (AetherCompat.isPortalBlock(Blocks.AIR.defaultBlockState())) {
            helper.fail("Aether.isPortalBlock should be false for AIR");
            return;
        }
        if (available) {
            
            if (AetherCompat.destinationDimension() == null) {
                helper.fail("destinationDimension should not be null when AN loaded"); return;
            }
            if (AetherCompat.returnDimension() == null) {
                helper.fail("returnDimension should not be null when AN loaded"); return;
            }
            if (AetherCompat.portalBlock() == null) {
                helper.fail("portalBlock should not be null when AN loaded"); return;
            }
        } else {
            
            if (AetherCompat.destinationDimension() != null) {
                helper.fail("destinationDimension should be null when Aether absent"); return;
            }
        }
        helper.succeed();
    }
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_aetherPortal_teleportsToAetherDim(GameTestHelper helper) {
        if (!AetherCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Aether not loaded - skipping Aether end-to-end teleport test");
            helper.succeed();
            return;
        }

        ServerLevel srcLevel = helper.getLevel();
        ResourceKey<Level> dstKey = AetherCompat.destinationDimension();
        if (dstKey == null) {
            helper.fail("AetherCompat.destinationDimension returned null despite isAvailable()==true");
            return;
        }
        ServerLevel dstLevel = srcLevel.getServer().getLevel(dstKey);
        if (dstLevel == null) {
            
            
            
            AeroPortals.LOGGER.info("[AeroPortals/test] Aether dim {} not initialized in gametest world; compat surface check only",
                    dstKey.location());
            if (!dstKey.location().toString().equals("aether:the_aether")) {
                helper.fail("expected Aether destination dim 'aether:the_aether', got " + dstKey.location());
                return;
            }
            helper.succeed();
            return;
        }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        Block aetherPortalBlock = AetherCompat.portalBlock();
        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    
                    BlockState portalState =
                            aetherPortalBlock.defaultBlockState().setValue(
                                    BlockStateProperties.HORIZONTAL_AXIS,
                                    Direction.Axis.X);
                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3,
                            Blocks.GLOWSTONE, portalState);
                })
                .thenIdle(3)
                .thenExecute(() -> PortalDetector.scan(srcLevel))
                .thenIdle(2)
                .thenExecute(() -> {
                    UUID id = subUuidRef[0];
                    boolean inSrc = srcContainer.getSubLevel(id) != null;
                    boolean inDst = dstContainer.getSubLevel(id) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(id) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] aether teleport: inSrc={} inDst={}", inSrc, inDst);

                    if (inSrc) { helper.fail("sub should be removed from src dim after aether teleport"); return; }
                    if (!inDst) { helper.fail("sub should be present in Aether dim (active or holding)"); return; }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void command_teleportToDimension_movesSubToTargetDim(GameTestHelper helper) {





        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        UUID[] subUuidRef = new UUID[1];
        TransferEventRecorder.reset();
        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

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
                    subUuidRef[0] = sub.getUniqueId();

                    Vec3 dstWorld = new Vec3(worldPos.getX() / 8.0 + 0.5, 64.5, worldPos.getZ() / 8.0 + 0.5);
                    clearNetherCube(dstLevel, BlockPos.containing(dstWorld), 3);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                            true, "test:command");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        UUID id = subUuidRef[0];
                        boolean inSrc = srcContainer.getSubLevel(id) != null;
                        TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(id);
                        AeroPortals.LOGGER.info("[AeroPortals/test] command teleport: inSrc={} eventFired={}",
                                inSrc, captured != null);

                        if (inSrc) {
                            helper.fail("sub should be removed from src dim after command teleport");
                            return;
                        }
                        if (captured == null) {
                            helper.fail("SubLevelTransferEvent did not fire for sub " + id);
                            return;
                        }
                        if (captured.src() != srcLevel || captured.dst() != dstLevel) {
                            helper.fail("event src/dst mismatch: " + captured.src() + " -> " + captured.dst());
                            return;
                        }
                    } finally {
                        NeoForge.EVENT_BUS.unregister(TransferEventRecorder.class);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void velocityHold_subArrivesStoppedThenRegainsMomentum(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        if (AeroPortalsConfig.CLEAR_VELOCITY_ON_ARRIVAL.get()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] clear_velocity_on_arrival is on; hold-and-restore path not exercised");
            helper.succeed();
            return;
        }

        Vector3d launch = new Vector3d(40.0, 0.0, 30.0);
        UUID[] subUuidRef = new UUID[1];
        double[] heldSpeedRef = new double[1];
        int[] heldBefore = {PortalTeleport.DeferredVelocityRestores.heldCount.get()};
        int[] restoredBefore = {PortalTeleport.DeferredVelocityRestores.restoredCount.get()};
        int[] skippedBefore = {PortalTeleport.DeferredVelocityRestores.skippedCount.get()};

        helper.startSequence()
                .thenExecute(() -> {
                    ServerSubLevel sub = assembleTestSub(helper, srcLevel);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    subUuidRef[0] = sub.getUniqueId();

                    srcContainer.physicsSystem().getPipeline()
                            .addLinearAndAngularVelocity(sub, launch, new Vector3d());
                    double before = horizontalSpeed(srcContainer, sub);
                    if (before < 1.0) {
                        helper.fail("test setup: sub did not take the launch velocity, horizontal speed=" + before);
                        return;
                    }

                    Vec3 dstWorld = new Vec3(helper.absolutePos(new BlockPos(7, 4, 7)).getX() / 8.0 + 0.5,
                            64.5, helper.absolutePos(new BlockPos(7, 4, 7)).getZ() / 8.0 + 0.5);
                    clearNetherCube(dstLevel, BlockPos.containing(dstWorld), 3);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                            true, "test:velocity_hold");

                    ServerSubLevel arrived = arrivedSub(dstLevel, subUuidRef[0]);
                    if (arrived == null) { helper.fail("sub not found in destination after teleport"); return; }
                    heldSpeedRef[0] = horizontalSpeed(SubLevelContainer.getContainer(dstLevel), arrived);
                    AeroPortals.LOGGER.info("[AeroPortals/test] horizontal speed on arrival = {}", heldSpeedRef[0]);
                })
                .thenExecute(() -> {
                    if (heldSpeedRef[0] > 1.0) {
                        helper.fail("sub should arrive stopped, but horizontal speed was " + heldSpeedRef[0]);
                        return;
                    }
                    int held = PortalTeleport.DeferredVelocityRestores.heldCount.get() - heldBefore[0];
                    if (held < 1) {
                        helper.fail("arrival did not capture the ship's momentum for handback");
                    }
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    int restored = PortalTeleport.DeferredVelocityRestores.restoredCount.get() - restoredBefore[0];
                    int skipped = PortalTeleport.DeferredVelocityRestores.skippedCount.get() - skippedBefore[0];
                    ServerSubLevel arrived = arrivedSub(dstLevel, subUuidRef[0]);
                    HoldingSubLevel holding = SubLevelContainer.getContainer(dstLevel)
                            .getHoldingChunkMap().getHoldingSubLevel(subUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] after hold: restored={} skipped={} loaded={} holding={}",
                            restored, skipped, arrived != null, holding != null);

                    if (restored + skipped == 0) {
                        helper.fail("hold window expired without the restore ever running");
                        return;
                    }
                    if (arrived == null) {
                        AeroPortals.LOGGER.warn("[AeroPortals/test] sub left the loaded set inside the hold window"
                                + " (holding={}); restore correctly skipped, momentum handback not asserted", holding != null);
                        return;
                    }
                    double after = horizontalSpeed(SubLevelContainer.getContainer(dstLevel), arrived);
                    AeroPortals.LOGGER.info("[AeroPortals/test] horizontal speed after hold = {}", after);
                    if (after < 1.0) {
                        helper.fail("momentum was never restored after the hold window; horizontal speed=" + after);
                    }
                })
                .thenSucceed();
    }

    private static ServerSubLevel assembleTestSub(GameTestHelper helper, ServerLevel srcLevel) {
        BlockPos local = new BlockPos(7, 4, 7);
        BlockPos worldPos = helper.absolutePos(local);
        helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
        BoundingBox3i bounds = new BoundingBox3i(
                worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
        return SubLevelAssemblyHelper.assembleBlocks(srcLevel, worldPos, List.of(worldPos), bounds);
    }

    private static ServerSubLevel arrivedSub(ServerLevel dstLevel, UUID id) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(dstLevel);
        if (container == null || id == null) return null;
        return container.getSubLevel(id) instanceof ServerSubLevel s ? s : null;
    }

    private static double horizontalSpeed(ServerSubLevelContainer container, ServerSubLevel sub) {
        Vector3d velocity = container.physicsSystem().getPipeline().getLinearVelocity(sub, new Vector3d());
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void command_parseDestination_coordinateForms(GameTestHelper helper) {
        var plain = AeroPortalsCommands.parseDestination("kubejs:deep_space");
        if (!plain.dimensionPart().equals("kubejs:deep_space") || plain.hasCoords()) {
            helper.fail("plain dimension parse wrong: " + plain);
            return;
        }

        var coords = AeroPortalsCommands.parseDestination("kubejs:deep_space -1000 ~ -1000");
        if (!coords.dimensionPart().equals("kubejs:deep_space") || !coords.hasCoords()) {
            helper.fail("dimension with coords parse wrong: " + coords);
            return;
        }
        if (coords.x().relative() || coords.x().value() != -1000.0
                || !coords.y().relative() || coords.y().value() != 0.0
                || coords.z().relative() || coords.z().value() != -1000.0) {
            helper.fail("coord specs wrong: " + coords);
            return;
        }

        var multiWord = AeroPortalsCommands.parseDestination("deep space -5 ~2.5 7");
        if (!multiWord.dimensionPart().equals("deep space") || !multiWord.hasCoords()
                || !multiWord.y().relative() || multiWord.y().value() != 2.5) {
            helper.fail("multi-word dimension with coords parse wrong: " + multiWord);
            return;
        }

        var words = AeroPortalsCommands.parseDestination("some dimension name here");
        if (!words.dimensionPart().equals("some dimension name here") || words.hasCoords()) {
            helper.fail("multi-word dimension without coords parse wrong: " + words);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void teleport_abortsWhenLandingBlockedByNetherrack(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        UUID[] subUuidRef = new UUID[1];
        TransferEventRecorder.reset();
        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

        final int dstX = 12345;
        final int dstY = 64;
        final int dstZ = 12345;

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
                    subUuidRef[0] = sub.getUniqueId();

                    fillNetherCube(dstLevel, new BlockPos(dstX, dstY, dstZ), 3, Blocks.NETHERRACK.defaultBlockState());

                    Vec3 dstWorld = new Vec3(dstX + 0.5, dstY + 0.5, dstZ + 0.5);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                            true, "test:abort_when_blocked");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        UUID id = subUuidRef[0];
                        boolean inSrc = srcContainer.getSubLevel(id) != null;
                        TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(id);
                        AeroPortals.LOGGER.info("[AeroPortals/test] abort-when-blocked: inSrc={} eventFired={}",
                                inSrc, captured != null);
                        if (!inSrc) {
                            helper.fail("sub should remain in src dim when teleport is aborted by blocked landing");
                            return;
                        }
                        if (captured != null) {
                            helper.fail("SubLevelTransferEvent must not fire when teleport is aborted");
                            return;
                        }
                    } finally {
                        NeoForge.EVENT_BUS.unregister(TransferEventRecorder.class);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void teleport_succeedsWhenLandingClearedFirst(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        UUID[] subUuidRef = new UUID[1];
        TransferEventRecorder.reset();
        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

        final int dstX = 12400;
        final int dstY = 64;
        final int dstZ = 12400;

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
                    subUuidRef[0] = sub.getUniqueId();

                    clearNetherCube(dstLevel, new BlockPos(dstX, dstY, dstZ), 3);

                    Vec3 dstWorld = new Vec3(dstX + 0.5, dstY + 0.5, dstZ + 0.5);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                            true, "test:success_when_cleared");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        UUID id = subUuidRef[0];
                        boolean inSrc = srcContainer.getSubLevel(id) != null;
                        TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(id);
                        AeroPortals.LOGGER.info("[AeroPortals/test] success-when-cleared: inSrc={} eventFired={}",
                                inSrc, captured != null);
                        if (inSrc) {
                            helper.fail("sub should be removed from src dim after successful teleport into cleared space");
                            return;
                        }
                        if (captured == null) {
                            helper.fail("SubLevelTransferEvent did not fire for cleared-space teleport");
                            return;
                        }
                        if (captured.dst() != dstLevel) {
                            helper.fail("event dst mismatch: " + captured.dst());
                            return;
                        }
                    } finally {
                        NeoForge.EVENT_BUS.unregister(TransferEventRecorder.class);
                    }
                })
                .thenSucceed();
    }

    private static void fillNetherCube(ServerLevel level, BlockPos centre, int radius, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    level.setBlock(cursor, state, 3);
                }
            }
        }
    }

    private static void clearNetherCube(ServerLevel level, BlockPos centre, int radius) {
        fillNetherCube(level, centre, radius, Blocks.AIR.defaultBlockState());
    }

    @GameTest(template = EMPTY)
    public static void draconicEvolutionCompat_noOpsCleanlyWhenAbsent(GameTestHelper helper) {
        boolean available = DraconicEvolutionCompat.isAvailable();
        AeroPortals.LOGGER.info("[AeroPortals/test] DE compat isAvailable={} (DE loaded in this run: {})",
                available, ModList.get().isLoaded(DraconicEvolutionCompat.MOD_ID));

        if (DraconicEvolutionCompat.isPortalBlock(Blocks.NETHER_PORTAL.defaultBlockState())) {
            helper.fail("DE.isPortalBlock should be false for NETHER_PORTAL");
            return;
        }
        if (DraconicEvolutionCompat.isPortalBlock(Blocks.AIR.defaultBlockState())) {
            helper.fail("DE.isPortalBlock should be false for AIR");
            return;
        }

        BlockPos local = new BlockPos(7, 4, 7);
        helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());
        if (DraconicEvolutionCompat.readDestination(helper.getLevel(), helper.absolutePos(local)).isPresent()) {
            helper.fail("readDestination on non-DE BE should be empty");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tropicraftCompat_noOpsCleanlyWhenAbsent(GameTestHelper helper) {
        boolean available = TropicraftCompat.isAvailable();
        AeroPortals.LOGGER.info("[AeroPortals/test] Tropicraft compat isAvailable={} (Tropicraft loaded in this run: {})",
                available, ModList.get().isLoaded(TropicraftCompat.MOD_ID));

        if (TropicraftCompat.isPinaColada(ItemStack.EMPTY)) {
            helper.fail("isPinaColada should be false for EMPTY stack");
            return;
        }
        if (TropicraftCompat.isPinaColada(new ItemStack(Items.DIAMOND))) {
            helper.fail("isPinaColada should be false for non-tropicraft items");
            return;
        }

        if (available) {
            if (TropicraftCompat.tropicsDimension() == null) {
                helper.fail("tropicsDimension should be non-null when Tropicraft loaded");
                return;
            }
            if (!TropicraftCompat.tropicsDimension().location().toString().equals("tropicraft:tropics")) {
                helper.fail("tropicsDimension should be tropicraft:tropics, got " + TropicraftCompat.tropicsDimension().location());
                return;
            }
        } else {
            if (TropicraftCompat.tropicsDimension() != null) {
                helper.fail("tropicsDimension should be null when Tropicraft absent");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void telepastriesCompat_noOpsCleanlyWhenAbsent(GameTestHelper helper) {
        boolean available = TelepastriesCompat.isAvailable();
        AeroPortals.LOGGER.info("[AeroPortals/test] TelePastries compat isAvailable={} (TelePastries loaded in this run: {})",
                available, ModList.get().isLoaded(TelepastriesCompat.MOD_ID));

        if (TelepastriesCompat.isTeleCake(Blocks.STONE.defaultBlockState())) {
            helper.fail("isTeleCake should be false for STONE");
            return;
        }
        if (TelepastriesCompat.isTeleCake(Blocks.CAKE.defaultBlockState())) {
            helper.fail("isTeleCake should be false for vanilla CAKE");
            return;
        }
        if (TelepastriesCompat.getCakeDestination(Blocks.STONE.defaultBlockState()) != null) {
            helper.fail("getCakeDestination should be null for STONE");
            return;
        }

        if (available) {
            BlockPos local = new BlockPos(7, 4, 7);
            BlockPos worldPos = helper.absolutePos(local);
            Block netherCake = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(TelepastriesCompat.MOD_ID, "nether_cake"));
            if (netherCake == Blocks.AIR) {
                helper.fail("nether_cake block missing from registry despite telepastries being loaded");
                return;
            }
            helper.setBlock(local, netherCake.defaultBlockState());
            BlockState placed = helper.getLevel().getBlockState(worldPos);
            if (!TelepastriesCompat.isTeleCake(placed)) {
                helper.fail("isTeleCake should return true for placed telepastries nether_cake");
                return;
            }
            ResourceKey<Level> dest = TelepastriesCompat.getCakeDestination(placed);
            if (dest == null || !dest.equals(Level.NETHER)) {
                helper.fail("nether_cake getCakeDestination should be Level.NETHER, got " + dest);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_pinaColadaOnSubLevel_teleportsToTropics(GameTestHelper helper) {
        if (!TropicraftCompat.isAvailable()) {
            AeroPortals.LOGGER.info("[AeroPortals/test] Tropicraft not loaded - skipping pina colada teleport test");
            helper.succeed();
            return;
        }

        ServerLevel srcLevel = helper.getLevel();
        ResourceKey<Level> dstKey = TropicraftCompat.tropicsDimension();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.info("[AeroPortals/test] tropicraft dim {} not initialised in gametest world; compat check only",
                    dstKey.location());
            helper.succeed();
            return;
        }

        
        
        
        ItemStack pinaColada;
        try {
            Class<?> drinksClass = Class.forName("net.tropicraft.core.common.drinks.TropicraftDrinks");
            ResourceKey<?> pinaKey =
                    (ResourceKey<?>) drinksClass.getField("PINA_COLADA").get(null);
            
            Registry<?> drinkRegistry = srcLevel.registryAccess()
                    .registry(ResourceKey.createRegistryKey(
                            ResourceLocation.fromNamespaceAndPath("tropicraft", "drink")))
                    .orElse(null);
            if (drinkRegistry == null) {
                helper.fail("tropicraft:drink registry not available");
                return;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            Optional<? extends Holder<?>> holderOpt =
                    ((Registry) drinkRegistry).getHolder(pinaKey);
            Holder<?> holder = holderOpt.orElse(null);
            if (holder == null) {
                helper.fail("pina_colada drink not registered (datapack not loaded?)");
                return;
            }
            Class<?> cocktailItemClass = Class.forName("net.tropicraft.core.common.item.CocktailItem");
            Method makeDrink = cocktailItemClass.getMethod("makeDrink", Holder.class);
            pinaColada = (ItemStack) makeDrink.invoke(null, holder);
        } catch (ReflectiveOperationException e) {
            helper.fail("failed to construct pina colada stack: " + e.getMessage());
            return;
        }

        if (!TropicraftCompat.isPinaColada(pinaColada)) {
            helper.fail("constructed cocktail not recognised as pina colada by TropicraftCompat");
            return;
        }

        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        UUID[] subUuidRef = new UUID[1];
        TransferEventRecorder.reset();
        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

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
                    subUuidRef[0] = sub.getUniqueId();

                    
                    
                    
                    
                    Vec3 dstWorld = new Vec3(worldPos.getX() + 0.5, 130, worldPos.getZ() + 0.5);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                            true, "test:pina_colada");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        UUID id = subUuidRef[0];
                        boolean inSrc = srcContainer.getSubLevel(id) != null;
                        TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(id);
                        AeroPortals.LOGGER.info("[AeroPortals/test] tropicraft teleport: inSrc={} eventFired={}",
                                inSrc, captured != null);
                        if (inSrc) { helper.fail("sub should be removed from src"); return; }
                        if (captured == null) { helper.fail("SubLevelTransferEvent did not fire"); return; }
                        if (captured.dst() != dstLevel) { helper.fail("event dst mismatch: " + captured.dst()); return; }
                    } finally {
                        NeoForge.EVENT_BUS.unregister(TransferEventRecorder.class);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_itemFrameWithItem_transfersWithContents(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        UUID[] frameUuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.OBSIDIAN.defaultBlockState());

                    ItemFrame frame = new ItemFrame(srcLevel, worldPos, Direction.UP);
                    frame.setItem(new ItemStack(Items.DIAMOND));
                    srcLevel.addFreshEntity(frame);
                    frameUuidRef[0] = frame.getUUID();
                    AeroPortals.LOGGER.info("[AeroPortals/test] spawned item frame uuid={}", frameUuidRef[0]);

                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }

                    PortalBuilder.build(srcLevel, worldPos, Direction.Axis.X, 2, 3);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    PortalDetector.scan(srcLevel);

                    Entity moved = PortalTeleport.lastMovedEntities.get(frameUuidRef[0]);
                    AeroPortals.LOGGER.info("[AeroPortals/test] item frame: lookupUuid={} srcRemoved={} movedRef={}",
                            frameUuidRef[0],
                            srcLevel.getEntity(frameUuidRef[0]) == null, moved);

                    if (srcLevel.getEntity(frameUuidRef[0]) != null) {
                        helper.fail("source item frame should have been removed by changeDimension");
                        return;
                    }
                    if (moved == null) {
                        helper.fail("replayEntityRiders did not record the item frame transfer");
                        return;
                    }
                    if (!(moved instanceof ItemFrame frameDst)) {
                        helper.fail("transferred entity is not an ItemFrame: " + moved.getType());
                        return;
                    }
                    if (moved.level() != dstLevel) {
                        helper.fail("transferred entity's level is not dst: " + moved.level());
                        return;
                    }
                    if (!frameDst.getItem().is(Items.DIAMOND)) {
                        helper.fail("item frame's held item was not preserved: " + frameDst.getItem());
                        return;
                    }
                    if (moved.isRemoved()) {
                        helper.fail("transferred item frame was removed before verify");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endToEnd_relocatedSubLevel_preservesSpawnerData(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] subUuidRef = new UUID[1];
        int[] occupiedIndexRef = new int[]{-1};

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.SPAWNER.defaultBlockState());
                    BlockEntity be = srcLevel.getBlockEntity(worldPos);
                    if (!(be instanceof SpawnerBlockEntity spawner)) { helper.fail("spawner BE missing in world"); return; }
                    spawner.getSpawner().setEntityId(EntityType.ZOMBIE, srcLevel, srcLevel.getRandom(), worldPos);
                    spawner.setChanged();

                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    subUuidRef[0] = sub.getUniqueId();

                    BlockPos plotSpawner = findSpawnerInPlot(srcLevel, sub);
                    if (plotSpawner == null) { helper.fail("assembly did not carry the spawner block into the SubLevel"); return; }
                    if (!spawnerEntityId(srcLevel, plotSpawner).equals("minecraft:zombie")) {
                        helper.fail("assembly did not preserve spawner data pre-teleport; cannot isolate the move");
                        return;
                    }

                    var origin = dstContainer.getOrigin();
                    int localPlotX = sub.getPlot().plotPos.x - origin.x;
                    int localPlotZ = sub.getPlot().plotPos.z - origin.y;
                    occupiedIndexRef[0] = dstContainer.getIndex(localPlotX, localPlotZ);
                    dstContainer.getOccupancy().set(occupiedIndexRef[0]);

                    PortalTeleport.teleport(srcLevel, sub, new PortalRect(worldPos, Direction.Axis.X, 2, 3));
                    if (occupiedIndexRef[0] >= 0) dstContainer.getOccupancy().clear(occupiedIndexRef[0]);

                    ServerSubLevel dstSub = (ServerSubLevel) dstContainer.getSubLevel(subUuidRef[0]);
                    if (dstSub == null) {
                        helper.fail("sub did not arrive active in dst dim (needed to read block entity)");
                        return;
                    }
                    int relocX = dstSub.getPlot().plotPos.x - dstContainer.getOrigin().x;
                    int relocZ = dstSub.getPlot().plotPos.z - dstContainer.getOrigin().y;
                    AeroPortals.LOGGER.info("[AeroPortals/test] sub crossed to dst plot {},{}", relocX, relocZ);

                    BlockPos dstPlotSpawner = findSpawnerInPlot(dstLevel, dstSub);
                    if (dstPlotSpawner == null) {
                        helper.fail("spawner block missing after the move");
                        return;
                    }
                    String id = spawnerEntityId(dstLevel, dstPlotSpawner);
                    AeroPortals.LOGGER.info("[AeroPortals/test] spawner entity id after move = '{}'", id);
                    if (!id.equals("minecraft:zombie")) {
                        helper.fail("spawner lost its data after the move (expected minecraft:zombie, got '" + id + "')");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void subLevelTransferEvent_plotShiftRemapsOldPlotPositions(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        NeoForge.EVENT_BUS.register(TransferEventRecorder.class);

        UUID[] subUuidRef = new UUID[1];
        int[] occupiedIndexRef = new int[]{-1};

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos local = new BlockPos(7, 4, 7);
                    BlockPos worldPos = helper.absolutePos(local);
                    helper.setBlock(local, Blocks.SPAWNER.defaultBlockState());

                    BoundingBox3i bounds = new BoundingBox3i(
                            worldPos.getX() - 1, worldPos.getY() - 1, worldPos.getZ() - 1,
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, worldPos, List.of(worldPos), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    subUuidRef[0] = sub.getUniqueId();

                    BlockPos oldPlotBlock = findSpawnerInPlot(srcLevel, sub);
                    if (oldPlotBlock == null) { helper.fail("spawner missing from plot pre-teleport"); return; }

                    var origin = dstContainer.getOrigin();
                    int localPlotX = sub.getPlot().plotPos.x - origin.x;
                    int localPlotZ = sub.getPlot().plotPos.z - origin.y;
                    occupiedIndexRef[0] = dstContainer.getIndex(localPlotX, localPlotZ);
                    dstContainer.getOccupancy().set(occupiedIndexRef[0]);

                    Vec3 dstWorld = new Vec3(worldPos.getX() / 8.0 + 4096.0, 128.0, worldPos.getZ() / 8.0 + 4096.0);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld, false, "test_plotshift");
                    if (occupiedIndexRef[0] >= 0) dstContainer.getOccupancy().clear(occupiedIndexRef[0]);

                    TransferEventRecorder.Captured captured = TransferEventRecorder.captured.get(subUuidRef[0]);
                    if (captured == null) { helper.fail("transfer event did not fire"); return; }
                    SubLevelTransferEvent event = captured.event();

                    BlockPos shift = event.plotShift();
                    if (shift.getX() == 0 && shift.getZ() == 0) {
                        helper.fail("plot was forced occupied, expected a nonzero XZ plot shift, got " + shift);
                        return;
                    }
                    if (event.chainPlotMoves().isEmpty()) {
                        helper.fail("chainPlotMoves is empty");
                        return;
                    }

                    BlockPos remapped = event.remapPlotPos(oldPlotBlock);
                    if (remapped.equals(oldPlotBlock)) {
                        helper.fail("remapPlotPos left an old-plot position unchanged despite relocation");
                        return;
                    }
                    if (!remapped.equals(oldPlotBlock.offset(shift))) {
                        helper.fail("remapPlotPos returned " + remapped + ", expected " + oldPlotBlock.offset(shift));
                        return;
                    }
                    if (!dstLevel.getBlockState(remapped).is(Blocks.SPAWNER)) {
                        helper.fail("no spawner at remapped position " + remapped + " in dst dim");
                        return;
                    }

                    BlockPos outside = new BlockPos(12, 70, 34);
                    if (!event.remapPlotPos(outside).equals(outside)) {
                        helper.fail("remapPlotPos changed a position outside the old plot region");
                        return;
                    }
                })
                .thenSucceed();
    }

    private static BlockPos findSpawnerInPlot(ServerLevel level, ServerSubLevel sub) {
        var bounds = sub.getPlot().getBoundingBox();
        if (bounds == BoundingBox3i.EMPTY) return null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).is(Blocks.SPAWNER)) return cursor.immutable();
                }
            }
        }
        return null;
    }

    private static String spawnerEntityId(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SpawnerBlockEntity)) return "";
        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        return tag.getCompound("SpawnData").getCompound("entity").getString("id");
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void bounceSuppression_arrivedSubDoesNotImmediatelyReturn(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    PortalTeleport.teleport(srcLevel, sub, new PortalRect(worldPos, Direction.Axis.X, 2, 3));

                    if (dstContainer.getSubLevel(subUuidRef[0]) == null) {
                        helper.fail("sub did not arrive in nether; cannot test bounce suppression");
                        return;
                    }
                    if (!PortalCooldown.isSuppressedUntilLeftPortal(subUuidRef[0])) {
                        helper.fail("arrived sub should be suppressed from an immediate return trip");
                        return;
                    }

                    PortalCooldown.mark(subUuidRef[0], 0L);
                    PortalDetector.scan(dstLevel);

                    boolean inDst = dstContainer.getSubLevel(subUuidRef[0]) != null;
                    boolean inSrc = srcContainer.getSubLevel(subUuidRef[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] bounce-suppress: inDst={} inSrc={}", inDst, inSrc);
                    if (inSrc || !inDst) {
                        helper.fail("suppressed sub bounced straight back through the portal (inDst=" + inDst + ", inSrc=" + inSrc + ")");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void selfHeal_destinationLoadFailure_restoresSubToSource(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] subUuidRef = new UUID[1];

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
                    subUuidRef[0] = sub.getUniqueId();

                    BitSet occ = dstContainer.getOccupancy();
                    BitSet saved = (BitSet) occ.clone();
                    int side = 1 << dstContainer.getLogSideLength();
                    occ.set(0, side * side);

                    SableBridge.Moved result;
                    try {
                        result = SableBridge.moveAcrossDimensions(sub, srcLevel, dstLevel,
                                new Vec3(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5));
                    } finally {
                        occ.clear();
                        occ.or(saved);
                    }

                    if (result != null) {
                        helper.fail("move should have failed with no free destination plot, but returned a sub");
                        return;
                    }

                    boolean inSrc = srcContainer.getSubLevel(subUuidRef[0]) != null;
                    boolean inDst = dstContainer.getSubLevel(subUuidRef[0]) != null
                            || dstContainer.getHoldingChunkMap().getHoldingSubLevel(subUuidRef[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] self-heal: inSrc={} inDst={}", inSrc, inDst);

                    if (!inSrc) {
                        helper.fail("sub should be restored to source after a failed destination load, but it vanished");
                        return;
                    }
                    if (inDst) {
                        helper.fail("sub should not be present in destination after a failed move");
                        return;
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void sableBridge_tallShipAboveDestinationRange_shiftsDownAndFits(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) { helper.fail("containers"); return; }

        UUID[] subUuidRef = new UUID[1];

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos base = helper.absolutePos(new BlockPos(7, 4, 7));
                    BlockPos anchor = new BlockPos(base.getX(), 100, base.getZ());
                    BlockPos top = anchor.above(150);
                    srcLevel.setBlock(anchor, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    srcLevel.setBlock(top, Blocks.OBSIDIAN.defaultBlockState(), 3);

                    BoundingBox3i bounds = new BoundingBox3i(
                            anchor.getX() - 1, anchor.getY() - 1, anchor.getZ() - 1,
                            top.getX() + 1, top.getY() + 1, top.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, anchor, List.of(anchor, top), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    subUuidRef[0] = sub.getUniqueId();

                    SableBridge.Moved moved = SableBridge.moveAcrossDimensions(sub, srcLevel, dstLevel,
                            new Vec3(anchor.getX() + 0.5, 128.0, anchor.getZ() + 0.5));
                    if (moved == null) {
                        helper.fail("150-block-tall sub should fit the nether after a section shift, but the move was aborted");
                        return;
                    }

                    ServerSubLevel arrived = (ServerSubLevel) dstContainer.getSubLevel(subUuidRef[0]);
                    if (arrived == null) {
                        helper.fail("sub not present in destination container after successful move");
                        return;
                    }
                    BoundingBox3i plotBounds = new BoundingBox3i(arrived.getPlot().getBoundingBox());
                    AeroPortals.LOGGER.info("[AeroPortals/test] tall-ship shift: dst plot bounds y [{}, {}]",
                            plotBounds.minY(), plotBounds.maxY());
                    if (plotBounds.maxY() - plotBounds.minY() != 150) {
                        helper.fail("plot content height changed across the move: y span " + (plotBounds.maxY() - plotBounds.minY()));
                        return;
                    }
                    if (plotBounds.minY() < dstLevel.getMinBuildHeight() || plotBounds.maxY() >= dstLevel.getMaxBuildHeight()) {
                        helper.fail("plot content [" + plotBounds.minY() + ", " + plotBounds.maxY()
                                + "] is outside the destination build range after the shift");
                        return;
                    }

                    dstContainer.removeSubLevel(arrived, SubLevelRemovalReason.REMOVED);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void sableBridge_shipTallerThanDestination_abortsWithoutRemovingSub(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) { helper.fail("Nether not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos base = helper.absolutePos(new BlockPos(7, 4, 7));
                    BlockPos anchor = new BlockPos(base.getX(), 105, base.getZ());
                    BlockPos bottom = anchor.below(150);
                    BlockPos top = anchor.above(150);
                    srcLevel.setBlock(bottom, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    srcLevel.setBlock(anchor, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    srcLevel.setBlock(top, Blocks.OBSIDIAN.defaultBlockState(), 3);

                    BoundingBox3i bounds = new BoundingBox3i(
                            bottom.getX() - 1, bottom.getY() - 1, bottom.getZ() - 1,
                            top.getX() + 1, top.getY() + 1, top.getZ() + 1);
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                            srcLevel, anchor, List.of(bottom, anchor, top), bounds);
                    if (sub == null) { helper.fail("assemble failed"); return; }
                    UUID id = sub.getUniqueId();

                    SableBridge.Moved moved = SableBridge.moveAcrossDimensions(sub, srcLevel, dstLevel,
                            new Vec3(anchor.getX() + 0.5, 128.0, anchor.getZ() + 0.5));
                    if (moved != null) {
                        helper.fail("301-block-tall sub cannot fit a 256-block dimension; move should have been aborted");
                        return;
                    }

                    SubLevel stillThere = srcContainer.getSubLevel(id);
                    if (stillThere != sub) {
                        helper.fail("aborted move must leave the original sub instance untouched (no remove/restore cycle); got "
                                + stillThere);
                        return;
                    }

                    srcContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "aeroportalsIsolated")
    public static void portalDetector_failedTeleport_armsCooldown(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel endLevel = srcLevel.getServer().getLevel(Level.END);
        if (endLevel == null) { helper.fail("End not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer endContainer = SubLevelContainer.getContainer(endLevel);
        if (srcContainer == null || endContainer == null) { helper.fail("containers"); return; }

        ServerSubLevel[] subRef = new ServerSubLevel[1];

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
                    subRef[0] = sub;
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    ServerSubLevel sub = subRef[0];
                    UUID id = sub.getUniqueId();

                    helper.setBlock(new BlockPos(7, 4, 7), Blocks.END_PORTAL.defaultBlockState());

                    BitSet occ = endContainer.getOccupancy();
                    BitSet saved = (BitSet) occ.clone();
                    int side = 1 << endContainer.getLogSideLength();
                    occ.set(0, side * side);
                    try {
                        PortalDetector.scan(srcLevel);
                    } finally {
                        occ.clear();
                        occ.or(saved);
                    }

                    long now = srcLevel.getServer().getTickCount();
                    SubLevel stillThere = srcContainer.getSubLevel(id);
                    if (stillThere != sub) {
                        helper.fail("failed teleport must leave the original sub instance in place (no remove/restore cycle); got " + stillThere);
                        return;
                    }
                    if (!PortalCooldown.isOnCooldown(id, now)) {
                        helper.fail("failed teleport attempt should arm the portal cooldown so it does not retry every scan");
                        return;
                    }

                    srcContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void endPortal_blockedLanding_raisesAboveTerrain(GameTestHelper helper) {
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel endLevel = srcLevel.getServer().getLevel(Level.END);
        if (endLevel == null) { helper.fail("End not loaded"); return; }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) { helper.fail("srcContainer"); return; }

        BlockPos landing = EndPortalLanding.SPAWN_POINT;

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
                    sub.updateBoundingBox();

                    endLevel.getChunk(landing.getX() >> 4, landing.getZ() >> 4);
                    try {
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dy = 0; dy <= 2; dy++) {
                                for (int dz = -2; dz <= 2; dz++) {
                                    endLevel.setBlock(landing.offset(dx, dy, dz),
                                            Blocks.END_STONE_BRICKS.defaultBlockState(), 3);
                                }
                            }
                        }

                        Vec3 blockedLanding = EndPortalLanding.landingPosition(sub);
                        Vec3 raised = PortalTeleport.raiseLandingUntilClear(endLevel, sub, blockedLanding);
                        AeroPortals.LOGGER.info("[AeroPortals/test] end-raised-landing: blocked={} raised={}",
                                blockedLanding, raised);

                        if (raised.y < blockedLanding.y + 3.0 - 1.0e-6) {
                            helper.fail("landing should be raised by 3 blocks to clear the bricks at y50-52; got y="
                                    + raised.y + " (was y=" + blockedLanding.y + ")");
                            return;
                        }
                        if (raised.x != blockedLanding.x || raised.z != blockedLanding.z) {
                            helper.fail("raise must only change Y; got " + raised + " from " + blockedLanding);
                            return;
                        }
                    } finally {
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dy = 0; dy <= 2; dy++) {
                                for (int dz = -2; dz <= 2; dz++) {
                                    endLevel.setBlock(landing.offset(dx, dy, dz),
                                            Blocks.AIR.defaultBlockState(), 3);
                                }
                            }
                        }
                        if (!sub.isRemoved() && srcContainer.getSubLevel(sub.getUniqueId()) != null) {
                            srcContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                        }
                    }
                })
                .thenSucceed();
    }
}
