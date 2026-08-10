package com.breakinblocks.aeroportals.compat.kubejs;

import com.breakinblocks.aeroportals.api.nbt.BlockEntityNbtFixer;
import com.breakinblocks.aeroportals.api.nbt.NbtFixers;
import dev.latvian.mods.kubejs.event.KubeEvent;

import java.util.List;

public class RegisterKubeEvent implements KubeEvent {

    public void blockPosFixer(String blockEntityId, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.blockPos(toArray(keys)));
    }

    public void blockPosFixer(String blockEntityId, String key) {
        blockPosFixer(blockEntityId, List.of(key));
    }

    public void dimensionFixer(String blockEntityId, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.dimensionId(toArray(keys)));
    }

    public void dimensionFixer(String blockEntityId, String key) {
        dimensionFixer(blockEntityId, List.of(key));
    }

    public void nestedBlockPosFixer(String blockEntityId, String path, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.nested(path, NbtFixers.blockPos(toArray(keys))));
    }

    public void nestedBlockPosFixer(String blockEntityId, String path, String key) {
        nestedBlockPosFixer(blockEntityId, path, List.of(key));
    }

    public void nestedDimensionFixer(String blockEntityId, String path, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.nested(path, NbtFixers.dimensionId(toArray(keys))));
    }

    public void nestedDimensionFixer(String blockEntityId, String path, String key) {
        nestedDimensionFixer(blockEntityId, path, List.of(key));
    }

    public void listBlockPosFixer(String blockEntityId, String listKey, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.each(listKey, NbtFixers.blockPos(toArray(keys))));
    }

    public void clearFixer(String blockEntityId, List<String> keys) {
        ScriptRegistry.addFixer(blockEntityId, NbtFixers.clearKeys(toArray(keys)));
    }

    public void clearFixer(String blockEntityId, String key) {
        clearFixer(blockEntityId, List.of(key));
    }

    public void customFixer(String blockEntityId, BlockEntityNbtFixer fixer) {
        ScriptRegistry.addFixer(blockEntityId, fixer);
    }

    public void portal(String id, List<String> blockIds, PortalResolver resolver) {
        ScriptRegistry.addPortal(id, blockIds, resolver);
    }

    public void portal(String id, String blockId, PortalResolver resolver) {
        ScriptRegistry.addPortal(id, List.of(blockId), resolver);
    }

    private static String[] toArray(List<String> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one NBT key is required");
        }
        return keys.toArray(new String[0]);
    }
}
