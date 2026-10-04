package myau.management;

import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import net.minecraft.client.Minecraft;
import myau.util.Ping;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;

/**
 * How long a hit takes to come back: from our attack going out to the hurt of
 * the player we attacked arriving, averaged over the last twenty that came
 * back inside half a second.
 *
 * That is the one number every timing decision in a fight needs at this
 * latency. The hurt time the client shows is the server's, late by half a
 * round trip; an attack sent now reaches the server half a round trip later
 * still. So a target showing a hurt time of N ticks can be damaged by an
 * attack sent now if N is no more than this round trip in ticks -- which is
 * what vulnerable() answers. Measured rather than taken from the ping in the
 * tab list, which is a slow average of keep-alives and misses whatever a
 * packet-holding module adds.
 *
 * The approach is Vape's (its AttackPacketTimingTracker, studied 2026-09-25):
 * attack stamp, hurt status for the same entity, rolling window of twenty,
 * anything slower than 500 ms thrown out. Written here from that description.
 */
public class HitTimer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SAMPLES = 20;
    private static final long MAX_DELAY = 500L;
    private static final int MIN_MEASURED = 3;
    /** The hurt status in S19. */
    private static final byte HURT = 2;

    private static HitTimer instance;

    private final long[] delays = new long[SAMPLES];
    private int count;
    private int next;
    private volatile long lastAttackAt;
    private volatile int targetId = -1;

    public HitTimer() {
        instance = this;
    }

    /** The registered instance; null only before the client has started. */
    public static HitTimer get() {
        return instance;
    }

    /* LOWEST, and cancelled sends ignored: an attack a module holds back
       (FakeLag hold-hits, or its queue) is stamped when it really leaves,
       through noteAttackSent, not when it was first made -- or the hold
       would be measured as latency and every decision built on it would
       drift later. */
    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            if (!event.isCancelled() && event.getPacket() instanceof C02PacketUseEntity) {
                C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
                if (use.getAction() == C02PacketUseEntity.Action.ATTACK) {
                    noteAttack(use.getEntityFromWorld(mc.theWorld));
                }
            }
            return;
        }
        if (event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
            if (status.getOpCode() != HURT || this.targetId < 0) {
                return;
            }
            Entity entity = status.getEntity(mc.theWorld);
            if (entity == null || entity.getEntityId() != this.targetId) {
                return;
            }
            long delay = System.currentTimeMillis() - this.lastAttackAt;
            if (delay >= 0L && delay < MAX_DELAY) {
                record(delay);
            }
            /* One hurt answers one attack. */
            this.targetId = -1;
        }
    }

    /**
     * Samples from the last server say nothing about this one (F-34).
     *
     * This used to be checked on the next attack, so between joining a new
     * server and the first hit there, Ping.own() -- and through it Clutch,
     * HitCheck, KillAura, LatencyGovernor -- ran on the old server's figure.
     * A reconnect to the same server keeps them: same route, same latency.
     */
    @EventTarget
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() == myau.events.SessionEvent.Type.START && event.serverChanged()) {
            clear();
        }
        if (event.getType() != myau.events.SessionEvent.Type.WORLD) {
            /* An attack still awaiting its hurt belongs to the last connection. */
            this.targetId = -1;
        }
    }

    synchronized void clear() {
        this.count = 0;
        this.next = 0;
    }

    private void noteAttack(Entity entity) {
        if (entity != null) {
            this.targetId = entity.getEntityId();
            this.lastAttackAt = System.currentTimeMillis();
        }
    }

    /** An attack sent without an event (a held one released). */
    public static void noteAttackSent(Entity entity) {
        if (instance != null) {
            instance.noteAttack(entity);
        }
    }

    synchronized void record(long delay) {
        this.delays[this.next] = delay;
        this.next = (this.next + 1) % SAMPLES;
        if (this.count < SAMPLES) {
            this.count++;
        }
    }

    /** Attack to hurt, in milliseconds; the tab-list ping until anything has been measured. */
    public synchronized long averageDelay() {
        if (this.count == 0) {
            int tab = Ping.tab();
            return tab >= 2 ? tab : 0;
        }
        long sum = 0L;
        for (int i = 0; i < this.count; i++) {
            sum += this.delays[i];
        }
        return sum / this.count;
    }

    /** The round trip in whole ticks. */
    public int expectedTicks() {
        return (int) (averageDelay() / 50L);
    }

    /** Attack to hurt from at least MIN_MEASURED hits on this server, else -1. */
    public synchronized long measured() {
        return this.count < MIN_MEASURED ? -1L : averageDelay();
    }

    public synchronized int samples() {
        return this.count;
    }

    /** An attack sent now lands after this target's hurt has run out. */
    public boolean vulnerable(EntityLivingBase target) {
        return target.hurtTime <= expectedTicks();
    }

    /** Convenience for callers that may run before the client has started. */
    public static int ticks() {
        return instance == null ? 0 : instance.expectedTicks();
    }

    public static boolean canHurt(EntityLivingBase target) {
        return instance == null || instance.vulnerable(target);
    }
}
