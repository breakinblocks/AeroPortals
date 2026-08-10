package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class TeleportJournal {
    private static final String DATA_DIR = "data";
    private static final String MOD_DIR = "aeroportals";
    private static final String PENDING_DIR = "pending";
    private static final String FILE_SUFFIX = ".nbt";

    private TeleportJournal() {}

    public static void write(
            MinecraftServer server,
            UUID subUuid,
            ResourceLocation srcDim,
            ResourceLocation dstDim,
            int srcMinBuildHeight,
            SubLevelData data) {
        try {
            Path dir = pendingDir(server);
            Files.createDirectories(dir);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("sub_uuid", subUuid);
            entry.putString("src_dim", srcDim.toString());
            entry.putString("dst_dim", dstDim.toString());
            entry.putInt("src_min_y", srcMinBuildHeight);
            entry.putLong("created_at", System.currentTimeMillis());
            entry.put("sub_level_data", data.fullTag().copy());
            Path file = dir.resolve(subUuid + FILE_SUFFIX);
            NbtIo.writeCompressed(entry, file);
            AeroPortals.LOGGER.debug("[AeroPortals] journal: wrote pending entry for sub {} ({} -> {}) at {}",
                    subUuid, srcDim, dstDim, file);
        } catch (IOException e) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: failed to write pending entry for sub {}: {}",
                    subUuid, e.getMessage());
        }
    }

    public static void delete(MinecraftServer server, UUID subUuid) {
        try {
            Path file = pendingDir(server).resolve(subUuid + FILE_SUFFIX);
            if (Files.deleteIfExists(file)) {
                AeroPortals.LOGGER.debug("[AeroPortals] journal: cleared pending entry for sub {}", subUuid);
            }
        } catch (IOException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] journal: failed to delete pending entry for sub {}: {}",
                    subUuid, e.getMessage());
        }
    }

    public static void replayPending(MinecraftServer server) {
        Path dir = pendingDir(server);
        if (!Files.isDirectory(dir)) return;

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX)).forEach(files::add);
        } catch (IOException e) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: failed to list pending dir {}: {}", dir, e.getMessage());
            return;
        }

        if (files.isEmpty()) return;
        AeroPortals.LOGGER.debug("[AeroPortals] journal: scanning {} pending entries", files.size());

        for (Path file : files) {
            replayOne(server, file);
        }
    }

    private static void replayOne(MinecraftServer server, Path file) {
        CompoundTag entry;
        try {
            entry = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: failed to read {}: {}; removing", file, e.getMessage());
            tryDelete(file);
            return;
        }

        UUID subUuid;
        ResourceLocation srcDim;
        ResourceLocation dstDim;
        int srcMinBuildHeight;
        CompoundTag subLevelDataTag;
        try {
            subUuid = entry.getUUID("sub_uuid");
            srcDim = ResourceLocation.parse(entry.getString("src_dim"));
            dstDim = ResourceLocation.parse(entry.getString("dst_dim"));
            srcMinBuildHeight = entry.getInt("src_min_y");
            subLevelDataTag = entry.getCompound("sub_level_data");
        } catch (RuntimeException e) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: malformed entry in {}: {}; removing", file, e.getMessage());
            tryDelete(file);
            return;
        }

        ServerLevel srcLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, srcDim));
        ServerLevel dstLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dstDim));
        if (dstLevel == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] journal: destination dim {} not loaded; leaving entry for next start", dstDim);
            return;
        }

        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (dstContainer == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] journal: no container for dst {}; leaving entry", dstDim);
            return;
        }

        if (dstContainer.getSubLevel(subUuid) != null
                || dstContainer.getHoldingChunkMap().getHoldingSubLevel(subUuid) != null) {
            AeroPortals.LOGGER.debug("[AeroPortals] journal: sub {} already present in dst {} (move completed before crash); clearing entry",
                    subUuid, dstDim);
            tryDelete(file);
            return;
        }

        if (srcLevel != null) {
            ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
            if (srcContainer != null
                    && (srcContainer.getSubLevel(subUuid) != null
                            || srcContainer.getHoldingChunkMap().getHoldingSubLevel(subUuid) != null)) {
                AeroPortals.LOGGER.debug("[AeroPortals] journal: sub {} still in src {} (crashed before remove); clearing entry",
                        subUuid, srcDim);
                tryDelete(file);
                return;
            }
        }

        SubLevelData data = SubLevelSerializer.fromData(subLevelDataTag);
        if (data == null) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: fromData returned null for sub {}; removing entry", subUuid);
            tryDelete(file);
            return;
        }

        AeroPortals.LOGGER.warn("[AeroPortals] journal: recovering lost sub {} into dst {}", subUuid, dstDim);
        SableBridge.Loaded recovered = SableBridge.reloadInDestination(
                SableBridge.SourceInfo.of(ResourceKey.create(Registries.DIMENSION, srcDim), srcMinBuildHeight),
                dstLevel, dstContainer, data);
        if (recovered == null) {
            AeroPortals.LOGGER.error("[AeroPortals] journal: fullyLoad failed for sub {}; leaving entry in place for next start", subUuid);
            return;
        }
        AeroPortals.LOGGER.debug("[AeroPortals] journal: recovered sub {} into dst {}", subUuid, dstDim);
        tryDelete(file);
    }

    private static void tryDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] journal: failed to delete {}: {}", file, e.getMessage());
        }
    }

    public static Path pendingDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DATA_DIR).resolve(MOD_DIR).resolve(PENDING_DIR);
    }
}
