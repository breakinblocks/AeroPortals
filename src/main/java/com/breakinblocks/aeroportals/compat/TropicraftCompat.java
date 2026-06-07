package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

public final class TropicraftCompat {
    public static final String MOD_ID = "tropicraft";
    private static final ResourceLocation COCKTAIL_ITEM_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "cocktail");
    private static final ResourceLocation COCKTAIL_COMPONENT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "cocktail");
    private static final ResourceLocation TROPICS_DIM_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "tropics");
    private static final ResourceLocation DRINK_REGISTRY_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "drink");
    private static final ResourceLocation PINA_COLADA_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "pina_colada");

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Item cocktailItem;
    private static volatile DataComponentType<?> cocktailComponent;
    private static volatile Method cocktailDrinkMethod;
    private static volatile ResourceKey<Level> tropicsKey;
    private static volatile ResourceKey<?> pinaColadaKey;

    private TropicraftCompat() {}

    public static boolean isAvailable() {
        if (initialized) return true;
        if (initAttempted) return false;
        if (!ModList.get().isLoaded(MOD_ID)) {
            initAttempted = true;
            return false;
        }
        return tryInit();
    }

    private static synchronized boolean tryInit() {
        if (initAttempted) return initialized;
        initAttempted = true;
        try {
            Item item = BuiltInRegistries.ITEM.get(COCKTAIL_ITEM_ID);
            if (item == null || item == Items.AIR) {
                AeroPortals.LOGGER.warn("[AeroPortals] Tropicraft loaded but {} not in item registry; compat disabled",
                        COCKTAIL_ITEM_ID);
                return false;
            }
            DataComponentType<?> component = BuiltInRegistries.DATA_COMPONENT_TYPE.get(COCKTAIL_COMPONENT_ID);
            if (component == null) {
                AeroPortals.LOGGER.warn("[AeroPortals] Tropicraft data component {} not registered; compat disabled",
                        COCKTAIL_COMPONENT_ID);
                return false;
            }
            Class<?> cocktailClass = Class.forName("net.tropicraft.core.common.drinks.Cocktail");
            Method drinkMethod = cocktailClass.getMethod("drink");

            ResourceKey<Registry<Object>> drinkRegistryKey = ResourceKey.createRegistryKey(DRINK_REGISTRY_ID);
            ResourceKey<?> pinaKey = ResourceKey.create(drinkRegistryKey, PINA_COLADA_ID);
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, TROPICS_DIM_ID);

            cocktailItem = item;
            cocktailComponent = component;
            cocktailDrinkMethod = drinkMethod;
            tropicsKey = dimKey;
            pinaColadaKey = pinaKey;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Tropicraft compat initialized (item={}, component={}, dim={}, drink={})",
                    COCKTAIL_ITEM_ID, COCKTAIL_COMPONENT_ID, TROPICS_DIM_ID, PINA_COLADA_ID);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Tropicraft loaded but Cocktail class not found ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Tropicraft Cocktail.drink() method missing ({}); compat disabled",
                    e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Tropicraft compat init failed: {}", t.toString());
            return false;
        }
    }

    public static ResourceKey<Level> tropicsDimension() {
        if (!isAvailable()) return null;
        return tropicsKey;
    }

    public static boolean isPinaColada(ItemStack stack) {
        if (!isAvailable()) return false;
        if (stack.isEmpty() || stack.getItem() != cocktailItem) return false;
        Object cocktail = stack.get(cocktailComponent);
        if (cocktail == null) return false;
        try {
            Object drinkOptional = cocktailDrinkMethod.invoke(cocktail);
            if (!(drinkOptional instanceof Optional<?> opt) || opt.isEmpty()) return false;
            Object drinkHolder = opt.get();
            if (!(drinkHolder instanceof Holder<?> holder)) return false;
            @SuppressWarnings({"rawtypes", "unchecked"})
            Holder rawHolder = holder;
            @SuppressWarnings({"rawtypes", "unchecked"})
            ResourceKey rawKey = pinaColadaKey;
            return rawHolder.is(rawKey);
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.error("[AeroPortals] failed to read cocktail.drink() via reflection: {}", e.getMessage());
            return false;
        }
    }
}
