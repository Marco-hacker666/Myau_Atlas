package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.ui.impl.clickgui.normal.ClickGuiScreen;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public class GuiModule extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public GuiModule() {
        super("ClickGui", false);
        setKey(Keyboard.KEY_RSHIFT);
    }

    @Override
    public void onEnabled() {
        setEnabled(false);
        ClickGUIModule clickGui = (ClickGUIModule) Myau.moduleManager.getModule("ClickGUI");
        if (clickGui != null) {
            if (clickGui.isEnabled()) {
                clickGui.openSelectedGui();
            } else {
                clickGui.setEnabled(true);
            }
            return;
        }
        mc.displayGuiScreen(ClickGuiScreen.getInstance());
    }
}
