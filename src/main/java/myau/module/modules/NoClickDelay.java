package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself adapted from MiauMinus ghost/NoClickDelay
// (leftClickCounter reset pattern). Reflection is used because no accessor for
// the field exists in this client, and it is guarded so a mapping change cannot
// break the tick loop.
import java.lang.reflect.Field;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import net.minecraft.client.Minecraft;

public class NoClickDelay extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Field leftClickField;

    public NoClickDelay() {
        super("NoClickDelay", false, false, "Removes the delay between left clicks.");
    }

    @Override
    public void onEnabled() {
        this.clearCounter();
    }

    @Override
    public void onDisabled() {
        this.clearCounter();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{"0ms"};
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.clearCounter();
    }

    private void clearCounter() {
        try {
            if (leftClickField == null) {
                leftClickField = Minecraft.class.getDeclaredField("leftClickCounter");
                leftClickField.setAccessible(true);
            }
            if (leftClickField.getInt(mc) > 0) {
                leftClickField.setInt(mc, 0);
            }
        } catch (Exception ignored) {
        }
    }
}
