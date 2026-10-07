package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.SubLevelTransferEvent;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@EventBusSubscriber(modid = AeroPortals.MOD_ID)
public final class AeronauticsBalloonFixup implements TransferCarrier<List<CompoundTag>> {
    private static final String MOD_ID = "aeronautics";
    private static final String HOT_AIR = "dev.eriksonn.aeronautics.content.blocks.hot_air.";
    private static final String GAS_PROVIDER_CLASS = HOT_AIR + "BlockEntityLiftingGasProvider";
    private static final String BALLOON_MAP_CLASS = HOT_AIR + "balloon.map.BalloonMap";
    private static final String SAVED_BALLOON_CLASS = HOT_AIR + "balloon.map.SavedBalloon";
    private static final String SERVER_BALLOON_CLASS = HOT_AIR + "balloon.ServerBalloon";

    private static volatile boolean attempted = false;
    private static volatile Reflection reflection = null;

    private record Reflection(
            Class<?> gasProvider,
            Method canOutputGas,
            Method tickBalloonLogic,
            Object balloonMaps,
            Method mapForLevel,
            Method getBalloons,
            Method getUnloadedBalloons,
            Method getBalloonAt,
            Method markDirty,
            Method saveBalloon,
            Class<?> serverBalloon,
            Method getControllerPos,
            Method loadFrom,
            Codec<Object> savedCodec,
            Method savedBounds,
            Method savedControllerPos,
            Method savedGasData,
            Constructor<?> savedConstructor) {}

    @Override
    public ResourceLocation id() {
        return AeroPortals.id("aeronautics_balloons");
    }

    @Override
    public boolean isEnabled() {
        return ModList.get().isLoaded(MOD_ID) && resolve() != null;
    }

    @Override
    public List<CompoundTag> capture(ServerLevel srcLevel, ServerSubLevel sub) {
        Reflection r = resolve();
        if (r == null) return null;
        try {
            Object map = r.mapForLevel().invoke(r.balloonMaps(), srcLevel);
            List<CompoundTag> saved = new ArrayList<>();
            for (Object balloon : (Iterable<?>) r.getBalloons().invoke(map)) {
                if (!r.serverBalloon().isInstance(balloon)) continue;
                BlockPos controller = (BlockPos) r.getControllerPos().invoke(balloon);
                if (Sable.HELPER.getContaining(srcLevel, controller) != sub) continue;
                Object snapshot = r.saveBalloon().invoke(null, balloon);
                Tag encoded = r.savedCodec().encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();
                if (encoded instanceof CompoundTag tag) saved.add(tag);
            }
            if (saved.isEmpty()) return null;
            AeroPortals.LOGGER.debug("[AeroPortals] captured {} Aeronautics balloon(s) with their gas from sub {}",
                    saved.size(), sub.getUniqueId());
            return saved;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not read Aeronautics balloon state", e);
        }
    }

    @Override
    public void discard(ServerLevel level, ServerSubLevel sub) {}

    @Override
    public void replay(ServerLevel dstLevel, ServerSubLevel newSub, List<CompoundTag> captured, BlockPos plotShift) {
        Reflection r = resolve();
        if (r == null) throw new IllegalStateException("Aeronautics balloon classes are unavailable");
        try {
            Object map = r.mapForLevel().invoke(r.balloonMaps(), dstLevel);
            Collection<?> unloaded = (Collection<?>) r.getUnloadedBalloons().invoke(map);
            for (CompoundTag tag : captured) {
                Object decoded = r.savedCodec().parse(NbtOps.INSTANCE, tag).getOrThrow();
                BlockPos controller = ((BlockPos) r.savedControllerPos().invoke(decoded)).offset(plotShift);
                BoundingBox3i bounds = new BoundingBox3i((BoundingBox3ic) r.savedBounds().invoke(decoded))
                        .move(plotShift.getX(), plotShift.getY(), plotShift.getZ());
                Object shifted = r.savedConstructor().newInstance(bounds, controller, r.savedGasData().invoke(decoded));

                Object live = r.getBalloonAt().invoke(map, controller);
                if (r.serverBalloon().isInstance(live)) {
                    r.loadFrom().invoke(live, shifted);
                    continue;
                }
                unloaded.removeIf(entry -> overlaps(r, entry, controller, bounds));
                @SuppressWarnings("unchecked")
                Collection<Object> writable = (Collection<Object>) unloaded;
                writable.add(shifted);
            }
            r.markDirty().invoke(map);
            AeroPortals.LOGGER.debug("[AeroPortals] restored gas for {} Aeronautics balloon(s) on sub {} (shift {})",
                    captured.size(), newSub.getUniqueId(), plotShift);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not restore Aeronautics balloon state", e);
        }
    }

