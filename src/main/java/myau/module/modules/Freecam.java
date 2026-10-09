package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): scout around freely while your body stays put.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Freecam extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty speed = new FloatProperty("speed", 2.5F, 0.5F, 10.0F);

    private double savedX;
    private double savedY;
    private double savedZ;
    private float savedYaw;
    private float savedPitch;
    private boolean hasSaved;

    public Freecam() {
        super("Freecam", false, false, "Scout around freely while your body stays put.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        mc.thePlayer.noClip = true;
        mc.thePlayer.fallDistance = 0.0F;
        double vertical = 0.0;
        if (mc.currentScreen == null) {
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                vertical += 0.42 * this.speed.getValue();
            }
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                vertical -= 0.42 * this.speed.getValue();
            }
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
        mc.thePlayer.motionY = vertical;
        if (MoveUtil.isMoving()) {
            MoveUtil.setSpeed(MoveUtil.getBaseMoveSpeed() * this.speed.getValue());
        } else {
            MoveUtil.setSpeed(0.0);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null) {
            this.hasSaved = false;
            this.setEnabled(false);
            return;
        }
        this.savedX = mc.thePlayer.posX;
        this.savedY = mc.thePlayer.posY;
        this.savedZ = mc.thePlayer.posZ;
        this.savedYaw = mc.thePlayer.rotationYaw;
        this.savedPitch = mc.thePlayer.rotationPitch;
        this.hasSaved = true;
        mc.thePlayer.noClip = true;
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            mc.thePlayer.noClip = false;
            if (this.hasSaved) {
                mc.thePlayer.setPosition(this.savedX, this.savedY, this.savedZ);
                mc.thePlayer.rotationYaw = this.savedYaw;
                mc.thePlayer.rotationPitch = this.savedPitch;
                this.hasSaved = false;
            }
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionY = 0.0;
            mc.thePlayer.motionZ = 0.0;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
        }
    }
}
