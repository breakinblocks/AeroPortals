package com.breakinblocks.aeroportals.portal;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Consumer;

/** Keeps block-entity unload failures from interrupting Sable after physics has been removed. */
public final class TransferRemovalScope {
    private static final ThreadLocal<TransferRemovalScope> CURRENT = new ThreadLocal<>();

    private final Level level;
    private final int minX;
    private final int minZ;
    private final int size;
    private RuntimeException failure;

    private TransferRemovalScope(ServerSubLevelContainer container, ServerSubLevel sub) {
        level = sub.getLevel();
        int bits = container.getLogPlotSize() + 4;
        minX = sub.getPlot().plotPos.x << bits;
        minZ = sub.getPlot().plotPos.z << bits;
        size = 1 << bits;
    }

    public static void remove(ServerSubLevelContainer container, ServerSubLevel sub) {
        TransferRemovalScope previous = CURRENT.get();
        TransferRemovalScope scope = new TransferRemovalScope(container, sub);
        CURRENT.set(scope);
        try {
            container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            if (scope.failure != null) throw scope.failure;
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static boolean isRemoving(Level level, BlockPos pos) {
        TransferRemovalScope scope = CURRENT.get();
        return scope != null && scope.level == level
                && pos.getX() >= scope.minX && pos.getX() < scope.minX + scope.size
                && pos.getZ() >= scope.minZ && pos.getZ() < scope.minZ + scope.size;
    }

    public static void unload(BlockEntity blockEntity, Consumer<BlockEntity> callback) {
        if (!isRemoving(blockEntity.getLevel(), blockEntity.getBlockPos())) {
            callback.accept(blockEntity);
            return;
        }
        try {
            callback.accept(blockEntity);
        } catch (RuntimeException ex) {
            TransferRemovalScope scope = CURRENT.get();
            if (scope.failure == null) scope.failure = new IllegalStateException("Ship block entities failed to unload");
            scope.failure.addSuppressed(ex);
        }
    }
}
