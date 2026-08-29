package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class ShipRecovery {
    private static final long WAIT_TICKS = 200L;
    private static final int WAKE_CHUNK_RADIUS = 4;

    private static final List<Pending> pending = Collections.synchronizedList(new ArrayList<>());

    private record Pending(UUID uuid, ServerLevel level, Vec3 wakePos, Vec3 target, long deadlineTick,
                           Consumer<Component> feedback) {}

    private ShipRecovery() {}

    public static boolean start(ServerLevel level, UUID uuid, Vec3 target, Consumer<Component> feedback) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;

        ServerSubLevel loaded = (ServerSubLevel) container.getSubLevel(uuid);
        if (loaded != null && !loaded.isRemoved()) {
            bring(level, container, loaded, target);
            feedback.accept(Component.literal("Airship " + uuid + " brought to " + format(target))
                    .withStyle(ChatFormatting.GREEN));
            return true;
        }

        ShipDirectory.Entry entry = ShipDirectory.find(level, uuid);
        if (entry == null) return false;

        PortalTeleport.holdDestinationChunks(level, entry.position(), WAKE_CHUNK_RADIUS);
        level.getChunk(entry.chunk().x, entry.chunk().z);
        pending.add(new Pending(uuid, level, entry.position(), target,
                level.getServer().getTickCount() + WAIT_TICKS, feedback));
        feedback.accept(Component.literal("Waking airship " + uuid + " where it was stored at "
                + format(entry.position()) + "; it will be brought to you once it loads")
                .withStyle(ChatFormatting.AQUA));
        AeroPortals.LOGGER.info("[AeroPortals] recovery requested for airship {} stored at {} in {}",
                uuid, entry.position(), level.dimension().location());
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        long now = server.getTickCount();
        synchronized (pending) {
            Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                ServerSubLevelContainer container = SubLevelContainer.getContainer(p.level);
                if (container == null) {
                    it.remove();
                    continue;
                }
                ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(p.uuid);
                if (sub != null && !sub.isRemoved()) {
                    bring(p.level, container, sub, p.target);
                    p.feedback.accept(Component.literal("Airship " + p.uuid + " recovered to " + format(p.target))
                            .withStyle(ChatFormatting.GREEN));
                    AeroPortals.LOGGER.info("[AeroPortals] recovered airship {} to {} in {}",
                            p.uuid, p.target, p.level.dimension().location());
                    it.remove();
                    continue;
                }
                if (now >= p.deadlineTick) {
                    p.feedback.accept(Component.literal("Airship " + p.uuid + " did not load; its stored data is at "
                            + format(p.wakePos) + ". Check the server log for why it would not load.")
                            .withStyle(ChatFormatting.RED));
                    AeroPortals.LOGGER.warn("[AeroPortals] recovery timed out for airship {} stored at {} in {}",
                            p.uuid, p.wakePos, p.level.dimension().location());
                    it.remove();
                    continue;
                }
                PortalTeleport.holdDestinationChunks(p.level, p.wakePos, WAKE_CHUNK_RADIUS);
            }
        }
    }

    private static void bring(ServerLevel level, ServerSubLevelContainer container, ServerSubLevel sub, Vec3 target) {
        AABB box = AabbUtil.worldAabb(sub);
        Vec3 subPos = new Vec3(sub.logicalPose().position().x(), sub.logicalPose().position().y(),
                sub.logicalPose().position().z());
        Vec3 landing = new Vec3(target.x, target.y + (subPos.y - box.minY), target.z);
        landing = PortalTeleport.raiseLandingUntilClear(level, sub, landing);

        int radius = (int) Math.ceil((Math.max(box.getXsize(), box.getZsize()) / 2.0 + 16.0) / 16.0);
        PortalTeleport.holdDestinationChunks(level, landing, radius);

        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(sub);
        pipeline.teleport(sub, new Vector3d(landing.x, landing.y, landing.z), sub.logicalPose().orientation());
        PortalTeleport.forceClientSync(level, sub, true);
        PortalCooldown.mark(sub.getUniqueId(), level.getServer().getTickCount());
    }

    private static String format(Vec3 pos) {
        return String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
    }
}
