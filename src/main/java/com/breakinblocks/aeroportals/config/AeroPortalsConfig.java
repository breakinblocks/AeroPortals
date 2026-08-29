package com.breakinblocks.aeroportals.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

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
    public static final ModConfigSpec.BooleanValue ONBOARD_PORTAL_JUMPS;
    public static final ModConfigSpec.IntValue ONBOARD_JUMP_DELAY_TICKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_TRAVEL_METHODS;
    public static final ModConfigSpec.BooleanValue CATCH_FALLING_SHIPS;
    public static final ModConfigSpec.IntValue CATCH_SHIPS_BELOW_FLOOR;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("detection");
        VERBOSE_LOGGING = builder
                .comment("Write detailed teleport diagnostics to the debug log. Turn on when reporting issues.")
                .define("verbose_logging", false);
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
        ONBOARD_PORTAL_JUMPS = builder
                .comment("If true, a lit nether portal built aboard an airship acts as a jump drive: shortly after the portal is lit the whole ship jumps to the other dimension, portal and all. The onboard portal must be extinguished and re-lit before it can jump again.")
                .define("onboard_portal_jumps", false);
        ONBOARD_JUMP_DELAY_TICKS = builder
                .comment("Ticks between an onboard portal being lit and the ship jumping. Gives riders time to abort by breaking the portal.")
                .defineInRange("onboard_jump_delay_ticks", 100, 0, 24000);
        builder.pop();

        builder.push("safety");
        CATCH_FALLING_SHIPS = builder
                .comment("Catch airships that fall out of the bottom of a dimension and set them down safely.",
                        "A ship that keeps falling is destroyed by the physics engine along with everything on board, with no drops and no way to get it back.",
                        "Turn this off only if you want that to happen.")
                .define("catch_falling_ships", true);
        CATCH_SHIPS_BELOW_FLOOR = builder
                .comment("How far below the bottom of a dimension an airship has to fall before it is caught and set down.")
                .defineInRange("catch_ships_below_floor", 64, 0, 512);
        builder.pop();

        builder.push("travel_methods");
        DISABLED_TRAVEL_METHODS = builder
                .comment("Ways of travelling that AeroPortals will ignore. Anything listed here is left alone: the portal, drink, or cake still works for players on foot, it just no longer carries airships.",
                        "Built-in ids: nether, end, aether, ars_nouveau, draconic, deeper_darker, create_teleporters, ender_gateway, pina_colada, telepastries, ae2_spatial, dimension_stack, kubejs",
                        "Ids added by other mods or by KubeJS scripts can be listed too. Run /aeroportals methods in game to see every id and whether it is on.",
                        "Example: [\"nether\", \"end\"] stops airships travelling through vanilla portals while leaving every modded portal working.",
                        "Listing 'nether' also switches off onboard portal jump drives.")
                .defineListAllowEmpty("disabled", List.<String>of(), () -> "",
                        entry -> entry instanceof String s && !s.isBlank());
        builder.pop();

        SPEC = builder.build();
    }

    private AeroPortalsConfig() {}
}
