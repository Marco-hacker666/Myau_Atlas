package myau.setup;

import net.minecraft.client.Minecraft;
import myau.ui.impl.mainmenu.MyauMainMenu;

/**
 * The entry points the rest of the client uses to reach the setup screen.
 * Ported from OpenSkid (GPL-3.0).
 */
public final class SetupHook {
    private SetupHook() {
    }

    /**
     * Called from the main menu's {@code initGui()}. Keeps OneConfig off the
     * ClickGUI key and, on the very first launch, shows the wizard.
     */
    public static void onMainMenu(MyauMainMenu menu) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.mcDataDir != null) {
                OneConfigPatcher.run(mc.mcDataDir, false);
            }
            if (!SetupState.isDone()) {
                SetupScreen screen = new SetupScreen();
                screen.setReturnToMenu(true);
                mc.displayGuiScreen(screen);
            }
        } catch (Exception ignored) {
        }
    }

    /** Opens the wizard on demand (the main menu's Setup button, or /setup). */
    public static void open() {
        try {
            Minecraft.getMinecraft().displayGuiScreen(new SetupScreen());
        } catch (Exception ignored) {
        }
    }
}
