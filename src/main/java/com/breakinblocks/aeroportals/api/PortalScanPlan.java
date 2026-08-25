package com.breakinblocks.aeroportals.api;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class PortalScanPlan {
    static final PortalScanPlan EMPTY = new PortalScanPlan(Map.of(), List.of());

    private final Map<Block, List<AeroPortalType>> byBlock;
    private final List<AeroPortalType> unindexed;
    private final Predicate<BlockState> paletteFilter;

    PortalScanPlan(Map<Block, List<AeroPortalType>> byBlock, List<AeroPortalType> unindexed) {
        this.byBlock = byBlock;
        this.unindexed = unindexed;
        this.paletteFilter = unindexed.isEmpty()
                ? state -> byBlock.containsKey(state.getBlock())
                : state -> byBlock.containsKey(state.getBlock()) || matchUnindexed(state) != null;
    }

    public boolean isEmpty() {
        return byBlock.isEmpty() && unindexed.isEmpty();
    }

    public Predicate<BlockState> paletteFilter() {
        return paletteFilter;
    }

    public AeroPortalType match(BlockState state) {
        List<AeroPortalType> candidates = byBlock.get(state.getBlock());
        if (candidates != null) {
            for (int i = 0; i < candidates.size(); i++) {
                AeroPortalType type = candidates.get(i);
                if (matches(type, state)) return type;
            }
        }
        return matchUnindexed(state);
    }

    private AeroPortalType matchUnindexed(BlockState state) {
        for (int i = 0; i < unindexed.size(); i++) {
            AeroPortalType type = unindexed.get(i);
            if (matches(type, state)) return type;
        }
        return null;
    }

    private static boolean matches(AeroPortalType type, BlockState state) {
        try {
            return type.matches(state);
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] portal type {} threw while matching {}", type.id(), state, e);
            return false;
        }
    }
}
