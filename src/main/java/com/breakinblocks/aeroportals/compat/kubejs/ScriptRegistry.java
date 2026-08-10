package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.api.nbt.BlockEntityNbtFixer;
import com.breakinblocks.aeroportals.api.nbt.NbtFixContext;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ScriptRegistry {
    private static final Map<String, List<BlockEntityNbtFixer>> FIXERS = new ConcurrentHashMap<>();
    private static final Set<String> DELEGATED_IDS = ConcurrentHashMap.newKeySet();
    private static final List<ScriptPortal> PORTALS = new CopyOnWriteArrayList<>();
    private static volatile boolean portalDelegateInstalled = false;

    private ScriptRegistry() {}

    record ScriptPortal(String id, Set<ResourceLocation> blocks, PortalResolver resolver) {
        boolean matches(BlockState state) {
            return this.blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
    }

    static void reset() {
        FIXERS.clear();
        PORTALS.clear();
    }

    static void addFixer(String blockEntityId, BlockEntityNbtFixer fixer) {
        FIXERS.computeIfAbsent(blockEntityId, k -> new CopyOnWriteArrayList<>()).add(fixer);
        if (DELEGATED_IDS.add(blockEntityId)) {
            AeroPortalsApi.registerNbtFixer(blockEntityId, new Delegate(blockEntityId));
        }
    }

    static void addPortal(String id, List<String> blockIds, PortalResolver resolver) {
        Set<ResourceLocation> blocks = new LinkedHashSet<>();
        for (String blockId : blockIds) {
            ResourceLocation parsed = ResourceLocation.tryParse(blockId);
            if (parsed == null) {
                throw new IllegalArgumentException("'" + blockId + "' is not a valid block id");
            }
            if (!BuiltInRegistries.BLOCK.containsKey(parsed)) {
                AeroPortals.LOGGER.warn("[AeroPortals] KubeJS portal '{}' lists block '{}' which is not registered; it will never match",
                        id, blockId);
            }
            blocks.add(parsed);
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("portal '" + id + "' needs at least one block id");
        }
        PORTALS.add(new ScriptPortal(id, blocks, resolver));
        installPortalDelegate();
    }

    private static synchronized void installPortalDelegate() {
        if (portalDelegateInstalled) return;
        portalDelegateInstalled = true;
        AeroPortalsApi.registerPortal(new PortalDelegate());
    }

    static int fixerCount() {
        int total = 0;
        for (List<BlockEntityNbtFixer> list : FIXERS.values()) {
            total += list.size();
        }
        return total;
    }

    static int portalCount() {
        return PORTALS.size();
    }

    private record Delegate(String blockEntityId) implements BlockEntityNbtFixer {
        @Override
        public void fix(CompoundTag tag, NbtFixContext context) {
            List<BlockEntityNbtFixer> fixers = FIXERS.get(this.blockEntityId);
            if (fixers == null) return;
            for (BlockEntityNbtFixer fixer : fixers) {
                fixer.fix(tag, context);
            }
        }
    }

    private static final class PortalDelegate implements AeroPortalType {
        private static final ResourceLocation ID = AeroPortals.id("kubejs");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean isEnabled() {
            return !PORTALS.isEmpty();
        }

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public boolean matches(BlockState state) {
            for (ScriptPortal portal : PORTALS) {
                if (portal.matches(state)) return true;
            }
            return false;
        }

        @Override
        public PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
            BlockState state = srcLevel.getBlockState(hitPos);
            List<ScriptPortal> matching = new ArrayList<>(1);
            for (ScriptPortal portal : PORTALS) {
                if (portal.matches(state)) matching.add(portal);
            }
            for (ScriptPortal portal : matching) {
                PortalResolveContext context = new PortalResolveContext(srcLevel, sub, hitPos, portal.id());
                try {
                    portal.resolver().resolve(context);
                } catch (RuntimeException e) {
                    AeroPortals.LOGGER.error("[AeroPortals] KubeJS portal '{}' threw while resolving a destination at {}",
                            portal.id(), hitPos, e);
                    continue;
                }
                PortalDestination destination = context.toDestination();
                if (destination != null) return destination;
            }
            return null;
        }
    }
}
