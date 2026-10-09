package myau.module.modules;

// Ported from OpenSkid (GPL-3.0).
import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

public class VClip extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty distance = new FloatProperty("distance", 3.0F, -20.0F, 20.0F);
    public final BooleanProperty chatConfirm = new BooleanProperty("chat-confirm", true);

    public VClip() {
        super("VClip", false, false, "Teleports you vertically by the configured block distance.");
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        float blocks = this.distance.getValue();
        if (blocks == 0.0F) {
            this.setEnabled(false);
            return;
        }
        mc.thePlayer.setPositionAndUpdate(mc.thePlayer.posX, mc.thePlayer.posY + blocks, mc.thePlayer.posZ);
        if (this.chatConfirm.getValue()) {
            String way = blocks > 0.0F ? "up" : "down";
            ChatUtil.sendFormatted(Myau.clientName + "Clipped " + way + " " + blocks + " blocks.");
        }
        this.setEnabled(false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.distance.getValue())};
    }
}
