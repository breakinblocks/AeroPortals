package com.breakinblocks.aeroportals.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

public interface AeroPortalsKubeEvents {
    EventGroup GROUP = EventGroup.of("AeroPortalsEvents");

    EventHandler REGISTER = GROUP.startup("register", () -> RegisterKubeEvent.class);
    EventHandler PRE_TRANSFER = GROUP.server("preTransfer", () -> PreTransferKubeEvent.class).hasResult();
    EventHandler TRANSFER = GROUP.server("transfer", () -> TransferKubeEvent.class);
}
