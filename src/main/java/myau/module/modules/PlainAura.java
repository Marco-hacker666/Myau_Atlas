package myau.module.modules;

import myau.util.Ping;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.util.Random;

/**
 * An aura that gives the server nothing to object to.
 *
 * The existing {@link KillAura} is large and has accumulated a mode for every
 * server anyone ever fought: a dozen rotation styles, several autoblock
 * schemes, extra packets injected on particular ticks. Each was added for a
 * reason, and together they mean a swing can leave this client in a shape no
 * ordinary player produces -- which is the thing every check is looking for.
 *
 * This is the opposite bet. It does the least that still works, and every rule
 * below exists to remove a class of detection rather than to win a fight:
 *
 * IT ONLY ATTACKS WHAT IT IS ACTUALLY POINTING AT. The rotation sent to the
 * server is computed first, and the attack goes out only if a raytrace from
 * that exact rotation lands on the target. Not the rotation on screen, not the
 * target's centre -- the rotation the server will see. Reach beyond vanilla,
 * aiming at a lead point the hitbox is not at, and hitting through a wall are
 * all impossible by construction rather than by a setting being left alone,
 * because in each case the raytrace simply fails and no packet is sent.
 *
 * IT SENDS WHAT VANILLA SENDS, IN VANILLA'S ORDER. A swing animation, then the
 * attack. Nothing else, ever: no duplicate attacks, no interaction packets, no
 * block-and-unblock cycle, no sprint toggling. Most of what an anticheat can
 * cheaply prove is not about aim at all but about packets a player cannot
 * produce with a mouse.
 *
 * IT ATTACKS ONCE PER INVULNERABILITY WINDOW. A second attack inside the ten
 * tick window cannot deal damage, so sending one is pure signal with no
 * benefit -- and at a high click rate it is the clearest tell there is. The
 * window is tracked per target and estimated against the round trip, because
 * the client's copy of that timer runs a round trip behind the server's.
 *
 * IT AIMS AT THE NEAREST POINT OF THE HITBOX. Not the centre. A human tracking
 * someone at close range is pointing at whatever part of them is easiest to
 * reach, and the centre of an unseen bounding box is a place nobody's crosshair
 * naturally sits.
 *
 * What it does not do is as important: no autoblock, no through-walls, no
 * reach, no target switching mid-swing, no multi-target. Those are the features
 * that make an aura worth detecting.
 */
