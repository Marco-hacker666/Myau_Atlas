package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): builds straight upward quickly with many
// modes, and its Tunnel mode mines forward with a stuck/back/turn engine.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.TimerUtil;
import myau.util.TunnelEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;

public class Tower extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TUNNEL_STUCK_MS = 3000L;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{
            "Vanilla", "Motion", "MotionJump", "MotionTP", "ConstantMotion", "Packet", "Teleport",
            "JumpSprint", "Hypixel", "HypixelFastVertical", "HypixelJumpSprint", "BlocksMC",
            "AAC339", "AAC364", "Vulcan", "Vulcan290", "Pulldown", "Tunnel"
    });

    public final FloatProperty vanillaSpeed = new FloatProperty("vanilla-speed", 0.95F, 0.5F, 1.0F, () -> this.mode.getValue() == 0);
    public final FloatProperty jumpHeight = new FloatProperty("jump-height", 0.42F, 0.36F, 0.79F, () -> this.mode.getValue() == 2 || this.mode.getValue() == 6);
    public final IntProperty jumpDelay = new IntProperty("jump-delay", 0, 0, 20, () -> this.mode.getValue() == 2);
    public final FloatProperty constantMotion = new FloatProperty("constant-motion", 0.42F, 0.1F, 1.0F, () -> this.mode.getValue() == 4);
    public final FloatProperty jumpGround = new FloatProperty("jump-ground", 0.79F, 0.76F, 1.0F, () -> this.mode.getValue() == 4);
    public final BooleanProperty jumpPacket = new BooleanProperty("jump-packet", true, () -> this.mode.getValue() == 4);
    public final IntProperty packetDelay = new IntProperty("packet-delay", 2, 0, 20, () -> this.mode.getValue() == 5);
    public final FloatProperty teleportHeight = new FloatProperty("teleport-height", 1.15F, 0.1F, 5.0F, () -> this.mode.getValue() == 6);
    public final IntProperty teleportDelay = new IntProperty("teleport-delay", 0, 0, 20, () -> this.mode.getValue() == 6);
    public final BooleanProperty teleportGround = new BooleanProperty("teleport-ground", true, () -> this.mode.getValue() == 6);
    public final BooleanProperty teleportNoMotion = new BooleanProperty("teleport-no-motion", false, () -> this.mode.getValue() == 6);
    public final FloatProperty sprintSpeed = new FloatProperty("sprint-speed", 0.95F, 0.5F, 1.0F, () -> this.mode.getValue() == 7 || this.mode.getValue() == 10);
    public final FloatProperty offGroundSpeed = new FloatProperty("off-ground-speed", 0.5F, 0.0F, 1.0F, () -> this.mode.getValue() == 7 || this.mode.getValue() == 10);
    public final BooleanProperty noStrafe = new BooleanProperty("no-strafe", false, () -> this.mode.getValue() == 7 || this.mode.getValue() == 10);
    public final ModeProperty lowHop = new ModeProperty("low-hop", 0, new String[]{"None", "Default", "Test2"}, () -> this.mode.getValue() == 7 || this.mode.getValue() == 10);
    public final BooleanProperty hypixelOnlyMoving = new BooleanProperty("only-while-moving", true, () -> this.mode.getValue() == 8 || this.mode.getValue() == 9);
    public final FloatProperty blocksSpeed = new FloatProperty("blocksmc-speed", 0.95F, 0.5F, 1.0F, () -> this.mode.getValue() == 11);
    public final FloatProperty towerTimer = new FloatProperty("timer", 1.6F, 1.0F, 2.0F, () -> this.mode.getValue() == 12);
    public final BooleanProperty vulcanNotMoving = new BooleanProperty("not-while-moving", true, () -> this.mode.getValue() == 14);
    public final FloatProperty triggerMotion = new FloatProperty("trigger-motion", 0.1F, 0.0F, 0.2F, () -> this.mode.getValue() == 16);
    public final FloatProperty dragMotion = new FloatProperty("drag-motion", 1.0F, 0.1F, 1.0F, () -> this.mode.getValue() == 16);
    public final FloatProperty tunnelSpeed = new FloatProperty("tunnel-speed", 1.0F, 0.5F, 2.0F, () -> this.mode.getValue() == 17);
    public final IntProperty turnDelay = new IntProperty("turn-delay", 20, 0, 100, () -> this.mode.getValue() == 17);
    public final BooleanProperty tunnelAutoBack = new BooleanProperty("tunnel-auto-back", true, () -> this.mode.getValue() == 17);
    public final BooleanProperty tunnelAutoTurn = new BooleanProperty("tunnel-auto-turn", true, () -> this.mode.getValue() == 17);
    public final IntProperty tunnelBackTicks = new IntProperty("tunnel-back-ticks", 10, 0, 40, () -> this.mode.getValue() == 17);
    public final IntProperty tunnelMineDelay = new IntProperty("tunnel-mine-delay", 0, 0, 20, () -> this.mode.getValue() == 17);
    public final FloatProperty tunnelMotion = new FloatProperty("tunnel-motion", 1.0F, 0.1F, 2.0F, () -> this.mode.getValue() == 17);
    /* Slave rule: Tower owns vertical motion when enabled and Scaffold must not
       tower then. With slave-to-scaffold on, Tower instead yields to Scaffold. */
    public final BooleanProperty slaveToScaffold = new BooleanProperty("slave-to-scaffold", true);

    private int towerTicks;
    private int offGroundTicks;
    private double jumpGroundPos;
    private boolean timerChanged;
    private BlockPos mineTarget;
    private final TimerUtil packetTimer = new TimerUtil();
    private final TimerUtil jumpTimer = new TimerUtil();
    private final TunnelEngine tunnelEngine = new TunnelEngine();

    public Tower() {
        super("Tower", false, false, "Builds straight upward quickly with many modes.");
    }

    private boolean scaffoldOn() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }

    private boolean baseActive() {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (mc.currentScreen != null) {
            return false;
        }
        return !this.slaveToScaffold.getValue() || !this.scaffoldOn();
    }

    private boolean canTower() {
        return this.baseActive() && this.mode.getValue() != 17 && mc.gameSettings.keyBindJump.isKeyDown();
    }

    private boolean tunnelActive() {
        return this.baseActive() && this.mode.getValue() == 17;
    }

    private boolean diagonal() {
        return Math.abs(mc.thePlayer.motionX) > 0.08 && Math.abs(mc.thePlayer.motionZ) > 0.08;
    }

    private void restoreTimer() {
        if (this.timerChanged) {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
            this.timerChanged = false;
        }
    }

    private void resetState() {
        this.towerTicks = 0;
        this.offGroundTicks = 0;
        this.jumpGroundPos = 0.0;
        this.mineTarget = null;
        this.packetTimer.reset();
        this.jumpTimer.reset();
        this.tunnelEngine.reset();
        this.restoreTimer();
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.onGround) {
            this.offGroundTicks = 0;
        } else {
            this.offGroundTicks++;
        }
        if (this.tunnelActive()) {
            this.runTunnel();
            return;
        }
        if (!this.canTower()) {
            this.restoreTimer();
            return;
        }
        this.towerTicks++;
        switch (this.mode.getValue()) {
            case 0:
                this.runVanilla();
                break;
            case 1:
                this.runMotion();
                break;
            case 2:
                this.runMotionJump();
                break;
            case 3:
                this.runMotionTP();
                break;
            case 4:
                this.runConstantMotion();
                break;
            case 5:
                this.runPacket();
                break;
            case 6:
                this.runTeleport();
                break;
            case 7:
            case 10:
                this.runJumpSprint();
                break;
            case 8:
                this.runHypixel(false);
                break;
            case 9:
                this.runHypixel(true);
                break;
            case 11:
                this.runBlocksMC();
                break;
            case 12:
                this.runAAC339();
                break;
            case 13:
                this.runAAC364();
                break;
            case 14:
                this.runVulcan();
                break;
            case 15:
                this.runVulcan290();
                break;
            case 16:
                this.runPulldown();
                break;
            default:
                break;
        }
    }

    private void runVanilla() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
        double target = Math.max((this.diagonal() ? 0.5 : this.vanillaSpeed.getValue().doubleValue()) * 0.1 - 0.25, 0.0);
        MoveUtil.setSpeed(target, MoveUtil.getMoveYaw());
    }

    private void runMotion() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
            mc.thePlayer.motionY = 0.42;
        } else if (mc.thePlayer.motionY < 0.1) {
            mc.thePlayer.motionY = -0.3;
        }
    }

    private void runMotionJump() {
        if (mc.thePlayer.onGround && this.jumpTimer.hasTimeElapsed((long) this.jumpDelay.getValue() * 50L)) {
            mc.thePlayer.jump();
            mc.thePlayer.motionY = this.jumpHeight.getValue().doubleValue();
            this.jumpTimer.reset();
        }
    }

    private void runMotionTP() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
            mc.thePlayer.motionY = 0.42;
        } else if (mc.thePlayer.motionY < 0.23) {
            mc.thePlayer.setPosition(mc.thePlayer.posX, Math.floor(mc.thePlayer.posY), mc.thePlayer.posZ);
        }
    }

    private void runConstantMotion() {
        if (mc.thePlayer.onGround) {
            if (this.jumpPacket.getValue()) {
                mc.thePlayer.jump();
            }
            this.jumpGroundPos = mc.thePlayer.posY;
            mc.thePlayer.motionY = this.constantMotion.getValue().doubleValue();
        }
        if (mc.thePlayer.posY > this.jumpGroundPos + this.jumpGround.getValue().doubleValue()) {
            if (this.jumpPacket.getValue() && mc.thePlayer.onGround) {
                mc.thePlayer.jump();
            }
            mc.thePlayer.setPosition(mc.thePlayer.posX, Math.floor(mc.thePlayer.posY), mc.thePlayer.posZ);
            mc.thePlayer.motionY = this.constantMotion.getValue().doubleValue();
            this.jumpGroundPos = mc.thePlayer.posY;
        }
    }

    private void runPacket() {
        if (mc.thePlayer.onGround && this.packetTimer.hasTimeElapsed((long) this.packetDelay.getValue() * 50L)) {
            double x = mc.thePlayer.posX;
            double y = mc.thePlayer.posY;
            double z = mc.thePlayer.posZ;
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.42, z, false));
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.753, z, false));
            mc.thePlayer.setPosition(x, y + 1.0, z);
            this.packetTimer.reset();
        }
    }

    private void runTeleport() {
        if (this.teleportNoMotion.getValue()) {
            mc.thePlayer.motionY = 0.0;
        }
        if ((mc.thePlayer.onGround || !this.teleportGround.getValue())
                && this.packetTimer.hasTimeElapsed((long) this.teleportDelay.getValue() * 50L)) {
            mc.thePlayer.moveEntity(0.0, this.teleportHeight.getValue().doubleValue(), 0.0);
            this.packetTimer.reset();
        }
    }

    private void runJumpSprint() {
        mc.thePlayer.setSprinting(false);
        double scale = mc.thePlayer.onGround ? this.sprintSpeed.getValue().doubleValue() : this.offGroundSpeed.getValue().doubleValue();
        if (this.noStrafe.getValue()) {
            if (Math.abs(mc.thePlayer.motionX) >= Math.abs(mc.thePlayer.motionZ)) {
                mc.thePlayer.motionX *= scale;
                mc.thePlayer.motionZ = 0.0;
            } else {
                mc.thePlayer.motionZ *= scale;
                mc.thePlayer.motionX = 0.0;
            }
        } else {
            mc.thePlayer.motionX *= scale;
            mc.thePlayer.motionZ *= scale;
        }
        if (MoveUtil.isMoving()) {
            this.runLowHop();
        }
    }

    private void runLowHop() {
        switch (this.lowHop.getValue()) {
            case 1:
                switch (this.offGroundTicks) {
                    case 0:
                        mc.thePlayer.motionY = 0.4196;
                        break;
                    case 3:
                    case 4:
                        mc.thePlayer.motionY = 0.0;
                        break;
                    case 5:
                        mc.thePlayer.motionY = 0.4191;
                        break;
                    case 6:
                        mc.thePlayer.motionY = 0.3275;
                        break;
                    case 11:
                        mc.thePlayer.motionY = -0.5;
                        break;
                    default:
                        break;
                }
                break;
            case 2:
                switch (this.offGroundTicks) {
                    case 0:
                        mc.thePlayer.motionY = 0.4191;
                        break;
                    case 1:
                        mc.thePlayer.motionY = 0.327318;
                        break;
                    case 4:
                        mc.thePlayer.motionY = 0.065;
                        break;
                    case 5:
                        mc.thePlayer.motionY = -0.005;
                        break;
                    case 6:
                        mc.thePlayer.motionY = -1.0;
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }

    private void runHypixel(boolean fastVertical) {
        if (mc.thePlayer.isPotionActive(Potion.blindness)) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            if (this.hypixelOnlyMoving.getValue()) {
                return;
            }
            if (fastVertical) {
                double targetZ = Math.floor(mc.thePlayer.posZ) + 0.99999;
                double z = mc.thePlayer.posZ;
                if (z != targetZ) {
                    MoveUtil.setSpeed(0.0);
                    double next = targetZ > z ? Math.min(z + 0.3, targetZ) : Math.max(z - 0.3, targetZ);
                    mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY, next);
                    return;
                }
            }
        }
        double towerSpeed = (this.diagonal() ? 0.22 : 0.29888888) - (0.0008 + Math.random() * 0.008);
        if (!mc.thePlayer.onGround) {
            if (this.towerTicks == 2) {
                double snapped = Math.floor(mc.thePlayer.posY + 1.0) - mc.thePlayer.posY;
                mc.thePlayer.motionY = snapped;
            } else if (this.towerTicks >= 3) {
                mc.thePlayer.motionY = 0.41985;
                if (MoveUtil.isMoving()) {
                    MoveUtil.setSpeed(towerSpeed, MoveUtil.getMoveYaw());
                }
                this.towerTicks = 0;
            }
        } else {
            this.towerTicks = 0;
            mc.thePlayer.motionY = 0.419848;
            if (MoveUtil.isMoving()) {
                MoveUtil.setSpeed(towerSpeed, MoveUtil.getMoveYaw());
            }
        }
    }

    private void runBlocksMC() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
            mc.thePlayer.motionY = 0.42;
        }
        mc.thePlayer.motionX *= this.blocksSpeed.getValue().doubleValue();
        mc.thePlayer.motionZ *= this.blocksSpeed.getValue().doubleValue();
    }

    private void runAAC339() {
        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
            mc.thePlayer.motionY = 0.4001;
        }
        if (mc.thePlayer.motionY < 0.0) {
            mc.thePlayer.motionY -= 9.45E-6;
            timer.timerSpeed = this.towerTimer.getValue();
        } else {
            timer.timerSpeed = 1.0F;
        }
        this.timerChanged = timer.timerSpeed != 1.0F;
    }

    private void runAAC364() {
        if (this.towerTicks % 4 == 1) {
            mc.thePlayer.motionY = 0.4195464;
            mc.thePlayer.setPosition(mc.thePlayer.posX - 0.035, mc.thePlayer.posY, mc.thePlayer.posZ);
        } else if (this.towerTicks % 4 == 0) {
            mc.thePlayer.motionY = -0.5;
            mc.thePlayer.setPosition(mc.thePlayer.posX + 0.035, mc.thePlayer.posY, mc.thePlayer.posZ);
        }
    }

    private void runVulcan() {
        if (this.vulcanNotMoving.getValue() && MoveUtil.isMoving()) {
            return;
        }
        mc.thePlayer.motionY = this.towerTicks % 2 == 0 ? 0.7 : (MoveUtil.isMoving() ? 0.42 : 0.6);
    }

    private void runVulcan290() {
        if (this.towerTicks % 10 == 0) {
            mc.thePlayer.motionY = -0.1;
            return;
        }
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
        mc.thePlayer.motionY = this.towerTicks % 2 == 0 ? 0.7 : (MoveUtil.isMoving() ? 0.42 : 0.6);
    }

    private void runPulldown() {
        if (!mc.thePlayer.onGround && mc.thePlayer.motionY < this.triggerMotion.getValue().doubleValue()) {
            mc.thePlayer.motionY = -this.dragMotion.getValue().doubleValue();
        } else if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
    }

    private void runTunnel() {
        TunnelEngine.Action action = this.tunnelEngine.update(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.isCollidedHorizontally, System.currentTimeMillis(), TUNNEL_STUCK_MS,
                this.tunnelBackTicks.getValue(), (long) this.turnDelay.getValue() * 50L,
                this.tunnelAutoBack.getValue(), this.tunnelAutoTurn.getValue());
        if (action == TunnelEngine.Action.TURN) {
            mc.thePlayer.rotationYaw += 90.0F;
            this.mineTarget = null;
        }
        if (action == TunnelEngine.Action.BACK) {
            mc.thePlayer.movementInput.moveForward = -1.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
        } else {
            mc.thePlayer.movementInput.moveForward = 1.0F;
            mc.thePlayer.setSprinting(true);
        }
        mc.thePlayer.motionX *= this.tunnelSpeed.getValue().doubleValue() * this.tunnelMotion.getValue().doubleValue();
        mc.thePlayer.motionZ *= this.tunnelSpeed.getValue().doubleValue() * this.tunnelMotion.getValue().doubleValue();
        this.mineForward();
    }

    private void mineForward() {
        if (mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || mc.objectMouseOver.getBlockPos() == null) {
            this.mineTarget = null;
            return;
        }
        BlockPos pos = mc.objectMouseOver.getBlockPos();
        if (!pos.equals(this.mineTarget)) {
            mc.playerController.clickBlock(pos, mc.objectMouseOver.sideHit);
            this.mineTarget = pos;
        }
        mc.playerController.onPlayerDamageBlock(pos, mc.objectMouseOver.sideHit);
        mc.thePlayer.swingItem();
        ((IAccessorPlayerControllerMP) mc.playerController).setBlockHitDelay(this.tunnelMineDelay.getValue());
    }
}
