package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET"});
    public final BooleanProperty intelligent = new BooleanProperty("intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("only-ground", true);
    private boolean shouldSprintReset;
    /* Both legit modes did nothing. LEGIT turned sprint off and on inside one
       tick, and sprint packets are only sent once a tick by comparing states,
       so nothing went out. LEGIT_FAST set sprintingTicksLeft to 0, which
       EntityPlayerSP only acts on when it counts down from above 0. And with
       the sprint key held (the Sprint module holds it), onLivingUpdate turns
       sprint straight back on in the same tick anyway.

       What actually drops sprint for a tick is what a player does: let go of
       forward. With no forward input vanilla stops sprinting and sends STOP;
       the next tick forward returns, sprint restarts and START goes out. */
    private int legitStopTicks;
    private EntityLivingBase target;

    public MoreKB() {
        super("MoreKB", false);
        this.shouldSprintReset = false;
        this.target = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Entity targetEntity = event.getTarget();
        if (targetEntity != null && targetEntity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) targetEntity;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (this.mode.getValue() == 1) {
            if (this.target != null && this.isMoving()) {
                if (((this.onlyGround.getValue() && mc.thePlayer.onGround) || !this.onlyGround.getValue())
                        && mc.thePlayer.isSprinting()) {
                    this.legitStopTicks = 1;
                }
                this.target = null;
            }
            return;
        }
        EntityLivingBase entity = null;
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            entity = (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        if (entity == null) {
            return;
        }
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
        if (this.intelligent.getValue() && diffY > 120.0F) {
            return;
        }
        if (entity.hurtTime == 10) {
            switch (this.mode.getValue()) {
                case 0:
                    if (mc.thePlayer.isSprinting()) {
                        this.legitStopTicks = 1;
                    }
                    break;
                case 2:
                    if (mc.thePlayer.isSprinting()) {
                        mc.thePlayer.setSprinting(false);
                    }
                    mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 3:
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 4:
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
            }
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @EventTarget
    public void onMoveInput(myau.events.MoveInputEvent event) {
        if (this.isEnabled() && this.legitStopTicks > 0 && mc.thePlayer != null) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            this.legitStopTicks--;
        }
    }

    @Override
    public void onDisabled() {
        this.legitStopTicks = 0;
        this.target = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue().toString()};
    }
}