package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.TropicraftCompat;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class PinaColadaDrinkHandler {
    private PinaColadaDrinkHandler() {}

    @SubscribeEvent
    public static void onFinishUseItem(LivingEntityUseItemEvent.Finish event) {
        if (!AeroPortals.sableLoaded) return;
        if (!TropicraftCompat.isAvailable()) return;

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player)) return;
        if (!TropicraftCompat.isPinaColada(event.getItem())) return;

        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        if (!(tracking instanceof ServerSubLevel sub) || sub.isRemoved()) return;

        ResourceKey<Level> dstKey = TropicraftCompat.tropicsDimension();
        if (dstKey == null) return;
        ServerLevel srcLevel = player.serverLevel();
        ServerLevel dstLevel = srcLevel.getServer().getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] pina colada hook: tropicraft dim {} not loaded; cannot teleport", dstKey.location());
            return;
        }
        if (srcLevel == dstLevel) {
            dstLevel = srcLevel.getServer().getLevel(Level.OVERWORLD);
            if (dstLevel == null) return;
        }

        Vec3 dstWorld = landingAtPlayerColumn(srcLevel, dstLevel, sub);
        AeroPortals.LOGGER.debug("[AeroPortals] pina colada hook: player {} on sub {} drank pina colada in {} -> teleporting to {} at {}",
                player.getGameProfile().getName(), sub.getUniqueId(),
                srcLevel.dimension().location(), dstLevel.dimension().location(), dstWorld);
        PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld, true, "pina_colada");
    }

    private static Vec3 landingAtPlayerColumn(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel sub) {
        double ratio = srcLevel.dimensionType().coordinateScale() / dstLevel.dimensionType().coordinateScale();
        double subX = sub.logicalPose().position().x();
        double subZ = sub.logicalPose().position().z();
        int dstX = (int) Math.round(subX * ratio);
        int dstZ = (int) Math.round(subZ * ratio);
        int surface = dstLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dstX, dstZ);
        int targetMinY = surface + 1;
        AABB aabb = AabbUtil.worldAabb(sub);
        double minYOffsetFromPose = aabb.minY - sub.logicalPose().position().y();
        double dstY = targetMinY - minYOffsetFromPose;
        return new Vec3(dstX + 0.5, dstY, dstZ + 0.5);
    }
}
