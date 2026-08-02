package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.Ae2SpatialCompat;
import com.breakinblocks.aeroportals.compat.ForgivingWorldCompat;
import com.breakinblocks.aeroportals.compat.SpdStackCompat;
import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.breakinblocks.aeroportals.portal.OnboardPortalJump;
import com.breakinblocks.aeroportals.portal.PortalDetector;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.portal.TeleportJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class ServerTickHandler {
    private static long tick = 0L;

    private ServerTickHandler() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!AeroPortals.sableLoaded) return;
        TeleportJournal.replayPending(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (!AeroPortals.sableLoaded) return;

        MinecraftServer server = event.getServer();
        PortalTeleport.DeferredClientSyncs.tick(server.getTickCount());
        PortalTeleport.DeferredVelocityRestores.tick(server.getTickCount());
        PortalTeleport.DeferredRiderSettles.tick(server.getTickCount());
        Ae2SpatialCompat.tick(server);

        long t = ++tick;
        int interval = AeroPortalsConfig.SCAN_INTERVAL_TICKS.get();
        if (t % interval != 0) return;

        for (ServerLevel level : server.getAllLevels()) {
            PortalDetector.scan(level);
            OnboardPortalJump.scan(level);
            SpdStackCompat.scan(level);
            ForgivingWorldCompat.scan(level);
        }
    }
}
