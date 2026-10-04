package myau.module.modules;

import myau.util.Ping;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.management.Arbiter;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds incoming knockback for a short window so the push lands late.
 *
 * Everything here happens on the receive side. The server has already sent
 * the velocity packet and moved on; withholding it changes only when this
 * client acts on it, so unlike a module that delays outgoing packets there is
 * nothing for the server to notice and nothing that can be rejected. That is
 * what makes it usable on a high-latency connection where send-side tricks
 * are already fighting the round trip.
 *
 * Once a knockback is held, every subsequent received packet is held with it
 * rather than only the velocity. Releasing them out of order would apply a
 * later world state before the push that preceded it, which is worse than the
 * delay itself: the queue has to stay a queue.
 *
 * Three guards decide when this is allowed to fire, and each exists because
 * firing without it is worse than not firing at all:
 *
 *   COOLDOWN. Holding again the moment the previous window closes turns a
 *   delay into a permanent one under sustained combo, which is a different
 *   and far more visible module. The refractory period bounds it to roughly
 *   one hold per exchange.
 *
 *   GROUND STATE. A push taken standing still is worth delaying much longer
 *   than one taken mid-air, where the extra hang time reads as wrong and the
 *   fall itself is what carries you. The two are separate settings, and the
 *   ground one only applies after the player has actually settled -- onGround
 *   flickers on every step, so a single tick of it means nothing.
 *
 *   TARGET. Away from a fight there is nothing to gain and the delayed world
 *   state is pure cost, so a nearby opponent in front of the player is
 *   required before anything is held at all.
 *
 * The delay is drawn from a range rather than used as a constant, because a
 * fixed figure makes every held window the same measurable length.
 */
