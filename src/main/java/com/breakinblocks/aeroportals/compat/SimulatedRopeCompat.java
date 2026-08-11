package com.breakinblocks.aeroportals.compat;

import com.breakinblocks.aeroportals.AeroPortals;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

public final class SimulatedRopeCompat {
    private static final String MOD_ID = "simulated";
    private static final String MANAGER_CLASS = "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager";
    private static final String STRAND_CLASS = "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand";
    private static final String ATTACHMENT_CLASS = "dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachment";
    private static final String ATTACHMENT_POINT_CLASS = "dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint";
    private static final String BEHAVIOUR_CLASS = "dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior";
    private static final String CREATE_BEHAVIOUR_CLASS = "com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour";

    private static volatile boolean attempted = false;
    private static volatile boolean available = false;

    private static Method managerGetOrCreate;
    private static Method managerGetAllStrands;
    private static Method strandGetUuid;
    private static Method strandGetAttachments;
    private static Method strandAddAttachment;
    private static Method strandReattachConstraints;
    private static Method attachmentPoint;
    private static Method attachmentSubLevelId;
    private static Method attachmentBlockPos;
    private static Class<?> attachmentClass;
    private static Class<?> attachmentPointClass;
    private static Object behaviourType;
    private static Method behaviourGet;
    private static Field behaviourOwnedStrand;

    private SimulatedRopeCompat() {}

    public record AttachmentView(Object point, UUID subLevelId, BlockPos blockPos) {}

    public static boolean isAvailable() {
        if (attempted) return available;
        synchronized (SimulatedRopeCompat.class) {
            if (attempted) return available;
            attempted = true;
            available = resolve();
            return available;
        }
    }

