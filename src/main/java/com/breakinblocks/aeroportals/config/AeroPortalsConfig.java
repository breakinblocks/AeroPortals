package com.breakinblocks.aeroportals.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AeroPortalsConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;
    public static final ModConfigSpec.IntValue PORTAL_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue MAX_SUBLEVEL_AABB_VOLUME;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("detection");
        VERBOSE_LOGGING = builder
                .comment("Log every detection scan and teleport attempt at INFO level.")
                .define("verbose_logging", true);
        SCAN_INTERVAL_TICKS = builder
                .comment("How often (in server ticks) to scan loaded SubLevels for portal contact.")
                .defineInRange("scan_interval_ticks", 5, 1, 200);
        MAX_SUBLEVEL_AABB_VOLUME = builder
                .comment("Skip scanning any SubLevel whose world-space AABB exceeds this volume (cubic blocks).")
                .defineInRange("max_sublevel_aabb_volume", 200_000.0d, 1.0d, Double.MAX_VALUE);
        builder.pop();

        builder.push("teleport");
        PORTAL_COOLDOWN_TICKS = builder
                .comment("Ticks after a teleport during which the same SubLevel will not be teleported again (anti-bounce).")
                .defineInRange("portal_cooldown_ticks", 200, 0, 24000);
        builder.pop();

        SPEC = builder.build();
    }

    private AeroPortalsConfig() {}
}
