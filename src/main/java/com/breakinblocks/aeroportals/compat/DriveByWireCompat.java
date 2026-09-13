package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Preserves the dimension-owned wire network, which Sable removal deletes outside plot NBT. */
public final class DriveByWireCompat implements TransferCarrier<CompoundTag> {
    private static final String MANAGER = "edn.stratodonut.drivebywire.wire.WireNetworkManager";

    @Override
    public ResourceLocation id() {
        return AeroPortals.id("drive_by_wire");
    }

    @Override
    public boolean isEnabled() {
        return ModList.get().isLoaded("drivebywire");
    }

    @Override
    public void validateGroup(ServerLevel src, List<ServerSubLevel> group, ServerLevel dst) {
        if (src == dst) return;
        Set<UUID> owners = new HashSet<>();
        group.forEach(sub -> owners.add(sub.getUniqueId()));
        for (Tag entry : snapshot(src).getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) entry;
            SubLevel source = Sable.HELPER.getContaining(src, BlockPos.of(wire.getLong("Source")));
            SubLevel sink = Sable.HELPER.getContaining(src, BlockPos.of(wire.getLong("Sink")));
            boolean sourceMoving = source != null && owners.contains(source.getUniqueId());
            boolean sinkMoving = sink != null && owners.contains(sink.getUniqueId());
            if (sourceMoving != sinkMoving) {
                throw new IllegalStateException("Drive By Wire connection crosses the transfer group; disconnect it or move both endpoints together");
            }
        }
    }

    @Override
    public CompoundTag capture(ServerLevel level, ServerSubLevel sub) {
        ListTag wires = new ListTag();
        for (Tag entry : snapshot(level).getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) entry;
            BlockPos sourcePos = BlockPos.of(wire.getLong("Source"));
            BlockPos sinkPos = BlockPos.of(wire.getLong("Sink"));
            SubLevel source = Sable.HELPER.getContaining(level, sourcePos);
            SubLevel sink = Sable.HELPER.getContaining(level, sinkPos);
            if (!owns(sub, source) && !owns(sub, sink)) continue;
            CompoundTag saved = wire.copy();
            saved.put("SourceEndpoint", endpoint(level, sourcePos, source));
            saved.put("SinkEndpoint", endpoint(level, sinkPos, sink));
            wires.add(saved);
        }
        if (wires.isEmpty()) return null;
        CompoundTag payload = new CompoundTag();
        payload.put("Connections", wires);
        return payload;
    }

    @Override
    public void replay(ServerLevel level, ServerSubLevel sub, CompoundTag captured, BlockPos plotShift) {
        try {
            Method create = api().getMethod("createConnection", Level.class, BlockPos.class,
                    BlockPos.class, Direction.class, String.class);
            Method has = api().getMethod("hasConnection", Level.class, BlockPos.class,
                    BlockPos.class, Direction.class, String.class);
            for (Tag entry : captured.getList("Connections", Tag.TAG_COMPOUND)) {
                CompoundTag wire = (CompoundTag) entry;
                BlockPos source = resolve(level, wire.getCompound("SourceEndpoint"));
                BlockPos sink = resolve(level, wire.getCompound("SinkEndpoint"));
                Direction direction = Direction.from3DDataValue(wire.getByte("Direction"));
                String channel = wire.getString("Channel");
                // DBW checks its 64-sink limit before checking duplicates, so querying first is
                // necessary for idempotent journal replay of a source already at that limit.
                if ((Boolean) has.invoke(null, level, source, sink, direction, channel)) continue;
                Object result = create.invoke(null, level, source, sink, direction, channel);
                // A wire shared by two transferred ships is captured by both. Recovery can replay again.
                if (!(result instanceof Enum<?> value)
                        || !(value.name().equals("OK") || value.name().equals("FAIL_EXISTS"))) {
                    throw new IllegalStateException("Drive By Wire rejected restored connection: " + result);
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not restore Drive By Wire connections", e);
        }
    }

    @Override
    public CompoundTag serialize(CompoundTag payload) {
        return payload.copy();
    }

    @Override
    public CompoundTag deserialize(CompoundTag tag) {
        return tag.copy();
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName(MANAGER);
    }

    private static CompoundTag snapshot(ServerLevel level) {
        try {
            Class<?> api = api();
            // Resolve replay before allowing removal, including with an empty network.
            api.getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class,
                    Direction.class, String.class);
            api.getMethod("hasConnection", Level.class, BlockPos.class, BlockPos.class,
                    Direction.class, String.class);
            Object manager = api.getMethod("get", Level.class).invoke(null, level);
            return (CompoundTag) api.getMethod("save", CompoundTag.class).invoke(manager, new CompoundTag());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Drive By Wire compatibility is unavailable; transfer cannot preserve its connections", e);
        }
    }

    private static boolean owns(ServerSubLevel expected, SubLevel actual) {
        return actual != null && expected.getUniqueId().equals(actual.getUniqueId());
    }

    private static BlockPos origin(ServerLevel level, SubLevel sub) {
        var container = SubLevelContainer.getContainer(level);
        int bits = container.getLogPlotSize() + 4;
        // The bridge shifts the pose pivot by the complete plot translation, including
        // vertical section compaction. Content bounds can change during assembly capture.
        return new BlockPos(sub.getPlot().plotPos.x << bits, (int) Math.floor(sub.logicalPose().rotationPoint().y()),
                sub.getPlot().plotPos.z << bits);
    }

    private static CompoundTag endpoint(ServerLevel level, BlockPos pos, SubLevel owner) {
        CompoundTag result = new CompoundTag();
        if (owner != null) {
            result.putUUID("Owner", owner.getUniqueId());
            result.putLong("Position", pos.subtract(origin(level, owner)).asLong());
        } else {
            result.putString("Dimension", level.dimension().location().toString());
            result.putLong("Position", pos.asLong());
        }
        return result;
    }

    private static BlockPos resolve(ServerLevel level, CompoundTag endpoint) {
        BlockPos pos = BlockPos.of(endpoint.getLong("Position"));
        if (!endpoint.hasUUID("Owner")) {
            if (!level.dimension().location().toString().equals(endpoint.getString("Dimension"))) {
                throw new IllegalStateException("Drive By Wire world endpoint is in another dimension");
            }
            return pos;
        }
        var container = SubLevelContainer.getContainer(level);
        SubLevel owner = container == null ? null : container.getSubLevel(endpoint.getUUID("Owner"));
        if (owner == null || owner.isRemoved()) {
            throw new IllegalStateException("Drive By Wire endpoint ship has not been restored: " + endpoint.getUUID("Owner"));
        }
        return origin(level, owner).offset(pos);
    }
}
