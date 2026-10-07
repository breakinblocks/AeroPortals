package com.breakinblocks.aeroportals.util;

import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalScanPlan;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public final class PortalBlockSearch {
    private static final double CONTACT_REACH = 1.25;

    private PortalBlockSearch() {}

    public record Hit(BlockPos pos, AeroPortalType type) {}

    public static boolean anyTouching(ServerLevel level, SubLevel sub) {
        PortalScanPlan plan = AeroPortalsApi.scanPlan();
        return !plan.isEmpty() && findTouching(level, sub, AabbUtil.worldAabb(sub).inflate(1.0), plan) != null;
    }

    public static Hit findTouching(ServerLevel level, SubLevel sub, AABB searchArea, PortalScanPlan plan) {
        return find(level, searchArea, plan, pos -> touches(level, sub, pos));
    }

    public static Hit find(ServerLevel level, AABB aabb, PortalScanPlan plan) {
        return find(level, aabb, plan, pos -> true);
    }

    private static boolean touches(ServerLevel level, SubLevel sub, BlockPos portal) {
        Vec3 local = sub.logicalPose().transformPositionInverse(Vec3.atCenterOf(portal));
        BoundingBox3ic plot = sub.getPlot().getBoundingBox();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(local.x - CONTACT_REACH); x <= Mth.floor(local.x + CONTACT_REACH); x++) {
            if (Math.abs(x + 0.5 - local.x) > CONTACT_REACH) continue;
            for (int y = Mth.floor(local.y - CONTACT_REACH); y <= Mth.floor(local.y + CONTACT_REACH); y++) {
                if (Math.abs(y + 0.5 - local.y) > CONTACT_REACH) continue;
                for (int z = Mth.floor(local.z - CONTACT_REACH); z <= Mth.floor(local.z + CONTACT_REACH); z++) {
                    if (Math.abs(z + 0.5 - local.z) > CONTACT_REACH) continue;
                    if (!plot.contains(x, y, z)) continue;
                    if (!level.getBlockState(cursor.set(x, y, z)).isAir()) return true;
                }
            }
        }
        return false;
    }

    private static Hit find(ServerLevel level, AABB aabb, PortalScanPlan plan, Predicate<BlockPos> accept) {
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
                                if (type == null) continue;
                                BlockPos pos = new BlockPos(x, y, z);
                                if (accept.test(pos)) return new Hit(pos, type);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
