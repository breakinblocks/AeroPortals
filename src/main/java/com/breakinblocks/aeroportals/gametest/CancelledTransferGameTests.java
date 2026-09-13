package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.*;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.*;
import com.breakinblocks.aeroportals.util.*;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class CancelledTransferGameTests {
    private static ServerSubLevel ship(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
        helper.getLevel().setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3);
        return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), pos, List.of(pos),
                new BoundingBox3i(pos.getX()-1, pos.getY()-1, pos.getZ()-1,
                        pos.getX()+1, pos.getY()+1, pos.getZ()+1));
    }

    @GameTest(batch="review_cancel", template="empty")
    public static void cancelledTransferMustNotClearDestination(GameTestHelper helper) {
        ServerSubLevel sub = ship(helper);
        ServerLevel dst = helper.getLevel().getServer().getLevel(Level.NETHER);
        BlockPos sourceOrigin = helper.absolutePos(new BlockPos(7, 4, 7));
        PortalRect rect = new PortalRect(new BlockPos(sourceOrigin.getX(), 80, sourceOrigin.getZ()), Direction.Axis.X, 2, 3);
        Vec3 center = rect.centerWorld();
        BlockPos destinationOrigin = new BlockPos((int)Math.floor(center.x / 8)-1, 80, (int)Math.floor(center.z / 8));
        dst.setBlock(destinationOrigin, Blocks.CHEST.defaultBlockState(), 3);
        int oldRadius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        java.util.function.Consumer<SubLevelPreTransferEvent> cancel = event -> {
            if(event.sub() == sub) event.cancel("review cancellation");
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.set(0);
        try {
            PortalTeleport.dispatch(helper.getLevel(), sub, PortalTeleport.resolveNether(helper.getLevel(), sub, rect));
            helper.assertTrue(!sub.isRemoved(), "Cancellation did not preserve source ship");
            helper.assertTrue(dst.getBlockState(destinationOrigin).is(Blocks.CHEST), "Cancelled transfer destroyed destination chest");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);
            AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.set(oldRadius);
        }
        helper.succeed();
    }
    @GameTest(batch="review_cancel_end", template="empty")
    public static void cancelledEndTransferMustNotRebuildPlatform(GameTestHelper helper) {
        ServerSubLevel sub = ship(helper);
        ServerLevel dst = helper.getLevel().getServer().getLevel(Level.END);
        BlockPos platform = EndPortalLanding.PLATFORM_CENTRE;
        BlockState original = dst.getBlockState(platform);
        dst.setBlock(platform, Blocks.CHEST.defaultBlockState(), 3);
        java.util.function.Consumer<SubLevelPreTransferEvent> cancel = event -> {
            if (event.sub() == sub) event.cancel("test cancellation");
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try {
            boolean moved = PortalTeleport.dispatchResult(helper.getLevel(), sub,
                    PortalTeleport.resolveEnd(helper.getLevel(), sub, helper.absolutePos(new BlockPos(7, 4, 7))));
            helper.assertTrue(!moved && !sub.isRemoved(), "Cancelled End transfer moved the ship");
            helper.assertTrue(dst.getBlockState(platform).is(Blocks.CHEST), "Cancelled transfer rebuilt the End platform");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);
            dst.setBlock(platform, original, 3);
        }
        helper.succeed();
    }
}
