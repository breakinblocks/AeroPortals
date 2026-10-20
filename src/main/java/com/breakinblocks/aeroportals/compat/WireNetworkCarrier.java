package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.api.TransferCarrier;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

abstract class WireNetworkCarrier implements TransferCarrier<CompoundTag> {
    private final String modId;
    private final String modName;
    private final String manager;

    WireNetworkCarrier(String modId, String modName, String manager) {
        this.modId = modId;
        this.modName = modName;
        this.manager = manager;
    }

    protected record Wire(BlockPos source, BlockPos sink, CompoundTag saved) {}

    protected abstract void requireRestore(Class<?> api) throws ReflectiveOperationException;

    protected abstract void restore(ServerLevel level, Class<?> api, List<Wire> wires) throws ReflectiveOperationException;

    protected void validateWire(SubLevel source, SubLevel sink) {}

    @Override
    public boolean isEnabled() {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void discard(ServerLevel level, ServerSubLevel sub) {
        // DBW's REMOVED observer retires this dimension's connections with the ship.
    }

    @Override
    public void validateGroup(ServerLevel src, List<ServerSubLevel> group, ServerLevel dst) {
        Set<UUID> owners = new HashSet<>();
        group.forEach(sub -> owners.add(sub.getUniqueId()));
        for (Tag entry : snapshot(src).getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) entry;
            SubLevel source = Sable.HELPER.getContaining(src, BlockPos.of(wire.getLong("Source")));
            SubLevel sink = Sable.HELPER.getContaining(src, BlockPos.of(wire.getLong("Sink")));
            boolean sourceMoving = source != null && owners.contains(source.getUniqueId());
            boolean sinkMoving = sink != null && owners.contains(sink.getUniqueId());
            if (src != dst && sourceMoving != sinkMoving) {
                throw new IllegalStateException(modName + " connection crosses the transfer group; disconnect it or move both endpoints together");
            }
            if (sourceMoving || sinkMoving) validateWire(source, sink);
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
        List<Wire> wires = new ArrayList<>();
        for (Tag entry : captured.getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) entry;
            wires.add(new Wire(resolve(level, wire.getCompound("SourceEndpoint")),
                    resolve(level, wire.getCompound("SinkEndpoint")), wire));
        }
        try {
            restore(level, api(), wires);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not restore " + modName + " connections", e);
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

    protected CompoundTag snapshot(ServerLevel level) {
        try {
            Class<?> api = api();
            // Resolve replay before allowing removal, including with an empty network.
            requireRestore(api);
            Object network = api.getMethod("get", Level.class).invoke(null, level);
            return (CompoundTag) api.getMethod("save", CompoundTag.class).invoke(network, new CompoundTag());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException(modName + " compatibility is unavailable; transfer cannot preserve its connections", e);
        }
    }

    private Class<?> api() throws ClassNotFoundException {
        return Class.forName(manager);
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

    private BlockPos resolve(ServerLevel level, CompoundTag endpoint) {
        BlockPos pos = BlockPos.of(endpoint.getLong("Position"));
        if (!endpoint.hasUUID("Owner")) {
            if (!level.dimension().location().toString().equals(endpoint.getString("Dimension"))) {
                throw new IllegalStateException(modName + " world endpoint is in another dimension");
            }
            return pos;
        }
        var container = SubLevelContainer.getContainer(level);
        SubLevel owner = container == null ? null : container.getSubLevel(endpoint.getUUID("Owner"));
        if (owner == null || owner.isRemoved()) {
            throw new IllegalStateException(modName + " endpoint ship has not been restored: " + endpoint.getUUID("Owner"));
        }
        return origin(level, owner).offset(pos);
    }
}
