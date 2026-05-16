package com.breakinblocks.aeroportals.events;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.portal.PortalCooldown;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class WorldUnloadHandler {
    private WorldUnloadHandler() {}

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        PortalCooldown.clear();
    }
}
