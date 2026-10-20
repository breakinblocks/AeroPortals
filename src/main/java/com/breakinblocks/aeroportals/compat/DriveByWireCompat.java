package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.List;

/** Preserves the dimension-owned wire network, which Sable removal deletes outside plot NBT. */
public final class DriveByWireCompat extends WireNetworkCarrier {
    public DriveByWireCompat() {
        super("drivebywire", "Drive By Wire", "edn.stratodonut.drivebywire.wire.WireNetworkManager");
    }

    @Override
    public ResourceLocation id() {
        return AeroPortals.id("drive_by_wire");
    }

    @Override
    protected void requireRestore(Class<?> api) throws ReflectiveOperationException {
        create(api);
        has(api);
    }

    @Override
    protected void restore(ServerLevel level, Class<?> api, List<Wire> wires) throws ReflectiveOperationException {
        Method create = create(api);
        Method has = has(api);
        for (Wire wire : wires) {
            Direction direction = Direction.from3DDataValue(wire.saved().getByte("Direction"));
            String channel = wire.saved().getString("Channel");
            // DBW checks its 64-sink limit before checking duplicates, so querying first is
            // necessary for idempotent journal replay of a source already at that limit.
            if ((Boolean) has.invoke(null, level, wire.source(), wire.sink(), direction, channel)) continue;
            Object result = create.invoke(null, level, wire.source(), wire.sink(), direction, channel);
            // A wire shared by two transferred ships is captured by both. Recovery can replay again.
            if (!(result instanceof Enum<?> value)
                    || !(value.name().equals("OK") || value.name().equals("FAIL_EXISTS"))) {
                throw new IllegalStateException("Drive By Wire rejected restored connection: " + result);
            }
        }
    }

    private static Method create(Class<?> api) throws NoSuchMethodException {
        return api.getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class);
    }

    private static Method has(Class<?> api) throws NoSuchMethodException {
        return api.getMethod("hasConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class, String.class);
    }
}
