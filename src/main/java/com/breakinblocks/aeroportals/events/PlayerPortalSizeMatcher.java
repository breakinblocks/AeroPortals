package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.PortalKind;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class PlayerPortalSizeMatcher {
    private static final int DEST_SCAN_RADIUS = 6;
    private static final int DEST_SCAN_HEIGHT = 21;

    private static final Map<UUID, Captured> pending = new ConcurrentHashMap<>();

    private record Captured(PortalKind kind, PortalRect rect) {}

    private PlayerPortalSizeMatcher() {}

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (!AeroPortalsConfig.MATCH_PLAYER_PORTAL_SIZE.get()) return;
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel srcLevel)) return;
        if (AeroPortals.sableLoaded) {
            SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
            if (tracking != null) return;
        }

        Captured captured = captureSourcePortal(srcLevel, player);
        if (captured != null) {
            pending.put(player.getUUID(), captured);
        } else {
            pending.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        Captured captured = pending.remove(event.getEntity().getUUID());
        if (captured == null) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel dstLevel)) return;

        matchDestinationPortal(dstLevel, player, captured);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        pending.remove(event.getEntity().getUUID());
    }

    private static Captured captureSourcePortal(ServerLevel level, ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(0.5);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    PortalKind kind = PortalKind.ofBlock(state);
                    if (kind == null) continue;
                    Block portalBlock = portalBlockFor(kind);
                    if (portalBlock == null) continue;
                    PortalRect rect = PortalGeom.measureFromBlock(level, cursor.immutable(), portalBlock);
                    if (rect != null) {
                        return new Captured(kind, rect);
                    }
                }
            }
        }
        return null;
    }

    private static void matchDestinationPortal(ServerLevel dstLevel, ServerPlayer player, Captured captured) {
        Block portalBlock = portalBlockFor(captured.kind());
        Block frameBlock = frameBlockFor(captured.kind());
        if (portalBlock == null || frameBlock == null) return;

        BlockPos found = findNearestPortalBlock(dstLevel, player.blockPosition(), portalBlock);
        if (found == null) return;

        PortalRect src = captured.rect();
        PortalRect dst = PortalGeom.measureFromBlock(dstLevel, found, portalBlock);
        if (dst == null) return;
        if (dst.width() >= src.width() && dst.height() >= src.height()) return;

        Direction.Axis axis = dst.axis();
        Vec3 center = dst.centerWorld();
        BlockPos buildOrigin = buildOriginFor(center, axis, src.width(), src.height());
        BlockState portalState = portalBlock.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);

        AeroPortals.LOGGER.debug("[AeroPortals] enlarging on-foot {} portal for {} from {}x{} to {}x{} at {}",
                captured.kind(), player.getGameProfile().getName(),
                dst.width(), dst.height(), src.width(), src.height(), buildOrigin);

        PortalRect built = PortalBuilder.build(dstLevel, buildOrigin, axis, src.width(), src.height(), frameBlock, portalState);

        Vec3 landing = built.centerWorld();
        player.connection.teleport(landing.x, built.minCorner().getY(), landing.z, player.getYRot(), player.getXRot());
    }

    private static BlockPos findNearestPortalBlock(ServerLevel level, BlockPos origin, Block portalBlock) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -DEST_SCAN_RADIUS; dx <= DEST_SCAN_RADIUS; dx++) {
            for (int dz = -DEST_SCAN_RADIUS; dz <= DEST_SCAN_RADIUS; dz++) {
                for (int dy = -3; dy <= DEST_SCAN_HEIGHT; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(cursor)) continue;
                    if (!level.getBlockState(cursor).is(portalBlock)) continue;
                    double dist = cursor.distSqr(origin);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos buildOriginFor(Vec3 center, Direction.Axis axis, int width, int height) {
        int halfW = width / 2;
        int halfH = height / 2;
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        if (axis == Direction.Axis.X) {
            return new BlockPos(cx - halfW, cy - halfH, cz);
        }
        return new BlockPos(cx, cy - halfH, cz - halfW);
    }

    private static Block portalBlockFor(PortalKind kind) {
        return switch (kind) {
            case NETHER -> Blocks.NETHER_PORTAL;
            case AETHER -> AetherCompat.portalBlock();
            case DEEPER_DARKER -> DeeperAndDarkerCompat.portalBlock();
            default -> null;
        };
    }

    private static Block frameBlockFor(PortalKind kind) {
        return switch (kind) {
            case NETHER -> Blocks.OBSIDIAN;
            case AETHER -> Blocks.GLOWSTONE;
            case DEEPER_DARKER -> Blocks.REINFORCED_DEEPSLATE;
            default -> null;
        };
    }
}