    private static boolean resolve() {
        if (!ModList.get().isLoaded(MOD_ID)) return false;
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Class<?> strandClass = Class.forName(STRAND_CLASS);
            attachmentClass = Class.forName(ATTACHMENT_CLASS);
            attachmentPointClass = Class.forName(ATTACHMENT_POINT_CLASS);
            Class<?> behaviourClass = Class.forName(BEHAVIOUR_CLASS);
            Class<?> createBehaviourClass = Class.forName(CREATE_BEHAVIOUR_CLASS);

            managerGetOrCreate = managerClass.getMethod("getOrCreate", Level.class);
            managerGetAllStrands = managerClass.getMethod("getAllStrands");
            strandGetUuid = strandClass.getMethod("getUUID");
            strandGetAttachments = strandClass.getMethod("getAttachments");
            strandAddAttachment = strandClass.getMethod("addAttachment", ServerLevel.class, attachmentPointClass, attachmentClass);
            strandReattachConstraints = strandClass.getMethod("reattachConstraints", ServerLevel.class);
            attachmentPoint = attachmentClass.getMethod("point");
            attachmentSubLevelId = attachmentClass.getMethod("subLevelID");
            attachmentBlockPos = attachmentClass.getMethod("blockAttachment");

            behaviourType = behaviourClass.getField("TYPE").get(null);
            behaviourGet = findBehaviourGetter(createBehaviourClass);
            behaviourOwnedStrand = behaviourClass.getDeclaredField("ownedServerStrand");
            behaviourOwnedStrand.setAccessible(true);

            AeroPortals.LOGGER.debug("[AeroPortals] Simulated rope compat initialized");
            return true;
        } catch (ClassNotFoundException e) {
            AeroPortals.LOGGER.debug("[AeroPortals] Simulated rope classes not present ({}); rope handover disabled", e.getMessage());
            return false;
        } catch (ReflectiveOperationException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] Simulated rope API changed ({}); rope handover disabled", e.toString());
            return false;
        }
    }

    private static Method findBehaviourGetter(Class<?> createBehaviourClass) throws NoSuchMethodException {
        for (Method method : createBehaviourClass.getMethods()) {
            if (!method.getName().equals("get")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == BlockEntity.class) return method;
        }
        throw new NoSuchMethodException("BlockEntityBehaviour.get(BlockEntity, BehaviourType)");
    }

    public static Object ownedStrand(BlockEntity blockEntity) {
        if (!isAvailable()) return null;
        try {
            Object behaviour = behaviourGet.invoke(null, blockEntity, behaviourType);
            return behaviour == null ? null : behaviourOwnedStrand.get(behaviour);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not read the rope on {}: {}", blockEntity.getBlockPos(), e.toString());
            return null;
        }
    }

    public static Collection<Object> strandsIn(ServerLevel level) {
        if (!isAvailable()) return List.of();
        try {
            Object manager = managerGetOrCreate.invoke(null, level);
            if (manager == null) return List.of();
            return new ArrayList<>((Collection<?>) managerGetAllStrands.invoke(manager));
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not list rope strands in {}: {}", level.dimension().location(), e.toString());
            return List.of();
        }
    }

    public static Collection<ServerSubLevel> withRopePartners(ServerLevel level, Collection<ServerSubLevel> chain) {
        if (!isAvailable() || chain.isEmpty()) return chain;

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return chain;

        Collection<Object> strands = strandsIn(level);
        if (strands.isEmpty()) return chain;

        Set<UUID> ids = new LinkedHashSet<>();
        for (ServerSubLevel sub : chain) {
            ids.add(sub.getUniqueId());
        }

        boolean grew = true;
        while (grew) {
            grew = false;
            for (Object strand : strands) {
                Set<UUID> tied = new LinkedHashSet<>();
                for (AttachmentView attachment : attachmentsOf(strand)) {
                    if (attachment.subLevelId() != null) tied.add(attachment.subLevelId());
                }
                if (tied.size() < 2 || Collections.disjoint(tied, ids)) continue;
                grew |= ids.addAll(tied);
            }
        }

        List<ServerSubLevel> expanded = new ArrayList<>(chain);
        for (UUID id : ids) {
            if (chain.stream().anyMatch(sub -> sub.getUniqueId().equals(id))) continue;
            if (container.getSubLevel(id) instanceof ServerSubLevel partner && !partner.isRemoved()) {
                expanded.add(partner);
                AeroPortals.LOGGER.debug("[AeroPortals] sub {} is roped to the travelling group and will come along", id);
            }
        }
        return expanded;
    }

    public static UUID strandId(Object strand) {
        try {
            return (UUID) strandGetUuid.invoke(strand);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static List<AttachmentView> attachmentsOf(Object strand) {
        if (!isAvailable() || strand == null) return List.of();
        try {
            List<AttachmentView> views = new ArrayList<>(2);
            for (Object attachment : (Iterable<?>) strandGetAttachments.invoke(strand)) {
                views.add(new AttachmentView(
                        attachmentPoint.invoke(attachment),
                        (UUID) attachmentSubLevelId.invoke(attachment),
                        (BlockPos) attachmentBlockPos.invoke(attachment)));
            }
            return views;
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not read rope attachments: {}", e.toString());
            return List.of();
        }
    }

    public static boolean moveAttachment(ServerLevel level, Object strand, AttachmentView attachment, BlockPos newPos) {
        if (!isAvailable()) return false;
        try {
            Object replacement = attachmentClass
                    .getConstructor(attachmentPointClass, UUID.class, BlockPos.class)
                    .newInstance(attachment.point(), attachment.subLevelId(), newPos);
            strandAddAttachment.invoke(strand, level, attachment.point(), replacement);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not move a rope attachment to {}: {}", newPos, e.toString());
            return false;
        }
    }

    public static void reattachConstraints(ServerLevel level, Object strand) {
        if (!isAvailable()) return;
        try {
            strandReattachConstraints.invoke(strand, level);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroPortals.LOGGER.warn("[AeroPortals] could not re-tie a rope after the move: {}", e.toString());
        }
    }
}
