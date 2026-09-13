package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.util.AabbUtil;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class SimulatedCompat {
    public static final String MOD_ID = "simulated";
    private static final String HONEY_GLUE_CLASS = "dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity";

    private static volatile boolean initAttempted = false;
    private static volatile boolean initialized = false;
    private static volatile Class<?> honeyGlueClass;
    private static volatile Constructor<?> honeyGlueCtor;
    private static volatile Method setBoundsAndSync;

    private SimulatedCompat() {}

    public static boolean isAvailable() {
        if (initialized) return true;
        if (initAttempted) return false;
        if (!ModList.get().isLoaded(MOD_ID)) {
            initAttempted = true;
            return false;
        }
        return tryInit();
    }

    private static synchronized boolean tryInit() {
        if (initAttempted) return initialized;
        initAttempted = true;
        try {
            Class<?> cls = Class.forName(HONEY_GLUE_CLASS);
            Constructor<?> ctor = cls.getConstructor(Level.class, AABB.class);
            Method sync = cls.getMethod("setBoundsAndSync", AABB.class);

            honeyGlueClass = cls;
            honeyGlueCtor = ctor;
            setBoundsAndSync = sync;
            initialized = true;
            AeroPortals.LOGGER.debug("[AeroPortals] Simulated honey glue compat initialized ({})", HONEY_GLUE_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Simulated loaded but HoneyGlueEntity class not found ({}); compat disabled", e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Simulated HoneyGlueEntity API changed ({}); compat disabled", e.getMessage());
            return false;
        } catch (Throwable t) {
            AeroPortals.LOGGER.warn("[AeroPortals] Simulated compat init failed: {}", t.toString());
            return false;
        }
    }

    public static List<AABB> captureHoneyGlue(ServerLevel level, ServerSubLevel sub) {
        if (!isAvailable()) return List.of();
        AABB plotAabb = AabbUtil.plotAabb(sub);
        if (plotAabb == null) return List.of();
        List<AABB> boxes = new ArrayList<>();
        for (Entity glue : level.getEntitiesOfClass(Entity.class, plotAabb.inflate(2.0), e -> honeyGlueClass.isInstance(e))) {
            boxes.add(glue.getBoundingBox());
            glue.discard();
        }
        if (!boxes.isEmpty()) {
            AeroPortals.LOGGER.debug("[AeroPortals] captured {} honey glue box(es) from sub {} pre-teleport", boxes.size(), sub.getUniqueId());
        }
        return boxes;
    }

    public static void replayHoneyGlue(ServerLevel level, List<AABB> glueBoxes, BlockPos shift) {
        if (glueBoxes.isEmpty() || !isAvailable()) return;
        for (AABB box : glueBoxes) {
            AABB moved = box.move(shift.getX(), shift.getY(), shift.getZ());
            boolean exists = !level.getEntitiesOfClass(Entity.class, moved.inflate(0.01),
                    entity -> honeyGlueClass.isInstance(entity) && !entity.isRemoved()
                            && entity.getBoundingBox().equals(moved)).isEmpty();
            if (exists) continue;
            try {
                Entity glue = (Entity) honeyGlueCtor.newInstance(level, moved);
                if (!level.addFreshEntity(glue)) throw new IllegalStateException("Destination rejected honey glue");
                setBoundsAndSync.invoke(glue, moved);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Failed to replay honey glue at " + moved, e);
            }
        }
        AeroPortals.LOGGER.debug("[AeroPortals] replayed {} honey glue box(es) post-teleport (shift {})", glueBoxes.size(), shift);
    }
}
