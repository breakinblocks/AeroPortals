package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class PortalPriorityGameTests {
    @GameTest(template = "empty")
    public static void unindexedAddonOverridesBuiltin(GameTestHelper helper) {
        AtomicBoolean enabled = new AtomicBoolean(true);
        ResourceLocation id = AeroPortals.id("priority_regression");
        AeroPortalsApi.registerPortal(new AeroPortalType() {
            public ResourceLocation id() { return id; }
            public int priority() { return 10000; }
            public boolean isEnabled() { return enabled.get(); }
            public boolean matches(BlockState state) { return state.is(Blocks.NETHER_PORTAL); }
            public PortalDestination resolve(ServerLevel level, ServerSubLevel sub, BlockPos pos) { return null; }
        });
        try {
            helper.assertTrue(AeroPortalsApi.findPortalType(Blocks.NETHER_PORTAL.defaultBlockState()).id().equals(id),
                    "Unindexed addon must retain its global priority");
        } finally {
            enabled.set(false);
            AeroPortalsApi.invalidateScanPlan();
        }
        helper.succeed();
    }
}