public class KnockbackDelay extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty airDelay = new IntProperty("AirDelay", 75, 0, 1000);
    public final IntProperty groundDelay = new IntProperty("GroundDelay", 150, 0, 1000);
    /** Half-width of the window each delay is drawn from, in milliseconds. */
    public final IntProperty randomize = new IntProperty("Randomize", 25, 0, 200);
    /** Consecutive grounded ticks before the ground figure replaces the air one. */
    public final IntProperty groundTicks = new IntProperty("GroundTicks", 3, 1, 20);
    public final IntProperty cooldown = new IntProperty("Cooldown", 475, 0, 2000);
    public final IntProperty chance = new IntProperty("Chance", 40, 0, 100);

    /**
     * Blocks of drift allowed before the hold ends early, whatever the timer
     * says. A knockback moves roughly one block, so this is the fraction of one
     * push the server may find this client out of place by.
     */
    public final FloatProperty maxDivergence = new FloatProperty("MaxDivergence", 0.40F, 0.1F, 3.0F);
    /**
     * Total staleness the server's view is allowed, in milliseconds. The round
     * trip already spends part of it; the hold may only use what is left. A
     * fixed delay ignores this, which is why a figure that behaves on a fast
     * connection produces corrections on a slow one -- the latency has already
     * spent the budget before the module adds to it.
     */
    public final IntProperty latencyBudget = new IntProperty("LatencyBudget", 380, 100, 1200);
    public final BooleanProperty pingAware = new BooleanProperty("PingAware", true);

    public final BooleanProperty requireTarget = new BooleanProperty("RequireTarget", true);
    public final FloatProperty targetRange = new FloatProperty("TargetRange", 5.0F, 1.0F, 12.0F,
            this.requireTarget::getValue);
    public final FloatProperty targetFov = new FloatProperty("TargetFOV", 90.0F, 10.0F, 360.0F,
            this.requireTarget::getValue);

    /**
     * Hold the rest of the stream behind the knockback, or only the knockback.
     *
     * Holding everything was justified by ordering: a later world update
     * applied before the push that preceded it is wrong. In practice nothing
     * in the stream depends on a velocity packet, and the cost of the strict
     * version is severe -- a single 150ms hold on a busy connection withholds
     * twenty other packets, and the client spends that window acting on a world
     * it has stopped receiving. Delaying the push alone produces the effect the
     * module exists for and leaves everything else on time.
     */
    public final BooleanProperty holdOthers = new BooleanProperty("HoldOthers", false);
    /**
     * Knockback with a large upward component is not held at all.
     *
     * A push that lifts the player is the one whose delay is most visible: the
     * client keeps standing where it was while the server has it in the air,
     * and the correction that follows is vertical. Those were most of what this
     * module was being blamed for, and they are the cheapest to give up --
     * upward knockback is what the fall carries anyway.
     */
    public final FloatProperty maxVertical = new FloatProperty("MaxVertical", 0.4F, 0.0F, 2.0F);

    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", true);
    public final BooleanProperty realtimeDamage = new BooleanProperty("RealtimeDamage", true);
    public final BooleanProperty onlySwords = new BooleanProperty("OnlySwords", false);

    private final Queue<Packet<?>> held = new ConcurrentLinkedQueue<Packet<?>>();
    /* Written on the client thread, read on the network thread. */
    private volatile long releaseAt;
    private volatile long lastTrigger;
    private volatile int groundedTicks;

    /**
     * Everything the packet handler needs to know about the world, decided once
     * a tick on the client thread.
     *
     * Received packets are handled on Netty's thread, where the world is being
     * mutated by the client thread at the same time. Walking the entity list,
     * reading the mouse-over or following the aura's target from there is a
     * race against that mutation, and the failure is not a wrong decision but a
     * crash inside the network handler. Vape evaluates its target on tick for
     * this reason and reads nothing but a cached field when a packet arrives;
     * this does the same.
     */
    private volatile boolean conditionsMet;
    private volatile int delayBase;
    /** Cached so the network thread never dereferences thePlayer. */
    private volatile int selfEntityId = Integer.MIN_VALUE;
    /** Milliseconds the connection leaves available, settled on tick. */
    private volatile int delayCeiling = Integer.MAX_VALUE;

    private boolean divergenceArmed;
    private double divergence;
    private double lastX;
    private double lastZ;

    private static Field STATUS_ENTITY_ID;

    public KnockbackDelay() {
        super("KnockbackDelay", false, false,
                "Holds incoming knockback for a short window so the push lands late");
        /* What is held, for PacketHolds (plan step 13). */
        myau.management.PacketHolds.register(() -> {
            int count = this.held.size();
            if (count == 0) {
                return null;
            }
            long left = this.releaseAt - System.currentTimeMillis();
            return new myau.management.PacketHolds.Hold("KnockbackDelay", myau.management.PacketHolds.Direction.IN, count, this.lastTrigger,
                    "own knockback", (this.releaseAt > 0L ? "in " + Math.max(0L, left) + "ms" : "on release")
                            + "; at once on a catch");
        }, new myau.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            /* The longest delay the settings can draw, and a second more. */
            @Override
            public long ceilingMs() {
                return Math.max(airDelay.getValue(), groundDelay.getValue()) + randomize.getValue()
                        + myau.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                flush();
            }
        });
    }

    @Override
    public String[] getSuffix() {
        if (!this.held.isEmpty()) {
            return new String[]{"&cholding"};
        }
        return new String[]{this.airDelay.getValue() + "/" + this.groundDelay.getValue() + "ms"};
    }

    @Override
    public void onEnabled() {
        this.releaseAt = 0L;
        this.lastTrigger = 0L;
        this.groundedTicks = 0;
    }

    @Override
    public void onDisabled() {
        this.flush();
    }

    private boolean holding() {
        return !this.held.isEmpty();
    }

    /** Packets currently withheld, for whatever is reporting on causes. */
    public int heldCount() {
        return this.held.size();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.flush();
        this.groundedTicks = 0;
    }

    @EventTarget(whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.held.clear();
            return;
        }

        this.groundedTicks = mc.thePlayer.onGround ? this.groundedTicks + 1 : 0;
        this.selfEntityId = mc.thePlayer.getEntityId();

        if (!this.isEnabled() || mc.currentScreen != null || mc.thePlayer.isDead || Arbiter.catching()) {
            /* A catch (see Arbiter) simulates the fall from the knockback
               as it arrives; holding it would have it planned against the
               wrong one. */
            if (Arbiter.catching() && this.holding()) {
                Arbiter.yielded(this.getName());
            }
            this.conditionsMet = false;
            this.flush();
            return;
        }
        this.evaluateConditions();
        if (this.holding()) {
            this.trackDivergence();
        } else {
            this.divergenceArmed = false;
            this.lastX = mc.thePlayer.posX;
            this.lastZ = mc.thePlayer.posZ;
        }
        if (this.holding() && System.currentTimeMillis() >= this.releaseAt) {
            this.flush();
        }
    }

    /**
     * Ends the hold once the two views of this player have drifted far enough
     * to be worth correcting.
     *
     * The timer alone cannot decide this. A push withheld while the player
     * stands still costs nothing -- the server's copy and this one agree, and
     * the delay is free. The same window while sprinting away from the hit is
     * the client covering ground the server believes it was knocked back from,
     * and it is that distance, not the elapsed time, the server acts on.
     *
     * Holding for a fixed 225ms therefore means something different in every
     * exchange, which is why the module worked in some fights and produced a
     * setback in others. Measuring the drift and releasing at a fixed number of
     * blocks makes the cost the same every time, and the number is one that can
     * be compared against what a correction actually needs.
     */
    private void trackDivergence() {
        if (!this.divergenceArmed) {
            this.divergenceArmed = true;
            this.divergence = 0.0;
            this.lastX = mc.thePlayer.posX;
            this.lastZ = mc.thePlayer.posZ;
            return;
        }
        double dx = mc.thePlayer.posX - this.lastX;
        double dz = mc.thePlayer.posZ - this.lastZ;
        this.lastX = mc.thePlayer.posX;
        this.lastZ = mc.thePlayer.posZ;
        this.divergence += Math.sqrt(dx * dx + dz * dz);
        if (this.divergence > this.maxDivergence.getValue()) {
            this.flush();
        }
    }

    /**
     * The whole world-dependent decision, taken on the client thread so the
     * packet handler never touches the world at all. The delay figure is
     * settled here too, since which of the two applies depends on ground state
     * that is only meaningful on tick.
     */
    private void evaluateConditions() {
        this.delayBase = this.groundedTicks >= this.groundTicks.getValue()
                ? this.groundDelay.getValue()
                : this.airDelay.getValue();
        this.delayCeiling = this.computeCeiling();

        if (this.waterCheck.getValue() && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) {
            this.conditionsMet = false;
            return;
        }
        if (this.onlySwords.getValue() && !ItemUtil.isHoldingSword()) {
            this.conditionsMet = false;
            return;
        }
        this.conditionsMet = !this.requireTarget.getValue() || this.findTarget() != null;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()
                || Arbiter.catching()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.isSingleplayer()
                || mc.thePlayer.ticksExisted < 20) {
            return;
        }

        Packet<?> packet = event.getPacket();

        /* Chunk and world-render traffic is bulky, unrelated to the exchange,
           and replaying it late causes visible terrain pops. */
        if (PacketUtil.isWorldRenderPacket(packet)) {
            return;
        }
        /* Keep-alive and transaction packets are clocks, not content. The
           server measures this client's latency by how quickly the reply comes
           back, and 1.8 anticheats use the transaction round trip to place
           events on a timeline. Holding either does not delay information --
           it misstates when this client is, which inflates the measured ping by
           the whole window and shifts every lag-compensated check with it. */
        if (packet instanceof S00PacketKeepAlive
                || packet instanceof S32PacketConfirmTransaction) {
            return;
        }
        if (packet instanceof S07PacketRespawn) {
            this.flush();
            return;
        }
        /* A position correction is the server stating where this player
           actually is, and it keeps restating it until the client agrees.
           Holding one therefore does not delay a single packet: the client
           carries on sending the position the server just rejected, the
           server answers with more corrections, and the whole escalating
           burst lands at once when the window closes. Corrections are applied
           immediately and end any hold in progress. */
        if (packet instanceof S08PacketPlayerPosLook) {
            this.flush();
            return;
        }
        /* The player's own hurt animation and sound ride on this packet.
           Delaying it makes the hit itself feel late, which is the one part
           of the exchange there is no reason to postpone. */
        if (this.realtimeDamage.getValue() && packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            /* The obvious getEntity(world) would resolve the id against the
               world from this thread, which is the lookup being avoided. The
               id is compared directly instead. */
            if (status.getOpCode() == 2 && statusEntityId(status) == this.selfEntityId) {
                return;
            }
        }

        if (this.holding() && !this.holdOthers.getValue()
                && !this.isPlayerKnockbackPacket(packet)) {
            /* Only the push is late; the world carries on arriving. */
            return;
        }

        if (this.holding()) {
            /* A second push arriving inside the window is a combo. Queueing it
               behind the first means both land on the same tick when the window
               closes, which moves this client further in one tick than any
               single knockback can -- the exact shape a speed check looks for,
               and a far worse trade than simply taking the second hit on time.
               The queue is released first so the packets stay in order. */
            if (this.isPlayerKnockbackPacket(packet)) {
                /* Two velocity packets in one flush do not add up -- the second
                   overwrites the first -- so the earlier push is not delayed,
                   it is discarded, and this client never moves for it at all.
                   The window is cut to nothing so both are applied on the next
                   tick in the order they arrived. */
                this.releaseAt = 0L;
            }
            this.held.add(packet);
            event.setCancelled(true);
            return;
        }

        if (!this.isHoldablePush(packet) || !this.canStart()) {
            return;
        }
        int delay = this.rollDelay();
        if (delay <= 0) {
            return;
        }
        this.lastTrigger = System.currentTimeMillis();
        this.releaseAt = this.lastTrigger + delay;
        this.held.add(packet);
        event.setCancelled(true);
    }

    /**
     * What the round trip leaves for the hold. Below the floor there is no
     * budget left at all and the module declines to hold rather than spending
     * borrowed time.
     */
    private int computeCeiling() {
        if (!this.pingAware.getValue()) {
            return Integer.MAX_VALUE;
        }
        int ping = this.ping();
        if (ping <= 0) {
            /* No usable reading. The conservative figure is the air delay,
               which is the shorter of the two by design. */
            return this.airDelay.getValue();
        }
        return this.latencyBudget.getValue() - ping;
    }

    private int ping() {
        return Ping.own();
    }

    /** Spreads the figure the client thread settled over the randomize window. */
    private int rollDelay() {
        int base = Math.min(this.delayBase, this.delayCeiling);
        if (base <= 0) {
            return 0;
        }
        int spread = Math.min(this.randomize.getValue(), base);
        if (spread <= 0) {
            return base;
        }
        int delay = base - spread + RandomUtil.nextInt(0, spread * 2);
        return Math.max(0, Math.min(delay, this.delayCeiling));
    }

    /**
     * The part that is safe to decide on the network thread: a clock, a dice
     * roll, and a flag the client thread already worked out. Nothing here
     * reads the world.
     */
    private boolean canStart() {
        if (!this.conditionsMet) {
            return false;
        }
        if (System.currentTimeMillis() - this.lastTrigger < (long) this.cooldown.getValue()) {
            return false;
        }
        return RandomUtil.nextInt(0, 100) <= this.chance.getValue();
    }

    /** The packet's subject, without asking the world to resolve it. */
    private static int statusEntityId(S19PacketEntityStatus status) {
        try {
            if (STATUS_ENTITY_ID == null) {
                Field field = S19PacketEntityStatus.class.getDeclaredField("entityId");
                field.setAccessible(true);
                STATUS_ENTITY_ID = field;
            }
            return STATUS_ENTITY_ID.getInt(status);
        } catch (Exception ignored) {
            /* An id that matches nothing: the packet is simply held with the
               rest, which is the behaviour before this exception existed. */
            return Integer.MIN_VALUE;
        }
    }

    /** A push this module is willing to hold, as opposed to merely one aimed here. */
    private boolean isHoldablePush(Packet<?> packet) {
        if (!this.isPlayerKnockbackPacket(packet)) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            /* The wire format is blocks per tick scaled by 8000. */
            double motionY = ((S12PacketEntityVelocity) packet).getMotionY() / 8000.0;
            return motionY <= this.maxVertical.getValue();
        }
        return true;
    }

    private boolean isPlayerKnockbackPacket(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == this.selfEntityId;
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F
                    || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F;
        }
        return false;
    }

    private void flush() {
        if (!this.held.isEmpty()) {
            /* What was held includes this player's own knockback; it lands
               now (plan step 12). */
            myau.util.ActionLedger.note(this.getName(), "release-self", this.held.size());
        }
        Packet<?> packet;
        while ((packet = this.held.poll()) != null) {
            this.processPacketSilent(packet);
        }
        this.releaseAt = 0L;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processPacketSilent(Packet<?> packet) {
        try {
            if (mc.getNetHandler() != null) {
                ((Packet) packet).processPacket(mc.getNetHandler());
            }
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
            // Rescheduled onto the client thread by vanilla, in order; not an error.
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Whatever the client is already fighting, falling back to a scan.
     *
     * The aura's own target is preferred because it has already applied the
     * client's team, friend and bot filtering; the scan is there for the case
     * where the aura is off and the player is swinging by hand.
     */
    private Entity findTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.target != null) {
            try {
                Field field = KillAura.AttackData.class.getDeclaredField("entity");
                field.setAccessible(true);
                Entity entity = (Entity) field.get(killAura.target);
                if (entity != null) {
                    return entity;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (mc.pointedEntity != null) {
            return mc.pointedEntity;
        }
        if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            return mc.objectMouseOver.entityHit;
        }
        return this.scanForTarget();
    }

    private Entity scanForTarget() {
        double range = this.targetRange.getValue();
        double halfFov = this.targetFov.getValue() / 2.0;
        Vec3 eyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);

        Entity best = null;
        double bestDistance = range;
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player.deathTime > 0
                    || TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player)
                    || TeamUtil.isBot(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(player, eyes);
            if (distance > bestDistance) {
                continue;
            }
            if (halfFov < 180.0) {
                double dx = player.posX - mc.thePlayer.posX;
                double dz = player.posZ - mc.thePlayer.posZ;
                float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float offset = MathHelper.wrapAngleTo180_float(yawTo - mc.thePlayer.rotationYaw);
                if (Math.abs(offset) > halfFov) {
                    continue;
                }
            }
            best = player;
            bestDistance = distance;
        }
        return best;
    }
}
