package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

public final class GameTestSupport {
    private static boolean travelIsolated = false;

    private GameTestSupport() {}

    public static void isolate(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        disableDimensionStackTravel();
        removeLeftoverSubLevels(server);
        removeLeftoverEntities(server);
    }

    private static void removeLeftoverEntities(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> present = new ArrayList<>();
            level.getAllEntities().forEach(present::add);
            for (Entity entity : present) {
                if (entity instanceof Player) continue;
                entity.discard();
                removed++;
            }
        }
        if (removed > 0) {
            AeroPortals.LOGGER.info("[AeroPortals/test] removed {} entity/entities left over from earlier tests", removed);
        }
    }

    private static void disableDimensionStackTravel() {
        if (travelIsolated) return;
        travelIsolated = true;
        if (!AeroPortalsConfig.SPEC.isLoaded()) return;

        AeroPortalsConfig.DISABLED_TRAVEL_METHODS.set(List.of("dimension_stack"));
        AeroPortals.LOGGER.info("[AeroPortals/test] dimension stack travel disabled for this run: test ships sit on the "
                + "world floor, where the stacking mod in the dev runtime would keep pulling them into the next dimension");
    }

    private static void removeLeftoverSubLevels(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel sub : List.copyOf(container.getAllSubLevels())) {
                if (sub.isRemoved()) continue;
                container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                removed++;
            }
        }
        if (removed > 0) {
            AeroPortals.LOGGER.info("[AeroPortals/test] removed {} ship(s) left over from earlier tests", removed);
        }
    }
}
