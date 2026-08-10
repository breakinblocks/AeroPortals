package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelPreTransferEvent;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventResult;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.neoforged.neoforge.common.NeoForge;

public class AeroPortalsKubePlugin implements KubeJSPlugin {

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(AeroPortalsKubeEvents.GROUP);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("AeroPortals", AeroPortalsBindings.class);
    }

    @Override
    public void init() {
        NeoForge.EVENT_BUS.addListener(AeroPortalsKubePlugin::onPreTransfer);
        NeoForge.EVENT_BUS.addListener(AeroPortalsKubePlugin::onTransfer);
        AeroPortals.LOGGER.debug("[AeroPortals] KubeJS plugin loaded; AeroPortalsEvents and the AeroPortals binding are available");
    }

    @Override
    public void afterInit() {
        ScriptRegistry.reset();
        AeroPortalsKubeEvents.REGISTER.post(ScriptType.STARTUP, new RegisterKubeEvent());
        AeroPortals.LOGGER.info("[AeroPortals] KubeJS scripts registered {} NBT fixer(s) and {} portal(s)",
                ScriptRegistry.fixerCount(), ScriptRegistry.portalCount());
    }

    private static void onPreTransfer(SubLevelPreTransferEvent event) {
        if (!AeroPortalsKubeEvents.PRE_TRANSFER.hasListeners()) return;
        EventResult result = AeroPortalsKubeEvents.PRE_TRANSFER.post(ScriptType.SERVER, new PreTransferKubeEvent(event));
        if (result.interruptFalse()) {
            event.cancel("cancelled by a KubeJS script");
        }
    }

    private static void onTransfer(SubLevelTransferEvent event) {
        if (!AeroPortalsKubeEvents.TRANSFER.hasListeners()) return;
        AeroPortalsKubeEvents.TRANSFER.post(ScriptType.SERVER, new TransferKubeEvent(event));
    }
}
