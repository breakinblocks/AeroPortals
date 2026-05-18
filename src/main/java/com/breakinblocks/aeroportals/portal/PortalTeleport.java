package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.ArsNouveauCompat;
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
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
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
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

        BlockPos searchCenter = BlockPos.containing(scaledPortalCenter);
        ensureChunksLoaded(dstLevel, searchCenter);

        DestinationResolution resolved = resolveDestinationPortal(dstLevel, srcRect, searchCenter);
        if (resolved == null) {
            AeroPortals.LOGGER.error("[AeroPortals] could not resolve destination portal; aborting teleport");
            return;
        }
        PortalRect dstRect = resolved.rect();

        Vec3 dstPortalCenter = dstRect.centerWorld();
        Vec3 dstWorld = dstPortalCenter.add(subOffsetFromPortal);

        AeroPortals.LOGGER.info("[AeroPortals] nether teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, axis={} {}x{}, generated={})",
                srcLevel.dimension().location(), srcWorld, srcPortalCenter,
                dstKey.location(), dstPortalCenter, dstWorld,
                ratio, dstRect.axis(), dstRect.width(), dstRect.height(), resolved.generated());

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, resolved.generated(), "nether");
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
            AeroPortals.LOGGER.info("[AeroPortals] end teleport (to End): src dim={} subPos={} portalBlock={} -> dst dim={} landing={} (platform-top y={})",
                    srcLevel.dimension().location(), srcWorld, srcPortalBlock,
                    dstKey.location(), dstWorld, EndPortalLanding.LANDING_Y);
        } else {
            dstWorld = clampToWorldBorder(dstLevel, overworldSpawnLanding(dstLevel, sub));
            AeroPortals.LOGGER.info("[AeroPortals] end teleport (to Overworld): src dim={} subPos={} portalBlock={} -> dst dim={} landing={} (spawn={})",
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
        Vec3 dstWorld = dstPortalCenter.add(subOffsetFromPortal);

        AeroPortals.LOGGER.info("[AeroPortals] aether teleport: src dim={} subPos={} portalCenter={} -> dst dim={} portalCenter={} subPos={} (ratio={}, axis={} {}x{}, generated={})",
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
                AeroPortals.LOGGER.info("[AeroPortals] linking to existing aether portal at {} (axis={} {}x{})",
                        rect.minCorner(), rect.axis(), rect.width(), rect.height());
                return new DestinationResolution(rect, false);
            }
            AeroPortals.LOGGER.info("[AeroPortals] existing aether portal at {} unsuitable (axisMatch={}, bigEnough={}, withinRadius={}); will generate",
                    rect.minCorner(), axisMatch, bigEnough, withinRadius);
        }

        if (!AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get()) {
            AeroPortals.LOGGER.warn("[AeroPortals] no suitable aether destination portal and generation disabled; cannot resolve");
            return null;
        }

        BlockPos buildPos = chooseBuildOrigin(searchCenter, srcRect);
        BlockState portalState = aetherPortalBlock.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, srcRect.axis());
        AeroPortals.LOGGER.info("[AeroPortals] generating matching aether portal at {} (axis={} {}x{})",
                buildPos, srcRect.axis(), srcRect.width(), srcRect.height());
        PortalRect generated = PortalBuilder.build(
                dstLevel, buildPos, srcRect.axis(), srcRect.width(), srcRect.height(),
                Blocks.GLOWSTONE, portalState);
        return new DestinationResolution(generated, true);
    }

    public static void teleportArsNouveau(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        Optional<ArsNouveauCompat.Destination> destOpt = ArsNouveauCompat.readDestination(srcLevel, srcPortalBlock);
        if (destOpt.isEmpty()) {
            AeroPortals.LOGGER.info("[AeroPortals] ars-nouveau portal at {} has no readable destination; skipping", srcPortalBlock);
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

        AeroPortals.LOGGER.info("[AeroPortals] ars-nouveau teleport: src dim={} portalBlock={} -> dst dim={} warpPos={} landing={}",
                srcLevel.dimension().location(), srcPortalBlock, dest.dim().location(), dest.warpPos(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "ars_nouveau");
    }

    public static void teleportDraconic(ServerLevel srcLevel, ServerSubLevel sub, BlockPos srcPortalBlock) {
        MinecraftServer server = srcLevel.getServer();
        Optional<DraconicEvolutionCompat.Destination> destOpt = DraconicEvolutionCompat.readDestination(srcLevel, srcPortalBlock);
        if (destOpt.isEmpty()) {
            AeroPortals.LOGGER.info("[AeroPortals] draconic portal at {} has no readable destination; skipping", srcPortalBlock);
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

        AeroPortals.LOGGER.info("[AeroPortals] draconic teleport: src dim={} portalBlock={} -> dst dim={} targetPos={} landing={}",
                srcLevel.dimension().location(), srcPortalBlock, dest.dim().location(), dest.pos(), dstWorld);

        executeChainMove(srcLevel, sub, dstLevel, dstWorld, true, "draconic");
    }

    private static Vec3 landingAboveBlock(ServerSubLevel sub, BlockPos warpPos) {
        int targetMinY = warpPos.getY() + 1;
        AABB aabb = AabbUtil.worldAabb(sub);
        Vec3 subPos = subWorldPos(sub.logicalPose());
        double dstY = targetMinY - (aabb.minY - subPos.y);
        return new Vec3(warpPos.getX() + 0.5, dstY, warpPos.getZ() + 0.5);
    }

    private static Vec3 overworldSpawnLanding(ServerLevel overworld, ServerSubLevel sub) {
        BlockPos spawn = overworld.getSharedSpawnPos();
        int safeY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
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
            boolean clearLanding,
            String contextLabel) {
        executeChainMove(srcLevel, sub, dstLevel, dstWorld, clearLanding, contextLabel);
    }

    private static void executeChainMove(
            ServerLevel srcLevel,
            ServerSubLevel sub,
            ServerLevel dstLevel,
            Vec3 dstWorld,
            boolean clearLanding,
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
                AeroPortals.LOGGER.info("[AeroPortals] chain member {} skipped (on cooldown)", s.getUniqueId());
                continue;
            }
            chain.add(s);
        }
        if (chain.isEmpty()) {
            AeroPortals.LOGGER.warn("[AeroPortals] {} teleport: chain empty after cooldown filter; aborting", contextLabel);
            return;
        }
        if (chain.size() > 1) {
            AeroPortals.LOGGER.info("[AeroPortals] {} teleport: dependency chain after filter: {} SubLevels will travel together: {}",
                    contextLabel, chain.size(),
                    chain.stream().map(s -> s.getUniqueId() + "@" + subWorldPos(s.logicalPose())).toList());
        }

        List<SubMovePlan> plans = new ArrayList<>(chain.size());
        for (ServerSubLevel chainedSub : chain) {
            if (chainedSub.isRemoved()) continue;
            Vec3 chainedSrcPos = subWorldPos(chainedSub.logicalPose());
            Vec3 chainedDstPos = clampToWorldBorder(dstLevel, chainedSrcPos.add(translation));

            List<RiderBinding> chainRiders = captureRiders(srcLevel, chainedSub);
            List<EntityRiderBinding> chainEntities = captureEntityRiders(srcLevel, chainedSub);
            AeroPortals.LOGGER.info("[AeroPortals] sub {} in chain: captured {} player(s), {} entity rider(s); dst={}",
                    chainedSub.getUniqueId(), chainRiders.size(), chainEntities.size(), chainedDstPos);

            if (clearLanding) {
                clearLandingSpace(dstLevel, chainedSub, chainedSrcPos, chainedDstPos);
            }
            plans.add(new SubMovePlan(chainedSub, chainedSrcPos, chainedDstPos, chainRiders, chainEntities));
        }

        Map<SubMovePlan, ServerSubLevel> moved = new IdentityHashMap<>();
        for (SubMovePlan plan : plans) {
            ServerSubLevel newSub = SableBridge.moveAcrossDimensions(plan.srcSub, srcLevel, dstLevel, plan.dstPos);
            if (newSub == null) {
                AeroPortals.LOGGER.error("[AeroPortals] SableBridge returned null for chained sub {}; chain may be partially broken", plan.srcSub.getUniqueId());
                continue;
            }
            moved.put(plan, newSub);
        }

        for (Map.Entry<SubMovePlan, ServerSubLevel> e : moved.entrySet()) {
            SubMovePlan plan = e.getKey();
            ServerSubLevel newSub = e.getValue();
            Pose3dc newPose = newSub.logicalPose();
            float newYawBase = (float) YawMath.yawFromOrientation(newPose.orientation());
            Vec3 newSubPos = subWorldPos(newPose);

            for (RiderBinding rb : plan.riders) {
                ServerPlayer p = server.getPlayerList().getPlayer(rb.playerUuid());
                if (p == null) {
                    AeroPortals.LOGGER.warn("[AeroPortals] rider {} not online post-teleport; skipping", rb.playerUuid());
                    continue;
                }
                Vec3 worldFinal = newSubPos.add(rb.localOffset());
                float yaw = rb.yawDelta() + newYawBase;
                p.teleportTo(dstLevel, worldFinal.x, worldFinal.y, worldFinal.z,
                        Collections.<RelativeMovement>emptySet(), yaw, rb.pitch());
                AeroPortals.LOGGER.info("[AeroPortals] moved rider {} -> {} yaw={} pitch={}",
                        p.getGameProfile().getName(), worldFinal, yaw, rb.pitch());
            }

            replayEntityRiders(srcLevel, dstLevel, newSub, plan.entityRiders);
            forceClientSync(dstLevel, newSub);
            DeferredClientSyncs.scheduleRetries(server.getTickCount(), dstLevel, newSub);
            PortalCooldown.mark(newSub.getUniqueId(), server.getTickCount());
            NeoForge.EVENT_BUS.post(new SubLevelTransferEvent(
                    newSub.getUniqueId(), newSub, srcLevel, dstLevel, translation));
        }

        AeroPortals.LOGGER.info("[AeroPortals] {} teleport complete; moved {}/{} sub(s) from chain",
                contextLabel, moved.size(), plans.size());
    }

    private record SubMovePlan(
            ServerSubLevel srcSub,
            Vec3 srcPos,
            Vec3 dstPos,
            List<RiderBinding> riders,
            List<EntityRiderBinding> entityRiders) {}

    static void forceClientSync(ServerLevel dstLevel, ServerSubLevel sub) {
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (dstContainer == null) return;
        PhysicsPipeline pipeline = dstContainer.physicsSystem().getPipeline();
        Pose3dc pose = sub.logicalPose();
        pipeline.resetVelocity(sub);
        pipeline.teleport(sub, pose.position(), pose.orientation());
        AeroPortals.LOGGER.info("[AeroPortals] forced client-sync for sub {} at {}", sub.getUniqueId(), pose.position());
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
                        forceClientSync(p.level, p.sub);
                        fireCount.incrementAndGet();
                    }
                    it.remove();
                }
            }
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
                AeroPortals.LOGGER.info("[AeroPortals] linking to existing destination portal at {} (axis={} {}x{})",
                        rect.minCorner(), rect.axis(), rect.width(), rect.height());
                return new DestinationResolution(rect, false);
            }
            AeroPortals.LOGGER.info("[AeroPortals] existing dest portal at {} unsuitable (axisMatch={}, bigEnough={}, withinRadius={}); will generate",
                    rect.minCorner(), axisMatch, bigEnough, withinRadius);
        }

        if (!AeroPortalsConfig.GENERATE_MATCHING_PORTAL.get()) {
            AeroPortals.LOGGER.warn("[AeroPortals] no suitable destination portal and generation disabled; cannot resolve");
            return null;
        }

        BlockPos buildPos = chooseBuildOrigin(searchCenter, srcRect);
        AeroPortals.LOGGER.info("[AeroPortals] generating matching portal at {} (axis={} {}x{})",
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

    private static void clearLandingSpace(ServerLevel dstLevel, ServerSubLevel sub, Vec3 srcWorld, Vec3 dstWorld) {
        AABB srcAabb = AabbUtil.worldAabb(sub);
        Vec3 translation = dstWorld.subtract(srcWorld);
        AABB dstAabb = srcAabb.move(translation).inflate(1.5);

        int x0 = (int) Math.floor(dstAabb.minX);
        int y0 = (int) Math.floor(dstAabb.minY);
        int z0 = (int) Math.floor(dstAabb.minZ);
        int x1 = (int) Math.floor(dstAabb.maxX);
        int y1 = (int) Math.floor(dstAabb.maxY);
        int z1 = (int) Math.floor(dstAabb.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int cleared = 0;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    BlockState s = dstLevel.getBlockState(cursor);
                    if (s.isAir()) continue;
                    if (s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK)) continue;
                    dstLevel.setBlock(cursor, air, 3);
                    cleared++;
                }
            }
        }
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        int platformY = y0 - 1;
        int platformBlocks = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                cursor.set(x, platformY, z);
                BlockState below = dstLevel.getBlockState(cursor);
                if (below.is(Blocks.NETHER_PORTAL) || below.is(Blocks.OBSIDIAN) || below.is(Blocks.BEDROCK)) continue;
                if (!below.isAir() && !below.is(Blocks.LAVA) && !below.is(Blocks.WATER) && !below.is(Blocks.FIRE)) continue;
                dstLevel.setBlock(cursor, obsidian, 3);
                platformBlocks++;
            }
        }

        if (cleared > 0 || platformBlocks > 0) {
            AeroPortals.LOGGER.info("[AeroPortals] cleared {} block(s), placed {} platform block(s) below landing AABB {}",
                    cleared, platformBlocks, dstAabb);
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
            float yawDelta = e.getYRot() - subYawNow;
            out.add(new EntityRiderBinding(e.getUUID(), offset, yawDelta, e.getXRot()));
            e.setPortalCooldown(VANILLA_PORTAL_COOLDOWN_OVERRIDE);
            AeroPortals.LOGGER.info("[AeroPortals] capture entity {} ({}) offset-from-sub={}",
                    e.getType(), e.getUUID(), offset);
        }
        return out;
    }

    private static void replayEntityRiders(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel newSub, List<EntityRiderBinding> bindings) {
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
                    dstLevel, worldFinal, Vec3.ZERO, yaw, b.pitch(), DimensionTransition.DO_NOTHING);
            Entity newEntity = e.changeDimension(transition);
            if (newEntity != null) {
                lastMovedEntities.put(b.entityUuid(), newEntity);
                AeroPortals.LOGGER.info("[AeroPortals] moved entity {} ({}) -> {} yaw={}",
                        newEntity.getType(), b.entityUuid(), worldFinal, yaw);
            } else {
                AeroPortals.LOGGER.warn("[AeroPortals] entity {} ({}) changeDimension returned null", e.getType(), b.entityUuid());
            }
        }
    }

    private static Vec3 subWorldPos(Pose3dc pose) {
        Vector3dc p = pose.position();
        return new Vec3(p.x(), p.y(), p.z());
    }

    private static List<RiderBinding> captureRiders(ServerLevel srcLevel, ServerSubLevel sub) {
        List<RiderBinding> out = new ArrayList<>();
        Pose3dc pose = sub.logicalPose();
        float subYawNow = (float) YawMath.yawFromOrientation(pose.orientation());
        Vec3 subPos = subWorldPos(pose);
        for (ServerPlayer p : srcLevel.players()) {
            SubLevel tracking = Sable.HELPER.getTrackingSubLevel(p);
            if (tracking != sub) continue;
            Vec3 offset = p.position().subtract(subPos);
            float yawDelta = p.getYRot() - subYawNow;
            out.add(new RiderBinding(p.getUUID(), offset, yawDelta, p.getXRot()));
            p.setPortalCooldown(VANILLA_PORTAL_COOLDOWN_OVERRIDE);
            AeroPortals.LOGGER.info("[AeroPortals] capture rider {} offset-from-sub={} yawDelta={}, suppressed vanilla portal travel",
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
