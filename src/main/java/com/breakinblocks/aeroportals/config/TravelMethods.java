package com.breakinblocks.aeroportals.config;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TravelMethods {
    public static final ResourceLocation NETHER = AeroPortals.id("nether");
    public static final ResourceLocation PINA_COLADA = AeroPortals.id("pina_colada");
    public static final ResourceLocation TELEPASTRIES = AeroPortals.id("telepastries");
    public static final ResourceLocation AE2_SPATIAL = AeroPortals.id("ae2_spatial");
    public static final ResourceLocation DIMENSION_STACK = AeroPortals.id("dimension_stack");

    private static final String PREFIX = AeroPortals.MOD_ID + ":";

    private static volatile List<? extends String> cachedSource;
    private static volatile Set<String> disabled = Set.of();

    private TravelMethods() {}

    public static boolean isEnabled(ResourceLocation id) {
        return !disabled().contains(normalize(id.toString()));
    }

    public static boolean isEnabled(String id) {
        return !disabled().contains(normalize(id));
    }

    public static Set<String> disabledIds() {
        return disabled();
    }

    private static Set<String> disabled() {
        if (!AeroPortalsConfig.SPEC.isLoaded()) return Set.of();

        List<? extends String> current = AeroPortalsConfig.DISABLED_TRAVEL_METHODS.get();
        if (current == cachedSource) return disabled;

        Set<String> parsed = new LinkedHashSet<>();
        for (String entry : current) {
            if (entry == null) continue;
            String normalized = normalize(entry);
            if (!normalized.isEmpty()) parsed.add(normalized);
        }
        disabled = Set.copyOf(parsed);
        cachedSource = current;
        AeroPortals.LOGGER.info("[AeroPortals] disabled travel methods: {}", parsed.isEmpty() ? "none" : parsed);
        return disabled;
    }

    private static String normalize(String id) {
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(PREFIX) ? trimmed.substring(PREFIX.length()) : trimmed;
    }
}
