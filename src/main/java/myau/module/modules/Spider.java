package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): climbs vertical walls like a spider.
import com.google.common.base.CaseFormat;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;

public class Spider extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SPEED", "VANILLA"});

    public Spider() {
        super("Spider", false, false, "Climbs vertical walls like a spider.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!mc.thePlayer.isCollidedHorizontally || !MoveUtil.isMoving()) {
            return;
        }
        if (this.mode.getValue() == 0) {
            mc.thePlayer.motionY = 0.35;
        } else {
            mc.thePlayer.motionY = 0.2;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
