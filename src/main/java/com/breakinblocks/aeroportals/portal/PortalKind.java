package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.ArsNouveauCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.compat.DraconicEvolutionCompat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public enum PortalKind {
    NETHER,
    END,
    ARS_NOUVEAU,
    AETHER,
    DRACONIC,
    DEEPER_DARKER;

    public static PortalKind ofBlock(BlockState state) {
        if (state.is(Blocks.NETHER_PORTAL)) return NETHER;
        if (state.is(Blocks.END_PORTAL)) return END;
        if (ArsNouveauCompat.isPortalBlock(state)) return ARS_NOUVEAU;
        if (AetherCompat.isPortalBlock(state)) return AETHER;
        if (DraconicEvolutionCompat.isPortalBlock(state)) return DRACONIC;
        if (DeeperAndDarkerCompat.isPortalBlock(state)) return DEEPER_DARKER;
        return null;
    }
}
