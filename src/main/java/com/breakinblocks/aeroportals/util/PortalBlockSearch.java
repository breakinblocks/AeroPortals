package com.breakinblocks.aeroportals.util;

import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalScanPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.function.Predicate;

public final class PortalBlockSearch {
    private PortalBlockSearch() {}

    public record Hit(BlockPos pos, AeroPortalType type) {}

    public static boolean any(ServerLevel level, AABB aabb) {
        PortalScanPlan plan = AeroPortalsApi.scanPlan();
        return !plan.isEmpty() && find(level, aabb, plan) != null;
    }

    public static Hit find(ServerLevel level, AABB aabb, PortalScanPlan plan) {
        int x0 = Mth.floor(aabb.minX);
        int x1 = Mth.floor(aabb.maxX);
        int z0 = Mth.floor(aabb.minZ);
        int z1 = Mth.floor(aabb.maxZ);
        int y0 = Math.max(Mth.floor(aabb.minY), level.getMinBuildHeight());
        int y1 = Math.min(Mth.floor(aabb.maxY), level.getMaxBuildHeight() - 1);
        if (y1 < y0) return null;

        Predicate<BlockState> paletteFilter = plan.paletteFilter();
        ServerChunkCache chunkSource = level.getChunkSource();

        for (int chunkX = x0 >> 4; chunkX <= x1 >> 4; chunkX++) {
            for (int chunkZ = z0 >> 4; chunkZ <= z1 >> 4; chunkZ++) {
                LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;

                int minX = Math.max(x0, chunkX << 4);
                int maxX = Math.min(x1, (chunkX << 4) + 15);
                int minZ = Math.max(z0, chunkZ << 4);
                int maxZ = Math.min(z1, (chunkZ << 4) + 15);
                LevelChunkSection[] sections = chunk.getSections();

                for (int sectionY = y0 >> 4; sectionY <= y1 >> 4; sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) continue;

                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) continue;
                    if (!section.maybeHas(paletteFilter)) continue;

                    int minY = Math.max(y0, sectionY << 4);
                    int maxY = Math.min(y1, (sectionY << 4) + 15);
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                AeroPortalType type = plan.match(state);
                                if (type != null) return new Hit(new BlockPos(x, y, z), type);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
