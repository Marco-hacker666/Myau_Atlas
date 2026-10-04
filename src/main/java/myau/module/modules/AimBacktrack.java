package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.util.CombatTargeting;
import myau.util.RotationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayDeque;
import java.util.List;

/**
 * A swing that just missed lands on whoever the last few ticks' aim was on
 * (2026-09-28, item 6 of the Rise comparison; Rise's AimBacktrack, rewritten
 * from what it does).
 *
 * A target that strafes out from under the crosshair between one tick and the
 * next turns a hit into air. The rotations this player sent over the last
 * few ticks are kept; when a swing goes out and the crosshair is on nothing,
 * each of them is ray cast in turn, and the first that would have hit a
 * valid player is attacked. The attack goes out in the same tick, after the
 * swing, as vanilla orders them. One per tick at most.
 *
 * Rise calls this module blatant, and it is: the server gets a hit along a
 * rotation this player sent up to a second ago.
 */
public class AimBacktrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty ticks = new IntProperty("ticks", 1, 1, 20);

    private final ArrayDeque<float[]> previous = new ArrayDeque<float[]>();
    private boolean attackedThisTick;
    /** A player found for a swing at air, attacked at this tick's update. */
    private EntityPlayer pending;
    private int used;

    public AimBacktrack() {
        super("AimBacktrack", false, false, "Lands a missed swing on where your aim was a few ticks ago");
    }

    @Override
    public void onEnabled() {
        this.previous.clear();
        this.attackedThisTick = false;
        this.pending = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        this.attackedThisTick = true;
    }

    /* A swing at nothing: look back for a rotation that was on someone. */
    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()
                || !(event.getPacket() instanceof C0APacketAnimation) || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.attackedThisTick || this.pending != null) {
            return;
        }
        /* KillAura picks its own hits, and its Advanced mode swings at the
           air on purpose: those misses are not this module's to fill in. */
        Module aura = Myau.moduleManager.modules.get(KillAura.class);
        if (aura != null && aura.isEnabled()) {
            return;
        }
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) {
            return;
        }
        double reach = reach();
        for (float[] rotation : this.previous) {
            EntityPlayer hit = playerAlong(rotation[0], rotation[1], reach);
            if (hit != null) {
                this.pending = hit;
                return;
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        EntityPlayer target = this.pending;
        this.pending = null;
        if (target != null && !this.attackedThisTick && !target.isDead && mc.playerController != null) {
            /* Through the controller, so AttackEvent and every module that
               listens for a hit (Criticals, SprintReset, ...) see it. */
            mc.playerController.attackEntity(mc.thePlayer, target);
            this.used++;
        }
        /* This tick's rotation, as it will be sent (LOWEST: after every
           module that sets one). */
        this.previous.addFirst(new float[]{event.getNewYaw(), event.getNewPitch()});
        while (this.previous.size() > this.ticks.getValue()) {
            this.previous.removeLast();
        }
        this.attackedThisTick = false;
    }

    private static double reach() {
        Module module = Myau.moduleManager.modules.get(Reach.class);
        if (module instanceof Reach && module.isEnabled()) {
            return Math.max(3.0, ((Reach) module).range.getValue());
        }
        return 3.0;
    }

    /** The nearest valid player a look along this rotation meets within reach, not through blocks. */
    private static EntityPlayer playerAlong(float yaw, float pitch, double reach) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationEngine.direction(yaw, pitch);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        double limit = reach;
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(eyes, end, false, true, false);
        if (block != null && block.hitVec != null) {
            limit = Math.min(limit, eyes.distanceTo(block.hitVec));
        }
        EntityPlayer best = null;
        double bestDistance = limit;
        List<Entity> nearby = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer,
                mc.thePlayer.getEntityBoundingBox().addCoord(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach)
                        .expand(1.0, 1.0, 1.0));
        for (Entity entity : nearby) {
            if (!(entity instanceof EntityPlayer) || !entity.canBeCollidedWith()) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) entity;
            if (!CombatTargeting.isTrackablePlayer(player)) {
                continue;
            }
            float border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            double distance;
            if (box.isVecInside(eyes)) {
                distance = 0.0;
            } else {
                MovingObjectPosition intercept = box.calculateIntercept(eyes, end);
                if (intercept == null) {
                    continue;
                }
                distance = eyes.distanceTo(intercept.hitVec);
            }
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.ticks.getValue() + "t" + (this.used > 0 ? " " + this.used : "")};
    }
}
