package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): lets you clip through blocks with several
// bypass modes (Grim, Vanilla, Vulcan, Watchdog).
import com.google.common.base.CaseFormat;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntityPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.potion.Potion;

public class Phase extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"GRIM", "VANILLA", "VULCAN", "WATCHDOG", "WATCHDOG2"});
    public final FloatProperty grimMotion = new FloatProperty("grim-motion", 3.9F, 1.0F, 10.0F, () -> this.mode.getValue() == 0);
    public final FloatProperty clipDistance = new FloatProperty("clip-distance", 1.5F, 0.5F, 4.0F, () -> this.mode.getValue() == 1);
    public final FloatProperty timerSpeed = new FloatProperty("timer-speed", 1.0F, 0.1F, 2.0F, () -> this.mode.getValue() == 2);
    public final IntProperty packetDelay = new IntProperty("packet-delay", 100, 0, 1000, () -> this.mode.getValue() == 3 || this.mode.getValue() == 4);
    public final BooleanProperty fast = new BooleanProperty("fast", false, () -> this.mode.getValue() == 2);
    public final BooleanProperty cancelS08 = new BooleanProperty("cancel-s08", false);
    public final BooleanProperty cancelVelocity = new BooleanProperty("cancel-velocity", true, () -> this.mode.getValue() == 2);

    private boolean phasing;
    private boolean wasInside;
    private int insideTicks;
    private boolean watchdogArmed;
    private boolean vulcanTeleported;
    private boolean vulcanTimerActive;
    private long enableTime;

    public Phase() {
        super("Phase", false, false, "Lets you clip through blocks with several bypass modes.");
    }

    private boolean isInsideBlock() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        try {
            return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, mc.thePlayer.getEntityBoundingBox().expand(-0.05, -0.05, -0.05)).isEmpty();
        } catch (Throwable ignored) {
            return mc.thePlayer.isCollidedHorizontally;
        }
    }

    private void setTimer(float value) {
        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (timer != null) {
            timer.timerSpeed = value;
        }
    }

    private void updateGrim(boolean inside) {
        mc.thePlayer.noClip = inside;
        if (inside && !this.wasInside) {
            mc.thePlayer.motionY = this.grimMotion.getValue().doubleValue();
        }
        this.phasing = inside;
    }

    private void updateVanilla(boolean inside) {
        this.phasing = false;
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        if (mc.thePlayer.isCollidedHorizontally) {
            mc.thePlayer.setPosition(mc.thePlayer.posX - dx * 0.005, mc.thePlayer.posY, mc.thePlayer.posZ + dz * 0.005);
            this.phasing = true;
        } else if (inside) {
            double dist = this.clipDistance.getValue().doubleValue();
            PacketUtil.sendPacketNoEvent(new C04PacketPlayerPosition(mc.thePlayer.posX - dx * dist, mc.thePlayer.posY, mc.thePlayer.posZ + dz * dist, false));
            mc.thePlayer.motionX *= 0.3;
            mc.thePlayer.motionZ *= 0.3;
            this.phasing = true;
        }
        mc.thePlayer.noClip = this.phasing && inside;
    }

    private void updateVulcan(boolean inside) {
        if (inside) {
            this.insideTicks++;
        } else {
            this.insideTicks = 0;
        }
        mc.thePlayer.noClip = inside;
        if (inside && this.timerSpeed.getValue() != 1.0F) {
            this.setTimer(this.timerSpeed.getValue());
            this.vulcanTimerActive = true;
        } else if (this.vulcanTimerActive) {
            net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
            if (timer == null || timer.timerSpeed == this.timerSpeed.getValue()) {
                this.setTimer(1.0F);
            }
            this.vulcanTimerActive = false;
        }
        if (mc.gameSettings.keyBindJump.isKeyDown() && mc.thePlayer.hurtTime > 0) {
            mc.thePlayer.motionY = 0.99;
        } else if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = -0.4;
        }
        if (this.fast.getValue() && inside) {
            double speed = 0.306;
            if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                speed = 0.0765 * (1 + mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier()) + 0.306;
            }
            MoveUtil.setSpeed(speed, MoveUtil.getMoveYaw());
        }
        if (mc.thePlayer.onGround && this.vulcanTeleported) {
            mc.thePlayer.jump();
            this.vulcanTeleported = false;
        }
        this.phasing = inside;
    }

    private void updateWatchdog(boolean inside, boolean auto) {
        long waited = System.currentTimeMillis() - this.enableTime;
        if (!this.watchdogArmed) {
            if (auto || waited < (long) this.packetDelay.getValue()) {
                mc.thePlayer.noClip = false;
                return;
            }
            this.watchdogArmed = true;
        } else if (auto && waited < (long) this.packetDelay.getValue()) {
            mc.thePlayer.noClip = false;
            return;
        }
        if (inside) {
            this.insideTicks++;
            mc.thePlayer.noClip = true;
            mc.thePlayer.motionX *= 0.95;
            mc.thePlayer.motionZ *= 0.95;
        } else {
            mc.thePlayer.noClip = false;
        }
        this.phasing = inside && this.watchdogArmed;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.mode.getValue() != 2 && this.vulcanTimerActive) {
            net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
            if (timer == null || timer.timerSpeed == this.timerSpeed.getValue()) {
                this.setTimer(1.0F);
            }
            this.vulcanTimerActive = false;
        }
        boolean inside = this.isInsideBlock();
        switch (this.mode.getValue()) {
            case 0:
                this.updateGrim(inside);
                break;
            case 1:
                this.updateVanilla(inside);
                break;
            case 2:
                this.updateVulcan(inside);
                break;
            case 3:
                this.updateWatchdog(inside, false);
                break;
            case 4:
                this.updateWatchdog(inside, true);
                break;
            default:
                break;
        }
        this.wasInside = inside;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            if (this.cancelS08.getValue()) {
                event.setCancelled(true);
            }
            if (this.mode.getValue() == 2) {
                this.vulcanTeleported = true;
            }
        } else if (event.getPacket() instanceof S12PacketEntityVelocity) {
            if (this.mode.getValue() == 2 && this.cancelVelocity.getValue() && this.isInsideBlock()) {
                S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
                if (velocity.getEntityID() == mc.thePlayer.getEntityId()) {
                    event.setCancelled(true);
                }
            }
        } else if (event.getPacket() instanceof S02PacketChat) {
            if (this.mode.getValue() == 4) {
                String text = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
                if (text.contains("Cages opened") || text.contains("FIGHT") || text.contains("SkyWars Duel")) {
                    this.watchdogArmed = false;
                    mc.thePlayer.noClip = false;
                } else if (text.contains("starts in 3") || text.contains("Cages open in")) {
                    this.watchdogArmed = true;
                    this.enableTime = System.currentTimeMillis();
                }
            }
        }
    }

    @Override
    public void onEnabled() {
        this.phasing = false;
        this.wasInside = false;
        this.insideTicks = 0;
        this.watchdogArmed = false;
        this.vulcanTeleported = false;
        this.vulcanTimerActive = false;
        this.enableTime = System.currentTimeMillis();
        if (mc.thePlayer != null && this.mode.getValue() == 2) {
            if (mc.thePlayer.onGround) {
                mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
                MoveUtil.setSpeed(0.0);
                ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.1F);
            } else {
                this.setEnabled(false);
            }
        }
    }

    @Override
    public void onDisabled() {
        this.phasing = false;
        this.wasInside = false;
        this.insideTicks = 0;
        this.watchdogArmed = false;
        this.vulcanTeleported = false;
        if (this.vulcanTimerActive) {
            net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
            if (timer == null || timer.timerSpeed == this.timerSpeed.getValue()) {
                this.setTimer(1.0F);
            }
            this.vulcanTimerActive = false;
        }
        if (mc.thePlayer != null) {
            mc.thePlayer.noClip = false;
            ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.02F);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
