package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.KnockbackPlanner;
import myau.util.MoveUtil;
import myau.util.RotationEngine;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Knocks the target toward danger, for fights by hand (2026-09-28, item 7 of
 * the Rise comparison; Rise's ManualKBDisplacement, rewritten from what it
 * does).
 *
 * After this player lands a hit, and for as long as the target is still
 * hurt, the yaw the server has is turned -- silently, the camera untouched --
 * toward the worst place around the target (KnockbackPlanner). When the next
 * hit lands, its sprint push goes that way. Movement is fixed to the camera,
 * so walking is unaffected. Stays out of the way of a falling crit, and off
 * while KillAura is on (KillAura has its own, KBDisplace).
 *
 * Risk, stated plainly: the server sees a hit while this player's yaw points
 * away from the target. Servers that check the facing of every hit will see
 * that.
 */
public class KBDisplacement extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /* Above AimAssist (1), below Scaffold (3) and Clutch (7). */
    private static final int PRIORITY = 2;

    /* Rise's units: times 36 degrees a tick. */
    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 8, 1, 20);
    public final FloatProperty trackingRange = new FloatProperty("tracking-range", 6.0F, 3.0F, 8.0F);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    private EntityLivingBase target;
    private int armedTicks;
    private float moveYaw = Float.NaN;
    private String lastDebug = "";
    private int lastDebugTick = -1;

    public KBDisplacement() {
        super("KBDisplacement", false, false, "Silently aims your knockback at nearby hazards after a hit");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    private void reset() {
        this.target = null;
        this.armedTicks = 0;
        this.moveYaw = Float.NaN;
        this.lastDebug = "";
        this.lastDebugTick = -1;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        reset();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        Module aura = Myau.moduleManager.modules.get(KillAura.class);
        if (aura != null && aura.isEnabled()) {
            return;
        }
        if (!event.isCancelled() && event.getTarget() instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) event.getTarget();
            this.armedTicks = 2;
            debug("armed", null);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        this.moveYaw = Float.NaN;
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.target == null || mc.thePlayer == null) {
            return;
        }
        if (this.armedTicks > 0) {
            this.armedTicks--;
        }
        EntityLivingBase living = this.target;
        if (living.isDead || mc.theWorld == null
                || mc.thePlayer.getDistanceToEntity(living) > this.trackingRange.getValue() + 1.0) {
            debug("rejected:no-target", null);
            reset();
            return;
        }
        if (!KnockbackPlanner.hasKnockbackSource(mc.thePlayer, EnchantmentHelper.getKnockbackModifier(mc.thePlayer))) {
            debug("rejected:no-kb-source", null);
            return;
        }
        if (living.hurtTime <= 0 && this.armedTicks <= 0) {
            debug("rejected:hurt-time-ended", null);
            reset();
            return;
        }
        if (KnockbackPlanner.fallingCrit(mc.thePlayer)) {
            debug("rejected:crit-priority", null);
            return;
        }
        KnockbackPlanner.Plan plan = KnockbackPlanner.plan(mc.theWorld, living);
        if (plan == null) {
            debug("rejected:no-plan", null);
            return;
        }
        float from = event.getYaw();
        float wantYaw = from + MathHelper.wrapAngleTo180_float(plan.yaw - from);
        float wantPitch = pitchTo(living, wantYaw);
        float limit = this.rotationSpeed.getValue() * 36.0F;
        float dYaw = MathHelper.clamp_float(wantYaw - from, -limit, limit);
        float dPitch = MathHelper.clamp_float(wantPitch - event.getPitch(), -limit, limit);
        float[] next = RotationUtil.gcd(new float[]{from + dYaw, event.getPitch() + dPitch},
                new float[]{from, event.getPitch()});
        event.setRotation(next[0], next[1], PRIORITY);
        this.moveYaw = next[0];
        event.setPervRotation(this.moveYaw, PRIORITY);
        debug("applied", plan);
    }

    /** Keeps walking where the camera points while the server yaw is turned. */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()
                && !Float.isNaN(this.moveYaw)
                && RotationState.isActived()
                && RotationState.getSmoothedYaw() == this.moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(this.moveYaw);
        }
    }

    /** The pitch that looks at the target's body along this yaw. */
    private static float pitchTo(EntityLivingBase living, float yaw) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 body = new Vec3(living.posX, living.posY + living.height * 0.5, living.posZ);
        return RotationEngine.rotationsTo(eyes, body, yaw)[1];
    }

    private void debug(String what, KnockbackPlanner.Plan plan) {
        if (!this.debug.getValue() || mc.thePlayer == null) {
            return;
        }
        String line = plan == null
                ? String.format("%s hurt=%d", what, this.target == null ? -1 : this.target.hurtTime)
                : String.format("%s %s hurt=%d", what, plan, this.target == null ? -1 : this.target.hurtTime);
        if (!line.equals(this.lastDebug) || mc.thePlayer.ticksExisted - this.lastDebugTick >= 8) {
            this.lastDebug = line;
            this.lastDebugTick = mc.thePlayer.ticksExisted;
            ChatUtil.sendFormatted("&7[&bKBDisplacement&7] " + line);
        }
    }
}
