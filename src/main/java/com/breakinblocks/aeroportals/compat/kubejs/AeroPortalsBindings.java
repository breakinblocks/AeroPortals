package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.portal.PortalTeleport;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AeroPortalsBindings {

    public static boolean isPortalBlock(BlockState state) {
        return AeroPortalsApi.isPortalBlock(state);
    }

    public static String portalTypeOf(BlockState state) {
        AeroPortalType type = AeroPortalsApi.findPortalType(state);
        return type == null ? null : type.id().toString();
    }

    public static List<String> portalTypes() {
        List<String> ids = new ArrayList<>();
        for (AeroPortalType type : AeroPortalsApi.portalTypes()) {
            ids.add(type.id().toString());
        }
        return ids;
    }

    public static ServerSubLevel subLevelOf(Entity entity) {
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(entity);
        return tracking instanceof ServerSubLevel server ? server : null;
    }

    public static List<ServerSubLevel> subLevelsIn(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        return container == null ? List.of() : List.copyOf(container.getAllSubLevels());
    }

    public static ServerSubLevel subLevelById(ServerLevel level, String uuid) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        SubLevel found;
        try {
            found = container.getSubLevel(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + uuid + "' is not a valid sub-level id");
        }
        return found instanceof ServerSubLevel server ? server : null;
    }

    public static List<ServerSubLevel> chainOf(ServerSubLevel sub) {
        return List.copyOf(SubLevelHelper.getLoadingDependencyChain(sub));
    }

    public static Vec3 positionOf(ServerSubLevel sub) {
        var position = sub.logicalPose().position();
        return new Vec3(position.x(), position.y(), position.z());
    }

    public static AABB boundsOf(ServerSubLevel sub) {
        return AabbUtil.worldAabb(sub);
    }

    public static void teleport(ServerSubLevel sub, String dimension, double x, double y, double z) {
        teleport(sub, dimension, x, y, z, true);
    }

    public static void teleport(ServerSubLevel sub, String dimension, double x, double y, double z, boolean validateLanding) {
        ServerLevel srcLevel = sub.getLevel();
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            throw new IllegalArgumentException("'" + dimension + "' is not a valid dimension id");
        }
        ServerLevel dstLevel = srcLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (dstLevel == null) {
            throw new IllegalArgumentException("dimension '" + dimension + "' is not loaded");
        }
        PortalTeleport.teleportToDimension(srcLevel, sub, dstLevel, new Vec3(x, y, z), validateLanding, "kubejs");
    }

    public static void teleport(ServerSubLevel sub, ServerLevel dstLevel, double x, double y, double z) {
        PortalTeleport.teleportToDimension(sub.getLevel(), sub, dstLevel, new Vec3(x, y, z), true, "kubejs");
    }

    public static Level levelOf(ServerSubLevel sub) {
        return sub.getLevel();
    }
}
