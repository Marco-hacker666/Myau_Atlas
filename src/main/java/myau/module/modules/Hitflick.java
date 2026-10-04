package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

public class Hitflick extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty direction = new ModeProperty("Direction", 0, new String[]{"Left", "Right", "Back", "Custom"});
    public final FloatProperty customAngle = new FloatProperty("Custom-Angle", 90F, 1F, 180F, () -> this.direction.getValue() == 3);
    public final IntProperty cooldown = new IntProperty("Cooldown", 1, 1, 40);
    public final BooleanProperty blink = new BooleanProperty("Blink", false);

    /* NONE by default, unlike the other rotating modules: a flick lasts one
       tick, and move-fix costs something on every flick.

       Turning the keys toward a yaw 90 or 180 degrees away leaves no forward
       in them, so vanilla stops sprinting that tick: STOP_SPRINTING on the
       flick, START on the restore, for every flick -- up to a pair every
       three ticks while clicking at cooldown 1. That is a sprint-toggle
       pattern, and a W-tap nobody asked for (each START re-arms sprint
       knockback on the server).

       Without it the flick tick moves by the camera while reporting the flick
       (ENGINEERING-NOTES 4.12), but only for that tick. For Left, Right and
       Back the step taken is exactly what the strafe or back key gives at the
       reported yaw, so a server re-simulating movement can still match it,
       provided it lets a sprinting player strafe. It cannot match a sprint
       jump on the flick tick (the boost goes along the camera) or a Custom
       angle off the 45-degree grid. If lagbacks line up with flicks, use
       SILENT. */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 0, new String[]{"NONE", "SILENT", "REAL"});

    private long sinceLastFlick;
    private float originalYaw;
    private float flickYaw;
    private float moveYaw = Float.NaN;
    private FlickState state = FlickState.IDLE;

    private enum FlickState {
        IDLE, FLICKING_AWAY, RESTORING
    }

    public Hitflick() {
        super("Hitflick", false, true, "Flick away on hit then restore");
    }

    @Override
    public void onEnabled() {
        sinceLastFlick = 0;
        state = FlickState.IDLE;
    }

    @Override
    public void onDisabled() {
        state = FlickState.IDLE;
        sinceLastFlick = 0;
        if (blink.getValue()) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.HITFLICK);
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;
        if (event.getTarget() == null || event.getTarget() == mc.thePlayer) return;
        if (state != FlickState.IDLE || sinceLastFlick < cooldown.getValue()) return;
        if (!(event.getTarget() instanceof EntityLivingBase)) return;

        originalYaw = mc.thePlayer.rotationYaw;
        flickYaw = originalYaw + getFlickAngle();
        state = FlickState.FLICKING_AWAY;

        if (blink.getValue()) {
            Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
            Myau.blinkManager.setBlinkState(true, BlinkModules.HITFLICK);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;
        moveYaw = Float.NaN;

        if (state == FlickState.IDLE) {
            sinceLastFlick++;
            return;
        }

        if (state == FlickState.FLICKING_AWAY) {
            rotate(event, flickYaw);
            state = FlickState.RESTORING;
        } else if (state == FlickState.RESTORING) {
            rotate(event, originalYaw);
            state = FlickState.IDLE;
            sinceLastFlick = 0;
            if (blink.getValue()) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.HITFLICK);
            }
        }
    }

    private void rotate(UpdateEvent event, float yaw) {
        event.setRotation(yaw, mc.thePlayer.rotationPitch, 0);
        moveYaw = moveFix.getValue() != 0 ? yaw : mc.thePlayer.rotationYaw;
        event.setPervRotation(moveYaw, 0);
        if (moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Myau.rotationManager.setRotation(yaw, mc.thePlayer.rotationPitch, 0, false);
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        // Only this module's own rotation; AntiFireball also rotates at 0.
        if (moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 0
                && RotationState.getSmoothedYaw() == moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    private float getFlickAngle() {
        switch (direction.getValue()) {
            case 0: return -90F;
            case 1: return 90F;
            case 2: return 180F;
            case 3: return customAngle.getValue();
            default: return 90F;
        }
    }
}
