package myau.module.modules;

// Ported from OpenSkid (GPL-3.0).
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;

public class StopMotion extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty stopX = new BooleanProperty("stop-x", true);
    public final BooleanProperty stopY = new BooleanProperty("stop-y", true);
    public final BooleanProperty stopZ = new BooleanProperty("stop-z", true);

    public StopMotion() {
        super("StopMotion", false, false, "Freezes motion on enable for precise midair stops.");
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            if (this.stopX.getValue()) {
                mc.thePlayer.motionX = 0.0;
            }
            if (this.stopY.getValue()) {
                mc.thePlayer.motionY = 0.0;
            }
            if (this.stopZ.getValue()) {
                mc.thePlayer.motionZ = 0.0;
            }
        }
        this.setEnabled(false);
    }
}
