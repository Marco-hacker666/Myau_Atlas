package myau.command.commands;

import myau.command.Command;
import myau.setup.SetupScreen;
import myau.setup.SetupState;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;

// Ported from OpenSkid (GPL-3.0): opens the setup wizard. Myau already ships
// that screen (myau.setup.SetupScreen) but had no command for it.
public class SetupCommand extends Command {
    public SetupCommand() {
        super(new ArrayList<>(Arrays.asList("setupwizard", "setup")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        try {
            Minecraft.getMinecraft().displayGuiScreen(new SetupScreen());
            SetupState.reset();
        } catch (Exception e) {
            ChatUtil.sendFormatted("Could not open setup wizard.");
        }
    }
}
