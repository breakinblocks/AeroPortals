package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class ShipDirectory {
    private static final String REGION_SUFFIX = ".slvlr";
    private static final int REGION_CHUNKS = 32;

    public enum State { LOADED, HELD, STORED }

    public record Entry(UUID uuid, State state, Vec3 position, String name, ChunkPos chunk) {}

    private ShipDirectory() {}

    public static List<Entry> list(ServerLevel level) {
        Map<UUID, Entry> entries = new LinkedHashMap<>();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();

        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            AABB box = AabbUtil.worldAabb(sub);
            Vec3 centre = box.getCenter();
            entries.put(sub.getUniqueId(), new Entry(sub.getUniqueId(), State.LOADED, centre,
                    sub.getName(), new ChunkPos((int) Math.floor(centre.x) >> 4, (int) Math.floor(centre.z) >> 4)));
        }

        SubLevelStorage storage = container.getHoldingChunkMap().getStorage();
        for (ChunkPos chunkPos : storedChunks(storage)) {
            SubLevelHoldingChunk chunk = storage.attemptLoadHoldingChunk(chunkPos);
            if (chunk == null) continue;
            for (SavedSubLevelPointer pointer : chunk.getSubLevelPointers()) {
                SubLevelData data = storage.attemptLoadSubLevel(chunkPos, pointer);
                if (data == null) continue;
                if (entries.containsKey(data.uuid())) continue;
                HoldingSubLevel held = container.getHoldingChunkMap().getHoldingSubLevel(data.uuid());
                BoundingBox3dc bounds = data.bounds();
                Vec3 centre = new Vec3(
                        (bounds.minX() + bounds.maxX()) / 2.0,
                        (bounds.minY() + bounds.maxY()) / 2.0,
                        (bounds.minZ() + bounds.maxZ()) / 2.0);
                entries.put(data.uuid(), new Entry(data.uuid(),
                        held != null ? State.HELD : State.STORED, centre, null, chunkPos));
            }
        }

        return new ArrayList<>(entries.values());
    }

    public static Entry find(ServerLevel level, UUID uuid) {
        for (Entry entry : list(level)) {
            if (entry.uuid().equals(uuid)) return entry;
        }
        return null;
    }

    private static List<ChunkPos> storedChunks(SubLevelStorage storage) {
        Path folder = storage.getFolder();
        if (!Files.isDirectory(folder)) return List.of();
        List<ChunkPos> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.endsWith(REGION_SUFFIX)) return;
                String[] parts = name.substring(0, name.length() - REGION_SUFFIX.length()).split("\\.");
                if (parts.length != 3 || !parts[0].equals("r")) return;
                int regionX;
                int regionZ;
                try {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    return;
                }
                for (int x = 0; x < REGION_CHUNKS; x++) {
                    for (int z = 0; z < REGION_CHUNKS; z++) {
                        chunks.add(new ChunkPos((regionX << 5) + x, (regionZ << 5) + z));
                    }
                }
            });
        } catch (IOException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not read stored airships in {}: {}", folder, e.getMessage());
        }
        return chunks;
    }
}
