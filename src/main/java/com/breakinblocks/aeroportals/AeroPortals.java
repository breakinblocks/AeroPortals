package com.breakinblocks.aeroportals;

import com.breakinblocks.aeroportals.config.AeroPortalsConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(AeroPortals.MOD_ID)
public class AeroPortals {
    public static final String MOD_ID = "aeroportals";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static boolean sableLoaded = false;

    public AeroPortals(IEventBus eventBus, ModContainer container, Dist dist) {
        container.registerConfig(ModConfig.Type.SERVER, AeroPortalsConfig.SPEC);
        eventBus.addListener(AeroPortals::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            sableLoaded = ModList.get().isLoaded("sable");
            LOGGER.info("[AeroPortals] common setup: sable loaded={}", sableLoaded);
            if (!sableLoaded) {
                LOGGER.warn("[AeroPortals] Sable mod not detected - AeroPortals will be dormant. Loaded mod ids:");
                ModList.get().getMods().forEach(m -> LOGGER.warn("[AeroPortals]   - {}", m.getModId()));
            }
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
