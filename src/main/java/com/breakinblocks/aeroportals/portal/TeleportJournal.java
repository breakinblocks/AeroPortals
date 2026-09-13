package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class TeleportJournal {
    private static final String FILE_SUFFIX = ".nbt";
    private static final String COMPLETE_SUFFIX = ".complete";

    private TeleportJournal() {}

    public static boolean write(MinecraftServer server, UUID uuid, ResourceLocation srcDim,
                                ResourceLocation dstDim, int srcMinY, SubLevelData data) {
        return write(server, uuid, srcDim, dstDim, srcMinY, data, new CompoundTag());
    }

    /** All group members must be written successfully before any source member is removed. */
    public static boolean write(MinecraftServer server, UUID uuid, ResourceLocation srcDim,
                                ResourceLocation dstDim, int srcMinY, SubLevelData data, CompoundTag metadata) {
        Path file = entryPath(server, uuid);
        try {
            if (Files.exists(file)) throw new IOException("A previous transfer is still pending for " + uuid);
            assertNoCompletionMarker(server, uuid);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("sub_uuid", uuid);
            entry.putString("src_dim", srcDim.toString());
            entry.putString("dst_dim", dstDim.toString());
            entry.putInt("src_min_y", srcMinY);
            entry.putLong("created_at", System.currentTimeMillis());
            entry.put("sub_level_data", data.fullTag().copy());
            entry.put("transfer_metadata", metadata.copy());
            ServerLevel source = level(server, srcDim);
            ServerSubLevelContainer container = source == null ? null : SubLevelContainer.getContainer(source);
            ServerSubLevel sub = container == null ? null : (ServerSubLevel) container.getSubLevel(uuid);
            if (sub != null && sub.getLastSerializationPointer() != null) {
                entry.put("source_pointer", pointerTag(sub.getLastSerializationPointer()));
            }
            atomicWrite(file, entry);
            return true;
        } catch (IOException | RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: cannot prepare transfer for {}; source must remain intact", uuid, ex);
            return false;
        }
    }

    /** Only for abandoning preparation before source removal. Finished moves must use completeGroup. */
    public static void delete(MinecraftServer server, UUID uuid) {
        tryDelete(entryPath(server, uuid));
    }

    public static boolean complete(MinecraftServer server, UUID uuid, ServerLevel source, ServerLevel destination,
                                   ServerSubLevel arrived) {
        if (arrived == null || !uuid.equals(arrived.getUniqueId()) || arrived.isRemoved()) return false;
        return completeGroup(server, List.of(uuid), source, destination);
    }

    public static boolean complete(MinecraftServer server, UUID uuid, ServerLevel source, ServerLevel destination) {
        return completeGroup(server, List.of(uuid), source, destination);
    }

    /** Retains all entries until every destination snapshot and source retirement is verified on disk. */
    public static boolean completeGroup(MinecraftServer server, Collection<UUID> uuids,
                                        ServerLevel source, ServerLevel destination) {
        return completeAt(server, uuids, source, destination, false);
    }

    public static boolean rollbackGroup(MinecraftServer server, Collection<UUID> uuids,
                                        ServerLevel source, ServerLevel destination) {
        return completeAt(server, uuids, source, destination, true);
    }

    private static boolean completeAt(MinecraftServer server, Collection<UUID> uuids, ServerLevel source,
                                       ServerLevel destination, boolean rollback) {
        try {
            List<Entry> entries = readGroup(server, uuids);
            if (!entries.getFirst().source().equals(source.dimension().location())
                    || !entries.getFirst().destination().equals(destination.dimension().location())) {
                throw new IOException("Completion dimensions do not match the prepared transfer");
            }
            ServerLevel target = rollback ? source : destination;
            ServerLevel retired = rollback ? destination : source;
            JournalStorage.saveAndFlush(target);
            Map<UUID, GlobalSavedSubLevelPointer> pointers = new LinkedHashMap<>();
            for (Entry entry : entries) pointers.put(entry.uuid(), JournalStorage.verifySaved(target, entry.uuid()));
            if (retired != target) JournalStorage.saveAndFlush(retired);
            for (Entry entry : entries) {
                GlobalSavedSubLevelPointer survivor = pointers.get(entry.uuid());
                JournalStorage.verifyRetired(source, entry.uuid(), entry.sourcePointer(), source == target ? survivor : null);
                if (retired != target && (SubLevelContainer.getContainer(retired).getSubLevel(entry.uuid()) != null
                        || !JournalStorage.find(retired, entry.uuid()).isEmpty())) {
                    throw new IOException("Ship still exists on the retired side: " + entry.uuid());
                }
            }
            finish(server, entries, rollback ? "source" : "destination");
            return true;
        } catch (IOException | RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: transfer is not durably complete; retaining recovery entries for {}", uuids, ex);
            return false;
        }
    }

    public static void replayPending(MinecraftServer server) {
        Path dir = pendingDir(server);
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.toList();
        } catch (IOException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: cannot list {}; leaving recovery state intact", dir, ex);
            return;
        }
        // The group decision is durable before removing the first member file.
        for (Path file : files) {
            if (file.getFileName().toString().endsWith(COMPLETE_SUFFIX)) cleanupCompleted(server, file);
        }
        Set<UUID> visited = new HashSet<>();
        for (Path file : files) {
            if (!file.getFileName().toString().endsWith(FILE_SUFFIX) || !Files.exists(file)) continue;
            try {
                Entry entry = readEntry(file);
                if (!visited.add(entry.group())) continue;
                // A malformed completion marker is evidence, never permission to roll the group back.
                if (Files.exists(completionPath(server, entry.group()))) continue;
                replayGroup(server, readGroup(server, entry.members()));
            } catch (IOException | RuntimeException ex) {
                AeroPortals.LOGGER.error("[AeroPortals] journal: cannot recover {}; preserving the entry for retry", file, ex);
            }
        }
    }

    private static void replayGroup(MinecraftServer server, List<Entry> entries) throws IOException {
        Entry first = entries.getFirst();
        ServerLevel source = level(server, first.source());
        ServerLevel destination = level(server, first.destination());
        if (source == null || destination == null) throw new IOException("A transfer dimension is unavailable");
        boolean rollback = entries.stream().allMatch(entry -> entry.metadata().contains("source_snapshot", Tag.TAG_COMPOUND));
        if (!rollback && entries.size() > 1) throw new IOException("Group recovery requires every source snapshot");

        Map<UUID, ServerSubLevel> sources = new LinkedHashMap<>();
        Map<UUID, ServerSubLevel> destinations = new LinkedHashMap<>();
        for (Entry entry : entries) {
            // Include ships whose holding chunks have not loaded at server startup.
            sources.put(entry.uuid(), JournalStorage.loadExisting(source, entry.uuid()));
            destinations.put(entry.uuid(), source == destination ? sources.get(entry.uuid())
                    : JournalStorage.loadExisting(destination, entry.uuid()));
        }
        // Old journals have no rollback snapshots. Keep their original forward-recovery behavior.
        if (!rollback && sources.get(first.uuid()) != null && destinations.get(first.uuid()) == null) rollback = true;
        ServerLevel target = rollback ? source : destination;
        ServerLevel retired = rollback ? destination : source;
        Map<UUID, ServerSubLevel> targets = rollback ? sources : destinations;
        Map<UUID, ServerSubLevel> discarded = rollback ? destinations : sources;
        Map<UUID, BlockPos> shifts = new LinkedHashMap<>();
        List<SubLevelTransferEvent.PlotMove> plotMoves = new ArrayList<>();

        if (rollback && source == destination && entries.stream()
                .allMatch(entry -> entry.metadata().contains("source_snapshot", Tag.TAG_COMPOUND))) {
            // Capture/discard the whole current group while all endpoints still exist. Removing
            // one ship at a time would kick carried entities into the world and invalidate links
            // before the remaining members could be cleaned up.
            discardRecordedCarriers(target, targets, entries);
            removeCopies(target, targets.values());
            for (Entry entry : entries) targets.put(entry.uuid(), null);
        }
        for (Entry entry : entries) {
            CompoundTag baseline = entry.metadata().contains("source_snapshot", Tag.TAG_COMPOUND)
                    ? entry.metadata().getCompound("source_snapshot") : entry.data();
            ServerSubLevel sub = targets.get(entry.uuid());
            if (sub == null) {
                SubLevelData data = SubLevelSerializer.fromData((rollback ? baseline : entry.data()).copy());
                SableBridge.SourceInfo info = new SableBridge.SourceInfo(source.dimension(), entry.sourceMinY(),
                        rollback ? Vec3.ZERO : translation(entry.metadata()), oldRegion(entry), entry.metadata().getInt("region_blocks"));
                SableBridge.Loaded loaded = SableBridge.reloadInDestination(info, target,
                        SubLevelContainer.getContainer(target), data);
                if (loaded == null) throw new IOException("Could not recover group member " + entry.uuid());
                sub = loaded.sub();
                shifts.put(entry.uuid(), loaded.shift());
                targets.put(entry.uuid(), sub);
            } else {
                shifts.put(entry.uuid(), plotShift(baseline, sub));
            }
            BlockPos region = oldRegion(entry);
            if (region != null) plotMoves.add(new SubLevelTransferEvent.PlotMove(entry.uuid(), region,
                    entry.metadata().getInt("region_blocks"), shifts.get(entry.uuid())));
        }
        for (Entry entry : entries) {
            if (!SableBridge.replayJournalCarriers(target, targets.get(entry.uuid()), entry.metadata(), shifts.get(entry.uuid()))) {
                throw new IOException("Carrier recovery failed for " + entry.uuid());
            }
        }
        for (Entry entry : entries) {
            NeoForge.EVENT_BUS.post(new SubLevelTransferEvent(entry.uuid(), targets.get(entry.uuid()), source, target,
                    rollback ? Vec3.ZERO : translation(entry.metadata()), shifts.get(entry.uuid()), plotMoves));
        }
        // Persist the chosen side before removing any surviving copy from the opposite side.
        JournalStorage.saveAndFlush(target);
        for (Entry entry : entries) JournalStorage.verifySaved(target, entry.uuid());
        if (retired != target) {
            discardRecordedCarriers(retired, discarded, entries);
            removeCopies(retired, discarded.values());
        }
        if (!completeAt(server, entries.stream().map(Entry::uuid).toList(), source, destination, rollback)) {
            throw new IOException("Recovered group still needs durable completion");
        }
        AeroPortals.LOGGER.warn("[AeroPortals] journal: recovered {} ship(s) into {}", entries.size(), target.dimension().location());
    }

    private static void discardRecordedCarriers(ServerLevel level, Map<UUID, ServerSubLevel> copies,
                                                List<Entry> entries) throws IOException {
        List<TransferCarrier<?>> carriers = AeroPortalsApi.carriers();
        Set<String> available = new HashSet<>();
        carriers.forEach(carrier -> available.add(carrier.id().toString()));
        // Resolve every required adapter before changing any retired state.
        for (Entry entry : entries) {
            for (String id : entry.metadata().getCompound("carriers").getAllKeys()) {
                if (!available.contains(id)) throw new IOException("Missing cleanup carrier " + id);
            }
        }
        IOException failure = null;
        for (Entry entry : entries) {
            ServerSubLevel sub = copies.get(entry.uuid());
            if (sub == null || sub.isRemoved()) continue;
            CompoundTag recorded = entry.metadata().getCompound("carriers");
            for (TransferCarrier<?> carrier : carriers) {
                String id = carrier.id().toString();
                if (!recorded.contains(id)) continue;
                try {
                    carrier.discard(level, sub);
                } catch (RuntimeException ex) {
                    if (failure == null) failure = new IOException("Could not discard all carried state from retired copies");
                    failure.addSuppressed(new IOException("Cleanup carrier " + id + " failed for " + entry.uuid(), ex));
                }
            }
        }
        if (failure != null) throw failure;
    }

    private static void removeCopies(ServerLevel level, Collection<ServerSubLevel> copies) throws IOException {
        IOException failure = null;
        for (ServerSubLevel sub : copies) {
            if (sub == null || sub.isRemoved()) continue;
            try {
                SubLevelContainer.getContainer(level).removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            } catch (RuntimeException ex) {
                if (failure == null) failure = new IOException("Could not retire all opposite ship copies");
                failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }

    private record Entry(UUID uuid, ResourceLocation source, ResourceLocation destination, int sourceMinY,
                         CompoundTag data, CompoundTag metadata, GlobalSavedSubLevelPointer sourcePointer,
                         UUID group, List<UUID> members) {}

    private static Entry readEntry(Path file) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        if (!tag.hasUUID("sub_uuid") || !tag.contains("sub_level_data", Tag.TAG_COMPOUND)
                || !tag.contains("src_min_y", Tag.TAG_INT)) {
            throw new IOException("Malformed journal entry " + file);
        }
        UUID uuid = tag.getUUID("sub_uuid");
        if (!file.getFileName().toString().equals(uuid + FILE_SUFFIX)) throw new IOException("Journal filename/UUID mismatch");
        CompoundTag data = tag.getCompound("sub_level_data");
        validateSnapshot(data, uuid);
        CompoundTag metadata = tag.getCompound("transfer_metadata");
        if (metadata.contains("source_snapshot", Tag.TAG_COMPOUND)) validateSnapshot(metadata.getCompound("source_snapshot"), uuid);
        UUID group = metadata.hasUUID("group_id") ? metadata.getUUID("group_id") : uuid;
        List<UUID> members = members(metadata, "group_members");
        if (members.isEmpty()) members = List.of(uuid);
        if (!members.contains(uuid) || !members.contains(group) || new HashSet<>(members).size() != members.size()) {
            throw new IOException("Malformed journal group for " + uuid);
        }
        GlobalSavedSubLevelPointer pointer = tag.contains("source_pointer", Tag.TAG_COMPOUND)
                ? readPointer(tag.getCompound("source_pointer")) : null;
        return new Entry(uuid, ResourceLocation.parse(tag.getString("src_dim")), ResourceLocation.parse(tag.getString("dst_dim")),
                tag.getInt("src_min_y"), data, metadata, pointer, group, List.copyOf(members));
    }

    private static void validateSnapshot(CompoundTag data, UUID uuid) throws IOException {
        if (!data.hasUUID("uuid") || !uuid.equals(data.getUUID("uuid"))
                || !data.contains("plot", Tag.TAG_COMPOUND) || !data.contains("pose", Tag.TAG_COMPOUND)
                || !data.contains("world_bounds", Tag.TAG_COMPOUND)) {
            throw new IOException("Malformed ship snapshot for " + uuid);
        }
    }

    private static List<Entry> readGroup(MinecraftServer server, Collection<UUID> uuids) throws IOException {
        if (uuids.isEmpty()) throw new IOException("Empty transfer group");
        List<Entry> entries = new ArrayList<>();
        Set<UUID> expected = new HashSet<>(uuids);
        if (expected.size() != uuids.size()) throw new IOException("Duplicate group member");
        for (UUID uuid : uuids) {
            Entry entry = readEntry(entryPath(server, uuid));
            if (!new HashSet<>(entry.members()).equals(expected)) throw new IOException("Incomplete transfer group " + entry.group());
            if (!entries.isEmpty()) {
                Entry first = entries.getFirst();
                if (!first.group().equals(entry.group()) || !first.source().equals(entry.source())
                        || !first.destination().equals(entry.destination())) throw new IOException("Inconsistent transfer group");
            }
            entries.add(entry);
        }
        return entries;
    }

    private static void finish(MinecraftServer server, List<Entry> entries, String outcome) throws IOException {
        CompoundTag marker = new CompoundTag();
        UUID group = entries.getFirst().group();
        marker.putUUID("group_id", group);
        marker.putString("outcome", outcome);
        ListTag members = new ListTag();
        for (Entry entry : entries) {
            CompoundTag member = new CompoundTag();
            member.putUUID("uuid", entry.uuid());
            members.add(member);
        }
        marker.put("members", members);
        Path file = completionPath(server, group);
        atomicWrite(file, marker);
        cleanupCompleted(server, file);
    }

    private static void cleanupCompleted(MinecraftServer server, Path markerPath) {
        try {
            CompoundTag marker = NbtIo.readCompressed(markerPath, NbtAccounter.unlimitedHeap());
            if (!marker.hasUUID("group_id") || !(marker.getString("outcome").equals("source")
                    || marker.getString("outcome").equals("destination"))) throw new IOException("Malformed completion marker");
            UUID group = marker.getUUID("group_id");
            if (!markerPath.equals(completionPath(server, group))) throw new IOException("Completion marker filename mismatch");
            List<UUID> members = members(marker, "members");
            if (members.isEmpty() || !members.contains(group) || new HashSet<>(members).size() != members.size()) {
                throw new IOException("Completion marker has malformed group members");
            }
            for (UUID uuid : members) Files.deleteIfExists(entryPath(server, uuid));
            forceDirectory(pendingDir(server));
            Files.deleteIfExists(markerPath);
            forceDirectory(pendingDir(server));
        } catch (IOException | RuntimeException ex) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: preserving completion marker {} after cleanup failure", markerPath, ex);
        }
    }

    private static List<UUID> members(CompoundTag tag, String key) throws IOException {
        List<UUID> members = new ArrayList<>();
        if (tag.contains(key) && !tag.contains(key, Tag.TAG_LIST)) throw new IOException("Malformed journal member list");
        if (tag.get(key) instanceof ListTag values && !values.isEmpty() && values.getElementType() != Tag.TAG_COMPOUND) {
            throw new IOException("Malformed journal member list contents");
        }
        for (Tag value : tag.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag member = (CompoundTag) value;
            if (!member.hasUUID("uuid")) throw new IOException("Journal member has no UUID");
            members.add(member.getUUID("uuid"));
        }
        return members;
    }

    private static void assertNoCompletionMarker(MinecraftServer server, UUID uuid) throws IOException {
        Path dir = pendingDir(server);
        if (!Files.isDirectory(dir)) return;
        List<Path> markers;
        try (Stream<Path> files = Files.list(dir)) {
            markers = files.filter(path -> path.getFileName().toString().endsWith(COMPLETE_SUFFIX)).toList();
        }
        for (Path path : markers) {
            CompoundTag marker = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            List<UUID> pending = members(marker, "members");
            if (pending.isEmpty() || !marker.hasUUID("group_id")) {
                throw new IOException("An unreadable completion marker needs repair before new transfers: " + path);
            }
            if (pending.contains(uuid)) throw new IOException("Previous transfer cleanup is still pending for " + uuid);
        }
    }

    private static BlockPos oldRegion(Entry entry) {
        return entry.metadata().contains("old_region_min", Tag.TAG_LONG)
                ? BlockPos.of(entry.metadata().getLong("old_region_min")) : null;
    }

    private static BlockPos plotShift(CompoundTag baseline, ServerSubLevel sub) {
        var original = SableNBTUtils.readPose3d(baseline.getCompound("pose")).rotationPoint();
        var current = sub.logicalPose().rotationPoint();
        return new BlockPos((int) Math.round(current.x() - original.x()), (int) Math.round(current.y() - original.y()),
                (int) Math.round(current.z() - original.z()));
    }

    private static Vec3 translation(CompoundTag metadata) {
        ListTag value = metadata.getList("world_translation", Tag.TAG_DOUBLE);
        return value.size() == 3 ? new Vec3(value.getDouble(0), value.getDouble(1), value.getDouble(2)) : Vec3.ZERO;
    }

    private static CompoundTag pointerTag(GlobalSavedSubLevelPointer pointer) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("chunk_x", pointer.chunkPos().x);
        tag.putInt("chunk_z", pointer.chunkPos().z);
        tag.putShort("storage_index", pointer.storageIndex());
        tag.putShort("sub_level_index", pointer.subLevelIndex());
        return tag;
    }

    private static GlobalSavedSubLevelPointer readPointer(CompoundTag tag) throws IOException {
        if (!tag.contains("chunk_x", Tag.TAG_INT) || !tag.contains("chunk_z", Tag.TAG_INT)
                || !tag.contains("storage_index", Tag.TAG_SHORT) || !tag.contains("sub_level_index", Tag.TAG_SHORT)
                || tag.getShort("storage_index") < 0 || tag.getShort("sub_level_index") < 0) {
            throw new IOException("Malformed source pointer");
        }
        return new GlobalSavedSubLevelPointer(new ChunkPos(tag.getInt("chunk_x"), tag.getInt("chunk_z")),
                tag.getShort("storage_index"), tag.getShort("sub_level_index"));
    }

    private static ServerLevel level(MinecraftServer server, ResourceLocation dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static void atomicWrite(Path file, CompoundTag entry) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            NbtIo.writeCompressed(entry, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Refuse a non-atomic fallback: interrupted rewrites must leave the prior entry usable.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            forceDirectory(file.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows has no directory FileChannels; file force and atomic rename are supported there.
        if (System.getProperty("os.name", "").startsWith("Windows")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void tryDelete(Path file) {
        try {
            if (Files.deleteIfExists(file)) forceDirectory(file.getParent());
        } catch (IOException ex) {
            AeroPortals.LOGGER.warn("[AeroPortals] journal: failed to delete {}; preserving it", file, ex);
        }
    }

    private static Path entryPath(MinecraftServer server, UUID uuid) {
        return pendingDir(server).resolve(uuid + FILE_SUFFIX);
    }

    private static Path completionPath(MinecraftServer server, UUID group) {
        return pendingDir(server).resolve(group + COMPLETE_SUFFIX);
    }

    public static Path pendingDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("aeroportals").resolve("pending");
    }
}
