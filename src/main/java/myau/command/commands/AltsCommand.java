package myau.command.commands;

import me.ksyz.accountmanager.gui.GuiAccountManager;
import myau.command.Command;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;

// Ported from OpenSkid (GPL-3.0): opens the account manager. Myau bundles the
// same account manager library but had no command for its screen.
public class AltsCommand extends Command {
    public AltsCommand() {
        super(new ArrayList<String>(Arrays.asList("alts", "alt", "altmanager")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAccountManager(null));
    }
}
