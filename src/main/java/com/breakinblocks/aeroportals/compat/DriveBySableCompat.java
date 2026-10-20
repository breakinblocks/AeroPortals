package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DriveBySableCompat extends WireNetworkCarrier {
    private static final String CONFIG = "edn.lakeopossmc.drivebysable.CableConfig";

    public DriveBySableCompat() {
        super("drivebysable", "Drive-By-Sable", "edn.lakeopossmc.drivebysable.cable.CableNetworkManager");
    }

    @Override
    public ResourceLocation id() {
        return AeroPortals.id("drive_by_sable");
    }

    @Override
    protected void requireRestore(Class<?> api) throws ReflectiveOperationException {
        merge(api);
    }

    @Override
    protected void validateWire(SubLevel source, SubLevel sink) {
        boolean sameShip = source != null && sink != null && source.getUniqueId().equals(sink.getUniqueId());
        if (!sameShip && forbidsCrossLevel()) {
            throw new IllegalStateException("Drive-By-Sable connection leaves its ship while cross-level connections are disabled; disconnect it");
        }
    }

    @Override
    protected void restore(ServerLevel level, Class<?> api, List<Wire> wires) throws ReflectiveOperationException {
        ListTag connections = new ListTag();
        for (Wire wire : wires) {
            CompoundTag connection = wire.saved().copy();
            connection.remove("SourceEndpoint");
            connection.remove("SinkEndpoint");
            connection.putLong("Source", wire.source().asLong());
            connection.putLong("Sink", wire.sink().asLong());
            connections.add(connection);
        }
        CompoundTag payload = new CompoundTag();
        payload.put("Connections", connections);
        Object network = api.getMethod("get", Level.class).invoke(null, level);
        merge(api).invoke(network, level, payload);
        Set<List<Object>> present = new HashSet<>();
        for (Tag entry : snapshot(level).getList("Connections", Tag.TAG_COMPOUND)) {
            present.add(key((CompoundTag) entry));
        }
        for (Tag entry : connections) {
            if (!present.contains(key((CompoundTag) entry))) {
                throw new IllegalStateException("Drive-By-Sable rejected restored connection: " + entry);
            }
        }
    }

    private static Method merge(Class<?> api) throws NoSuchMethodException {
        return api.getMethod("mergeSavedConnections", Level.class, CompoundTag.class);
    }

    private static List<Object> key(CompoundTag connection) {
        return List.of(connection.getLong("Source"), connection.getLong("Sink"), connection.getByte("Direction"),
                connection.getString("Channel"), connection.getString("SinkChannel"));
    }

    private static boolean forbidsCrossLevel() {
        try {
            Object config = Class.forName(CONFIG).getField("CONFIG").get(null);
            return config.getClass().getField("forbidCrossLevelConnections").get(config)
                    instanceof ModConfigSpec.BooleanValue value && value.get();
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException e) {
            return false;
        }
    }
}
