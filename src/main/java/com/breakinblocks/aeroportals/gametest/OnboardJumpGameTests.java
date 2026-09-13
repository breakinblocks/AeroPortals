package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class OnboardJumpGameTests {
    @GameTest(batch = "onboardJump_portalFreeIgnoresMatchingPortalSetting", template = "empty")
    public static void onboardJump_portalFreeIgnoresMatchingPortalSetting(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel level = helper.getLevel();
        ServerLevel destinationLevel = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(destinationLevel != null, "Nether must be available");
        BlockPos origin = helper.absolutePos(new BlockPos(7, 4, 7));
        level.setBlockAndUpdate(origin, Blocks.STONE.defaultBlockState());
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, origin, List.of(origin),
                new BoundingBox3i(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                        origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1));
        helper.assertTrue(sub != null, "test ship must assemble");
        boolean oldOnboard = AeroPortalsConfig.ONBOARD_GENERATE_DESTINATION_PORTAL.get();
        boolean oldMatching = AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get();
        int oldRadius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        try {
            BlockPos plotPos = sub.getPlot().getCenterBlock();
            PortalRect rect = new PortalRect(plotPos, Direction.Axis.X, 2, 3);
            Vector3d portalCenter = sub.logicalPose().transformPosition(new Vector3d(
                    rect.centerWorld().x, rect.centerWorld().y, rect.centerWorld().z));
            double ratio = level.dimensionType().coordinateScale() / destinationLevel.dimensionType().coordinateScale();
            BlockPos arrivalCenter = BlockPos.containing(portalCenter.x * ratio, portalCenter.y, portalCenter.z * ratio);
            Map<BlockPos, BlockState> before = new LinkedHashMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(arrivalCenter.offset(-4, -5, -4), arrivalCenter.offset(4, 5, 4))) {
                before.put(pos.immutable(), destinationLevel.getBlockState(pos));
            }
            AeroPortalsConfig.ONBOARD_GENERATE_DESTINATION_PORTAL.set(false);
            PortalDestination first = null;
            for (boolean matching : new boolean[]{false, true}) {
                AeroPortalsConfig.GENERATE_MATCHING_PORTAL.set(matching);
                PortalDestination destination = PortalTeleport.resolveOnboardNetherPortal(level, sub, rect);
                helper.assertTrue(destination != null, "portal-free arrival must resolve with matching generation " + matching);
                helper.assertTrue(destination.level() == destinationLevel, "onboard jump must resolve the other dimension");
                helper.assertTrue(destination.validateLanding(), "portal-free jumps must retain landing safety checks");
                double arrivedPortalX = destination.subWorldPos().x + portalCenter.x - sub.logicalPose().position().x();
                double arrivedPortalZ = destination.subWorldPos().z + portalCenter.z - sub.logicalPose().position().z();
                helper.assertTrue(Math.abs(arrivedPortalX - portalCenter.x * ratio) < 0.0001,
                        "portal-free arrival must scale the portal's world X coordinate");
                helper.assertTrue(Math.abs(arrivedPortalZ - portalCenter.z * ratio) < 0.0001,
                        "portal-free arrival must scale the portal's world Z coordinate");
                if (first != null) {
                    helper.assertTrue(first.subWorldPos().equals(destination.subWorldPos()),
                            "global matching generation must not change a portal-free destination");
                }
                first = destination;
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                helper.assertTrue(destinationLevel.getBlockState(entry.getKey()).equals(entry.getValue()),
                        "portal-free resolution must not build or clear destination blocks");
            }
            AeroPortalsConfig.ONBOARD_GENERATE_DESTINATION_PORTAL.set(true);
            AeroPortalsConfig.GENERATE_MATCHING_PORTAL.set(false);
            AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.set(0);
            helper.assertTrue(PortalTeleport.resolveOnboardNetherPortal(level, sub, rect) == null,
                    "matching-portal mode must preserve the generation-disabled behavior");
            helper.assertTrue(!PortalTeleport.jumpOnboardNetherPortal(level, sub, rect),
                    "an unresolved onboard jump must report failure to the charging state");
        } finally {
            AeroPortalsConfig.ONBOARD_GENERATE_DESTINATION_PORTAL.set(oldOnboard);
            AeroPortalsConfig.GENERATE_MATCHING_PORTAL.set(oldMatching);
            AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.set(oldRadius);
            if (!sub.isRemoved()) {
                SubLevelContainer.getContainer(level).removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            }
        }
        helper.succeed();
    }
}
