package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AimAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 3.0F, 0.0F, 10.0F);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 0.0F, 0.0F, 10.0F);
    public final PercentProperty smoothing = new PercentProperty("smoothing", 50);
    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("fov", 90, 30, 360);
    /* Pull toward the point of the hitbox nearest the crosshair instead of its
       middle (RotationEngine.nearestOnBox). With the crosshair already on the
       player there is nothing to correct and the view is left alone -- an
       assist that keeps dragging an on-target aim to the centre is the
       pattern aim checks look for. */
    public final BooleanProperty multipoint = new BooleanProperty("multipoint", true);
    /* After Vape's AimAssist (studied 2026-09-25), rewritten here.
       lead: aim where the target will be, its last tick's movement carried
       this many ticks ahead. A pull toward where they were trails a
       strafing target by a tick of their movement every tick.
       strafe-increase: pull 1.6 times harder while this player strafes away
       from the side the target is on -- the case where the gap opens
       fastest. Capped at a full step, so at speed 10 it changes nothing. */
    public final FloatProperty lead = new FloatProperty("lead", 1.0F, 0.0F, 3.0F);
    public final BooleanProperty strafeIncrease = new BooleanProperty("strafe-increase", true);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponOnly::getValue);
    public final BooleanProperty botChecks = new BooleanProperty("bot-check", true);
    public final BooleanProperty team = new BooleanProperty("teams", true);

    /* NORMAL moves the real camera through RotationManager, which writes
       mc.thePlayer.rotationYaw directly. SILENT leaves the camera alone and
       only overrides the yaw/pitch carried by the movement packet.

       Silent aim is not simply "the safer option": it makes the direction you
       report to the server differ from the one your movement is computed
       from, which is a mismatch a server can check on its own. The MoveFix
       module does not close it: it turns the keys toward the yaw movement is
       computed from, and a rotation that only goes on the packet leaves that
       yaw at the camera's, so MoveFix turns them by nothing. move-fix below
       is what closes it (ENGINEERING-NOTES 4.12). */
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "SILENT"});

    /* How far the silent aim may drift from where the player is actually
       looking. Without a ceiling an assist that nudges a little every tick
       accumulates into a full silent killaura pointing somewhere the player
       never looked, which is both a different feature and a far louder one. */
    public final FloatProperty maxOffset = new FloatProperty("max-offset", 30.0F, 1.0F, 180.0F,
            () -> this.mode.getValue() == 1);

    /* SILENT makes the silent aim the yaw movement is computed from too, and
       turns the keys to match, so the player still goes where the camera
       points (to within the 22.5 degrees eight key combinations leave) and
       the server sees movement that agrees with the yaw it was sent. NONE
       keeps moving by the camera -- the mismatch described above. */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"},
            () -> this.mode.getValue() == 1);

    private float silentYaw;
    private float silentPitch;
    private boolean silentActive;
    private boolean silentQueued;

    /* The yaw handed to setPervRotation this tick; NaN on ticks this module
       did not rotate. */
    private float moveYaw = Float.NaN;

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        if (entityPlayer != mc.thePlayer && entityPlayer != mc.thePlayer.ridingEntity) {
            if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityPlayer.deathTime > 0) {
                return false;
            } else if (RotationUtil.distanceToEntity(entityPlayer) > (double) this.range.getValue()) {
                return false;
            } else if (RotationUtil.angleToEntity(entityPlayer) > (float) this.fov.getValue()) {
                return false;
            } else if (RotationUtil.rayTrace(entityPlayer) != null) {
                return false;
            } else if (TeamUtil.isFriend(entityPlayer)) {
                return false;
            } else {
                return (!this.team.getValue() || !TeamUtil.isSameTeam(entityPlayer)) && (!this.botChecks.getValue() || !TeamUtil.isBot(entityPlayer));
            }
        } else {
            return false;
        }
    }

    private boolean isInReach(EntityPlayer entityPlayer) {
        Reach reach = (Reach) Myau.moduleManager.modules.get(Reach.class);
        double distance = reach.isEnabled() ? (double) reach.range.getValue() : 3.0;
        return RotationUtil.distanceToEntity(entityPlayer) <= distance;
    }

    private boolean isLookingAtBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    @Override
    public void onEnabled() {
        this.silentActive = false;
        this.silentQueued = false;
    }

    @Override
    public void onDisabled() {
        this.silentActive = false;
        this.silentQueued = false;
    }

    /** Clamps an angle so the silent aim stays within max-offset of the camera. */
    private float limit(float target, float actual) {
        float max = this.maxOffset.getValue();
        float delta = MathHelper.wrapAngleTo180_float(target - actual);
        if (delta > max) {
            delta = max;
        } else if (delta < -max) {
            delta = -max;
        }
        return actual + delta;
    }

    public AimAssist() {
        super("AimAssist", false , false, "Assists your aim by subtly adjusting your view towards nearby targets when attacking.");
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST && mc.currentScreen == null) {
            if (!(Boolean) this.weaponOnly.getValue()
                    || ItemUtil.hasRawUnbreakingEnchant()
                    || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
                boolean attacking = PlayerUtil.isAttacking();
                if (!attacking || !this.isLookingAtBlock()) {
                    if (attacking || !this.timer.hasTimeElapsed(350L)) {
                        List<EntityPlayer> inRange = mc.theWorld
                                .loadedEntityList
                                .stream()
                                .filter(entity -> entity instanceof EntityPlayer)
                                .map(entity -> (EntityPlayer) entity)
                                .filter(this::isValidTarget)
                                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                                .collect(Collectors.toList());
                        if (!inRange.isEmpty()) {
                            if (inRange.stream().anyMatch(this::isInReach)) {
                                inRange.removeIf(entityPlayer -> !this.isInReach(entityPlayer));
                            }
                            EntityPlayer player = inRange.get(0);
                            if (!(RotationUtil.distanceToEntity(player) <= 0.0)) {
                                AxisAlignedBB axisAlignedBB = player.getEntityBoundingBox();
                                double collisionBorderSize = player.getCollisionBorderSize();
                                AxisAlignedBB hitbox = axisAlignedBB.expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
                                float ahead = this.lead.getValue();
                                if (ahead > 0.0F) {
                                    hitbox = hitbox.offset((player.posX - player.lastTickPosX) * ahead, 0.0,
                                            (player.posZ - player.lastTickPosZ) * ahead);
                                }
                                float[] rotation;
                                if (this.multipoint.getValue()) {
                                    Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
                                    Vec3 point = RotationEngine.nearestOnBox(RotationEngine.aimBox(hitbox, 0.15, 0.05, 0.85),
                                            eyes, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                                    rotation = RotationUtil.getRotations(point.xCoord - eyes.xCoord,
                                            point.yCoord - eyes.yCoord, point.zCoord - eyes.zCoord,
                                            mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, 180.0F,
                                            (float) this.smoothing.getValue() / 100.0F);
                                } else {
                                    rotation = RotationUtil.getRotationsToBox(
                                            hitbox,
                                            mc.thePlayer.rotationYaw,
                                            mc.thePlayer.rotationPitch,
                                            180.0F,
                                            (float) this.smoothing.getValue() / 100.0F
                                    );
                                }
                                float yaw = Math.min(Math.abs(this.hSpeed.getValue()), 10.0F);
                                float pitch = Math.min(Math.abs(this.vSpeed.getValue()), 10.0F);
                                float yawStep = 0.1F * yaw;
                                if (this.strafeIncrease.getValue()) {
                                    float side = MathHelper.wrapAngleTo180_float(rotation[0] - mc.thePlayer.rotationYaw);
                                    float strafe = mc.thePlayer.movementInput.moveStrafe;
                                    /* moveStrafe > 0 is left; a target to the right has a positive side. */
                                    if (side > 0.0F && strafe > 0.0F || side < 0.0F && strafe < 0.0F) {
                                        yawStep = Math.min(1.0F, yawStep * 1.6F);
                                    }
                                }
                                float nextYaw = mc.thePlayer.rotationYaw
                                        + (rotation[0] - mc.thePlayer.rotationYaw) * yawStep;
                                float nextPitch = mc.thePlayer.rotationPitch
                                        + (rotation[1] - mc.thePlayer.rotationPitch) * 0.1F * pitch;

                                if (this.mode.getValue() == 1) {
                                    this.silentYaw = limit(nextYaw, mc.thePlayer.rotationYaw);
                                    this.silentPitch = MathHelper.clamp_float(
                                            limit(nextPitch, mc.thePlayer.rotationPitch), -90.0F, 90.0F);
                                    this.silentQueued = true;
                                } else {
                                    Myau.rotationManager.setRotation(nextYaw, nextPitch, 0, false);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the silent aim into the movement packet. The camera is untouched,
     * so this is the only place the server ever sees the adjusted angle.
     *
     * Priority 1 keeps this below Scaffold (3) and Clutch (7): those are
     * placing blocks and need the rotation they asked for, while an aim assist
     * losing a tick to them costs nothing.
     */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        this.moveYaw = Float.NaN;
        if (this.mode.getValue() != 1) {
            this.silentActive = false;
            this.silentQueued = false;
            return;
        }
        if (!this.silentQueued) {
            // Nothing aimed at this tick: let the real rotation through so the
            // packet stream returns to the camera instead of holding a stale
            // angle at a target that is gone.
            this.silentActive = false;
            return;
        }
        this.silentQueued = false;
        this.silentActive = true;
        event.setRotation(this.silentYaw, this.silentPitch, 1);
        // Same priority, so whichever module wins the packet also sets the
        // yaw the player moves by.
        this.moveYaw = this.moveFix.getValue() != 0 ? this.silentYaw : mc.thePlayer.rotationYaw;
        event.setPervRotation(this.moveYaw, 1);
    }

    /**
     * Turns the movement keys toward the silent yaw so the player keeps going
     * where the camera points.
     *
     * Only for this module's own rotation: the yaw in force must be the one
     * set above, not one another module at priority 1 won with -- Speed moves
     * by its own yaw with the keys as pressed, on purpose, and KillAura fixes
     * its own. fixStrafe reads the raw keys, so running after MoveFix is
     * harmless.
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()
                && this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 1
                && RotationState.getSmoothedYaw() == this.moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget(whenDisabled = true)
    public void onPress(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindAttack.getKeyCode() && !Myau.moduleManager.modules.get(AutoClicker.class).isEnabled()) {
            this.timer.reset();
        }
    }
}
