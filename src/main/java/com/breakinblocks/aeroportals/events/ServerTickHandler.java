package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.PortalDetector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class ServerTickHandler {
    private static long tick = 0L;

    private ServerTickHandler() {}

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (!AeroPortals.sableLoaded) return;
        long t = ++tick;
        int interval = AeroPortalsConfig.SCAN_INTERVAL_TICKS.get();
        if (t % interval != 0) return;

        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            PortalDetector.scan(level);
        }
    }
}
