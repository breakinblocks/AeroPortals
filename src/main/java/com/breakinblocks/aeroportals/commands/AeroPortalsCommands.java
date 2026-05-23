package com.breakinblocks.aeroportals.commands;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.compat.TropicraftCompat;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
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
                                .then(Commands.argument(ARG_DIMENSION, StringArgumentType.word())
                                        .suggests(SUGGEST_DIMENSIONS)
                                        .executes(ctx -> runTeleport(ctx.getSource(),
                                                StringArgumentType.getString(ctx, ARG_DIMENSION))))));
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
        ResourceKey<Level> dstKey = resolveDimensionKey(dimensionArg);
        if (dstKey == null) {
            source.sendFailure(Component.literal("Unknown destination dimension: " + dimensionArg));
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
            Vec3 dstWorld = chooseLandingForSub(srcLevel, dstLevel, sub, dimensionArg);
            source.sendSuccess(() -> Component.literal(
                    "AeroPortals: teleporting your airship and you to " + dstKey.location()
                            + " at " + formatVec(dstWorld)), true);
            PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, dstWorld,
                    true, "command:" + dimensionArg);
            return 1;
        }

        Vec3 dstPos = chooseLandingForPlayer(srcLevel, dstLevel, player, dimensionArg);
        ResourceKey<Level> dstKeyFinal = dstKey;
        source.sendSuccess(() -> Component.literal(
                "AeroPortals: teleporting you to " + dstKeyFinal.location()
                        + " at " + formatVec(dstPos) + " (no airship detected)"), true);
        player.teleportTo(dstLevel, dstPos.x, dstPos.y, dstPos.z,
                Set.<RelativeMovement>of(), player.getYRot(), player.getXRot());
        AeroPortals.LOGGER.info("[AeroPortals] OP command: teleported player {} to {} at {}",
                player.getGameProfile().getName(), dstKeyFinal.location(), dstPos);
        return 1;
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

    private static Vec3 chooseLandingForSub(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel sub, String dimensionArg) {
        if (dstLevel.dimension() == Level.END) {
            EndPortalLanding.ensurePlatform(dstLevel);
            return EndPortalLanding.landingPosition(sub);
        }
        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();
        double subX = sub.logicalPose().position().x();
        double subZ = sub.logicalPose().position().z();
        int dstX = (int) Math.round(subX * ratio);
        int dstZ = (int) Math.round(subZ * ratio);
        int surface = dstLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dstX, dstZ);
        int targetMinY = surface + 1;
        AABB aabb = AabbUtil.worldAabb(sub);
        double minYOffsetFromPose = aabb.minY - sub.logicalPose().position().y();
        double dstY = targetMinY - minYOffsetFromPose;
        return new Vec3(dstX + 0.5, dstY, dstZ + 0.5);
    }

    private static Vec3 chooseLandingForPlayer(ServerLevel srcLevel, ServerLevel dstLevel, ServerPlayer player, String dimensionArg) {
        if (dstLevel.dimension() == Level.END) {
            EndPortalLanding.ensurePlatform(dstLevel);
            return new Vec3(EndPortalLanding.PLATFORM_CENTRE.getX() + 0.5,
                    EndPortalLanding.LANDING_Y,
                    EndPortalLanding.PLATFORM_CENTRE.getZ() + 0.5);
        }
        DimensionType srcDim = srcLevel.dimensionType();
        DimensionType dstDim = dstLevel.dimensionType();
        double ratio = srcDim.coordinateScale() / dstDim.coordinateScale();
        int dstX = (int) Math.round(player.getX() * ratio);
        int dstZ = (int) Math.round(player.getZ() * ratio);
        int surface = dstLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dstX, dstZ);
        return new Vec3(dstX + 0.5, surface + 1, dstZ + 0.5);
    }

    private static String formatVec(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
}