public class PlainAura extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Vanilla reach is three blocks; this cannot be raised for a reason. */
    private static final float VANILLA_REACH = 3.0F;
    /** Ticks of invulnerability a hit opens, and the same span in milliseconds. */
    private static final float WINDOW = 10.0F;
    private static final long WINDOW_MS = 500L;

    public final IntProperty minCps = new IntProperty("MinCPS", 8, 1, 20);
    public final IntProperty maxCps = new IntProperty("MaxCPS", 12, 1, 20);
    public final FloatProperty fov = new FloatProperty("FOV", 120.0F, 30.0F, 360.0F);
    /** Degrees per tick the aim may move. Fixed, so arrival time is predictable. */
    public final FloatProperty turnSpeed = new FloatProperty("TurnSpeed", 42.0F, 5.0F, 180.0F);
    /** Below this the aim is already close enough and stops correcting. */
    public final FloatProperty deadZone = new FloatProperty("DeadZone", 0.6F, 0.0F, 3.0F);
    public final BooleanProperty requirePress = new BooleanProperty("RequirePress", false);
    public final BooleanProperty weaponOnly = new BooleanProperty("WeaponOnly", false);
    /**
     * SILENT: the player moves by the yaw that is sent, with the keys turned
     * so it still goes where the camera points. NONE moves by the camera
     * while reporting the aim -- movement a server re-simulating it cannot
     * reproduce (ENGINEERING-NOTES 4.12). Sprint survives while the aim is
     * within 67.5 degrees of the camera; past that the turned keys have no
     * forward in them and vanilla stops sprinting, so keep FOV at 135 or less.
     */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "REAL"});

    private EntityLivingBase target;
    private long nextAttackAt;
    private final Random random = new Random();

    /* When the last attack on a target went out, so a second is never sent
       into a window that cannot accept it.
    
       Measured in wall time rather than in ticks counted here. The tick count
       was incremented from an event that does not fire exactly once per tick,
       so ten of them was not half a second and the guard let bursts through --
       eleven attacks on one player inside two seconds, of which at most three
       could ever have landed. Wall time cannot drift like that. */
    private long lastHitAt;
    private int lastHitTarget = -1;

    private int sent;
    private int skipped;

    /** The yaw handed to setPervRotation this tick; NaN when this did not rotate. */
    private float moveYaw = Float.NaN;

    public PlainAura() {
        super("PlainAura", false, false,
                "A minimal aura that only attacks what its sent rotation actually hits");
    }

    @Override
    public void onEnabled() {
        this.target = null;
        this.nextAttackAt = 0L;
        this.lastHitAt = 0L;
        this.lastHitTarget = -1;
        this.sent = 0;
        this.skipped = 0;
    }

    @Override
    public void onDisabled() {
        this.target = null;
    }

    private int ping() {
        return Ping.own();
    }

    /**
     * Whether this target can still be damaged, allowing for the fact that the
     * timer the client holds started counting when the packet arrived, half a
     * round trip after the server started counting -- and the attack takes the
     * other half to get back.
     */
    private boolean damageable(EntityLivingBase entity) {
        float timer = entity.hurtTime;
        int ping = ping();
        if (ping > 0) {
            timer -= Math.min(WINDOW, ping / 50.0F);
        }
        return timer <= 0.0F;
    }

    private boolean valid(EntityLivingBase entity) {
        if (entity == null || entity == mc.thePlayer || entity.isDead
                || entity.getHealth() <= 0.0F || entity.deathTime > 0) {
            return false;
        }
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        /* The shared filter, so this module and the latency modules agree
           about who counts. It answers the same as the three calls below when
           TargetFilter is switched off. */
        return TargetFilter.accepts(player)
                && !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player)
                && !TeamUtil.isBot(player);
    }

    /** Nearest valid player whose box is within vanilla reach and inside the cone. */
    private EntityLivingBase pick() {
        EntityLivingBase best = null;
        double bestDistance = VANILLA_REACH;
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) object;
            if (!valid(entity)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(box(entity));
            if (distance > bestDistance) {
                continue;
            }
            if (this.fov.getValue() < 360.0F
                    && RotationUtil.angleToEntity(entity) > this.fov.getValue() / 2.0F) {
                continue;
            }
            best = entity;
            bestDistance = distance;
        }
        return best;
    }

    private static AxisAlignedBB box(Entity entity) {
        double border = entity.getCollisionBorderSize();
        return entity.getEntityBoundingBox().expand(border, border, border);
    }

    @EventTarget(whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            this.moveYaw = Float.NaN;
        }
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            return;
        }
        if (this.requirePress.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) {
            this.target = null;
            return;
        }
        if (this.weaponOnly.getValue() && !myau.util.ItemUtil.isHoldingSword()) {
            this.target = null;
            return;
        }

        this.target = pick();
        if (this.target == null) {
            return;
        }

        /* Aim at the nearest point of the hitbox to where the crosshair
           already is, rather than at its centre: that is where a hand aiming
           at someone this close actually ends up. */
        float[] wanted = RotationUtil.getRotationsToBox(box(this.target),
                event.getYaw(), event.getPitch(), 180.0F, 0.0F);
        if (wanted == null) {
            return;
        }

        float yaw = step(event.getYaw(), wanted[0]);
        float pitch = step(event.getPitch(), wanted[1]);
        event.setRotation(yaw, pitch, 2);
        /* The rotation above only changes what the packet says. Movement is
           computed from its own yaw, and unless that is this one too the
           player reports one direction and walks in another. */
        this.moveYaw = this.moveFix.getValue() != 0 ? yaw : mc.thePlayer.rotationYaw;
        event.setPervRotation(this.moveYaw, 2);
        if (this.moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Myau.rotationManager.setRotation(yaw, pitch, 2, false);
        }

        tryAttack(yaw, pitch);
    }

    /**
     * Turns the keys toward the sent yaw so the player keeps going where the
     * camera points.
     *
     * Only while the yaw in force is this module's own. Velocity also rotates
     * at priority 2 and moves by its yaw with the keys as pressed -- that is
     * how it walks into knockback -- so turning the keys for its rotation
     * would undo it.
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()
                && this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 2
                && RotationState.getSmoothedYaw() == this.moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    /** Moves at a fixed number of degrees per tick, so arrival is predictable. */
    private float step(float from, float to) {
        float difference = MathHelper.wrapAngleTo180_float(to - from);
        if (Math.abs(difference) <= this.deadZone.getValue()) {
            return from;
        }
        float speed = this.turnSpeed.getValue();
        return from + MathHelper.clamp_float(difference, -speed, speed);
    }

    /**
     * Sends an attack only when the rotation about to be sent actually lands on
     * the target, and only when that target can take damage.
     *
     * The raytrace is against the rotation this client is about to report, not
     * against where the camera is looking and not against the entity's position
     * -- which is what makes reach and off-model aiming impossible here rather
     * than merely discouraged.
     */
    private void tryAttack(float yaw, float pitch) {
        long now = System.currentTimeMillis();
        if (now < this.nextAttackAt) {
            return;
        }
        MovingObjectPosition hit = RotationUtil.rayTrace(box(this.target), yaw, pitch, VANILLA_REACH);
        if (hit == null) {
            return;
        }
        /* The box intercept above knows nothing about blocks, so on its own it
           attacked straight through walls -- the exact thing this module says
           is impossible by construction. A block anywhere between the eyes and
           the hit point means the rotation does not actually land. */
        if (mc.theWorld.rayTraceBlocks(mc.thePlayer.getPositionEyes(1.0F), hit.hitVec, false, true, false) != null) {
            return;
        }
        if (!damageable(this.target)) {
            /* Inside the window. Sending anyway costs nothing but evidence. */
            this.skipped++;
            return;
        }
        if (this.target.getEntityId() == this.lastHitTarget
                && now - this.lastHitAt < WINDOW_MS) {
            /* Ten ticks is half a second, and nothing can change that: a second
               attack inside it cannot deal damage however it is aimed. */
            this.skipped++;
            return;
        }

        /* Vanilla's order, and nothing besides.
        
           swingItem() on the client player already queues the animation packet
           itself -- so sending one here as well put two animations on the wire
           for every attack. A hand cannot do that, which makes it precisely the
           kind of signature this module exists to avoid, and it was in the
           first version of the module that claimed to avoid it. */
        mc.thePlayer.swingItem();
        /* Vanilla's attackEntity syncs the held slot first; without it an
           attack in the same tick as a slot change is judged with the old
           item. */
        ((myau.mixin.IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        /* As in KillAura: the attack modules listen for this, and an aura that
           sends its own packet has to announce the attack itself. */
        myau.event.EventManager.call(new myau.events.AttackEvent(this.target));
        PacketUtil.sendPacket(new C02PacketUseEntity(this.target,
                C02PacketUseEntity.Action.ATTACK));

        this.lastHitTarget = this.target.getEntityId();
        this.lastHitAt = now;
        this.sent++;
        this.nextAttackAt = now + delay();
    }

    /**
     * Milliseconds until the next attack may go out.
     *
     * Drawn fresh each time from the range rather than held as a rate: a
     * constant interval is the single easiest thing to measure about a click,
     * and an interval redrawn per click has the spread a hand does.
     */
    private long delay() {
        int min = Math.min(this.minCps.getValue(), this.maxCps.getValue());
        int max = Math.max(this.minCps.getValue(), this.maxCps.getValue());
        double cps = min + this.random.nextDouble() * (max - min);
        return (long) (1000.0 / Math.max(1.0, cps));
    }

    @Override
    public String[] getSuffix() {
        if (this.target == null) {
            return new String[]{"idle"};
        }
        return new String[]{this.sent + "/" + (this.sent + this.skipped)};
    }
}
