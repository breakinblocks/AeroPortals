package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.TelepastriesCompat;
import com.breakinblocks.aeroportals.portal.EndPortalLanding;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class TelepastriesCakeHandler {
    private TelepastriesCakeHandler() {}

    @SubscribeEvent
    public static void onRightClickCake(PlayerInteractEvent.RightClickBlock event) {
        if (!AeroPortals.sableLoaded) return;
        if (!TelepastriesCompat.isAvailable()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!TelepastriesCompat.isTeleCake(state)) return;

        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        if (!(tracking instanceof ServerSubLevel sub) || sub.isRemoved()) return;

        ResourceKey<Level> destKey = TelepastriesCompat.getCakeDestination(state);
        if (destKey == null) return;

        ServerLevel srcLevel = player.serverLevel();
        if (srcLevel.dimension().equals(destKey)) return;

        ServerLevel dstLevel = srcLevel.getServer().getLevel(destKey);
        if (dstLevel == null) {
            player.sendSystemMessage(Component.literal(
                    "AeroPortals: destination dimension " + destKey.location() + " is not loaded.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        if (!player.canEat(true)) return;

        TelepastriesCompat.eatSlice(srcLevel, event.getPos(), state, player);

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        Vec3 dstWorld = landingForCake(srcLevel, dstLevel, sub, destKey);
        AeroPortals.LOGGER.debug("[AeroPortals] telepastries hook: player {} on sub {} ate {} in {} -> teleporting to {} at {}",
                player.getGameProfile().getName(), sub.getUniqueId(),
                state.getBlock(), srcLevel.dimension().location(),
                destKey.location(), dstWorld);
        PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld, true,
                "telepastries:" + destKey.location().getPath());
    }

    private static Vec3 landingForCake(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel sub, ResourceKey<Level> destKey) {
        if (destKey.equals(Level.END)) {
            EndPortalLanding.ensurePlatform(dstLevel);
            return EndPortalLanding.landingPosition(sub);
        }
        double ratio = srcLevel.dimensionType().coordinateScale() / dstLevel.dimensionType().coordinateScale();
        double subX = sub.logicalPose().position().x();
        double subZ = sub.logicalPose().position().z();
        int dstX = (int) Math.round(subX * ratio);
        int dstZ = (int) Math.round(subZ * ratio);
        int surface = PortalTeleport.loadedSurfaceY(dstLevel, dstX, dstZ);
        int targetMinY = surface + 1;
        AABB aabb = AabbUtil.worldAabb(sub);
        double minYOffsetFromPose = aabb.minY - sub.logicalPose().position().y();
        double dstY = targetMinY - minYOffsetFromPose;
        return new Vec3(dstX + 0.5, dstY, dstZ + 0.5);
    }
}
