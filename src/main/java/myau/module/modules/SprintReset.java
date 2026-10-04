package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;

public class SprintReset extends Module {

    /* LEGIT (the default since 2026-09-25) lets go of forward for one tick:
       one STOP, then one START the tick after, exactly a w-tap. PACKET and
       SILENT send STOP and START in the same tick, two sprint changes in one
       tick, which no keyboard produces and which bad-packet checks count. */
    public final ModeProperty mode = new ModeProperty("Mode", 1, new String[]{"PACKET", "LEGIT", "SILENT"});
    private final BooleanProperty onlyWhileSprinting = new BooleanProperty("Only While Sprinting", true);
    private final BooleanProperty onlyWhileMoving = new BooleanProperty("Only While Moving", true);
    /* Sends four made-up positions (a packet critical) with the reset. That
       is a movement no player makes, on every hit; off by default since
       2026-09-25, and not something to turn on on Pika. */
    private final BooleanProperty resetOnCrit = new BooleanProperty("Reset On Crit", false);
    /* Ticks between resets. The server takes sprint away only on a hit that
       does damage, and a player can be damaged once every ten ticks; at 12-16
       clicks a second, resetting on every click let go of forward almost every
       tick. One reset per damage window is all the knockback needs. */
    private final IntProperty minGap = new IntProperty("min-gap", 8, 0, 20);
    private int lastResetTick = -1000;

    /* The rest after Vape's WTap (studied 2026-09-25), rewritten here.
       - chance: not every hit. A reset on every single one is a rhythm.
       - release-delay / release-ticks: when forward is let go after the
         hit, and for how long, in ticks (Vape: 50 ms each, one tick).
       - select-hits: only on a hit that can do damage when it lands -- the
         target's hurt time within the measured round trip (HitTimer). The
         server takes sprint away only on a damaging hit, so a reset spent
         on one that cannot land is a slowdown for nothing.
       LEGIT only; PACKET and SILENT keep their old behaviour. */
    private final PercentProperty chance = new PercentProperty("chance", 90);
    private final IntProperty releaseDelay = new IntProperty("release-delay", 0, 0, 5,
            () -> this.mode.getValue() == 1);
    private final IntProperty releaseTicks = new IntProperty("release-ticks", 1, 1, 5,
            () -> this.mode.getValue() == 1);
    private final BooleanProperty selectHits = new BooleanProperty("select-hits", true);
    private int releaseWait = 0;
    private final BooleanProperty smartReset = new BooleanProperty("Smart Reset", false);
    private final BooleanProperty fastReset = new BooleanProperty("Fast Reset", false);
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean attacked = false;
    private boolean needsReset = false;
    private int ticksSinceAttack = 0;
    private Entity lastTarget = null;
    /* 2026-09-24. LEGIT and SILENT did not work.

       LEGIT set sprint off at the attack, but with the sprint key held (the
       Sprint module holds it) EntityPlayerSP.onLivingUpdate turns it straight
       back on in the same tick, and sprint packets only go out once a tick by
       comparing states -- so no STOP ever reached the server. Now it lets go
       of forward for one tick, which is what drops sprint for a real player.

       SILENT sent STOP from the attack event, which fires BEFORE the attack
       packet (vanilla's order, see MixinPlayerControllerMP). So the hit it
       meant to help landed without sprint knockback, and the START it sent a
       tick later was undone by the next attack's STOP. Now nothing is sent
       at the attack; STOP and START go together on the next tick, after the
       hit, so the next one lands sprinting. */
    private int forwardReleaseTicks = 0;

    public SprintReset() {
        super("SprintReset", false, false);
    }

    @Override
    public void onEnabled() {
        lastResetTick = -1000;
        attacked = false;
        needsReset = false;
        ticksSinceAttack = 0;
        lastTarget = null;
    }

    @Override
    public void onDisabled() {
        releaseWait = 0;
        forwardReleaseTicks = 0;
        attacked = false;
        needsReset = false;
        ticksSinceAttack = 0;
        lastTarget = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        Entity target = event.getTarget();
        if (target == null) return;
        if (!(target instanceof EntityLivingBase)) return;

        if (onlyWhileSprinting.getValue() && !mc.thePlayer.isSprinting()) return;
        if (onlyWhileMoving.getValue() && !isMoving()) return;
        /* One w-tap at a time: a new hit does not restart one in progress. */
        if (releaseWait > 0 || forwardReleaseTicks > 0) return;
        if (selectHits.getValue() && ((EntityLivingBase) target).hurtTime > myau.management.HitTimer.ticks() + 1) return;
        int now = mc.thePlayer.ticksExisted;
        if (now >= lastResetTick && now - lastResetTick < minGap.getValue()) return;
        if (Math.random() * 100.0 >= chance.getValue()) return;
        lastResetTick = now;

        if (smartReset.getValue()) {
            if (((EntityLivingBase) target).getHealth() <= 0) return;
            if (mc.thePlayer.getDistanceToEntity(target) > 6.0f) return;
        }
        if (fastReset.getValue() && lastTarget != null && lastTarget != target) {
            handlePacketReset();
        }

        lastTarget = target;
        attacked = true;

        switch (mode.getValue()) {
            case 0:
                handlePacketReset();
                break;
            case 1:
                handleLegitReset();
                break;
            case 2:
                handleSilentReset();
                break;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        // Once per tick: this used to count PRE and POST, two a tick.
        if (event.getType() != myau.event.types.EventType.PRE) return;
        if (attacked) {
            ticksSinceAttack++;
        }
        if (mode.getValue() == 1 && needsReset && ticksSinceAttack >= 1) {
            needsReset = false;
            attacked = false;
            ticksSinceAttack = 0;
        }
        if (mode.getValue() == 2 && needsReset && ticksSinceAttack >= 1) {
            if (mc.thePlayer.isSprinting()) {
                mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                );
                mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                );
            }
            needsReset = false;
            attacked = false;
            ticksSinceAttack = 0;
        }
        if (ticksSinceAttack > 5) {
            attacked = false;
            needsReset = false;
            ticksSinceAttack = 0;
        }
    }

    private void handlePacketReset() {
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
        );
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
        );
        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
        attacked = false;
        ticksSinceAttack = 0;
    }

    private void handleLegitReset() {
        releaseWait = releaseDelay.getValue();
        forwardReleaseTicks = releaseTicks.getValue();
        needsReset = true;
        ticksSinceAttack = 0;

        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
    }

    private void handleSilentReset() {
        needsReset = true;
        ticksSinceAttack = 0;

        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
    }

    private void performCritReset() {
        if (mc.thePlayer.onGround && !mc.thePlayer.isInWater() && !mc.thePlayer.isOnLadder()) {
            double x = mc.thePlayer.posX;
            double y = mc.thePlayer.posY;
            double z = mc.thePlayer.posZ;

            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.0625, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.1E-5, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false)
            );
        }
    }

    @EventTarget
    public void onMoveInput(myau.events.MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (releaseWait > 0) {
            releaseWait--;
            return;
        }
        if (forwardReleaseTicks > 0) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            forwardReleaseTicks--;
        }
    }

    private boolean canCrit() {
        return mc.thePlayer.onGround
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.blindness)
                && mc.thePlayer.ridingEntity == null;
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0 || mc.thePlayer.moveStrafing != 0;
    }
}