package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP", "ACTIVE"});

    /* ACTIVE, after Vape's HitSelect "Active" (studied 2026-09-25), written
       here from what it does rather than how.

       Closing in on someone, most clicks cannot do anything: the target is
       inside the ten ticks after a hit and the server refuses the damage.
       They still cost -- every attack while sprinting cuts this player's own
       speed to 60% and ends the sprint on the client -- so the chase is
       slower for them. ACTIVE lets through the click that can land (the
       target's hurt time within the round trip HitTimer measures, or one
       tick short of it) and drops the rest before they happen: no swing, no
       attack, no slowdown. It cancels the click itself rather than the
       packet after it, which is what the other three modes do and why they
       have to patch the slowdown up with KeepSprint afterwards.

       Only while moving toward the target; backing off or strafing, every
       click goes through. After being knocked back, the preference decides:
       KB_REDUCTION lets every click through until landing (a sprint hit
       takes 40% off this player's own motion, knockback included);
       CRITICALS holds clicks while still rising and lets them go on the way
       down. */
    public final PercentProperty chance = new PercentProperty("chance", 90, () -> this.mode.getValue() == 3);
    public final ModeProperty preference = new ModeProperty("preference", 0,
            new String[]{"KB_REDUCTION", "CRITICALS"}, () -> this.mode.getValue() == 3);
    /** Ticks after our own knockback still treated as part of it. */
    private static final int VELOCITY_TICKS = 7;
    private volatile int velocityTicks = 0;
    private int sinceVelocity = 0;
    private int lastAllowedTick = -1000;
    private int dropped = 0;

    private boolean sprintState = false;
    private boolean set = false;
    private boolean keepSprintWasEnabled = false;
    private double savedSlowdown = 0.0;

    private int blockedHits = 0;
    private int allowedHits = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (event.getType() == EventType.POST) {
            this.resetMotion();
        } else if (this.velocityTicks > 0) {
            this.sinceVelocity++;
            this.velocityTicks--;
            /* Landed: the knockback is over. Not on the tick it arrived,
               when the player has not yet left the ground. */
            if (this.sinceVelocity > 1 && mc.thePlayer.onGround) {
                this.velocityTicks = 0;
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 3 || event.isCancelled()
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        EntityLivingBase target = mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                ? (EntityLivingBase) mc.objectMouseOver.entityHit : null;
        if (this.activeDrops(target)) {
            drop(event);
        }
    }

    /**
     * K7 (2026-10-08): KillAura asks before an aura hit. True when ACTIVE
     * would drop this click on {@code target} -- the same decision as for a
     * hand click, so the aura and the mouse never follow two rules. The aura
     * counts a dropped click as a click (its CPS rhythm goes on), as a hand
     * click dropped here is still a click.
     */
    public boolean dropsAuraHit(EntityLivingBase target) {
        if (!this.isEnabled() || this.mode.getValue() != 3 || mc.thePlayer == null || target == null) {
            return false;
        }
        if (this.activeDrops(target)) {
            this.dropped++;
            return true;
        }
        return false;
    }

    /** ACTIVE's rule for one click; target may be null (nothing under the crosshair). */
    private boolean activeDrops(EntityLivingBase target) {
        if (Math.random() * 100.0 >= this.chance.getValue()) {
            return false;
        }
        if (this.velocityTicks > 0) {
            if (this.preference.getValue() == 0) {
                return false;
            }
            if (mc.thePlayer.motionY > 0.0) {
                return true;
            }
            if (mc.thePlayer.onGround) {
                return false;
            }
        }
        if (target == null) {
            return false;
        }
        if (!this.isMovingTowards(mc.thePlayer, target, 90.0)) {
            return false;
        }
        int expected = myau.management.HitTimer.ticks();
        int now = mc.thePlayer.ticksExisted;
        if (target.hurtTime <= expected) {
            /* The opening: the first click in it goes, and the next only
               once two round trips have passed without it being answered. */
            int spacing = Math.max(2, expected * 2);
            if (now - this.lastAllowedTick >= spacing || now < this.lastAllowedTick) {
                this.lastAllowedTick = now;
                return false;
            }
        } else if (target.hurtTime == expected + 1) {
            return false;
        }
        return true;
    }

    private void drop(LeftClickMouseEvent event) {
        event.setCancelled(true);
        this.dropped++;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof net.minecraft.network.play.server.S12PacketEntityVelocity
                    && mc.thePlayer != null
                    && ((net.minecraft.network.play.server.S12PacketEntityVelocity) event.getPacket()).getEntityID()
                    == mc.thePlayer.getEntityId()) {
                this.velocityTicks = VELOCITY_TICKS;
                this.sinceVelocity = 0;
            }
            return;
        }
        if (event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.sprintState = true;
                    break;
                case STOP_SPRINTING:
                    this.sprintState = false;
                    break;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();

            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball) {
                return;
            }

            if (!(target instanceof EntityLivingBase)) {
                return;
            }

            EntityLivingBase living = (EntityLivingBase) target;
            boolean allow = true;

            switch (this.mode.getValue()) {
                case 3: // ACTIVE decides at the click, above
                    break;
                case 0: // SECOND
                    allow = this.prioritizeSecondHit(mc.thePlayer, living);
                    break;
                case 1: // CRITICALS
                    allow = this.prioritizeCriticalHits(mc.thePlayer);
                    break;
                case 2: // WTAP
                    allow = this.prioritizeWTapHits(mc.thePlayer, this.sprintState);
                    break;
            }

            if (!allow) {
                event.setCancelled(true);
                this.blockedHits++;
            } else {
                this.allowedHits++;
            }
        }
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        // If target is already hurt, allow the hit
        if (target.hurtTime != 0) {
            return true;
        }

        // If player hasn't recovered from hurt time, allow the hit
        if (player.hurtTime <= player.maxHurtTime - 1) {
            return true;
        }

        // If too close, allow the hit
        double dist = player.getDistanceToEntity(target);
        if (dist < 2.5) {
            return true;
        }

        // If not moving towards each other, allow the hit
        if (!this.isMovingTowards(target, player, 60.0)) {
            return true;
        }

        if (!this.isMovingTowards(player, target, 60.0)) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        // If on ground, allow the hit
        if (player.onGround) {
            return true;
        }

        // If hurt, allow the hit
        if (player.hurtTime != 0) {
            return true;
        }

        // If falling, allow the hit (for crits)
        if (player.fallDistance > 0.0f) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        // If against wall, allow the hit
        if (player.isCollidedHorizontally) {
            return true;
        }

        // If not moving forward, allow the hit
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return true;
        }

        // If already sprinting, allow the hit
        if (sprinting) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private void fixMotion() {
        if (this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Save the current slowdown value
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            this.keepSprintWasEnabled = keepSprint.isEnabled();

            // Temporarily enable KeepSprint silently so this internal motion fix does not spam toggles.
            if (!this.keepSprintWasEnabled) {
                keepSprint.setEnabled(true);
            }
            keepSprint.slowdown.setValue(0);

            this.set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Restore the original slowdown value
            keepSprint.slowdown.setValue((int) this.savedSlowdown);

            // Only restore the enabled state if HitSelect changed it.
            if (!this.keepSprintWasEnabled && keepSprint.isEnabled()) {
                keepSprint.setEnabled(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.set = false;
        this.keepSprintWasEnabled = false;
        this.savedSlowdown = 0.0;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        // Calculate movement vector
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        // If not moving, return false
        if (movementLength == 0.0) {
            return false;
        }

        // Normalize movement vector
        mx /= movementLength;
        mz /= movementLength;

        // Calculate vector to target
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        // If target is at same position, return false
        if (targetLength == 0.0) {
            return false;
        }

        // Normalize target vector
        tx /= targetLength;
        tz /= targetLength;

        // Calculate dot product (cosine of angle between vectors)
        double dotProduct = mx * tx + mz * tz;

        // Check if angle is within threshold
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        this.resetMotion();
        this.sprintState = false;
        this.set = false;
        this.savedSlowdown = 0.0;
        this.blockedHits = 0;
        this.allowedHits = 0;
        this.velocityTicks = 0;
        this.dropped = 0;
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 3) {
            return new String[]{this.mode.getModeString(), this.dropped + " dropped"};
        }
        return new String[]{this.mode.getModeString()};
    }
}
