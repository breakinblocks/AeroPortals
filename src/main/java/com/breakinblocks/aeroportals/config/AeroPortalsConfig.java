package com.breakinblocks.aeroportals.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AeroPortalsConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;
    public static final ModConfigSpec.IntValue PORTAL_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue MAX_SUBLEVEL_AABB_VOLUME;
    public static final ModConfigSpec.IntValue DEST_PORTAL_SEARCH_RADIUS;
    public static final ModConfigSpec.BooleanValue GENERATE_MATCHING_PORTAL;
    public static final ModConfigSpec.BooleanValue MATCH_PLAYER_PORTAL_SIZE;
    public static final ModConfigSpec.BooleanValue CLEAR_VELOCITY_ON_ARRIVAL;
    public static final ModConfigSpec.BooleanValue CLEAR_DESTINATION_BLOCKS;

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
        DEST_PORTAL_SEARCH_RADIUS = builder
                .comment("Horizontal block radius around the scaled destination point to search for an existing nether portal to link to.")
                .defineInRange("dest_portal_search_radius", 128, 0, 1024);
        GENERATE_MATCHING_PORTAL = builder
                .comment("If true, generate a portal frame at the destination (matching source dimensions) when no existing portal is found or the existing one is too small.")
                .define("generate_matching_portal", true);
        MATCH_PLAYER_PORTAL_SIZE = builder
                .comment("If true, when a player travels on foot and a new destination portal is created, enlarge it to match the size of the portal they entered.")
                .define("match_player_portal_size", true);
        CLEAR_VELOCITY_ON_ARRIVAL = builder
                .comment("If true, airships arrive from a teleport with all momentum removed. If false, they keep their velocity, subject to Sable's velocity retention setting.")
                .define("clear_velocity_on_arrival", false);
        CLEAR_DESTINATION_BLOCKS = builder
                .comment("DESTRUCTIVE: if true, blocks that would overlap the arriving airship are destroyed (without drops) to make room, instead of cancelling the teleport. Portal blocks, portal frames, and unbreakable blocks are never destroyed.")
                .define("clear_destination_blocks", false);
        builder.pop();

        SPEC = builder.build();
    }

    private AeroPortalsConfig() {}
}
