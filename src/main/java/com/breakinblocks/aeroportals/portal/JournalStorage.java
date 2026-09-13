package com.breakinblocks.aeroportals.portal;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Strict journal readback: Sable's attempt* methods log IO errors and return success-shaped values. */
final class JournalStorage {
    record Stored(GlobalSavedSubLevelPointer pointer, SubLevelData data) {}

    private JournalStorage() {}

    static void saveAndFlush(ServerLevel level) throws IOException {
        // Carrier entities and mod SavedData are outside the Sable plot snapshot.
        // Sable's ServerLevel.save mixin saves the holding map before vanilla chunk/entity data.
        level.save(null, true, false);
        var map = container(level).getHoldingChunkMap();
        map.getStorage().flush();
    }

    static GlobalSavedSubLevelPointer verifySaved(ServerLevel level, UUID uuid) throws IOException {
        ServerSubLevelContainer container = container(level);
        ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(uuid);
        if (sub == null || sub.isRemoved()) throw new IOException("Ship is not active for readback: " + uuid);
        GlobalSavedSubLevelPointer pointer = sub.getLastSerializationPointer();
        if (pointer == null) throw new IOException("Destination ship has no saved pointer: " + uuid);
        CompoundTag disk = read(level, pointer);
        if (disk == null || !disk.hasUUID("uuid") || !uuid.equals(disk.getUUID("uuid"))) {
            throw new IOException("Destination pointer does not contain ship " + uuid);
        }
        SubLevelData stored = SubLevelSerializer.fromData(disk);
        CompoundTag expected = SubLevelSerializer.toData(sub, stored.dependencies()).fullTag();
        if (!disk.equals(expected)) throw new IOException("Stored ship does not match the live snapshot: " + uuid);
        CompoundTag chunk = readHoldingChunk(level, pointer.chunkPos());
        boolean indexed = false;
        if (chunk != null) {
            for (int packed : chunk.getIntArray("pointers")) {
                if (SavedSubLevelPointer.unpack(packed).equals(pointer.local())) indexed = true;
            }
        }
        if (!indexed) throw new IOException("Destination ship is missing from its holding index: " + uuid);
        return pointer;
    }

    static void verifyRetired(ServerLevel level, UUID uuid, GlobalSavedSubLevelPointer old,
                              GlobalSavedSubLevelPointer survivingPointer) throws IOException {
        if (old == null || old.equals(survivingPointer)) return;
        CompoundTag data = read(level, old);
        if (data != null && (!data.hasUUID("uuid") || uuid.equals(data.getUUID("uuid")))) {
            throw new IOException("Original source pointer still contains ship " + uuid);
        }
        CompoundTag chunk = readHoldingChunk(level, old.chunkPos());
        if (chunk != null && data == null) {
            for (int packed : chunk.getIntArray("pointers")) {
                if (SavedSubLevelPointer.unpack(packed).equals(old.local())) {
                    throw new IOException("Source holding index still points to retired ship " + uuid);
                }
            }
        }
    }

    static ServerSubLevel loadExisting(ServerLevel level, UUID uuid) throws IOException {
        ServerSubLevelContainer container = container(level);
        ServerSubLevel loaded = (ServerSubLevel) container.getSubLevel(uuid);
        if (loaded != null && !loaded.isRemoved()) return loaded;
        var held = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
        if (held != null) {
            if (held.pointer() == null) {
                saveAndFlush(level);
                loaded = (ServerSubLevel) container.getSubLevel(uuid);
                if (loaded != null && !loaded.isRemoved()) return loaded;
            }
            if (held.pointer() == null) throw new IOException("Held ship has no saved pointer: " + uuid);
            container.getHoldingChunkMap().snatchAndLoad(held.pointer(), uuid);
        } else {
            List<Stored> stored = find(level, uuid);
            if (stored.size() > 1) throw new IOException("Multiple stored copies of ship " + uuid + " in " + level.dimension().location());
            if (stored.isEmpty()) return null;
            container.getHoldingChunkMap().snatchAndLoad(stored.getFirst().pointer(), uuid);
        }
        loaded = (ServerSubLevel) container.getSubLevel(uuid);
        if (loaded == null || loaded.isRemoved()) throw new IOException("Stored ship could not be loaded: " + uuid);
        return loaded;
    }

