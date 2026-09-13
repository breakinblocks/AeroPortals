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
public class PortalConstructionGameTests {
    private static ServerSubLevel ship(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
        helper.getLevel().setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3);
        return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), pos, List.of(pos),
                new BoundingBox3i(pos.getX()-1, pos.getY()-1, pos.getZ()-1,
                        pos.getX()+1, pos.getY()+1, pos.getZ()+1));
    }

    @GameTest(batch="review_bedrock", template="empty")
    public static void portalBuilderMustPreserveBedrock(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(7, 20, 7));
        helper.getLevel().setBlock(origin, Blocks.BEDROCK.defaultBlockState(), 3);
        PortalBuilder.build(helper.getLevel(), origin, Direction.Axis.X, 2, 3);
        helper.assertTrue(helper.getLevel().getBlockState(origin).is(Blocks.BEDROCK), "Portal fill overwrote bedrock despite clearing pass protection");
        helper.succeed();
    }

}
