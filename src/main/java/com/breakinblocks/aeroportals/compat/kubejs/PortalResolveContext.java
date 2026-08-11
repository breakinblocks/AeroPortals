package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.api.PortalDestination;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class PortalResolveContext {
    private final ServerLevel srcLevel;
    private final ServerSubLevel sub;
    private final BlockPos hitPos;
    private final String portalId;

    private ServerLevel dstLevel;
    private Vec3 destination;
    private boolean validateLanding = true;

    PortalResolveContext(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos, String portalId) {
        this.srcLevel = srcLevel;
        this.sub = sub;
        this.hitPos = hitPos;
        this.portalId = portalId;
    }

    public ServerLevel getSrcLevel() {
        return this.srcLevel;
    }

    public String getSrcDimension() {
        return this.srcLevel.dimension().location().toString();
    }

    public ServerSubLevel getSub() {
        return this.sub;
    }

    public String getSubId() {
        return this.sub.getUniqueId().toString();
    }

    public BlockPos getPortalPos() {
        return this.hitPos;
    }

    public String getPortalId() {
        return this.portalId;
    }

    public Vec3 getSubPosition() {
        var position = this.sub.logicalPose().position();
        return new Vec3(position.x(), position.y(), position.z());
    }

    public void setValidateLanding(boolean validateLanding) {
        this.validateLanding = validateLanding;
    }

    public void setDestination(String dimension, double x, double y, double z) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            throw new IllegalArgumentException("'" + dimension + "' is not a valid dimension id");
        }
        ServerLevel level = this.srcLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw new IllegalArgumentException("dimension '" + dimension + "' is not loaded");
        }
        this.dstLevel = level;
        this.destination = new Vec3(x, y, z);
    }

    public void setDestination(ServerLevel level, double x, double y, double z) {
        this.dstLevel = level;
        this.destination = new Vec3(x, y, z);
    }

    public void landOn(String dimension, BlockPos pos) {
        setDestination(dimension, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    PortalDestination toDestination() {
        if (this.dstLevel == null || this.destination == null) {
            return null;
        }
        return PortalDestination.of(this.dstLevel, this.destination, this.validateLanding, this.portalId);
    }
}
