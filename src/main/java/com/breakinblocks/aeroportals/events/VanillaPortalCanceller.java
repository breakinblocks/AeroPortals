package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.breakinblocks.aeroportals.portal.PortalKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class VanillaPortalCanceller {
    private static final int COOLDOWN_AFTER_CANCEL = 300;

    public static final Set<UUID> cancelledFor = ConcurrentHashMap.newKeySet();

    private VanillaPortalCanceller() {}

    @SubscribeEvent
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!AeroPortals.sableLoaded) return;
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel srcLevel)) return;

        if (entity instanceof ServerPlayer player) {
            SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
            if (tracking != null && isOverlappingPortal(srcLevel, tracking)) {
                event.setCanceled(true);
                player.setPortalCooldown(COOLDOWN_AFTER_CANCEL);
                cancelledFor.add(player.getUUID());
                AeroPortals.LOGGER.info("[AeroPortals] cancelled vanilla portal for rider {} (SubLevel {} is at a portal)",
                        player.getGameProfile().getName(), tracking.getUniqueId());
            }
            return;
        }

        if (EntitySubLevelUtil.shouldKick(entity)) return;

        AABB entityBox = entity.getBoundingBox().inflate(0.5);
        BoundingBox3d sableBox = new BoundingBox3d(entityBox);
        for (SubLevel sub : (Iterable<SubLevel>) Sable.HELPER.getAllIntersecting(srcLevel, sableBox)) {
            if (sub.isRemoved()) continue;
            if (isOverlappingPortal(srcLevel, sub)) {
                event.setCanceled(true);
                entity.setPortalCooldown(COOLDOWN_AFTER_CANCEL);
                cancelledFor.add(entity.getUUID());
                AeroPortals.LOGGER.info("[AeroPortals] cancelled vanilla portal for {} ({}) - on/near SubLevel {} at a portal",
                        entity.getType(), entity.getUUID(), sub.getUniqueId());
                return;
            }
        }
    }

    private static boolean isOverlappingPortal(ServerLevel level, SubLevel sub) {
        AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
        int x0 = (int) Math.floor(aabb.minX);
        int y0 = (int) Math.floor(aabb.minY);
        int z0 = (int) Math.floor(aabb.minZ);
        int x1 = (int) Math.floor(aabb.maxX);
        int y1 = (int) Math.floor(aabb.maxY);
        int z1 = (int) Math.floor(aabb.maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) continue;
                    if (PortalKind.ofBlock(level.getBlockState(cursor)) != null) return true;
                }
            }
        }
        return false;
    }
}
