package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import java.util.function.Supplier;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.management.Arbiter;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RandomUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps the target where the client last saw it for a moment longer while
 * they move away, so a swing reaches the position the server still has in
 * its history for this player's latency.
 *
 * Second version (2026-09-25). What the first got wrong:
 * - The target's position was started from its rendered position, which is
 *   an interpolation three ticks behind the packets, and advanced from there
 *   by the packets' deltas: every decision was taken about a position that
 *   did not exist. It is now the entity's server position (the last one the
 *   client applied) plus whatever is being held.
 * - Distances were feet to feet. What decides a hit is the eyes to the
 *   nearest point of the hitbox, and that is what is compared now.
 * - "Release on hit" fired on hurtTime == 1, which is the end of the hurt,
 *   half a second after the hit. It is now the moment the hurt starts.
 * - Only the target's movement was held; everything else overtook it. The
 *   server's transactions and keep-alives were answered on time while the
 *   entity packets between them came late -- a pattern latency never makes.
 *   ALL (the default) holds the whole stream in order while a hold is open,
 *   which to the server is simply a moment of latency.
 * - The box showing the real position jumped with each packet; it is now
 *   eased and interpolated between frames, and fades in and out.
 */
public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Beyond this many packets the hold is let go whatever else is true. */
    private static final int MAX_HELD = 400;
    /** Hits taken on this side of this are the hitbox border 1.8 adds. */
    private static final double BORDER = 0.1;

    /** Farthest the target's real hitbox may be, from the eyes, for a hold to go on. */
    public final FloatProperty range;
    /* Nearer than this (the real hitbox, from the eyes) nothing is held: the
       target is in reach anyway, and a hold would be latency for nothing. */
    public final FloatProperty minRange;
    /* A hold is only opened while the target's hurt time is at most this.
       Just hit, they cannot be hit again for half a second: holding them
       then is lag spent on nothing. 10 is off. */
    public final IntProperty maxHurtTime;
    /* After a hold ends, this long before another may open. Holds back to
       back are a latency that never lets up, which is what a timing check
       sees; a gap between them is ordinary jitter. */
    public final IntProperty cooldown;
    /* Only while holding a sword (or anything enchanted with Sharpness). */
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty adaptive;
    public final IntProperty normalDelay;
    public final IntProperty adaptiveDelay;
    public final BooleanProperty releaseOnHit;
    /* A window that is always the same length is a measurable constant. The
       figure above becomes the centre of a range instead, redrawn for each
       hold so no two are alike. */
    public final IntProperty randomize;
    /* ALL holds every incoming packet, in order, while a hold is open;
       TARGET only the target's own movement. */
    public final ModeProperty hold;
    public final BooleanProperty interruptLagRange;
    public final BooleanProperty players;
    public final BooleanProperty teams;
    public final BooleanProperty botCheck;
    public final BooleanProperty esp;
    /* The ESP box also shows which way the target is facing. */
    public final BooleanProperty espHead;
    public final BooleanProperty hitStats;

    private final Object lock = new Object();
    private final ConcurrentLinkedQueue<Packet<?>> incomingQueue = new ConcurrentLinkedQueue<>();

    private volatile EntityLivingBase target;
    /** The target's position with what is held applied: where the server has them. */
    private volatile Vec3 realPosition;
    private volatile long backtrackStartTime = 0L;
    private volatile int rolledDelay = 0;
    /** Holds opened since enabled: the suffix shows it, so it can be seen working. */
    private volatile int holds = 0;
    /** When the last hold ended, for the cooldown. */
    private volatile long lastReleaseAt = 0L;

    private EntityLivingBase lastAttacked;
    private long lastAttackTime = 0L;
    private static final long COMBAT_LOCK_MS = 3000L;
    private boolean lagRangeInterrupted = false;
    private int targetHurtTime = 0;
    private static BackTrack instance;

    /* The box drawn at the real position: eased toward it each tick and
       interpolated between the frames of the tick, as entities are. */
    private Vec3 espPrevious;
    private Vec3 espCurrent;
    private float espAlphaPrevious;
    private float espAlpha;

    /* Swing accounting. A swing that the server rejects for reach looks
       exactly like a swing that landed: same animation, same particles, no
       feedback either way. Counting how many of our attacks are followed by
       the target actually taking damage turns "it feels better" into a
       number, which is the only way to tell whether a given delay is
       spending the reach budget or overrunning it.

       Attribution is approximate - another player hitting the same target
       inside the window counts as ours - so read the ratio as a trend
       between settings, not an absolute. */
    private static final long HIT_WINDOW_MS = 600L;
    private int swings = 0;
    private int landed = 0;
    private long swingPendingAt = 0L;
    private int lastTargetHurtTime = 0;

    public BackTrack() {
        super("Backtrack", false);
        /* What is held, for PacketHolds (plan step 13). */
        myau.management.PacketHolds.register(() -> {
            int count = this.incomingQueue.size();
            return count == 0 ? null : new myau.management.PacketHolds.Hold("Backtrack", myau.management.PacketHolds.Direction.IN, count,
                    this.backtrackStartTime, "the target's older position",
                    "after the delay, out of range, or on a catch");
        }, new myau.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            /* The longest window rollDelay() can draw, and a second more. */
            @Override
            public long ceilingMs() {
                return Math.max(normalDelay.getValue(), adaptiveDelay.getValue()) + randomize.getValue()
                        + myau.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseIncoming();
            }
        });
        this.range = new FloatProperty("range", 3.5F, 1.0F, 8.0F);
        this.minRange = new FloatProperty("min-range", 0.0F, 0.0F, 4.0F);
        this.maxHurtTime = new IntProperty("max-hurt-time", 10, 0, 10);
        this.cooldown = new IntProperty("cooldown", 0, 0, 2000);
        this.weaponsOnly = new BooleanProperty("weapons-only", false);
        this.adaptive = new BooleanProperty("adaptive", true);
        /* Keep both choices editable in the GUI. Adaptive selects which one
           is used at runtime; hiding the inactive delay made the setting look
           like it could not be configured until Adaptive was switched off. */
        /* Only the delay that applies is shown (2026-10-01). */
        this.normalDelay = new IntProperty("normal-delay", 100, 50, 1000, () -> !this.adaptive.getValue());
        this.adaptiveDelay = new IntProperty("adaptive-delay", 100, 50, 1000, this.adaptive::getValue);
        this.releaseOnHit = new BooleanProperty("release-on-hit", true);
        this.randomize = new IntProperty("randomize", 25, 0, 200);
        this.hold = new ModeProperty("hold", 1, new String[]{"TARGET", "ALL"});
        this.interruptLagRange = new BooleanProperty("interrupt-lagrange", true);
        this.players = new BooleanProperty("players", true);
        this.teams = new BooleanProperty("teams", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.esp = new BooleanProperty("esp", true);
        this.espHead = new BooleanProperty("esp-head", true, this.esp::getValue);
        this.hitStats = new BooleanProperty("hit-stats", false);
        instance = this;
    }

    @Override
    public void onEnabled() {
        incomingQueue.clear();
        realPosition = null;
        backtrackStartTime = 0L;
        rolledDelay = 0;
        target = null;
        lastAttacked = null;
        lastAttackTime = 0L;
        lagRangeInterrupted = false;
        targetHurtTime = 0;
        espPrevious = null;
        espCurrent = null;
        espAlpha = 0.0F;
        espAlphaPrevious = 0.0F;
        swings = 0;
        landed = 0;
        swingPendingAt = 0L;
        lastTargetHurtTime = 0;
        holds = 0;
    }

    @Override
    public void onDisabled() {
        setLagRangeEnabled(true);
        releaseIncoming();
        incomingQueue.clear();
        realPosition = null;
        target = null;
        lastAttacked = null;
        lastAttackTime = 0L;
        espPrevious = null;
        espCurrent = null;
    }

    private int baseDelay() {
        return adaptive.getValue() ? adaptiveDelay.getValue() : normalDelay.getValue();
    }

    /** Packets currently withheld, for whatever is reporting on causes. */
    public int heldCount() {
        return incomingQueue.size();
    }

    /** The window for the hold currently open, drawn once when it opens. */
    private int currentMaxDelay() {
        return rolledDelay > 0 ? rolledDelay : baseDelay();
    }

    private int rollDelay() {
        int base = baseDelay();
        int spread = randomize.getValue();
        if (spread <= 0) return base;
        return Math.max(1, base - spread + RandomUtil.nextInt(0, spread * 2));
    }

    private LagRange getLagRange() {
        Module m = Myau.moduleManager.getModule(LagRange.class);
        return (m instanceof LagRange) ? (LagRange) m : null;
    }

    private void setLagRangeEnabled(boolean enabled) {
        if (!interruptLagRange.getValue()) return;
        LagRange lr = getLagRange();
        if (lr == null) return;
        if (enabled && lagRangeInterrupted) {
            lagRangeInterrupted = false;
            lr.setEnabled(true);
        } else if (!enabled && !lagRangeInterrupted && lr.isEnabled()) {
            lagRangeInterrupted = true;
            lr.setEnabled(false);
        }
    }

    private boolean isInCombat() {
        if (lastAttacked == null || lastAttacked != target || lastAttacked.isDead) return false;
        return System.currentTimeMillis() - lastAttackTime <= COMBAT_LOCK_MS;
    }

    // ------------------------------------------------------------ positions

    /** The last position of the entity the client applied, exactly as the server sent it. */
    private static Vec3 serverPosition(Entity entity) {
        return new Vec3(entity.serverPosX / 32.0, entity.serverPosY / 32.0, entity.serverPosZ / 32.0);
    }

    private static AxisAlignedBB boxAt(Entity entity, Vec3 pos) {
        double hw = entity.width / 2.0;
        return new AxisAlignedBB(pos.xCoord - hw, pos.yCoord, pos.zCoord - hw,
                pos.xCoord + hw, pos.yCoord + entity.height, pos.zCoord + hw);
    }

    /** From the eyes to the nearest point of the hitbox there, border included. */
    private static double reachTo(Entity entity, Vec3 pos) {
        AxisAlignedBB box = boxAt(entity, pos).expand(BORDER, BORDER, BORDER);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double x = Math.max(box.minX, Math.min(box.maxX, eyes.xCoord)) - eyes.xCoord;
        double y = Math.max(box.minY, Math.min(box.maxY, eyes.yCoord)) - eyes.yCoord;
        double z = Math.max(box.minZ, Math.min(box.maxZ, eyes.zCoord)) - eyes.zCoord;
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Where this packet puts the target from here, or null if it does not move them. */
    private Vec3 advance(Vec3 from, Packet<?> packet) {
        EntityLivingBase t = target;
        if (t == null || mc.theWorld == null) return null;
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            if (p.getEntity(mc.theWorld) != t) return null;
            return from.addVector(p.func_149062_c() / 32.0, p.func_149061_d() / 32.0, p.func_149064_e() / 32.0);
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            if (p.getEntityId() != t.getEntityId()) return null;
            return new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0);
        }
        return null;
    }

    /**
     * Whether holding the target at the position the client has, with the
     * real one here, is worth it: the real one within range and farther
     * than the held one -- the target is getting away -- and the window
     * not yet used up.
     */
    private boolean worthHolding(Vec3 real) {
        EntityLivingBase t = target;
        if (t == null || t.isDead || mc.thePlayer == null) return false;
        if (backtrackStartTime > 0 && System.currentTimeMillis() - backtrackStartTime > currentMaxDelay()) return false;
        double far = reachTo(t, real);
        if (far > range.getValue() || far < minRange.getValue()) return false;
        double near = reachTo(t, serverPosition(t));
        return adaptive.getValue() ? far > near : far > near + 0.1;
    }

    /** Whether a new hold may open now: cooldown over, target hittable, weapon in hand. */
    private boolean mayOpen(EntityLivingBase t) {
        if (cooldown.getValue() > 0 && System.currentTimeMillis() - lastReleaseAt < cooldown.getValue()) return false;
        if (maxHurtTime.getValue() < 10 && t.hurtTime > maxHurtTime.getValue()) return false;
        if (weaponsOnly.getValue() && !myau.util.ItemUtil.hasRawUnbreakingEnchant()) return false;
        return true;
    }

    // -------------------------------------------------------------- packets

    /** Packets after which nothing held may stay held: the player's own state changes. */
    private boolean flushes(Packet<?> p) {
        if (p instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) p).getEntityID() == mc.thePlayer.getEntityId();
        }
        return p instanceof S08PacketPlayerPosLook || p instanceof S27PacketExplosion
                || p instanceof S07PacketRespawn || p instanceof S01PacketJoinGame
                || p instanceof S40PacketDisconnect;
    }

    /** In TARGET, what is held along with the target's movement: the rest of their own packets. */
    private boolean aboutTarget(Packet<?> p) {
        EntityLivingBase t = target;
        if (t == null) return false;
        if (p instanceof S19PacketEntityHeadLook) return ((S19PacketEntityHeadLook) p).getEntity(mc.theWorld) == t;
        if (p instanceof S12PacketEntityVelocity) return ((S12PacketEntityVelocity) p).getEntityID() == t.getEntityId();
        return false;
    }

    /*
     * Releases run on whichever thread asks, and always empty the queue at
     * once. On the network thread each vanilla handler reschedules itself
     * onto the client thread (ThreadQuickExitException, caught), so the held
     * packets land in the client's task queue in the order they came and
     * ahead of the packet that asked for the release. The lock keeps a
     * release on one thread from interleaving with holding or releasing on
     * the other.
     */
    private void releaseIncoming() {
        synchronized (lock) {
            if (!incomingQueue.isEmpty()) {
                lastReleaseAt = System.currentTimeMillis();
            }
            if (mc.getNetHandler() == null) {
                incomingQueue.clear();
            } else {
                Packet<?> p;
                while ((p = incomingQueue.poll()) != null) processPacketUnchecked(p);
            }
            backtrackStartTime = 0L;
            rolledDelay = 0;
            realPosition = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.network.INetHandler> void processPacketUnchecked(Packet<T> packet) {
        try {
            packet.processPacket((T) Minecraft.getMinecraft().getNetHandler());
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
            // Rescheduled onto the client thread by vanilla; nothing lost.
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.getTarget() instanceof EntityLivingBase) {
            lastAttacked = (EntityLivingBase) event.getTarget();
            lastAttackTime = System.currentTimeMillis();
            if (hitStats.getValue()) {
                swings++;
                swingPendingAt = lastAttackTime;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.RECEIVE) return;
        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            if (!incomingQueue.isEmpty()) releaseIncoming();
            return;
        }
        handleIncoming(event);
    }

    private void handleIncoming(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        synchronized (lock) {
            boolean holding = !incomingQueue.isEmpty();
            if (Arbiter.catching()) {
                /* A fall is being caught (see Arbiter): the block changes
                   and corrections it waits on must not be late. */
                if (holding) {
                    releaseIncoming();
                    Arbiter.yielded(getName());
                }
                return;
            }
            if (flushes(packet)) {
                /* Released ahead of it, and it goes through: the player's own
                   knockback or teleport is never made to wait. */
                if (holding) releaseIncoming();
                return;
            }
            EntityLivingBase t = target;
            if (t == null) {
                if (holding) releaseIncoming();
                return;
            }
            Vec3 from = holding && realPosition != null ? realPosition : serverPosition(t);
            Vec3 next = advance(from, packet);
            if (next != null && !holding && !mayOpen(t)) {
                return;
            }
            if (next != null) {
                if (!holding) {
                    backtrackStartTime = System.currentTimeMillis();
                    rolledDelay = rollDelay();
                }
                if (worthHolding(next) && incomingQueue.size() < MAX_HELD) {
                    if (!holding) holds++;
                    realPosition = next;
                    incomingQueue.add(packet);
                    event.setCancelled(true);
                } else {
                    /* Nothing to gain any more: everything goes, this last. */
                    releaseIncoming();
                }
                return;
            }
            if (holding && (hold.getValue() == 1 || aboutTarget(packet))) {
                incomingQueue.add(packet);
                event.setCancelled(true);
            }
        }
    }

    // ----------------------------------------------------------------- tick

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() == EventType.PRE) tickPre();
    }

    private void tickPre() {
        if (hitStats.getValue() && lastAttacked != null) {
            int hurt = lastAttacked.hurtTime;
            if (hurt > lastTargetHurtTime && swingPendingAt > 0L
                    && System.currentTimeMillis() - swingPendingAt <= HIT_WINDOW_MS) {
                landed++;
                swingPendingAt = 0L;
            }
            lastTargetHurtTime = hurt;
        }

        EntityLivingBase newTarget = resolveTarget();
        if (newTarget != target) {
            setLagRangeEnabled(true);
            releaseIncoming();
            targetHurtTime = newTarget != null ? newTarget.hurtTime : 0;
            espPrevious = null;
            espCurrent = null;
            espAlpha = 0.0F;
        }
        target = newTarget;

        if (target == null) {
            setLagRangeEnabled(true);
            updateEsp(null, false);
            return;
        }

        boolean hitLanded = target.hurtTime > targetHurtTime;
        targetHurtTime = target.hurtTime;
        if (!incomingQueue.isEmpty()) {
            Vec3 real = realPosition;
            boolean release = real == null || !worthHolding(real);
            if (mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0) release = true;
            /* The hit is in; the knockback that follows is better seen now. */
            if (releaseOnHit.getValue() && hitLanded) release = true;
            if (release) releaseIncoming();
        }

        boolean holding = !incomingQueue.isEmpty();
        setLagRangeEnabled(!(holding && isInCombat()));
        Vec3 real = realPosition;
        updateEsp(holding && real != null ? real : serverPosition(target), holding);
    }

    private void updateEsp(Vec3 real, boolean show) {
        espAlphaPrevious = espAlpha;
        /* On at once -- a hold is often only a tick or two -- and off slowly,
           so one can be seen at all. */
        espAlpha = show ? 1.0F : Math.max(0.0F, espAlpha - 0.12F);
        if (real == null) {
            return;
        }
        if (espCurrent == null) {
            espCurrent = target != null ? serverPosition(target) : real;
        }
        espPrevious = espCurrent;
        /* Half the way each tick: a packet's step reads as motion, not a jump. */
        espCurrent = new Vec3(espCurrent.xCoord + (real.xCoord - espCurrent.xCoord) * 0.5,
                espCurrent.yCoord + (real.yCoord - espCurrent.yCoord) * 0.5,
                espCurrent.zCoord + (real.zCoord - espCurrent.zCoord) * 0.5);
    }

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        if (!isEnabled() || !esp.getValue()) return;
        EntityLivingBase t = target;
        if (t == null || espCurrent == null || espPrevious == null) return;
        float partial = event.getPartialTicks();
        float alpha = espAlphaPrevious + (espAlpha - espAlphaPrevious) * partial;
        if (alpha <= 0.01F) return;
        double x = espPrevious.xCoord + (espCurrent.xCoord - espPrevious.xCoord) * partial;
        double y = espPrevious.yCoord + (espCurrent.yCoord - espPrevious.yCoord) * partial;
        double z = espPrevious.zCoord + (espCurrent.zCoord - espPrevious.zCoord) * partial;
        /* Nothing to show where the box would sit on the player as drawn. */
        double rx = RenderUtil.lerpDouble(t.posX, t.lastTickPosX, partial);
        double ry = RenderUtil.lerpDouble(t.posY, t.lastTickPosY, partial);
        double rz = RenderUtil.lerpDouble(t.posZ, t.lastTickPosZ, partial);
        double apart = (x - rx) * (x - rx) + (y - ry) * (y - ry) + (z - rz) * (z - rz);
        if (apart < 0.0025) return;
        Color color = (t instanceof EntityPlayer)
                ? TeamUtil.getTeamColor((EntityPlayer) t, 1.0F)
                : new Color(255, 60, 60);
        int fill = 55;
        int line = 200;
        float width = 1.5F;
        ThemeStyle style = ThemeStyle.active(BacktrackColors.class);
        if (style != null) {
            color = style.color(color, t, -1);
            fill = style.fillAlpha255();
            line = style.lineAlpha255();
            width = style.width();
        }
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        AxisAlignedBB box = boxAt(t, new Vec3(x, y, z))
                .offset(-rm.getRenderPosX(), -rm.getRenderPosY(), -rm.getRenderPosZ());
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue(), (int) (fill * alpha));
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), (int) (line * alpha), width);
        if (espHead.getValue()) {
            /* A line from the eyes of the box the way the head faces. */
            float yaw = t.prevRotationYawHead + MathHelper.wrapAngleTo180_float(t.rotationYawHead - t.prevRotationYawHead) * partial;
            float pitch = t.prevRotationPitch + (t.rotationPitch - t.prevRotationPitch) * partial;
            Vec3 look = myau.util.RotationEngine.direction(yaw, pitch);
            double ex = (box.minX + box.maxX) / 2.0;
            double ey = box.minY + t.getEyeHeight();
            double ez = (box.minZ + box.maxZ) / 2.0;
            org.lwjgl.opengl.GL11.glLineWidth(width);
            net.minecraft.client.renderer.GlStateManager.color(color.getRed() / 255.0F, color.getGreen() / 255.0F,
                    color.getBlue() / 255.0F, line / 255.0F * alpha);
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINES);
            org.lwjgl.opengl.GL11.glVertex3d(ex, ey, ez);
            org.lwjgl.opengl.GL11.glVertex3d(ex + look.xCoord * 0.9, ey + look.yCoord * 0.9, ez + look.zCoord * 0.9);
            org.lwjgl.opengl.GL11.glEnd();
            org.lwjgl.opengl.GL11.glLineWidth(2.0F);
            net.minecraft.client.renderer.GlStateManager.resetColor();
        }
        RenderUtil.disableRenderState();
    }

    // -------------------------------------------------------------- targets

    private EntityLivingBase resolveTarget() {
        KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (ka != null && ka.isEnabled() && ka.getTarget() != null) return ka.getTarget();
        if (lastAttacked != null && !lastAttacked.isDead
                && System.currentTimeMillis() - lastAttackTime <= COMBAT_LOCK_MS
                && mc.thePlayer.getDistanceToEntity(lastAttacked) <= range.getValue() * 2.0F) {
            return lastAttacked;
        }
        ArrayList<EntityLivingBase> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase e = (EntityLivingBase) entity;
            if (isValidTarget(e) && mc.thePlayer.getDistanceToEntity(e) <= range.getValue()) candidates.add(e);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Float.compare(RotationUtil.angleToEntity(a), RotationUtil.angleToEntity(b)));
        return candidates.get(0);
    }

    private boolean isValidTarget(EntityLivingBase e) {
        if (!mc.theWorld.loadedEntityList.contains(e)) return false;
        if (e == mc.thePlayer || e == mc.thePlayer.ridingEntity) return false;
        if (e == mc.getRenderViewEntity() || e == mc.getRenderViewEntity().ridingEntity) return false;
        if (e.deathTime > 0) return false;
        if (e instanceof EntityPlayer) {
            if (!players.getValue()) return false;
            EntityPlayer p = (EntityPlayer) e;
            if (TeamUtil.isFriend(p)) return false;
            if (teams.getValue() && TeamUtil.isSameTeam(p)) return false;
            if (botCheck.getValue() && TeamUtil.isBot(p)) return false;
            return true;
        }
        return false;
    }

    public static boolean runWithNearestTrackedDistance(net.minecraft.entity.Entity entity, Supplier<Boolean> action) {
        return action.get();
    }

    @Override
    public String[] getSuffix() {
        String held = holds + (holds == 1 ? " hold" : " holds");
        if (hitStats.getValue() && swings > 0) {
            return new String[]{ currentMaxDelay() + "ms", held, (landed * 100 / swings) + "% of " + swings };
        }
        return new String[]{ currentMaxDelay() + "ms", held };
    }
}