    static List<Stored> find(ServerLevel level, UUID uuid) throws IOException {
        Path folder = folder(level);
        List<Stored> result = new ArrayList<>();
        if (!Files.isDirectory(folder)) return result;
        List<Path> regions;
        try (Stream<Path> files = Files.list(folder)) {
            regions = files.filter(path -> path.getFileName().toString().endsWith(SubLevelRegionFile.FILE_EXTENSION)).toList();
        }
        for (Path path : regions) {
            // Sable creates empty region files while looking up chunks that have never held ships.
            if (Files.size(path) == 0) continue;
            String name = path.getFileName().toString();
            String[] parts = name.substring(0, name.length() - SubLevelRegionFile.FILE_EXTENSION.length()).split("\\.");
            if (parts.length != 3 || !parts[0].equals("r")) continue;
            final int rx;
            final int rz;
            try {
                rx = Integer.parseInt(parts[1]);
                rz = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            checkHeader(path);
            try (SubLevelRegionFile region = new SubLevelRegionFile(path, folder.resolve("r." + rx + "." + rz + ".r"))) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        CompoundTag chunk = region.read(region.getIndex(x, z));
                        if (chunk == null) continue;
                        ChunkPos pos = new ChunkPos((rx << 5) + x, (rz << 5) + z);
                        for (int packed : chunk.getIntArray("pointers")) {
                            SavedSubLevelPointer local = SavedSubLevelPointer.unpack(packed);
                            GlobalSavedSubLevelPointer pointer = new GlobalSavedSubLevelPointer(pos, local.storageIndex(), local.subLevelIndex());
                            CompoundTag tag = read(level, pointer);
                            if (tag == null) throw new IOException("Holding index points to missing ship data: " + pointer);
                            if (!tag.hasUUID("uuid")) throw new IOException("Stored ship has no UUID: " + pointer);
                            if (uuid.equals(tag.getUUID("uuid"))) result.add(new Stored(pointer, SubLevelSerializer.fromData(tag)));
                        }
                    }
                }
            }
        }
        return result;
    }

    static CompoundTag read(ServerLevel level, GlobalSavedSubLevelPointer pointer) throws IOException {
        Path folder = folder(level);
        String region = "r." + pointer.chunkPos().getRegionX() + "." + pointer.chunkPos().getRegionZ();
        Path file = folder.resolve(region + "." + pointer.storageIndex() + SubLevelStorageFile.FILE_EXTENSION);
        if (!Files.exists(file)) return null;
        checkHeader(file);
        // Matches SubLevelStorage.getRegionStorageFile's external-file directory.
        try (SubLevelStorageFile storage = new SubLevelStorageFile(file, folder.resolve(region + ".r"))) {
            return storage.read(pointer.subLevelIndex());
        }
    }

    private static CompoundTag readHoldingChunk(ServerLevel level, ChunkPos pos) throws IOException {
        Path folder = folder(level);
        String region = "r." + pos.getRegionX() + "." + pos.getRegionZ();
        Path file = folder.resolve(region + SubLevelRegionFile.FILE_EXTENSION);
        if (!Files.exists(file)) return null;
        checkHeader(file);
        try (SubLevelRegionFile storage = new SubLevelRegionFile(file, folder.resolve(region + ".r"))) {
            return storage.read(storage.getIndex(pos.getRegionLocalX(), pos.getRegionLocalZ()));
        }
    }

    private static void checkHeader(Path path) throws IOException {
        if (Files.size(path) < 4096) throw new IOException("Truncated Sable storage header: " + path);
    }

    private static Path folder(ServerLevel level) throws IOException {
        return container(level).getHoldingChunkMap().getStorage().getFolder();
    }

    private static ServerSubLevelContainer container(ServerLevel level) throws IOException {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IOException("No Sable container for " + level.dimension().location());
        return container;
    }
}
