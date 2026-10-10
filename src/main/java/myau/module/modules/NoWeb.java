package myau.module.modules;

// Ported from OpenSkid (GPL-3.0). Cancels cobweb slowdown; Normal restores
// motion, Sprint keeps sprinting through webs.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

public class NoWeb extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Normal", "Sprint"});
    public final BooleanProperty onlyMoving = new BooleanProperty("only-moving", true);

    public NoWeb() {
        super("NoWeb", false, false, "Removes slowdown while inside cobwebs.");
    }

    private boolean isInWeb() {
        return mc.thePlayer != null && ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.isInWeb()) {
            return;
        }
        if (this.onlyMoving.getValue() && mc.thePlayer.moveForward == 0.0F && mc.thePlayer.moveStrafing == 0.0F) {
            return;
        }
        mc.thePlayer.motionX /= 0.25;
        mc.thePlayer.motionZ /= 0.25;
        if (this.mode.getValue() == 1 && !mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(true);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
