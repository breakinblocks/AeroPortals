package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.SubLevelPreTransferEvent;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import com.breakinblocks.aeroportals.api.nbt.NbtFixContext;
import com.breakinblocks.aeroportals.api.nbt.NbtFixers;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.config.TravelMethods;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class ApiGameTests {
    private static final String EMPTY = "empty";
    private static final UUID SUB_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static NbtFixContext context(BlockPos shift) {
        return new NbtFixContext(SUB_UUID, Level.OVERWORLD, Level.NETHER, shift, Vec3.ZERO, null, 0);
    }

    private static CompoundTag beTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }

    @GameTest(batch = "api_nbtFixer_shiftsIntArrayLongAndCompoundPositions", template = EMPTY)
    public static void api_nbtFixer_shiftsIntArrayLongAndCompoundPositions(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag tag = beTag("aeroportals:fixer_probe_a");
        tag.putIntArray("Array", new int[]{1, 2, 3});
        tag.putLong("Packed", new BlockPos(1, 2, 3).asLong());
        CompoundTag nested = new CompoundTag();
        nested.putInt("X", 1);
        nested.putInt("Y", 2);
        nested.putInt("Z", 3);
        tag.put("Compound", nested);

        NbtFixers.blockPos("Array", "Packed", "Compound").fix(tag, context(new BlockPos(10, 20, 30)));

        int[] array = tag.getIntArray("Array");
        if (array[0] != 11 || array[1] != 22 || array[2] != 33) {
            helper.fail("int-array position not shifted, got [" + array[0] + "," + array[1] + "," + array[2] + "]");
            return;
        }
        BlockPos packed = BlockPos.of(tag.getLong("Packed"));
        if (!packed.equals(new BlockPos(11, 22, 33))) {
            helper.fail("packed-long position not shifted, got " + packed);
            return;
        }
        CompoundTag fixedNested = tag.getCompound("Compound");
        if (fixedNested.getInt("X") != 11 || fixedNested.getInt("Y") != 22 || fixedNested.getInt("Z") != 33) {
            helper.fail("compound position not shifted, got " + fixedNested);
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_nbtFixer_leavesPositionsAloneWhenPlotDidNotMove", template = EMPTY)
    public static void api_nbtFixer_leavesPositionsAloneWhenPlotDidNotMove(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag tag = beTag("aeroportals:fixer_probe_b");
        tag.putIntArray("Array", new int[]{1, 2, 3});

        NbtFixers.blockPos("Array").fix(tag, context(BlockPos.ZERO));

        int[] array = tag.getIntArray("Array");
        if (array[0] != 1 || array[1] != 2 || array[2] != 3) {
            helper.fail("position was shifted despite a zero plot shift");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_dimensionFixer_rewritesOnlyTheSourceDimension", template = EMPTY)
    public static void api_dimensionFixer_rewritesOnlyTheSourceDimension(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag tag = beTag("aeroportals:fixer_probe_c");
        tag.putString("Mine", "minecraft:overworld");
        tag.putString("Someone else's", "minecraft:the_end");

        NbtFixers.dimensionId("Mine", "Someone else's").fix(tag, context(BlockPos.ZERO));

        if (!tag.getString("Mine").equals("minecraft:the_nether")) {
            helper.fail("source dimension was not rewritten, got " + tag.getString("Mine"));
            return;
        }
        if (!tag.getString("Someone else's").equals("minecraft:the_end")) {
            helper.fail("an unrelated dimension was rewritten, got " + tag.getString("Someone else's"));
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_nestedFixer_descendsIntoChildCompounds", template = EMPTY)
    public static void api_nestedFixer_descendsIntoChildCompounds(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag tag = beTag("aeroportals:fixer_probe_d");
        CompoundTag components = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putIntArray("selected_pos", new int[]{1, 1, 1});
        components.put("create:click_to_link_data", data);
        tag.put("components", components);

        NbtFixers.nested("components.create:click_to_link_data", NbtFixers.blockPos("selected_pos"))
                .fix(tag, context(new BlockPos(5, 5, 5)));

        int[] pos = tag.getCompound("components").getCompound("create:click_to_link_data").getIntArray("selected_pos");
        if (pos[0] != 6 || pos[1] != 6 || pos[2] != 6) {
            helper.fail("nested position not shifted, got [" + pos[0] + "," + pos[1] + "," + pos[2] + "]");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_registeredFixerRunsForItsBlockEntityIdOnly", template = EMPTY)
    public static void api_registeredFixerRunsForItsBlockEntityIdOnly(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        AeroPortalsApi.registerNbtFixer("aeroportals:registry_probe", NbtFixers.blockPos("Pos"));

        CompoundTag matching = beTag("aeroportals:registry_probe");
        matching.putIntArray("Pos", new int[]{0, 0, 0});
        AeroPortalsApi.applyNbtFixers(matching, context(new BlockPos(4, 4, 4)));
        int[] shifted = matching.getIntArray("Pos");
        if (shifted[0] != 4 || shifted[1] != 4 || shifted[2] != 4) {
            helper.fail("registered fixer did not run for its own block entity id");
            return;
        }

        CompoundTag other = beTag("aeroportals:some_other_block_entity");
        other.putIntArray("Pos", new int[]{0, 0, 0});
        AeroPortalsApi.applyNbtFixers(other, context(new BlockPos(4, 4, 4)));
        int[] untouched = other.getIntArray("Pos");
        if (untouched[0] != 0 || untouched[1] != 0 || untouched[2] != 0) {
            helper.fail("registered fixer leaked onto an unrelated block entity id");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_builtinPortalTypesAreRegisteredAndMatchTheirBlocks", template = EMPTY)
    public static void api_builtinPortalTypesAreRegisteredAndMatchTheirBlocks(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (AeroPortalsApi.portalTypes().isEmpty()) {
            helper.fail("no portal types registered; built-in registration did not run");
            return;
        }
        AeroPortalType nether = AeroPortalsApi.findPortalType(Blocks.NETHER_PORTAL.defaultBlockState());
        if (nether == null || !nether.id().equals(AeroPortals.id("nether"))) {
            helper.fail("nether portal block did not resolve to the built-in nether portal type, got " + nether);
            return;
        }
        AeroPortalType end = AeroPortalsApi.findPortalType(Blocks.END_PORTAL.defaultBlockState());
        if (end == null || !end.id().equals(AeroPortals.id("end"))) {
            helper.fail("end portal block did not resolve to the built-in end portal type, got " + end);
            return;
        }
        if (AeroPortalsApi.isPortalBlock(Blocks.STONE.defaultBlockState())) {
            helper.fail("plain stone should not be a portal block");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "api_disabledTravelMethodsAreSkipped", template = EMPTY)
    public static void api_disabledTravelMethodsAreSkipped(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        List<? extends String> previous = AeroPortalsConfig.DISABLED_TRAVEL_METHODS.get();
        try {
            AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(List.of("nether", "AeroPortals:End"));
            if (AeroPortalsApi.findPortalType(Blocks.NETHER_PORTAL.defaultBlockState()) != null) {
                helper.fail("nether portal type was still consulted after being disabled in the config");
                return;
            }
            if (AeroPortalsApi.findPortalType(Blocks.END_PORTAL.defaultBlockState()) != null) {
                helper.fail("end portal type was still consulted after being disabled by its full id");
                return;
            }

            AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(List.of("nether"));
            if (AeroPortalsApi.findPortalType(Blocks.END_PORTAL.defaultBlockState()) == null) {
                helper.fail("end portal type stayed disabled after being removed from the config list");
                return;
            }
            if (TravelMethods.isEnabled(TravelMethods.NETHER)) {
                helper.fail("nether travel method reported enabled while listed as disabled");
                return;
            }
            if (!TravelMethods.isEnabled(TravelMethods.PINA_COLADA)) {
                helper.fail("pina colada travel method reported disabled while not listed");
                return;
            }
        } finally {
            AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(previous);
        }

        if (AeroPortalsApi.findPortalType(Blocks.NETHER_PORTAL.defaultBlockState()) == null) {
            helper.fail("nether portal type did not come back after the config list was restored");
            return;
        }
        helper.succeed();
    }

    public static final class ProbePortalType implements AeroPortalType {
        static final AtomicBoolean armed = new AtomicBoolean(false);
        private static final ResourceLocation ID = AeroPortals.id("probe_portal");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean isEnabled() {
            return armed.get();
        }

        @Override
        public boolean matches(net.minecraft.world.level.block.state.BlockState state) {
            return state.is(Blocks.BOOKSHELF);
        }

        @Override
        public com.breakinblocks.aeroportals.api.PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
            return null;
        }
    }

    @GameTest(batch = "api_addonPortalTypeIsDiscoveredOnlyWhileEnabled", template = EMPTY)
    public static void api_addonPortalTypeIsDiscoveredOnlyWhileEnabled(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        AeroPortalsApi.registerPortal(new ProbePortalType());

        ProbePortalType.armed.set(false);
        if (AeroPortalsApi.isPortalBlock(Blocks.BOOKSHELF.defaultBlockState())) {
            helper.fail("disabled portal type was still consulted");
            return;
        }

        ProbePortalType.armed.set(true);
        try {
            AeroPortalType found = AeroPortalsApi.findPortalType(Blocks.BOOKSHELF.defaultBlockState());
            if (found == null || !found.id().equals(AeroPortals.id("probe_portal"))) {
                helper.fail("registered addon portal type was not discovered, got " + found);
                return;
            }
        } finally {
            ProbePortalType.armed.set(false);
        }
        helper.succeed();
    }

    public static final class ProbeCarrier implements TransferCarrier<String> {
        static final AtomicBoolean armed = new AtomicBoolean(false);
        static final AtomicInteger captures = new AtomicInteger(0);
        static final AtomicInteger replays = new AtomicInteger(0);
        private static final ResourceLocation ID = AeroPortals.id("probe_carrier");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean isEnabled() {
            return armed.get();
        }

        @Override
        public String capture(ServerLevel srcLevel, ServerSubLevel sub) {
            captures.incrementAndGet();
            return sub.getUniqueId().toString();
        }

        @Override
        public void replay(ServerLevel dstLevel, ServerSubLevel newSub, String captured, BlockPos plotShift) {
            if (captured.equals(newSub.getUniqueId().toString())) {
                replays.incrementAndGet();
            } else {
                AeroPortals.LOGGER.error("[AeroPortals/test] probe carrier replayed against the wrong sub: {} vs {}",
                        captured, newSub.getUniqueId());
            }
        }
    }

    public static final class CancelListener {
        static final AtomicBoolean armed = new AtomicBoolean(false);
        static final AtomicInteger seen = new AtomicInteger(0);

        @SubscribeEvent
        public static void onPre(SubLevelPreTransferEvent event) {
            if (!armed.get()) return;
            seen.incrementAndGet();
            event.cancel("probe listener veto");
        }
    }

    @GameTest(batch = "api_preTransferEventCanVetoATeleport", template = EMPTY, timeoutTicks = 200)
    public static void api_preTransferEventCanVetoATeleport(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) {
            helper.fail("Nether not loaded");
            return;
        }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) {
            helper.fail("no source container");
            return;
        }

        UUID[] subUuid = new UUID[1];
        CancelListener.seen.set(0);
        NeoForge.EVENT_BUS.register(CancelListener.class);

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
                    if (sub == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    subUuid[0] = sub.getUniqueId();
                    CancelListener.armed.set(true);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel,
                            new Vec3(worldPos.getX(), 100.0, worldPos.getZ()), false, "api-veto-probe");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    CancelListener.armed.set(false);
                    NeoForge.EVENT_BUS.unregister(CancelListener.class);
                    boolean stillInSrc = srcContainer.getSubLevel(subUuid[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] veto probe: listenerSaw={} stillInSrc={}",
                            CancelListener.seen.get(), stillInSrc);
                    if (CancelListener.seen.get() == 0) {
                        helper.fail("SubLevelPreTransferEvent never fired");
                        return;
                    }
                    if (!stillInSrc) {
                        helper.fail("sub left the source dimension even though the transfer was vetoed");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    private static boolean kubeJsAbsent(GameTestHelper helper) {
        if (net.neoforged.fml.ModList.get().isLoaded("kubejs")) return false;
        AeroPortals.LOGGER.info("[AeroPortals/test] KubeJS not loaded in this run; skipping the KubeJS probe");
        helper.succeed();
        return true;
    }

    @GameTest(batch = "kubejs_startupScriptRegistersWorkingNbtFixers", template = EMPTY)
    public static void kubejs_startupScriptRegistersWorkingNbtFixers(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (kubeJsAbsent(helper)) return;

        CompoundTag tag = beTag("aeroportals:kubejs_probe");
        tag.putIntArray("ProbePos", new int[]{1, 1, 1});
        tag.putString("ProbeDim", "minecraft:overworld");
        CompoundTag child = new CompoundTag();
        child.putIntArray("NestedPos", new int[]{2, 2, 2});
        tag.put("Child", child);

        AeroPortalsApi.applyNbtFixers(tag, context(new BlockPos(7, 7, 7)));

        int[] pos = tag.getIntArray("ProbePos");
        if (pos[0] != 8 || pos[1] != 8 || pos[2] != 8) {
            helper.fail("startup script's blockPosFixer did not run; got [" + pos[0] + "," + pos[1] + "," + pos[2] + "]");
            return;
        }
        if (!tag.getString("ProbeDim").equals("minecraft:the_nether")) {
            helper.fail("startup script's dimensionFixer did not run; got " + tag.getString("ProbeDim"));
            return;
        }
        int[] nested = tag.getCompound("Child").getIntArray("NestedPos");
        if (nested[0] != 9 || nested[1] != 9 || nested[2] != 9) {
            helper.fail("startup script's nestedBlockPosFixer did not run; got [" + nested[0] + "," + nested[1] + "," + nested[2] + "]");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "kubejs_startupScriptRegisteredPortalIsDiscovered", template = EMPTY)
    public static void kubejs_startupScriptRegisteredPortalIsDiscovered(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (kubeJsAbsent(helper)) return;

        AeroPortalType found = AeroPortalsApi.findPortalType(Blocks.JUKEBOX.defaultBlockState());
        if (found == null) {
            helper.fail("startup script registered a jukebox portal but no portal type claims that block");
            return;
        }
        if (!found.id().equals(AeroPortals.id("kubejs"))) {
            helper.fail("jukebox resolved to " + found.id() + " rather than the KubeJS portal delegate");
            return;
        }
        helper.succeed();
    }

    @GameTest(batch = "kubejs_serverScriptCanVetoATransfer", template = EMPTY, timeoutTicks = 200)
    public static void kubejs_serverScriptCanVetoATransfer(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        if (kubeJsAbsent(helper)) return;

        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) {
            helper.fail("Nether not loaded");
            return;
        }
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        if (srcContainer == null) {
            helper.fail("no source container");
            return;
        }

        UUID[] subUuid = new UUID[1];
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
                    if (sub == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    subUuid[0] = sub.getUniqueId();
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel,
                            new Vec3(worldPos.getX(), 100.0, worldPos.getZ()), false, "kubejs-veto-probe");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    boolean stillInSrc = srcContainer.getSubLevel(subUuid[0]) != null;
                    AeroPortals.LOGGER.info("[AeroPortals/test] kubejs veto probe: stillInSrc={}", stillInSrc);
                    if (!stillInSrc) {
                        helper.fail("server script vetoed the transfer but the sub left the source dimension anyway");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    @GameTest(batch = "api_transferCarrierCapturesAndReplaysAcrossTheMove", template = EMPTY, timeoutTicks = 200)
    public static void api_transferCarrierCapturesAndReplaysAcrossTheMove(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel srcLevel = helper.getLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(Level.NETHER);
        if (dstLevel == null) {
            helper.fail("Nether not loaded");
            return;
        }
        AeroPortalsApi.registerCarrier(new ProbeCarrier());
        ProbeCarrier.captures.set(0);
        ProbeCarrier.replays.set(0);

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
                    if (sub == null) {
                        helper.fail("assemble failed");
                        return;
                    }
                    ProbeCarrier.armed.set(true);
                    PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel,
                            new Vec3(worldPos.getX() / 8.0, 100.0, worldPos.getZ() / 8.0), false, "api-carrier-probe");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    ProbeCarrier.armed.set(false);
                    AeroPortals.LOGGER.info("[AeroPortals/test] carrier probe: captures={} replays={}",
                            ProbeCarrier.captures.get(), ProbeCarrier.replays.get());
                    if (ProbeCarrier.captures.get() == 0) {
                        helper.fail("transfer carrier was never asked to capture");
                        return;
                    }
                    if (ProbeCarrier.replays.get() == 0) {
                        helper.fail("transfer carrier was never replayed on the destination sub");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }
}
