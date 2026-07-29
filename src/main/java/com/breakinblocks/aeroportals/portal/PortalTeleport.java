package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.ArsNouveauCompat;
import com.breakinblocks.aeroportals.compat.CreateEnderGatewayCompat;
import com.breakinblocks.aeroportals.compat.CreateTeleportersCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.compat.DraconicEvolutionCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalBuilder;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import com.breakinblocks.aeroportals.util.YawMath;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PortalTeleport {
    private static final int DEST_CHUNK_RADIUS = 2;
    private static final int VANILLA_PORTAL_COOLDOWN_OVERRIDE = 300;
    private static final double MAX_RIDER_OFFSET_SQR = 1024.0 * 1024.0;
    private static final double RIDER_REACQUIRE_MARGIN = 1.0;

    public static final ConcurrentHashMap<UUID, Entity> lastMovedEntities = new ConcurrentHashMap<>();

    private PortalTeleport() {}

    public static void teleport(ServerLevel srcLevel, ServerSubLevel sub, PortalRect srcRect) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> dstKey = (srcLevel.dimension() == Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] destination dimension {} not loaded; aborting", dstKey.location());
            return;
        }

        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();

        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 srcPortalCenter = srcRect.centerWorld();
        Vec3 subOffsetFromPortal = srcWorld.subtract(srcPortalCenter);

        Vec3 scaledPortalCenter = clampToWorldBorder(dstLevel,
                new Vec3(srcPortalCenter.x * ratio, srcPortalCenter.y, srcPortalCenter.z * ratio));
        scaledPortalCenter = clampPortalCenterY(dstLevel, scaledPortalCenter, srcRect.height());

        BlockPos searchCenter = BlockPos.containing(scaledPortalCenter);
        ensureChunksLoaded(dstLevel, searchCenter);

        DestinationResolution resolved = resolveDestinationPortal(dstLevel, srcRect, searchCenter);
        if (resolved == null) {
            AeroPortals.LOGGER.error("[AeroPortals] could not resolve destination portal; aborting teleport");
            return;
        }
        PortalRect dstRect = resolved.rect();

        Vec3 dstPortalCenter = dstRect.centerWorld();
        Vec3 dstWorld = pushClearOfPortalPlane(sub, srcWorld, dstRect, dstPortalCenter.add(subOffsetFromPortal));

        AeroPortals.LOGGER.debug("[AeroPortals] nether teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, axis={} {}x{}, generated={})",
                srcLevel.dimension().location(), srcWorld, srcPortalCenter,
                dstKey.location(), dstPortalCenter, dstWorld,
                ratio, dstRect.axis(), dstRect.width(), dstRect.height(), resolved.generated());

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "nether");
    }

    public static void jumpOnboardNetherPortal(ServerLevel srcLevel, ServerSubLevel sub, PortalRect plotRect) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> dstKey = (srcLevel.dimension() == Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] onboard jump: destination dimension {} not loaded; aborting", dstKey.location());
            return;
        }

        double ratio = srcLevel.dimensionType().coordinateScale() / dstLevel.dimensionType().coordinateScale();

        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 localCenter = plotRect.centerWorld();
        Vector3d transformed = sub.logicalPose().transformPosition(new Vector3d(localCenter.x, localCenter.y, localCenter.z));
        Vec3 srcPortalCenter = new Vec3(transformed.x(), transformed.y(), transformed.z());
        Vec3 subOffsetFromPortal = srcWorld.subtract(srcPortalCenter);

        Vec3 scaledPortalCenter = clampToWorldBorder(dstLevel,
                new Vec3(srcPortalCenter.x * ratio, srcPortalCenter.y, srcPortalCenter.z * ratio));
        scaledPortalCenter = clampPortalCenterY(dstLevel, scaledPortalCenter, plotRect.height());

        BlockPos searchCenter = BlockPos.containing(scaledPortalCenter);
        ensureChunksLoaded(dstLevel, searchCenter);

        DestinationResolution resolved = resolveDestinationPortal(dstLevel, plotRect, searchCenter);
        if (resolved == null) {
            AeroPortals.LOGGER.error("[AeroPortals] onboard jump: could not resolve destination portal; aborting");
            return;
        }
        PortalRect dstRect = resolved.rect();
        Vec3 dstWorld = pushClearOfPortalPlane(sub, srcWorld, dstRect, dstRect.centerWorld().add(subOffsetFromPortal));

        AeroPortals.LOGGER.debug("[AeroPortals] onboard jump: src dim={} subPos={} portalWorldCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, generated={})",
                srcLevel.dimension().location(), srcWorld, srcPortalCenter,
                dstKey.location(), dstRect.centerWorld(), dstWorld, ratio, resolved.generated());

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "onboard-portal");
    }

    public static void teleportEnd(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        boolean goingToEnd = srcLevel.dimension() != Level.END;
        ResourceKey<Level> dstKey = goingToEnd ? Level.END : Level.OVERWORLD;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] end teleport: destination dim {} not loaded; aborting", dstKey.location());
            return;
        }

        Vec3 srcWorld = subWorldPos(sub.logicalPose());

        Vec3 dstWorld;
        if (goingToEnd) {
            ensureChunksLoaded(dstLevel, EndPortalLanding.PLATFORM_CENTRE);
            EndPortalLanding.ensurePlatform(dstLevel);
            dstWorld = clampToWorldBorder(dstLevel, EndPortalLanding.landingPosition(sub));
            AeroPortals.LOGGER.debug("[AeroPortals] end teleport (to End): src dim={} subPos={} portalBlock={} -> dst dim={} landing={} (platform-top y={})",
                    srcLevel.dimension().location(), srcWorld, srcPortalBlock,
                    dstKey.location(), dstWorld, EndPortalLanding.LANDING_Y);
        } else {
            dstWorld = clampToWorldBorder(dstLevel, overworldSpawnLanding(dstLevel, sub));
            AeroPortals.LOGGER.debug("[AeroPortals] end teleport (to Overworld): src dim={} subPos={} portalBlock={} -> dst dim={} landing={} (spawn={})",
                    srcLevel.dimension().location(), srcWorld, srcPortalBlock,
                    dstKey.location(), dstWorld, dstLevel.getSharedSpawnPos());
        }
        ensureChunksLoaded(dstLevel, BlockPos.containing(dstWorld));

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "end");
    }

    public static void teleportAether(ServerLevel srcLevel, ServerSubLevel sub, PortalRect srcRect) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> destKey = AetherCompat.destinationDimension();
        ResourceKey<Level> returnKey = AetherCompat.returnDimension();
        if (destKey == null || returnKey == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] aether teleport: compat returned null dimension keys; aborting");
            return;
        }
        ResourceKey<Level> dstKey = srcLevel.dimension().equals(destKey) ? returnKey : destKey;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] aether destination dim {} not loaded; aborting", dstKey.location());
            return;
        }

        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();

        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 srcPortalCenter = srcRect.centerWorld();
        Vec3 subOffsetFromPortal = srcWorld.subtract(srcPortalCenter);

        Vec3 scaledPortalCenter = clampToWorldBorder(dstLevel,
                new Vec3(srcPortalCenter.x * ratio, srcPortalCenter.y, srcPortalCenter.z * ratio));
        scaledPortalCenter = clampPortalCenterY(dstLevel, scaledPortalCenter, srcRect.height());

        BlockPos searchCenter = BlockPos.containing(scaledPortalCenter);
        ensureChunksLoaded(dstLevel, searchCenter);

        Block aetherPortalBlock = AetherCompat.portalBlock();
        DestinationResolution resolved = resolveAetherDestinationPortal(dstLevel, srcRect, searchCenter, aetherPortalBlock);
        if (resolved == null) {
            AeroPortals.LOGGER.error("[AeroPortals] aether teleport: could not resolve destination portal; aborting");
            return;
        }
        PortalRect dstRect = resolved.rect();
        Vec3 dstPortalCenter = dstRect.centerWorld();
        Vec3 dstWorld = pushClearOfPortalPlane(sub, srcWorld, dstRect, dstPortalCenter.add(subOffsetFromPortal));

        AeroPortals.LOGGER.debug("[AeroPortals] aether teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, axis={} {}x{}, generated={})",
                srcLevel.dimension().location(), srcWorld, srcPortalCenter,
                dstKey.location(), dstPortalCenter, dstWorld,
                ratio, dstRect.axis(), dstRect.width(), dstRect.height(), resolved.generated());

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, resolved.generated(), "aether");
    }

    private static DestinationResolution resolveAetherDestinationPortal(
            ServerLevel dstLevel, PortalRect srcRect, BlockPos searchCenter, Block aetherPortalBlock) {
        int radius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        Optional<PortalRect> existing = Optional.empty();
        if (radius > 0) {
            Optional<BlockPos> pos = AetherCompat.findClosestPortalPosition(dstLevel, searchCenter);
            existing = pos.map(p -> PortalGeom.measureFromBlock(dstLevel, p, aetherPortalBlock));
        }

        if (existing.isPresent()) {
            PortalRect rect = existing.get();
            boolean axisMatch = rect.axis() == srcRect.axis();
            boolean bigEnough = rect.width() >= srcRect.width() && rect.height() >= srcRect.height();
            boolean withinRadius = rect.minCorner().distSqr(searchCenter) <= (long) radius * radius;

            if (axisMatch && bigEnough && withinRadius) {
                AeroPortals.LOGGER.debug("[AeroPortals] linking to existing aether portal at {} (axis={} {}x{})",
                        rect.minCorner(), rect.axis(), rect.width(), rect.height());
                return new DestinationResolution(rect, false);
            }
            AeroPortals.LOGGER.debug("[AeroPortals] existing aether portal at {} unsuitable (axisMatch={}, bigEnough={}, withinRadius={}); will generate",
                    rect.minCorner(), axisMatch, bigEnough, withinRadius);
        }

        if (!AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get()) {
            AeroPortals.LOGGER.warn("[AeroPortals] no suitable aether destination portal and generation disabled; cannot resolve");
            return null;
        }

        BlockPos buildPos = chooseBuildOrigin(searchCenter, srcRect);
        BlockState portalState = aetherPortalBlock.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, srcRect.axis());
        AeroPortals.LOGGER.debug("[AeroPortals] generating matching aether portal at {} (axis={} {}x{})",
                buildPos, srcRect.axis(), srcRect.width(), srcRect.height());
        PortalRect generated = PortalBuilder.build(
                dstLevel, buildPos, srcRect.axis(), srcRect.width(), srcRect.height(),
                Blocks.GLOWSTONE, portalState);
        return new DestinationResolution(generated, true);
    }

    public static void teleportDeeperDarker(ServerLevel srcLevel, ServerSubLevel sub, PortalRect srcRect) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> destKey = DeeperAndDarkerCompat.destinationDimension();
        ResourceKey<Level> returnKey = DeeperAndDarkerCompat.returnDimension();
        if (destKey == null || returnKey == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] deeperdarker teleport: compat returned null dimension keys; aborting");
            return;
        }
        ResourceKey<Level> dstKey = srcLevel.dimension().equals(destKey) ? returnKey : destKey;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] deeperdarker destination dim {} not loaded; aborting", dstKey.location());
            return;
        }

        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();

        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 srcPortalCenter = srcRect.centerWorld();
        Vec3 subOffsetFromPortal = srcWorld.subtract(srcPortalCenter);

        Vec3 scaledPortalCenter = clampToWorldBorder(dstLevel,
                new Vec3(srcPortalCenter.x * ratio, srcPortalCenter.y, srcPortalCenter.z * ratio));
        scaledPortalCenter = clampPortalCenterY(dstLevel, scaledPortalCenter, srcRect.height());

        BlockPos searchCenter = BlockPos.containing(scaledPortalCenter);
        ensureChunksLoaded(dstLevel, searchCenter);

        Block ddPortalBlock = DeeperAndDarkerCompat.portalBlock();
        DestinationResolution resolved = resolveDeeperDarkerDestinationPortal(dstLevel, srcRect, searchCenter, ddPortalBlock);
        if (resolved == null) {
            AeroPortals.LOGGER.error("[AeroPortals] deeperdarker teleport: could not resolve destination portal; aborting");
            return;
        }
        PortalRect dstRect = resolved.rect();
        Vec3 dstPortalCenter = dstRect.centerWorld();
        Vec3 dstWorld = pushClearOfPortalPlane(sub, srcWorld, dstRect, dstPortalCenter.add(subOffsetFromPortal));

        AeroPortals.LOGGER.debug("[AeroPortals] deeperdarker teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, axis={} {}x{}, generated={})",
                srcLevel.dimension().location(), srcWorld, srcPortalCenter,
                dstKey.location(), dstPortalCenter, dstWorld,
                ratio, dstRect.axis(), dstRect.width(), dstRect.height(), resolved.generated());

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, resolved.generated(), "deeperdarker");
    }

    private static DestinationResolution resolveDeeperDarkerDestinationPortal(
            ServerLevel dstLevel, PortalRect srcRect, BlockPos searchCenter, Block ddPortalBlock) {
        int radius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        Optional<PortalRect> existing = Optional.empty();
        if (radius > 0) {
            Optional<BlockPos> pos = DeeperAndDarkerCompat.findClosestPortalPosition(dstLevel, searchCenter);
            existing = pos.map(p -> PortalGeom.measureFromBlock(dstLevel, p, ddPortalBlock));
        }

        if (existing.isPresent()) {
            PortalRect rect = existing.get();
            boolean axisMatch = rect.axis() == srcRect.axis();
            boolean bigEnough = rect.width() >= srcRect.width() && rect.height() >= srcRect.height();
            boolean withinRadius = rect.minCorner().distSqr(searchCenter) <= (long) radius * radius;

            if (axisMatch && bigEnough && withinRadius) {
                AeroPortals.LOGGER.debug("[AeroPortals] linking to existing deeperdarker portal at {} (axis={} {}x{})",
                        rect.minCorner(), rect.axis(), rect.width(), rect.height());
                return new DestinationResolution(rect, false);
            }
            AeroPortals.LOGGER.debug("[AeroPortals] existing deeperdarker portal at {} unsuitable (axisMatch={}, bigEnough={}, withinRadius={}); will generate",
                    rect.minCorner(), axisMatch, bigEnough, withinRadius);
        }

        if (!AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get()) {
            AeroPortals.LOGGER.warn("[AeroPortals] no suitable deeperdarker destination portal and generation disabled; cannot resolve");
            return null;
        }

        BlockPos buildPos = chooseBuildOrigin(searchCenter, srcRect);
        BlockState portalState = ddPortalBlock.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, srcRect.axis());
        AeroPortals.LOGGER.debug("[AeroPortals] generating matching deeperdarker portal at {} (axis={} {}x{})",
                buildPos, srcRect.axis(), srcRect.width(), srcRect.height());
        PortalRect generated = PortalBuilder.build(
                dstLevel, buildPos, srcRect.axis(), srcRect.width(), srcRect.height(),
                Blocks.REINFORCED_DEEPSLATE, portalState);
        return new DestinationResolution(generated, true);
    }

    public static void teleportArsNouveau(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        Optional<ArsNouveauCompat.Destination> destOpt = ArsNouveauCompat.readDestination(srcLevel, srcPortalBlock);
        if (destOpt.isEmpty()) {
            AeroPortals.LOGGER.debug("[AeroPortals] ars-nouveau portal at {} has no readable destination; skipping", srcPortalBlock);
            return;
        }
        ArsNouveauCompat.Destination dest = destOpt.get();
        ServerLevel dstLevel = server.getLevel(dest.dim());
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] ars-nouveau dest dim {} not loaded; aborting", dest.dim().location());
            return;
        }

        ensureChunksLoaded(dstLevel, dest.warpPos());
        Vec3 dstWorld = clampToWorldBorder(dstLevel, landingAboveBlock(sub, dest.warpPos()));

        AeroPortals.LOGGER.debug("[AeroPortals] ars-nouveau teleport: src dim={} portalBlock={} -> dst dim={} warpPos={} landing={}",
                srcLevel.dimension().location(), srcPortalBlock, dest.dim().location(), dest.warpPos(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "ars_nouveau");
    }

    public static void teleportDraconic(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        Optional<DraconicEvolutionCompat.Destination> destOpt = DraconicEvolutionCompat.readDestination(srcLevel, srcPortalBlock);
        if (destOpt.isEmpty()) {
            AeroPortals.LOGGER.debug("[AeroPortals] draconic portal at {} has no readable destination; skipping", srcPortalBlock);
            return;
        }
        DraconicEvolutionCompat.Destination dest = destOpt.get();
        ServerLevel dstLevel = server.getLevel(dest.dim());
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] draconic dest dim {} not loaded; aborting", dest.dim().location());
            return;
        }

        ensureChunksLoaded(dstLevel, dest.pos());
        Vec3 dstWorld = clampToWorldBorder(dstLevel, landingAboveBlock(sub, dest.pos()));

        AeroPortals.LOGGER.debug("[AeroPortals] draconic teleport: src dim={} portalBlock={} -> dst dim={} targetPos={} landing={}",
                srcLevel.dimension().location(), srcPortalBlock, dest.dim().location(), dest.pos(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "draconic");
    }

    public static void teleportEnderGateway(ServerLevel srcLevel, ServerSubLevel sub, PortalRect srcRect, BlockPos hitPos) {
        MinecraftServer server = srcLevel.getServer();
        ResourceKey<Level> dstKey = (srcLevel.dimension() == Level.END) ? Level.OVERWORLD : Level.END;
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] ender gateway: destination dimension {} not loaded; aborting", dstKey.location());
            return;
        }

        Optional<BlockPos> linked = CreateEnderGatewayCompat.readLinkedPos(srcLevel, hitPos);
        if (linked.isEmpty()) {
            BlockPos min = srcRect.minCorner();
            outer:
            for (int w = 0; w < srcRect.width() && linked.isEmpty(); w++) {
                for (int h = 0; h < srcRect.height(); h++) {
                    BlockPos candidate = srcRect.axis() == Direction.Axis.X
                            ? min.offset(w, h, 0)
                            : min.offset(0, h, w);
                    linked = CreateEnderGatewayCompat.readLinkedPos(srcLevel, candidate);
                    if (linked.isPresent()) break outer;
                }
            }
        }
        if (linked.isEmpty()) {
            AeroPortals.LOGGER.debug("[AeroPortals] ender gateway at {} is not linked; skipping", hitPos);
            return;
        }

        BlockPos linkedPos = linked.get();
        ensureChunksLoaded(dstLevel, linkedPos);
        if (!CreateEnderGatewayCompat.isPortalBlock(dstLevel.getBlockState(linkedPos))) {
            AeroPortals.LOGGER.warn("[AeroPortals] ender gateway at {} links to {} in {} but no gateway exists there; skipping",
                    hitPos, linkedPos, dstKey.location());
            return;
        }
        PortalRect dstRect = PortalGeom.measureFromBlock(dstLevel, linkedPos, CreateEnderGatewayCompat.portalBlock());
        if (dstRect == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] destination ender gateway at {} failed rect measurement; skipping", linkedPos);
            return;
        }

        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 subOffsetFromPortal = srcWorld.subtract(srcRect.centerWorld());
        Vec3 dstWorld = pushClearOfPortalPlane(sub, srcWorld, dstRect,
                clampToWorldBorder(dstLevel, dstRect.centerWorld().add(subOffsetFromPortal)));

        AeroPortals.LOGGER.debug("[AeroPortals] ender gateway teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={}",
                srcLevel.dimension().location(), srcWorld, srcRect.centerWorld(),
                dstKey.location(), dstRect.centerWorld(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "ender-gateway");
    }

    public static void teleportCreateTeleporters(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        Optional<CreateTeleportersCompat.Destination> destOpt = CreateTeleportersCompat.readDestination(srcLevel, srcPortalBlock);
        if (destOpt.isEmpty()) {
            AeroPortals.LOGGER.debug("[AeroPortals] create-teleporters portal at {} has no readable destination; skipping", srcPortalBlock);
            return;
        }
        CreateTeleportersCompat.Destination dest = destOpt.get();
        ServerLevel dstLevel = server.getLevel(dest.dim());
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] create-teleporters dest dim {} not loaded; aborting", dest.dim().location());
            return;
        }

        ensureChunksLoaded(dstLevel, dest.pos());
        Vec3 dstWorld = clampToWorldBorder(dstLevel, landingAboveBlock(sub, dest.pos()));

        AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
        for (ServerPlayer p : srcLevel.players()) {
            if (Sable.HELPER.getTrackingSubLevel(p) == sub) {
                p.getPersistentData().putInt("PortalTeleportCooldown", VANILLA_PORTAL_COOLDOWN_OVERRIDE);
            }
        }
        for (Entity e : srcLevel.getEntities((Entity) null, aabb)) {
            e.getPersistentData().putInt("PortalTeleportCooldown", VANILLA_PORTAL_COOLDOWN_OVERRIDE);
        }

        AeroPortals.LOGGER.debug("[AeroPortals] create-teleporters teleport: src dim={} portalBlock={} -> dst dim={} targetPos={} landing={}",
                srcLevel.dimension().location(), srcPortalBlock, dest.dim().location(), dest.pos(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "create_teleporters");
    }

    private static final double PORTAL_EXIT_CLEARANCE = 1.5;

    private static Vec3 pushClearOfPortalPlane(ServerSubLevel sub, Vec3 srcWorld, PortalRect dstRect, Vec3 dstWorld) {
        AABB aabb = AabbUtil.worldAabb(sub);
        boolean normalIsZ = dstRect.axis() == Direction.Axis.X;
        Vec3 dstPortalCenter = dstRect.centerWorld();
        double plane = normalIsZ ? dstPortalCenter.z : dstPortalCenter.x;
        double subCoord = normalIsZ ? dstWorld.z : dstWorld.x;
        double offMin = (normalIsZ ? aabb.minZ : aabb.minX) - (normalIsZ ? srcWorld.z : srcWorld.x);
        double offMax = (normalIsZ ? aabb.maxZ : aabb.maxX) - (normalIsZ ? srcWorld.z : srcWorld.x);
        double sign = Math.signum(subCoord - plane);
        if (sign == 0) sign = 1;

        double adjusted;
        if (sign > 0) {
            adjusted = Math.max(subCoord, plane + PORTAL_EXIT_CLEARANCE - offMin);
        } else {
            adjusted = Math.min(subCoord, plane - PORTAL_EXIT_CLEARANCE - offMax);
        }
        if (adjusted == subCoord) return dstWorld;

        AeroPortals.LOGGER.debug("[AeroPortals] pushed sub {} clear of destination portal plane along {} ({} -> {})",
                sub.getUniqueId(), normalIsZ ? "Z" : "X", subCoord, adjusted);
        return normalIsZ
                ? new Vec3(dstWorld.x, dstWorld.y, adjusted)
                : new Vec3(adjusted, dstWorld.y, dstWorld.z);
    }

    private static Vec3 landingAboveBlock(ServerSubLevel sub, BlockPos warpPos) {
        int targetMinY = warpPos.getY() + 1;
        AABB aabb = AabbUtil.worldAabb(sub);
        Vec3 subPos = subWorldPos(sub.logicalPose());
        double dstY = targetMinY - (aabb.minY - subPos.y);
        return new Vec3(warpPos.getX() + 0.5, dstY, warpPos.getZ() + 0.5);
    }

    public static int loadedSurfaceY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static Vec3 overworldSpawnLanding(ServerLevel overworld, ServerSubLevel sub) {
        BlockPos spawn = overworld.getSharedSpawnPos();
        int safeY = loadedSurfaceY(overworld, spawn.getX(), spawn.getZ());
        AABB aabb = AabbUtil.worldAabb(sub);
        Vec3 subPos = subWorldPos(sub.logicalPose());
        double dstY = (safeY + 1) - (aabb.minY - subPos.y);
        return new Vec3(spawn.getX() + 0.5, dstY, spawn.getZ() + 0.5);
    }

    public static void teleportToDimension(
            ServerLevel srcLevel,
            ServerSubLevel sub,
            ServerLevel dstLevel,
            Vec3 dstWorld,
            boolean validateLanding,
            String contextLabel) {
        executeChainMove(srcLevel, sub, dstLevel, dstWorld, validateLanding, contextLabel);
    }

    private static void executeChainMove(
            ServerLevel srcLevel,
            ServerSubLevel sub,
            ServerLevel dstLevel,
            Vec3 dstWorld,
            boolean validateLanding,
            String contextLabel) {
        MinecraftServer server = srcLevel.getServer();
        Vec3 srcWorld = subWorldPos(sub.logicalPose());
        Vec3 translation = dstWorld.subtract(srcWorld);

        Collection<ServerSubLevel> chainRaw = SubLevelHelper.getLoadingDependencyChain(sub);
        long currentTick = server.getTickCount();
        List<ServerSubLevel> chain = new ArrayList<>(chainRaw.size());
        for (ServerSubLevel s : chainRaw) {
            if (s.isRemoved()) continue;
            if (PortalCooldown.isOnCooldown(s.getUniqueId(), currentTick)) {
                AeroPortals.LOGGER.debug("[AeroPortals] chain member {} skipped (on cooldown)", s.getUniqueId());
                continue;
            }
            chain.add(s);
        }
        if (chain.isEmpty()) {
            AeroPortals.LOGGER.warn("[AeroPortals] {} teleport: chain empty after cooldown filter; aborting", contextLabel);
            return;
        }
        if (chain.size() > 1) {
            AeroPortals.LOGGER.debug("[AeroPortals] {} teleport: dependency chain after filter: {} SubLevels will travel together: {}",
                    contextLabel, chain.size(),
                    chain.stream().map(s -> s.getUniqueId() + "@" + subWorldPos(s.logicalPose())).toList());
        }

        List<PendingMove> pending = new ArrayList<>(chain.size());
        for (ServerSubLevel chainedSub : chain) {
            if (chainedSub.isRemoved()) continue;
            Vec3 chainedSrcPos = subWorldPos(chainedSub.logicalPose());
            Vec3 chainedDstPos = clampToWorldBorder(dstLevel, chainedSrcPos.add(translation));
            pending.add(new PendingMove(chainedSub, chainedSrcPos, chainedDstPos));
        }

        if (validateLanding) {
            boolean clearBlocks = AeroPortalsConfig.CLEAR_DESTINATION_BLOCKS.get();
            for (PendingMove pm : pending) {
                if (clearBlocks) {
                    int cleared = clearLandingSpace(dstLevel, pm.sub, pm.srcPos, pm.dstPos);
                    if (cleared > 0) {
                        AeroPortals.LOGGER.debug("[AeroPortals] {} teleport: cleared {} destination block(s) for sub {}",
                                contextLabel, cleared, pm.sub.getUniqueId());
                    }
                    continue;
                }
                Optional<BlockPos> blocker = validateLandingSpace(dstLevel, pm.sub, pm.srcPos, pm.dstPos);
                if (blocker.isPresent()) {
                    BlockPos blockerPos = blocker.get();
                    BlockState blockerState = dstLevel.getBlockState(blockerPos);
                    AeroPortals.LOGGER.warn("[AeroPortals] {} teleport aborted: landing for sub {} blocked at {} by {} (dstAabb origin {})",
                            contextLabel, pm.sub.getUniqueId(), blockerPos,
                            blockerState.getBlock(), pm.dstPos);
                    messageAbort(srcLevel, dstLevel, chain, blockerPos, blockerState);
                    return;
                }
            }
        }

        List<SubMovePlan> plans = new ArrayList<>(pending.size());
        for (PendingMove pm : pending) {
            List<RiderBinding> chainRiders = captureRiders(srcLevel, pm.sub);
            List<EntityRiderBinding> chainEntities = captureEntityRiders(srcLevel, pm.sub);
            AeroPortals.LOGGER.debug("[AeroPortals] sub {} in chain: captured {} player(s), {} entity rider(s); dst={}",
                    pm.sub.getUniqueId(), chainRiders.size(), chainEntities.size(), pm.dstPos);
            plans.add(new SubMovePlan(pm.sub, pm.srcPos, pm.dstPos, chainRiders, chainEntities));
        }

        Map<SubMovePlan, SableBridge.Moved> moved = new IdentityHashMap<>();
        for (SubMovePlan plan : plans) {
            SableBridge.Moved move = SableBridge.moveAcrossDimensions(plan.srcSub, srcLevel, dstLevel, plan.dstPos);
            if (move == null) {
                AeroPortals.LOGGER.error("[AeroPortals] SableBridge returned null for chained sub {}; chain may be partially broken", plan.srcSub.getUniqueId());
                continue;
            }
            moved.put(plan, move);
        }

        List<SubLevelTransferEvent.PlotMove> chainPlotMoves = new ArrayList<>(moved.size());
        for (SableBridge.Moved move : moved.values()) {
            chainPlotMoves.add(new SubLevelTransferEvent.PlotMove(
                    move.sub().getUniqueId(), move.oldRegionMin(), move.regionBlocks(), move.shift()));
        }

        for (Map.Entry<SubMovePlan, SableBridge.Moved> e : moved.entrySet()) {
            SubMovePlan plan = e.getKey();
            ServerSubLevel newSub = e.getValue().sub();
            forceClientSync(dstLevel, newSub, AeroPortalsConfig.CLEAR_VELOCITY_ON_ARRIVAL.get());

            Pose3dc newPose = newSub.logicalPose();
            float newYawBase = (float) YawMath.yawFromOrientation(newPose.orientation());
            Vec3 newSubPos = subWorldPos(newPose);
            Vec3 arrivalVelocity = subVelocity(newSub);

            for (RiderBinding rb : plan.riders) {
                ServerPlayer p = server.getPlayerList().getPlayer(rb.playerUuid());
                if (p == null) {
                    AeroPortals.LOGGER.warn("[AeroPortals] rider {} not online post-teleport; skipping", rb.playerUuid());
                    continue;
                }
                if (p.isPassenger()) {
                    p.stopRiding();
                }
                Vec3 worldFinal = newSubPos.add(rb.localOffset());
                float yaw = rb.yawDelta() + newYawBase;
                p.teleportTo(dstLevel, worldFinal.x, worldFinal.y, worldFinal.z,
                        Collections.<RelativeMovement>emptySet(), yaw, rb.pitch());
                p.setDeltaMovement(arrivalVelocity);
                p.hurtMarked = true;
                AeroPortals.LOGGER.debug("[AeroPortals] moved rider {} -> {} yaw={} pitch={} velocity={}",
                        p.getGameProfile().getName(), worldFinal, yaw, rb.pitch(), arrivalVelocity);
            }

            replayEntityRiders(srcLevel, dstLevel, newSub, plan.entityRiders, arrivalVelocity);
            DeferredClientSyncs.scheduleRetries(server.getTickCount(), dstLevel, newSub);
            DeferredRiderSettles.schedule(server.getTickCount(), dstLevel, newSub, plan.riders, plan.entityRiders);
            PortalCooldown.mark(newSub.getUniqueId(), server.getTickCount());
            PortalCooldown.suppressUntilLeftPortal(newSub.getUniqueId(), newSubPos);
            NeoForge.EVENT_BUS.post(new SubLevelTransferEvent(
                    newSub.getUniqueId(), newSub, srcLevel, dstLevel, translation,
                    e.getValue().shift(), chainPlotMoves));
        }

        AeroPortals.LOGGER.debug("[AeroPortals] {} teleport complete; moved {}/{} sub(s) from chain",
                contextLabel, moved.size(), plans.size());
    }

    private record PendingMove(ServerSubLevel sub, Vec3 srcPos, Vec3 dstPos) {}

    private record SubMovePlan(
            ServerSubLevel srcSub,
            Vec3 srcPos,
            Vec3 dstPos,
            List<RiderBinding> riders,
            List<EntityRiderBinding> entityRiders) {}

    static void forceClientSync(ServerLevel dstLevel, ServerSubLevel sub, boolean clearVelocity) {
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (dstContainer == null) return;
        PhysicsPipeline pipeline = dstContainer.physicsSystem().getPipeline();
        Pose3dc pose = sub.logicalPose();
        if (clearVelocity) {
            pipeline.resetVelocity(sub);
        }
        pipeline.teleport(sub, pose.position(), pose.orientation());
        AeroPortals.LOGGER.debug("[AeroPortals] forced client-sync for sub {} at {} (clearVelocity={})",
                sub.getUniqueId(), pose.position(), clearVelocity);
    }

    public static final class DeferredClientSyncs {
        private static final Set<Long> DELAYS_TICKS = new LinkedHashSet<>(List.of(10L, 30L));
        private static final List<Pending> pending = Collections.synchronizedList(new ArrayList<>());
        public static AtomicInteger fireCount = new AtomicInteger(0);

        private record Pending(long targetTick, ServerLevel level, ServerSubLevel sub) {}

        public static void scheduleRetries(long currentTick, ServerLevel level, ServerSubLevel sub) {
            for (long delay : DELAYS_TICKS) {
                pending.add(new Pending(currentTick + delay, level, sub));
            }
        }

        public static void tick(long currentTick) {
            synchronized (pending) {
                Iterator<Pending> it = pending.iterator();
                while (it.hasNext()) {
                    Pending p = it.next();
                    if (p.targetTick > currentTick) continue;
                    if (!p.sub.isRemoved()) {
                        forceClientSync(p.level, p.sub, false);
                        fireCount.incrementAndGet();
                    }
                    it.remove();
                }
            }
        }
    }

    public static final class DeferredRiderSettles {
        private static final long[] DELAYS_TICKS = {2L, 5L, 10L, 20L, 40L};
        private static final double DRIFT_THRESHOLD_SQR = 1.0;
        private static final double MAX_CORRECTION_DIST_SQR = 128.0 * 128.0;
        private static final List<PendingSettle> pending = Collections.synchronizedList(new ArrayList<>());

        private record PendingSettle(long targetTick, ServerLevel level, ServerSubLevel sub,
                                     List<RiderBinding> riders, List<EntityRiderBinding> entityRiders) {}

        public static void schedule(long currentTick, ServerLevel level, ServerSubLevel sub,
                                    List<RiderBinding> riders, List<EntityRiderBinding> entityRiders) {
            if (riders.isEmpty() && entityRiders.isEmpty()) return;
            for (long delay : DELAYS_TICKS) {
                pending.add(new PendingSettle(currentTick + delay, level, sub, riders, entityRiders));
            }
        }

        public static void tick(long currentTick) {
            synchronized (pending) {
                Iterator<PendingSettle> it = pending.iterator();
                while (it.hasNext()) {
                    PendingSettle p = it.next();
                    if (p.targetTick > currentTick) continue;
                    if (!p.sub.isRemoved()) {
                        settle(p);
                    }
                    it.remove();
                }
            }
        }

        private static void settle(PendingSettle p) {
            Vec3 subPos = subWorldPos(p.sub.logicalPose());
            AABB subBox = AabbUtil.worldAabb(p.sub).inflate(RIDER_REACQUIRE_MARGIN);
            Vec3 velocity = subVelocity(p.sub);
            MinecraftServer server = p.level.getServer();

            for (RiderBinding rb : p.riders) {
                ServerPlayer player = server.getPlayerList().getPlayer(rb.playerUuid());
                if (player == null || player.serverLevel() != p.level) continue;
                if (player.isSpectator() || player.isFallFlying() || player.getAbilities().flying) continue;
                Vec3 expected = subPos.add(rb.localOffset());
                if (!driftedOff(player.position(), expected, player.getBoundingBox(), subBox)) continue;
                player.teleportTo(p.level, expected.x, expected.y, expected.z,
                        Collections.<RelativeMovement>emptySet(), player.getYRot(), player.getXRot());
                player.setDeltaMovement(velocity);
                player.hurtMarked = true;
                player.fallDistance = 0.0f;
                AeroPortals.LOGGER.debug("[AeroPortals] settle correction: rider {} drifted off sub {} (at {}); moved back to {} with velocity {}",
                        player.getGameProfile().getName(), p.sub.getUniqueId(), player.position(), expected, velocity);
            }

            for (EntityRiderBinding b : p.entityRiders) {
                Entity e = p.level.getEntity(b.entityUuid());
                if (e == null || !e.isAlive()) continue;
                Vec3 expected = subPos.add(b.localOffset());
                if (!driftedOff(e.position(), expected, e.getBoundingBox(), subBox)) continue;
                e.teleportTo(expected.x, expected.y, expected.z);
                e.setDeltaMovement(velocity);
                e.hurtMarked = true;
                e.fallDistance = 0.0f;
                AeroPortals.LOGGER.debug("[AeroPortals] settle correction: entity {} ({}) drifted off sub {}; moved back to {} with velocity {}",
                        e.getType(), b.entityUuid(), p.sub.getUniqueId(), expected, velocity);
            }
        }

        private static boolean driftedOff(Vec3 actual, Vec3 expected, AABB riderBox, AABB subBox) {
            double distSqr = actual.distanceToSqr(expected);
            if (distSqr <= DRIFT_THRESHOLD_SQR) return false;
            if (distSqr > MAX_CORRECTION_DIST_SQR) return false;
            return !subBox.intersects(riderBox);
        }
    }

    private record DestinationResolution(PortalRect rect, boolean generated) {}

    private static DestinationResolution resolveDestinationPortal(ServerLevel dstLevel, PortalRect srcRect, BlockPos searchCenter) {
        int radius = AeroPortalsConfig.DEST_PORTAL_SEARCH_RADIUS.get();
        Optional<PortalRect> existing = radius > 0
                ? PortalGeom.findExistingPortal(dstLevel, searchCenter)
                : Optional.empty();

        if (existing.isPresent()) {
            PortalRect rect = existing.get();
            boolean axisMatch = rect.axis() == srcRect.axis();
            boolean bigEnough = rect.width() >= srcRect.width() && rect.height() >= srcRect.height();
            boolean withinRadius = rect.minCorner().distSqr(searchCenter) <= (long) radius * radius;

            if (axisMatch && bigEnough && withinRadius) {
                AeroPortals.LOGGER.debug("[AeroPortals] linking to existing destination portal at {} (axis={} {}x{})",
                        rect.minCorner(), rect.axis(), rect.width(), rect.height());
                return new DestinationResolution(rect, false);
            }
            AeroPortals.LOGGER.debug("[AeroPortals] existing dest portal at {} unsuitable (axisMatch={}, bigEnough={}, withinRadius={}); will generate",
                    rect.minCorner(), axisMatch, bigEnough, withinRadius);
        }

        if (!AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get()) {
            AeroPortals.LOGGER.warn("[AeroPortals] no suitable destination portal and generation disabled; cannot resolve");
            return null;
        }

        BlockPos buildPos = chooseBuildOrigin(searchCenter, srcRect);
        AeroPortals.LOGGER.debug("[AeroPortals] generating matching portal at {} (axis={} {}x{})",
                buildPos, srcRect.axis(), srcRect.width(), srcRect.height());
        PortalRect generated = PortalBuilder.build(dstLevel, buildPos, srcRect.axis(), srcRect.width(), srcRect.height());
        return new DestinationResolution(generated, true);
    }

    private static BlockPos chooseBuildOrigin(BlockPos searchCenter, PortalRect srcRect) {
        int halfW = srcRect.width() / 2;
        int halfH = srcRect.height() / 2;
        if (srcRect.axis() == Direction.Axis.X) {
            return new BlockPos(searchCenter.getX() - halfW, searchCenter.getY() - halfH, searchCenter.getZ());
        }
        return new BlockPos(searchCenter.getX(), searchCenter.getY() - halfH, searchCenter.getZ() - halfW);
    }

    private static Optional<BlockPos> validateLandingSpace(ServerLevel dstLevel, ServerSubLevel sub, Vec3 srcWorld, Vec3 dstWorld) {
        AABB srcAabb = AabbUtil.worldAabb(sub);
        Vec3 translation = dstWorld.subtract(srcWorld);
        AABB dstAabb = srcAabb.move(translation);

        int x0 = (int) Math.floor(dstAabb.minX);
        int y0 = (int) Math.floor(dstAabb.minY);
        int z0 = (int) Math.floor(dstAabb.minZ);
        int x1 = (int) Math.floor(dstAabb.maxX - 1.0e-6);
        int y1 = (int) Math.floor(dstAabb.maxY - 1.0e-6);
        int z1 = (int) Math.floor(dstAabb.maxZ - 1.0e-6);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    BlockState s = dstLevel.getBlockState(cursor);
                    if (s.isAir()) continue;
                    if (s.canBeReplaced()) continue;
                    if (!s.getFluidState().isEmpty()) continue;
                    if (s.is(Blocks.END_GATEWAY)) continue;
                    if (isPortalRelated(dstLevel, cursor)) continue;
                    return Optional.of(cursor.immutable());
                }
            }
        }
        return Optional.empty();
    }

    private static int clearLandingSpace(ServerLevel dstLevel, ServerSubLevel sub, Vec3 srcWorld, Vec3 dstWorld) {
        AABB srcAabb = AabbUtil.worldAabb(sub);
        Vec3 translation = dstWorld.subtract(srcWorld);
        AABB dstAabb = srcAabb.move(translation);

        int x0 = (int) Math.floor(dstAabb.minX);
        int y0 = (int) Math.floor(dstAabb.minY);
        int z0 = (int) Math.floor(dstAabb.minZ);
        int x1 = (int) Math.floor(dstAabb.maxX - 1.0e-6);
        int y1 = (int) Math.floor(dstAabb.maxY - 1.0e-6);
        int z1 = (int) Math.floor(dstAabb.maxZ - 1.0e-6);

        int cleared = 0;
        int unbreakable = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    BlockState s = dstLevel.getBlockState(cursor);
                    if (s.isAir()) continue;
                    if (s.canBeReplaced()) continue;
                    if (!s.getFluidState().isEmpty()) continue;
                    if (s.is(Blocks.END_GATEWAY)) continue;
                    if (isPortalRelated(dstLevel, cursor)) continue;
                    if (s.getDestroySpeed(dstLevel, cursor) < 0) {
                        unbreakable++;
                        continue;
                    }
                    dstLevel.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    cleared++;
                }
            }
        }
        if (unbreakable > 0) {
            AeroPortals.LOGGER.warn("[AeroPortals] {} unbreakable block(s) inside the landing area for sub {} could not be cleared",
                    unbreakable, sub.getUniqueId());
        }
        return cleared;
    }

    private static boolean isPortalRelated(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (PortalKind.ofBlock(state) != null) return true;
        if (!isPortalFrameMaterial(state)) return false;
        for (Direction d : Direction.values()) {
            if (PortalKind.ofBlock(level.getBlockState(pos.relative(d))) != null) return true;
        }
        return false;
    }

    private static boolean isPortalFrameMaterial(BlockState state) {
        return state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.GLOWSTONE)
                || state.is(Blocks.REINFORCED_DEEPSLATE);
    }

    private static Vec3 clampPortalCenterY(ServerLevel dstLevel, Vec3 portalCenter, int portalHeight) {
        DimensionType dim = dstLevel.dimensionType();
        int dimMinY = dim.minY();
        int playableMaxY = dimMinY + dim.logicalHeight() - 1;
        boolean hasRoofBedrock = dim.hasCeiling();
        int floorBuffer = hasRoofBedrock ? 6 : 1;
        int roofBuffer = hasRoofBedrock ? (5 + Math.max(1, portalHeight)) : 1;
        int safeMinY = dimMinY + floorBuffer;
        int safeMaxY = playableMaxY - roofBuffer;
        if (safeMaxY < safeMinY) safeMaxY = safeMinY;
        double clampedY = Math.max(safeMinY, Math.min(safeMaxY, portalCenter.y));
        if (clampedY != portalCenter.y) {
            AeroPortals.LOGGER.debug("[AeroPortals] clamped destination portal centre Y in {} from {} to {} (safe range [{}, {}])",
                    dstLevel.dimension().location(), portalCenter.y, clampedY, safeMinY, safeMaxY);
        }
        return new Vec3(portalCenter.x, clampedY, portalCenter.z);
    }

    private static Set<ServerPlayer> ridersOfChain(ServerLevel srcLevel, Collection<ServerSubLevel> chain) {
        Set<ServerPlayer> result = new HashSet<>();
        for (ServerSubLevel s : chain) {
            AABB subBox = AabbUtil.worldAabb(s).inflate(RIDER_REACQUIRE_MARGIN);
            for (ServerPlayer p : srcLevel.players()) {
                if (isAboard(p, s, subBox)) result.add(p);
            }
        }
        return result;
    }

    private static void messageAbort(ServerLevel srcLevel, ServerLevel dstLevel, Collection<ServerSubLevel> chain,
                                     BlockPos blockerPos, BlockState blockerState) {
        String blockName = blockerState.getBlock().getName().getString();
        Component msg = Component.literal(
                "AeroPortals: teleport cancelled - landing in " + dstLevel.dimension().location()
                        + " is blocked by " + blockName
                        + " at " + blockerPos.getX() + ", " + blockerPos.getY() + ", " + blockerPos.getZ()
                        + ". Clear that area on the destination side and try again.")
                .withStyle(ChatFormatting.YELLOW);
        Set<ServerPlayer> riders = ridersOfChain(srcLevel, chain);
        for (ServerPlayer p : riders) {
            p.sendSystemMessage(msg);
        }
    }

    private static List<EntityRiderBinding> captureEntityRiders(ServerLevel srcLevel, ServerSubLevel sub) {
        List<EntityRiderBinding> out = new ArrayList<>();
        Pose3dc pose = sub.logicalPose();
        float subYawNow = (float) YawMath.yawFromOrientation(pose.orientation());
        Vec3 subPos = subWorldPos(pose);
        AABB aabb = AabbUtil.worldAabb(sub).inflate(1.0);
        for (Entity e : srcLevel.getEntities((Entity) null, aabb)) {
            if (e instanceof Player) continue;
            if (EntitySubLevelUtil.shouldKick(e)) continue;
            Vec3 offset = e.position().subtract(subPos);
            if (offset.lengthSqr() > MAX_RIDER_OFFSET_SQR) {
                AeroPortals.LOGGER.debug("[AeroPortals] skipping entity {} ({}) - lives in SubLevel-local space, not a world rider (offset {})",
                        e.getType(), e.getUUID(), offset);
                continue;
            }
            float yawDelta = e.getYRot() - subYawNow;
            out.add(new EntityRiderBinding(e.getUUID(), offset, yawDelta, e.getXRot()));
            e.setPortalCooldown(VANILLA_PORTAL_COOLDOWN_OVERRIDE);
            AeroPortals.LOGGER.debug("[AeroPortals] capture entity {} ({}) offset-from-sub={}",
                    e.getType(), e.getUUID(), offset);
        }
        return out;
    }

    private static void replayEntityRiders(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel newSub,
                                           List<EntityRiderBinding> bindings, Vec3 arrivalVelocity) {
        if (bindings.isEmpty()) return;
        Pose3dc newPose = newSub.logicalPose();
        float newYawBase = (float) YawMath.yawFromOrientation(newPose.orientation());
        Vec3 newSubPos = subWorldPos(newPose);
        for (EntityRiderBinding b : bindings) {
            Entity e = srcLevel.getEntity(b.entityUuid());
            if (e == null) {
                AeroPortals.LOGGER.warn("[AeroPortals] entity rider {} no longer in source dim; skipping", b.entityUuid());
                continue;
            }
            Vec3 worldFinal = newSubPos.add(b.localOffset());
            float yaw = b.yawDelta() + newYawBase;
            DimensionTransition transition = new DimensionTransition(
                    dstLevel, worldFinal, arrivalVelocity, yaw, b.pitch(), DimensionTransition.DO_NOTHING);
            Entity newEntity = e.changeDimension(transition);
            if (newEntity != null) {
                lastMovedEntities.put(b.entityUuid(), newEntity);
                AeroPortals.LOGGER.debug("[AeroPortals] moved entity {} ({}) -> {} yaw={} velocity={}",
                        newEntity.getType(), b.entityUuid(), worldFinal, yaw, arrivalVelocity);
            } else {
                AeroPortals.LOGGER.warn("[AeroPortals] entity {} ({}) changeDimension returned null", e.getType(), b.entityUuid());
            }
        }
    }

    private static Vec3 subWorldPos(Pose3dc pose) {
        Vector3dc p = pose.position();
        return new Vec3(p.x(), p.y(), p.z());
    }

    static Vec3 subVelocity(ServerSubLevel sub) {
        if (sub.isRemoved()) return Vec3.ZERO;
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle == null) return Vec3.ZERO;
            Vector3d velocity = handle.getLinearVelocity(new Vector3d());
            return new Vec3(velocity.x, velocity.y, velocity.z).scale(1.0 / 20.0);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.debug("[AeroPortals] could not read velocity for sub {}; treating as stationary", sub.getUniqueId());
            return Vec3.ZERO;
        }
    }

    static boolean isAboard(ServerPlayer player, ServerSubLevel sub, AABB subBox) {
        if (Sable.HELPER.getTrackingSubLevel(player) == sub) return true;
        return Sable.HELPER.getLastTrackingSubLevel(player) == sub
                && subBox.intersects(player.getBoundingBox());
    }

    private static List<RiderBinding> captureRiders(ServerLevel srcLevel, ServerSubLevel sub) {
        List<RiderBinding> out = new ArrayList<>();
        Pose3dc pose = sub.logicalPose();
        float subYawNow = (float) YawMath.yawFromOrientation(pose.orientation());
        Vec3 subPos = subWorldPos(pose);
        AABB subBox = AabbUtil.worldAabb(sub).inflate(RIDER_REACQUIRE_MARGIN);
        for (ServerPlayer p : srcLevel.players()) {
            if (!isAboard(p, sub, subBox)) continue;
            Vec3 offset = p.position().subtract(subPos);
            float yawDelta = p.getYRot() - subYawNow;
            out.add(new RiderBinding(p.getUUID(), offset, yawDelta, p.getXRot()));
            p.setPortalCooldown(VANILLA_PORTAL_COOLDOWN_OVERRIDE);
            AeroPortals.LOGGER.debug("[AeroPortals] capture rider {} offset-from-sub={} yawDelta={}, suppressed vanilla portal travel",
                    p.getGameProfile().getName(), offset, yawDelta);
        }
        return out;
    }

    private static Vec3 clampToWorldBorder(ServerLevel level, Vec3 pos) {
        var border = level.getWorldBorder();
        double half = border.getSize() / 2.0;
        double cx = border.getCenterX();
        double cz = border.getCenterZ();
        double x = Math.max(cx - half, Math.min(cx + half, pos.x));
        double z = Math.max(cz - half, Math.min(cz + half, pos.z));
        return new Vec3(x, pos.y, z);
    }

    private static void ensureChunksLoaded(ServerLevel level, BlockPos center) {
        int cx = SectionPos.blockToSectionCoord(center.getX());
        int cz = SectionPos.blockToSectionCoord(center.getZ());
        for (int dx = -DEST_CHUNK_RADIUS; dx <= DEST_CHUNK_RADIUS; dx++) {
            for (int dz = -DEST_CHUNK_RADIUS; dz <= DEST_CHUNK_RADIUS; dz++) {
                level.getChunk(cx + dx, cz + dz);
            }
        }
    }
}
