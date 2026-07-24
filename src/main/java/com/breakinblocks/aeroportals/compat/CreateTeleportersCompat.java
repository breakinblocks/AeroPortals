package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CreateTeleportersCompat {
    public static final String MOD_ID = "createteleporters";
    private static final List<ResourceLocation> PORTAL_BLOCK_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "quantum_portal_block"),
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "custom_portal_on"),
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "custom_portal"));
    private static final ResourceLocation BASE_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "custom_portal_base");
    private static final int BASE_SEARCH_RADIUS = 33;
    private static final int MAX_PORTAL_HEIGHT = 64;

    private static volatile boolean initAttempted;
    private static volatile boolean initialized;
    private static volatile List<Block> portalBlocks = List.of();
    private static volatile Block baseBlock;

    private CreateTeleportersCompat() {}

    public static boolean isAvailable() {
        if (initialized) return true;
        if (initAttempted) return false;
        if (!ModList.get().isLoaded(MOD_ID)) {
            initAttempted = true;
            return false;
        }
        return tryInit();
    }

    private static synchronized boolean tryInit() {
        if (initAttempted) return initialized;
        initAttempted = true;
        Block base = BuiltInRegistries.BLOCK.get(BASE_BLOCK_ID);
        if (base == null || base.defaultBlockState().isAir()) {
            AeroPortals.LOGGER.warn("[AeroPortals] Create: Teleporters loaded but {} not in registry; compat disabled", BASE_BLOCK_ID);
            return false;
        }
        List<Block> portals = new ArrayList<>();
        for (ResourceLocation id : PORTAL_BLOCK_IDS) {
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block != null && !block.defaultBlockState().isAir()) {
                portals.add(block);
            }
        }
        if (portals.isEmpty()) {
            AeroPortals.LOGGER.warn("[AeroPortals] Create: Teleporters loaded but no portal interior blocks found; compat disabled");
            return false;
        }
        baseBlock = base;
        portalBlocks = List.copyOf(portals);
        initialized = true;
        AeroPortals.LOGGER.debug("[AeroPortals] Create: Teleporters compat initialized ({} portal block(s))", portals.size());
        return true;
    }

    public static boolean isPortalBlock(BlockState state) {
        if (!isAvailable()) return false;
        for (Block block : portalBlocks) {
            if (state.is(block)) return true;
        }
        return false;
    }

    public record Destination(ResourceKey<Level> dim, BlockPos pos) {}

    public static Optional<Destination> readDestination(ServerLevel level, BlockPos portalPos) {
        if (!isAvailable()) return Optional.empty();
        BlockEntity base = findBase(level, portalPos);
        if (base == null) {
            AeroPortals.LOGGER.debug("[AeroPortals] no custom portal base found near portal block at {}", portalPos);
            return Optional.empty();
        }

        CompoundTag pd = base.getPersistentData();
        if (pd.getBoolean("isLinked") && !pd.getString("linkedDim").isEmpty()) {
            return toDestination(pd.getString("linkedDim"), num(pd, "linkedX"), num(pd, "linkedY"), num(pd, "linkedZ"));
        }

        if (base instanceof Container container) {
            ItemStack stack = container.getItem(0);
            if (!stack.isEmpty()) {
                CompoundTag cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (!cd.getString("dimension").isEmpty()) {
                    return toDestination(cd.getString("dimension"), num(cd, "xpo"), num(cd, "ypo"), num(cd, "zpo"));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Destination> toDestination(String dimId, double x, double y, double z) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimId);
        if (dimLoc == null) return Optional.empty();
        return Optional.of(new Destination(
                ResourceKey.create(Registries.DIMENSION, dimLoc),
                BlockPos.containing(x, y, z)));
    }

    private static double num(CompoundTag tag, String key) {
        return tag.get(key) instanceof NumericTag n ? n.getAsDouble() : 0.0;
    }

    private static BlockEntity findBase(ServerLevel level, BlockPos portalPos) {
        BlockPos.MutableBlockPos cursor = portalPos.mutable();
        int guard = 0;
        while (isPortalBlock(level.getBlockState(cursor)) && guard++ < MAX_PORTAL_HEIGHT) {
            cursor.move(0, -1, 0);
        }
        int baseY = cursor.getY();

        BlockEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -BASE_SEARCH_RADIUS; dx <= BASE_SEARCH_RADIUS; dx++) {
                for (int dz = -BASE_SEARCH_RADIUS; dz <= BASE_SEARCH_RADIUS; dz++) {
                    probe.set(portalPos.getX() + dx, baseY + dy, portalPos.getZ() + dz);
                    if (!level.isLoaded(probe)) continue;
                    if (!level.getBlockState(probe).is(baseBlock)) continue;
                    double distSqr = probe.distSqr(portalPos);
                    if (distSqr < bestDistSqr) {
                        BlockEntity be = level.getBlockEntity(probe);
                        if (be != null) {
                            best = be;
                            bestDistSqr = distSqr;
                        }
                    }
                }
            }
        }
        return best;
    }
}
