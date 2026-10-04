package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorKeyBinding;
import myau.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import myau.util.KeyBindUtil;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class AntiAFK extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastInput;

    public AntiAFK() {
        super("AntiAFK", false);
    }

    private static boolean physicallyDown(KeyBinding binding) {
        int code = binding.getKeyCode();
        if (code == 0) {
            return false;
        }
        return code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
    }

    /* The simulated keys used to be left wherever the last tick put them, so
       switching off mid-strafe kept the player walking. */
    @Override
    public void onDisabled() {
        this.lastInput = 0;
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindRight.getKeyCode());
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindLeft.getKeyCode());
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindJump.getKeyCode());
    }

    @EventTarget(whenDisabled = true)
    public void onUpdate(UpdateEvent event){
        if(event.getType() == EventType.PRE && this.isEnabled()){
            GameSettings gameSettings = mc.gameSettings;
            /* Physical keys, not the bindings. isPressed() consumes a one-shot
               press counter, so a key held down only counted once; and
               isKeyDown() would see this module's own simulated presses and
               never let the idle timer run out. */
            if (physicallyDown(gameSettings.keyBindJump) || physicallyDown(gameSettings.keyBindRight)
                    || physicallyDown(gameSettings.keyBindForward) || physicallyDown(gameSettings.keyBindLeft)
                    || physicallyDown(gameSettings.keyBindBack)) {
                lastInput = 0;
            }
            lastInput++;
            if (lastInput < 20 * 10) return;
            if (mc.thePlayer.ticksExisted % 5 == 0) {
                ((IAccessorKeyBinding)mc.gameSettings.keyBindRight).setPressed(false);
                ((IAccessorKeyBinding)mc.gameSettings.keyBindLeft).setPressed(false);
                ((IAccessorKeyBinding)mc.gameSettings.keyBindJump).setPressed(false);
            }
            if (mc.thePlayer.ticksExisted % 20 == 0) {
                if (mc.thePlayer.ticksExisted % 40 == 0) {
                    ((IAccessorKeyBinding)mc.gameSettings.keyBindRight).setPressed(true);
                } else {
                    ((IAccessorKeyBinding)mc.gameSettings.keyBindLeft).setPressed(true);
                }
            }
            if (mc.thePlayer.ticksExisted % 100 == 0) {
                ((IAccessorKeyBinding)mc.gameSettings.keyBindJump).setPressed(true);
            }
        }
    }
}
