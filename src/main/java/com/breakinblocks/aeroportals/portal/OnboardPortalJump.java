package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.config.TravelMethods;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OnboardPortalJump {
    private static final long STALE_STATE_TICKS = 6000L;

    private static final class State {
        boolean spent;
        long jumpAtTick;
        long lastSeenTick;
    }

    private static final Map<UUID, State> states = new HashMap<>();

    private OnboardPortalJump() {}

    public static void scan(ServerLevel level) {
        if (!AeroPortalsConfig.ONBOARD_PORTAL_JUMPS.get()) return;
        if (!TravelMethods.isEnabled(TravelMethods.NETHER)) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<ServerSubLevel> subs = container.getAllSubLevels();
        if (subs.isEmpty()) return;

        long now = level.getServer().getTickCount();
        double maxVolume = AeroPortalsConfig.MAX_SUBLEVEL_AABB_VOLUME.get();

        for (ServerSubLevel sub : List.copyOf(subs)) {
            if (sub.isRemoved()) continue;
            AABB aabb = AabbUtil.worldAabb(sub);
            if (aabb.getXsize() * aabb.getYsize() * aabb.getZsize() > maxVolume) continue;

            UUID id = sub.getUniqueId();
            BlockPos portalPos = findOnboardPortal(level, sub);
            if (portalPos == null) {
                State removed = states.remove(id);
                if (removed != null && !removed.spent) {
                    messageRiders(level, sub, "AeroPortals: onboard portal lost - jump aborted.");
                }
                continue;
            }

            State state = states.get(id);
            if (state == null) {
                state = new State();
                state.jumpAtTick = now + AeroPortalsConfig.ONBOARD_JUMP_DELAY_TICKS.get();
                state.lastSeenTick = now;
                states.put(id, state);
                long seconds = Math.max(1, (state.jumpAtTick - now) / 20);
                messageRiders(level, sub, "AeroPortals: onboard portal charged - jumping in about " + seconds
                        + "s. Break or extinguish the portal to stay.");
                continue;
            }
            state.lastSeenTick = now;
            if (state.spent) continue;
            if (PortalCooldown.isOnCooldown(id, now)) continue;
            if (now < state.jumpAtTick) continue;

            PortalRect rect = PortalGeom.measureFromBlock(level, portalPos);
            if (rect == null) {
                AeroPortals.LOGGER.warn("[AeroPortals] onboard portal on sub {} at plot pos {} failed rect measurement; jump skipped", id, portalPos);
                states.remove(id);
                continue;
            }
            state.spent = true;
            AeroPortals.LOGGER.debug("[AeroPortals] sub {} onboard portal jump firing (portal at plot pos {}, axis={} {}x{})",
                    id, portalPos, rect.axis(), rect.width(), rect.height());
            PortalTeleport.jumpOnboardNetherPortal(level, sub, rect);
        }

        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeenTick > STALE_STATE_TICKS) {
                it.remove();
            }
        }
    }

    private static BlockPos findOnboardPortal(ServerLevel level, ServerSubLevel sub) {
        for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = level.getChunk(holder.getPos().x, holder.getPos().z);
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section.hasOnlyAir()) continue;
                if (!section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL))) continue;
                int baseX = chunk.getPos().x << 4;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                int baseZ = chunk.getPos().z << 4;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.is(Blocks.NETHER_PORTAL)) {
                                return new BlockPos(baseX + x, baseY + y, baseZ + z);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void messageRiders(ServerLevel level, ServerSubLevel sub, String message) {
        Component text = Component.literal(message).withStyle(ChatFormatting.DARK_AQUA);
        for (ServerPlayer p : level.players()) {
            if (Sable.HELPER.getTrackingSubLevel(p) == sub) {
                p.sendSystemMessage(text);
            }
        }
    }
}
