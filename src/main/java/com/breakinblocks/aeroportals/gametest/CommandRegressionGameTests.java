package com.breakinblocks.aeroportals.gametest;

import com.breakinblocks.aeroportals.commands.AeroPortalsCommands;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeroportals")
@PrefixGameTestTemplate(false)
public class CommandRegressionGameTests {
    @GameTest(template = "empty")
    public static void shipsAcceptsNamespacedDimensions(GameTestHelper helper) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        var register = AeroPortalsCommands.class.getDeclaredMethod("register", CommandDispatcher.class);
        register.setAccessible(true);
        register.invoke(null, dispatcher);
        var parsed = dispatcher.parse("aeroportals ships minecraft:overworld",
                helper.getLevel().getServer().createCommandSourceStack());
        helper.assertTrue(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead(),
                "Namespaced dimension must parse completely");
        helper.assertTrue(dispatcher.execute(parsed) >= 0, "Ships command should execute");
        helper.succeed();
    }
}
