package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.events.VanillaPortalCanceller;
import com.breakinblocks.aeroportals.portal.TransferTravelScope;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class RiderTransferGameTests {
    @GameTest(batch = "riderTransfer_onlyScopedTravelBypassesCancellation", template = "empty")
    public static void riderTransfer_onlyScopedTravelBypassesCancellation(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(7, 4, 7));
        level.setBlockAndUpdate(origin, Blocks.STONE.defaultBlockState());
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, origin, List.of(origin),
                new BoundingBox3i(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                        origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1));
        helper.assertTrue(sub != null, "test ship must assemble");
        ServerPlayer rider = player(level, "scope-rider");
        ServerPlayer other = player(level, "scope-other");
        ((EntityMovementExtension) rider).sable$setTrackingSubLevel(sub);
        ((EntityMovementExtension) other).sable$setTrackingSubLevel(sub);
        level.setBlockAndUpdate(origin, Blocks.NETHER_PORTAL.defaultBlockState());
        try {
            helper.assertTrue(canceled(rider), "ordinary portal travel must still be canceled");
            TransferTravelScope.allow(rider, () -> {
                helper.assertTrue(!canceled(rider), "our rider transfer must bypass cancellation");
                helper.assertTrue(canceled(other), "another rider must not inherit the bypass");
                try {
                    TransferTravelScope.allow(rider, () -> {
                        throw new IllegalStateException("scope cleanup probe");
                    });
                } catch (IllegalStateException expected) {
                    helper.assertTrue(!canceled(rider), "nested failure must preserve the outer scope");
                }
                return null;
            });
            helper.assertTrue(canceled(rider), "successful transfer scope must not remain active");
            try {
                TransferTravelScope.allow(rider, () -> {
                    throw new IllegalStateException("scope cleanup probe");
                });
            } catch (IllegalStateException expected) {
                helper.assertTrue(canceled(rider), "failed transfer scope must not remain active");
            }
            SubLevelContainer.getContainer(level).removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            helper.assertTrue(!canceled(rider), "stale tracking of a removed ship must not cancel travel");
        } finally {
            if (!sub.isRemoved()) {
                SubLevelContainer.getContainer(level).removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            }
            level.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
            VanillaPortalCanceller.cancelledFor.remove(rider.getUUID());
            VanillaPortalCanceller.cancelledFor.remove(other.getUUID());
        }
        helper.succeed();
    }

    private static ServerPlayer player(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault());
    }

    private static boolean canceled(ServerPlayer player) {
        EntityTravelToDimensionEvent event = new EntityTravelToDimensionEvent(player, Level.NETHER);
        VanillaPortalCanceller.onEntityTravelToDimension(event);
        return event.isCanceled();
    }
}
