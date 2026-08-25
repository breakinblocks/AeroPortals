package com.breakinblocks.aeroportals.api;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.nbt.BlockEntityNbtFixer;
import com.breakinblocks.aeroportals.api.nbt.NbtFixContext;
import com.breakinblocks.aeroportals.config.TravelMethods;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class AeroPortalsApi {
    private static final List<AeroPortalType> PORTAL_TYPES = new CopyOnWriteArrayList<>();
    private static final Map<String, List<BlockEntityNbtFixer>> NBT_FIXERS = new ConcurrentHashMap<>();
    private static final List<TransferCarrier<?>> CARRIERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong REGISTRY_GENERATION = new AtomicLong();

    private static volatile PortalScanPlan cachedPlan;
    private static volatile long planRegistryGeneration = -1L;
    private static volatile long planTravelGeneration = -1L;
    private static volatile int planEnabledHash;

    private AeroPortalsApi() {}

    public static void registerPortal(AeroPortalType type) {
        for (AeroPortalType existing : PORTAL_TYPES) {
            if (existing.id().equals(type.id())) {
                AeroPortals.LOGGER.warn("[AeroPortals] portal type {} already registered; ignoring duplicate", type.id());
                return;
            }
        }
        PORTAL_TYPES.add(type);
        PORTAL_TYPES.sort(Comparator.comparingInt(AeroPortalType::priority).reversed());
        REGISTRY_GENERATION.incrementAndGet();
        AeroPortals.LOGGER.debug("[AeroPortals] registered portal type {} (priority {})", type.id(), type.priority());
    }

    public static void registerCarrier(TransferCarrier<?> carrier) {
        for (TransferCarrier<?> existing : CARRIERS) {
            if (existing.id().equals(carrier.id())) {
                AeroPortals.LOGGER.warn("[AeroPortals] transfer carrier {} already registered; ignoring duplicate", carrier.id());
                return;
            }
        }
        CARRIERS.add(carrier);
        AeroPortals.LOGGER.debug("[AeroPortals] registered transfer carrier {}", carrier.id());
    }

    public static void registerNbtFixer(String blockEntityId, BlockEntityNbtFixer fixer) {
        NBT_FIXERS.computeIfAbsent(blockEntityId, k -> new CopyOnWriteArrayList<>()).add(fixer);
        AeroPortals.LOGGER.debug("[AeroPortals] registered NBT fixer for {}", blockEntityId);
    }

    public static void registerNbtFixer(Collection<String> blockEntityIds, BlockEntityNbtFixer fixer) {
        for (String id : blockEntityIds) {
            registerNbtFixer(id, fixer);
        }
    }

    public static List<AeroPortalType> portalTypes() {
        return List.copyOf(PORTAL_TYPES);
    }

    public static void invalidateScanPlan() {
        REGISTRY_GENERATION.incrementAndGet();
    }

    public static PortalScanPlan scanPlan() {
        long registryGeneration = REGISTRY_GENERATION.get();
        long travelGeneration = TravelMethods.generation();
        int enabledHash = enabledHash();

        PortalScanPlan plan = cachedPlan;
        if (plan != null
                && planRegistryGeneration == registryGeneration
                && planTravelGeneration == travelGeneration
                && planEnabledHash == enabledHash) {
            return plan;
        }

        plan = buildPlan();
        cachedPlan = plan;
        planRegistryGeneration = registryGeneration;
        planTravelGeneration = travelGeneration;
        planEnabledHash = enabledHash;
        return plan;
    }

    public static AeroPortalType findPortalType(BlockState state) {
        return scanPlan().match(state);
    }

    private static int enabledHash() {
        int hash = 1;
        for (AeroPortalType type : PORTAL_TYPES) {
            hash = hash * 31 + (isActive(type) ? 1 : 0);
        }
        return hash;
    }

    private static boolean isActive(AeroPortalType type) {
        try {
            return type.isEnabled() && TravelMethods.isEnabled(type.id());
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] portal type {} threw while reporting whether it is enabled", type.id(), e);
            return false;
        }
    }

    private static PortalScanPlan buildPlan() {
        Map<Block, List<AeroPortalType>> byBlock = new IdentityHashMap<>();
        List<AeroPortalType> unindexed = new ArrayList<>();

        for (AeroPortalType type : PORTAL_TYPES) {
            if (!isActive(type)) continue;

            Collection<Block> blocks;
            try {
                blocks = type.matchedBlocks();
            } catch (RuntimeException e) {
                AeroPortals.LOGGER.error("[AeroPortals] portal type {} threw while listing its blocks", type.id(), e);
                blocks = List.of();
            }

            if (blocks == null || blocks.isEmpty()) {
                unindexed.add(type);
                continue;
            }
            for (Block block : blocks) {
                if (block != null) byBlock.computeIfAbsent(block, k -> new ArrayList<>()).add(type);
            }
        }

        if (byBlock.isEmpty() && unindexed.isEmpty()) return PortalScanPlan.EMPTY;

        Map<Block, List<AeroPortalType>> indexed = new IdentityHashMap<>(byBlock.size());
        byBlock.forEach((block, types) -> indexed.put(block, List.copyOf(types)));
        return new PortalScanPlan(indexed, List.copyOf(unindexed));
    }

    public static boolean isPortalBlock(BlockState state) {
        return findPortalType(state) != null;
    }

    public static List<TransferCarrier<?>> carriers() {
        List<TransferCarrier<?>> enabled = new ArrayList<>(CARRIERS.size());
        for (TransferCarrier<?> carrier : CARRIERS) {
            if (carrier.isEnabled()) enabled.add(carrier);
        }
        return enabled;
    }

    public static boolean hasNbtFixers() {
        return !NBT_FIXERS.isEmpty();
    }

    public static void applyNbtFixers(CompoundTag blockEntityTag, NbtFixContext context) {
        String id = blockEntityTag.getString("id");
        if (id.isEmpty()) return;
        List<BlockEntityNbtFixer> fixers = NBT_FIXERS.get(id);
        if (fixers == null) return;
        for (BlockEntityNbtFixer fixer : fixers) {
            try {
                fixer.fix(blockEntityTag, context);
            } catch (RuntimeException e) {
                AeroPortals.LOGGER.error("[AeroPortals] NBT fixer for {} threw on sub {}", id, context.subUuid(), e);
            }
        }
    }
}
