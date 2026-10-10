package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): steps up tall blocks without jumping
// (Hypixel 1.5, Hypixel, Vanilla, NCP, OldNCP, Matrix).
import com.google.common.base.CaseFormat;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Step extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"HYPIXEL15", "HYPIXEL", "Vanilla", "NCP", "OldNCP", "Matrix"});
    public final FloatProperty height15 = new FloatProperty("height-15", 1.5F, 0.6F, 2.5F, () -> this.mode.getValue() == 0);
    public final IntProperty delay15 = new IntProperty("delay-15", 1000, 0, 5000, () -> this.mode.getValue() == 0);
    public final FloatProperty height = new FloatProperty("height", 1.0F, 0.6F, 2.5F, () -> this.mode.getValue() == 1);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 5000, () -> this.mode.getValue() == 1);
    public final FloatProperty vanillaHeight = new FloatProperty("vanilla-height", 1.0F, 0.6F, 2.5F, () -> this.mode.getValue() == 2);
    public final FloatProperty ncpHeight = new FloatProperty("ncp-height", 1.0F, 0.6F, 2.5F, () -> this.mode.getValue() == 3);
    public final FloatProperty oldNcpHeight = new FloatProperty("oldncp-height", 1.0F, 0.6F, 2.5F, () -> this.mode.getValue() == 4);
    public final FloatProperty matrixHeight = new FloatProperty("matrix-height", 2.0F, 0.6F, 2.5F, () -> this.mode.getValue() == 5);

    private int offGroundTicks = -1;
    private boolean stepping;
    private long lastStep = -1L;

    public Step() {
        super("Step", false, false, "Steps up tall blocks without jumping.");
    }

    private float activeHeight() {
        switch (this.mode.getValue()) {
            case 0:
                return this.height15.getValue();
            case 2:
                return this.vanillaHeight.getValue();
            case 3:
                return this.ncpHeight.getValue();
            case 4:
                return this.oldNcpHeight.getValue();
            case 5:
                return this.matrixHeight.getValue();
            default:
                return this.height.getValue();
        }
    }

    private int activeDelay() {
        return this.mode.getValue() == 0 ? this.delay15.getValue() : this.delay.getValue();
    }

    private void stop() {
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionZ = 0.0;
    }

    private void strafe() {
        MoveUtil.setSpeed(MoveUtil.getAllowedHorizontalDistance(), MoveUtil.getMoveYaw());
    }

    private double predictedMotion(double motion, int ticks) {
        double predicted = motion;
        for (int i = 0; i < ticks; i++) {
            predicted = (predicted - 0.08) * 0.98;
        }
        return predicted;
    }

    private void updateHypixel15() {
        switch (this.offGroundTicks) {
            case 0:
                this.stop();
                this.strafe();
                mc.thePlayer.jump();
                break;
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
                this.stop();
                break;
            case 10:
            case 11:
            case 13:
            case 14:
            case 15:
                mc.thePlayer.motionY = 0.0;
                this.stop();
                break;
            case 16:
                mc.thePlayer.jump();
                this.stepping = false;
                break;
            default:
                break;
        }
    }

    private void updateHypixel() {
        switch (this.offGroundTicks) {
            case 0:
                this.stop();
                this.strafe();
                mc.thePlayer.jump();
                break;
            case 5:
                MoveUtil.setSpeed(MoveUtil.getAllowedHorizontalDistance() * (1.0 + (double) this.activeHeight()), MoveUtil.getMoveYaw());
                mc.thePlayer.motionY = this.predictedMotion(mc.thePlayer.motionY, 2);
                break;
            default:
                break;
        }
    }

    private void sendNcpStep(double baseY) {
        double x = mc.thePlayer.posX;
        double y = baseY;
        double z = mc.thePlayer.posZ;
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.41999998688698, z, false));
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.7531999805212, z, false));
    }

    private void updateVanilla() {
        mc.thePlayer.stepHeight = this.vanillaHeight.getValue();
        this.stepping = false;
    }

    private void updateNcp() {
        if (this.offGroundTicks == 0) {
            mc.thePlayer.stepHeight = this.ncpHeight.getValue();
            this.sendNcpStep(mc.thePlayer.posY);
        }
        if (this.offGroundTicks > 5) {
            this.stepping = false;
        }
    }

    private void updateOldNcp() {
        if (this.offGroundTicks == 0) {
            mc.thePlayer.stepHeight = this.oldNcpHeight.getValue();
            this.sendNcpStep(mc.thePlayer.posY);
        }
        if (this.offGroundTicks > 5) {
            this.stepping = false;
        }
    }

    private void updateMatrix() {
        if (this.offGroundTicks == 0) {
            mc.thePlayer.stepHeight = this.matrixHeight.getValue();
            double x = mc.thePlayer.posX;
            double y = mc.thePlayer.posY;
            double z = mc.thePlayer.posZ;
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.41999998688698, z, false));
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.7531999805212, z, false));
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.001335979112147, z, false));
        }
        if (this.offGroundTicks > 5) {
            this.stepping = false;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.thePlayer.onGround) {
            this.offGroundTicks = 0;
        } else if (this.offGroundTicks != -1) {
            this.offGroundTicks++;
        }
        long now = System.currentTimeMillis();
        if (mc.thePlayer.onGround && mc.thePlayer.isCollidedHorizontally && MoveUtil.isMoving() && now - this.lastStep >= (long) this.activeDelay()) {
            this.stepping = true;
            this.lastStep = now;
        }
        if (!this.stepping) {
            return;
        }
        if (!MoveUtil.isMoving() || mc.gameSettings.keyBindJump.isKeyDown() || (!mc.thePlayer.isCollidedHorizontally && this.offGroundTicks > 5)) {
            this.stepping = false;
            return;
        }
        if (this.mode.getValue() == 0) {
            this.updateHypixel15();
        } else if (this.mode.getValue() == 1) {
            this.updateHypixel();
        } else if (this.mode.getValue() == 2) {
            this.updateVanilla();
        } else if (this.mode.getValue() == 3) {
            this.updateNcp();
        } else if (this.mode.getValue() == 4) {
            this.updateOldNcp();
        } else {
            this.updateMatrix();
        }
        if (this.offGroundTicks > 20) {
            this.stepping = false;
        }
    }

    @EventTarget
    public void onStepPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (this.mode.getValue() == 4 && this.stepping && event.getPacket() instanceof C03PacketPlayer && mc.thePlayer != null) {
            mc.thePlayer.motionY += 0.02;
            this.stepping = false;
        }
    }

    @Override
    public boolean shouldKeepSprint() {
        return this.isEnabled() && this.stepping;
    }

    @Override
    public void onEnabled() {
        this.offGroundTicks = -1;
        this.stepping = false;
        this.lastStep = -1L;
        if (mc.thePlayer != null) {
            mc.thePlayer.stepHeight = Math.max(0.6F, this.activeHeight());
        }
    }

    @Override
    public void onDisabled() {
        this.offGroundTicks = -1;
        this.stepping = false;
        if (mc.thePlayer != null) {
            mc.thePlayer.stepHeight = 0.6F;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
