package com.breakinblocks.aeroportals.commands;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.compat.Ae2SpatialCompat;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.compat.ForgivingWorldCompat;
import com.breakinblocks.aeroportals.compat.SpdStackCompat;
import com.breakinblocks.aeroportals.compat.TelepastriesCompat;
import com.breakinblocks.aeroportals.compat.TropicraftCompat;
import com.breakinblocks.aeroportals.config.TravelMethods;
import com.breakinblocks.aeroportals.portal.EndPortalLanding;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class AeroPortalsCommands {
    private static final int OP_LEVEL = 2;
    private static final String ARG_DIMENSION = "dimension";

    private AeroPortalsCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("aeroportals")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.literal("teleport")
                                .then(Commands.argument(ARG_DIMENSION, StringArgumentType.greedyString())
                                        .suggests(SUGGEST_DIMENSIONS)
                                        .executes(ctx -> runTeleport(ctx.getSource(),
                                                StringArgumentType.getString(ctx, ARG_DIMENSION)))))
                        .then(Commands.literal("methods")
                                .executes(ctx -> listMethods(ctx.getSource()))));
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DIMENSIONS = (ctx, builder) -> {
        for (String s : knownDestinations()) {
            if (s.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(s);
            }
        }
        return builder.buildFuture();
    };

    private static String[] knownDestinations() {
        List<String> dests = new ArrayList<>();
        dests.add("overworld");
        dests.add("nether");
        dests.add("end");
        if (AetherCompat.isAvailable()) dests.add("aether");
        if (TropicraftCompat.isAvailable()) dests.add("tropicraft");
        if (DeeperAndDarkerCompat.isAvailable()) dests.add("otherside");
        return dests.toArray(new String[0]);
    }

    private static int runTeleport(CommandSourceStack source, String dimensionArg) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("/aeroportals teleport must be run by a player"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        ParsedDestination dest = parseDestination(dimensionArg);
        ResourceKey<Level> dstKey = resolveDimensionKey(dest.dimensionPart());
        if (dstKey == null) {
            source.sendFailure(Component.literal("Unknown destination dimension: " + dest.dimensionPart()));
            return 0;
        }
        ServerLevel dstLevel = server.getLevel(dstKey);
        if (dstLevel == null) {
            source.sendFailure(Component.literal("Destination dimension '" + dstKey.location() + "' is not loaded on this server."));
            return 0;
        }
        ServerLevel srcLevel = player.serverLevel();
        if (srcLevel == dstLevel) {
            source.sendFailure(Component.literal("Already in " + dstKey.location() + "; no-op."));
            return 0;
        }

        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        if (tracking instanceof ServerSubLevel sub && !sub.isRemoved()) {
            Vec3 dstWorld = chooseLandingForSub(srcLevel, dstLevel, sub, dest);
            source.sendSuccess(() -> Component.literal(
                    "AeroPortals: teleporting your airship and you to " + dstKey.location()
                            + " at " + formatVec(dstWorld)), true);
            PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                    true, "command:" + dest.dimensionPart());
            return 1;
        }

        Vec3 dstPos = chooseLandingForPlayer(srcLevel, dstLevel, player, dest);
        ResourceKey<Level> dstKeyFinal = dstKey;
        source.sendSuccess(() -> Component.literal(
                "AeroPortals: teleporting you to " + dstKeyFinal.location()
                        + " at " + formatVec(dstPos) + " (no airship detected)"), true);
        player.teleportTo(dstLevel, dstPos.x, dstPos.y, dstPos.z,
                Set.<RelativeMovement>of(), player.getYRot(), player.getXRot());
        AeroPortals.LOGGER.debug("[AeroPortals] OP command: teleported player {} to {} at {}",
                player.getGameProfile().getName(), dstKeyFinal.location(), dstPos);
        return 1;
    }

    private static int listMethods(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("AeroPortals travel methods (server config: travel_methods.disabled)")
                .withStyle(ChatFormatting.AQUA), false);

        for (AeroPortalType type : AeroPortalsApi.portalTypes()) {
            sendMethod(source, displayId(type.id()), TravelMethods.isEnabled(type.id()), type.isEnabled());
        }
        sendMethod(source, "pina_colada", TravelMethods.isEnabled(TravelMethods.PINA_COLADA),
                TropicraftCompat.isAvailable());
        sendMethod(source, "telepastries", TravelMethods.isEnabled(TravelMethods.TELEPASTRIES),
                TelepastriesCompat.isAvailable());
        sendMethod(source, "ae2_spatial", TravelMethods.isEnabled(TravelMethods.AE2_SPATIAL),
                Ae2SpatialCompat.isAvailable());
        sendMethod(source, "dimension_stack", TravelMethods.isEnabled(TravelMethods.DIMENSION_STACK),
                SpdStackCompat.isAvailable() || ForgivingWorldCompat.isAvailable());

        source.sendSuccess(() -> Component.literal("inactive means the mod or script that provides it is not present")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static void sendMethod(CommandSourceStack source, String id, boolean allowedByConfig, boolean available) {
        String state = !allowedByConfig ? "disabled in config" : available ? "active" : "inactive";
        ChatFormatting colour = !allowedByConfig
                ? ChatFormatting.RED
                : available ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        source.sendSuccess(() -> Component.literal("  " + id + ": " + state).withStyle(colour), false);
    }

    private static String displayId(ResourceLocation id) {
        return id.getNamespace().equals(AeroPortals.MOD_ID) ? id.getPath() : id.toString();
    }

    private static ResourceKey<Level> resolveDimensionKey(String arg) {
        return switch (arg.toLowerCase()) {
            case "overworld" -> Level.OVERWORLD;
            case "nether" -> Level.NETHER;
            case "end", "the_end" -> Level.END;
            case "aether" -> AetherCompat.isAvailable() ? AetherCompat.destinationDimension() : null;
            case "tropicraft", "tropics" -> TropicraftCompat.isAvailable() ? TropicraftCompat.tropicsDimension() : null;
            case "otherside", "deeperdarker" -> DeeperAndDarkerCompat.isAvailable() ? DeeperAndDarkerCompat.destinationDimension() : null;
            default -> {
                try {
                    ResourceLocation rl = ResourceLocation.parse(arg);
                    yield ResourceKey.create(Registries.DIMENSION, rl);
                } catch (RuntimeException e) {
                    yield null;
                }
            }
        };
    }

    private static Vec3 chooseLandingForSub(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel sub, ParsedDestination dest) {
        if (dstLevel.dimension() == Level.END && !dest.hasCoords()) {
            EndPortalLanding.ensurePlatform(dstLevel);
            return EndPortalLanding.landingPosition(sub);
        }
        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();
        double subX = sub.logicalPose().position().x();
        double subZ = sub.logicalPose().position().z();
        double dstX = resolveCoord(dest.x(), Math.round(subX * ratio) + 0.5);
        double dstZ = resolveCoord(dest.z(), Math.round(subZ * ratio) + 0.5);
        int surface = PortalTeleport.loadedSurfaceY(dstLevel, (int) Math.floor(dstX), (int) Math.floor(dstZ));
        double targetMinY = resolveCoord(dest.y(), surface + 1);
        AABB aabb = AabbUtil.worldAabb(sub);
        double minYOffsetFromPose = aabb.minY - sub.logicalPose().position().y();
        double dstY = targetMinY - minYOffsetFromPose;
        return new Vec3(dstX, dstY, dstZ);
    }

    private static Vec3 chooseLandingForPlayer(ServerLevel srcLevel, ServerLevel dstLevel, ServerPlayer player, ParsedDestination dest) {
        if (dstLevel.dimension() == Level.END && !dest.hasCoords()) {
            EndPortalLanding.ensurePlatform(dstLevel);
            return new Vec3(EndPortalLanding.PLATFORM_CENTRE.getX() + 0.5,
                    EndPortalLanding.LANDING_Y,
                    EndPortalLanding.PLATFORM_CENTRE.getZ() + 0.5);
        }
        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();
        double dstX = resolveCoord(dest.x(), Math.round(player.getX() * ratio) + 0.5);
        double dstZ = resolveCoord(dest.z(), Math.round(player.getZ() * ratio) + 0.5);
        int surface = PortalTeleport.loadedSurfaceY(dstLevel, (int) Math.floor(dstX), (int) Math.floor(dstZ));
        double dstY = resolveCoord(dest.y(), surface + 1);
        return new Vec3(dstX, dstY, dstZ);
    }

    public record CoordSpec(boolean relative, double value) {}

    public record ParsedDestination(String dimensionPart, CoordSpec x, CoordSpec y, CoordSpec z) {
        public boolean hasCoords() {
            return this.x != null;
        }
    }

    public static ParsedDestination parseDestination(String arg) {
        String trimmed = arg.trim();
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length >= 4) {
            CoordSpec x = parseCoordSpec(tokens[tokens.length - 3]);
            CoordSpec y = parseCoordSpec(tokens[tokens.length - 2]);
            CoordSpec z = parseCoordSpec(tokens[tokens.length - 1]);
            if (x != null && y != null && z != null) {
                String dimensionPart = String.join(" ", Arrays.copyOfRange(tokens, 0, tokens.length - 3));
                return new ParsedDestination(dimensionPart, x, y, z);
            }
        }
        return new ParsedDestination(trimmed, null, null, null);
    }

    private static CoordSpec parseCoordSpec(String token) {
        String numberPart = token;
        boolean relative = false;
        if (token.startsWith("~")) {
            relative = true;
            numberPart = token.substring(1);
            if (numberPart.isEmpty()) return new CoordSpec(true, 0.0);
        }
        try {
            double value = Double.parseDouble(numberPart);
            if (!Double.isFinite(value)) return null;
            return new CoordSpec(relative, value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double resolveCoord(CoordSpec spec, double fallback) {
        if (spec == null) return fallback;
        return spec.relative() ? fallback + spec.value() : spec.value();
    }

    private static String formatVec(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
}
