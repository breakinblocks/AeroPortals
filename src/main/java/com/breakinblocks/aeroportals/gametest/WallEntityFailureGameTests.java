package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.TransferCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public final class WallEntityFailureGameTests {
    private WallEntityFailureGameTests() {}

    @GameTest(batch = "wallEntity_malformedPayloadFailsReplay", template = "empty")
    public static void wallEntity_malformedPayloadFailsReplay(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag malformed = framePayload(helper);
        malformed.putString("id", "aeroportals:missing_entity_type");
        CompoundTag original = malformed.copy();
        boolean rejected = false;
        try {
            carrier().replay(helper.getLevel(), null, List.of(malformed), new BlockPos(32, 64, 48));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "An unreadable wall entity payload must fail replay so recovery data is retained");
        helper.assertTrue(original.equals(malformed), "Failed replay must preserve the original payload for rollback/retry");
        helper.succeed();
    }

    @GameTest(batch = "wallEntity_destinationRejectionFailsReplay", template = "empty")
    public static void wallEntity_destinationRejectionFailsReplay(GameTestHelper helper) {
        GameTestSupport.isolate(helper);
        CompoundTag payload = framePayload(helper);
        UUID uuid = payload.getUUID("UUID");
        Consumer<EntityJoinLevelEvent> veto = event -> {
            if (uuid.equals(event.getEntity().getUUID())) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(veto);
        boolean rejected = false;
        try {
            carrier().replay(helper.getLevel(), null, List.of(payload), BlockPos.ZERO);
        } catch (IllegalStateException expected) {
            rejected = true;
        } finally {
            NeoForge.EVENT_BUS.unregister(veto);
        }
        helper.assertTrue(rejected, "A rejected entity spawn must fail replay so the transaction cannot complete");
        helper.assertTrue(helper.getLevel().getEntity(uuid) == null, "Vetoed wall entity must not be present");
        helper.succeed();
    }

    private static CompoundTag framePayload(GameTestHelper helper) {
        ItemFrame frame = new ItemFrame(helper.getLevel(), helper.absolutePos(new BlockPos(7, 5, 7)), Direction.NORTH);
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(frame.save(tag), "Item frame fixture must serialize");
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static TransferCarrier<List<CompoundTag>> carrier() {
        return (TransferCarrier<List<CompoundTag>>) AeroPortalsApi.carriers().stream()
                .filter(carrier -> carrier.id().equals(AeroPortals.id("wall_entities")))
                .findFirst().orElseThrow();
    }
}