    @Override
    public CompoundTag serialize(List<CompoundTag> payload) {
        CompoundTag result = new CompoundTag();
        ListTag balloons = new ListTag();
        for (CompoundTag tag : payload) balloons.add(tag.copy());
        result.put("Balloons", balloons);
        return result;
    }

    @Override
    public List<CompoundTag> deserialize(CompoundTag tag) {
        List<CompoundTag> balloons = new ArrayList<>();
        for (Tag entry : tag.getList("Balloons", Tag.TAG_COMPOUND)) balloons.add(((CompoundTag) entry).copy());
        return balloons;
    }

    private static boolean overlaps(Reflection r, Object saved, BlockPos controller, BoundingBox3ic bounds) {
        try {
            if (controller.equals(r.savedControllerPos().invoke(saved))) return true;
            return bounds.intersects((BoundingBox3ic) r.savedBounds().invoke(saved));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read an unloaded Aeronautics balloon", e);
        }
    }

    @SubscribeEvent
    public static void onSubLevelTransfer(SubLevelTransferEvent event) {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        Reflection r = resolve();
        if (r == null) return;

        ServerSubLevel sub = event.newSub();
        int rebuilt = 0;
        for (var chunkHolder : sub.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            for (BlockEntity be : List.copyOf(chunk.getBlockEntities().values())) {
                if (!r.gasProvider().isInstance(be)) continue;
                try {
                    if (!(Boolean) r.canOutputGas().invoke(be)) continue;
                    r.tickBalloonLogic().invoke(be);
                    rebuilt++;
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    AeroPortals.LOGGER.error("[AeroPortals] balloon rebuild failed @ {}", be.getBlockPos(), ex);
                }
            }
        }
        if (rebuilt > 0) {
            AeroPortals.LOGGER.debug("[AeroPortals] asked {} Aeronautics gas provider(s) to rebuild their balloon on sub {}",
                    rebuilt, event.subUuid());
        }
    }

    @SuppressWarnings("unchecked")
    private static Reflection resolve() {
        if (attempted) return reflection;
        synchronized (AeronauticsBalloonFixup.class) {
            if (attempted) return reflection;
            attempted = true;
            if (!ModList.get().isLoaded(MOD_ID)) return null;
            try {
                Class<?> provider = Class.forName(GAS_PROVIDER_CLASS);
                Class<?> mapClass = Class.forName(BALLOON_MAP_CLASS);
                Class<?> savedClass = Class.forName(SAVED_BALLOON_CLASS);
                Class<?> serverBalloon = Class.forName(SERVER_BALLOON_CLASS);
                Field mapField = mapClass.getField("MAP");
                Object maps = mapField.get(null);
                Field codecField = savedClass.getField("CODEC");
                reflection = new Reflection(
                        provider,
                        provider.getMethod("canOutputGas"),
                        provider.getMethod("tickBalloonLogic"),
                        maps,
                        maps.getClass().getMethod("get", LevelAccessor.class),
                        mapClass.getMethod("getBalloons"),
                        mapClass.getMethod("getUnloadedBalloons"),
                        mapClass.getMethod("getBalloon", BlockPos.class),
                        mapClass.getMethod("markDirty"),
                        mapClass.getMethod("saveBalloon", serverBalloon),
                        serverBalloon,
                        serverBalloon.getMethod("getControllerPos"),
                        serverBalloon.getMethod("loadFrom", savedClass),
                        (Codec<Object>) codecField.get(null),
                        savedClass.getMethod("bounds"),
                        savedClass.getMethod("controllerPos"),
                        savedClass.getMethod("gasData"),
                        savedClass.getConstructor(BoundingBox3i.class, BlockPos.class, List.class));
            } catch (ClassNotFoundException e) {
                AeroPortals.LOGGER.debug("[AeroPortals] Aeronautics balloon classes not present; skipping balloon fixup");
            } catch (ReflectiveOperationException | LinkageError e) {
                AeroPortals.LOGGER.warn("[AeroPortals] Aeronautics balloon API changed; balloons will refill from empty after a transfer", e);
            }
            return reflection;
        }
    }
}
