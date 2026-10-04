package myau.module.modules;

import myau.util.AsyncLog;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.management.Arbiter;
import myau.util.ActionLedger;
import myau.util.ChatUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds outgoing movement, so the server's copy of this player runs behind the
 * real one.
 *
 * Built to match Vape v4: three modes, a delay in milliseconds, and a
 * transmission offset that only Repel uses. The modes are the interesting
 * part, and they were never what went wrong here -- an earlier attempt at this
 * had all three and was kicked from the server twice before being stripped
 * back to a constant delay. The faults were all in the plumbing underneath,
 * and they are fixed below rather than avoided by keeping the module simple.
 *
 * WHAT THE MODES ARE. Each asks a different question every tick.
 *
 *   LATENCY asks nothing. Every eligible packet waits the same number of
 *   milliseconds. Fifty milliseconds of real ping with two hundred set here is
 *   a two hundred and fifty millisecond connection, in both directions, all
 *   the time.
 *
 *   DYNAMIC asks whether there is anyone to gain anything against. Packets are
 *   held only while an opponent is within range, and the queue empties the
 *   moment an attack is sent. Out of a fight it adds nothing at all, which is
 *   the point of it: a connection that is only ever bad during a fight is a
 *   smaller thing for anyone to notice than one that is always bad.
 *
 *   REPEL (since 2026-09-25 Vape's Repel, not the old position game) holds
 *   no movement at all. It holds one thing: an attack on someone who cannot
 *   be hurt yet, until they can -- their hurt time within the round trip
 *   HitTimer measures -- and then sends it, with a swing in front. An attack
 *   the server would have refused becomes one it accepts. Only attacks early
 *   by no more than delay (+ delay-random, + transmission offset) are held,
 *   and never longer. The old REPEL -- send only the positions that do not
 *   close the distance -- did what DYNAMIC and LIQUID already do and was
 *   dropped.
 *
 * WHAT WENT WRONG BEFORE, AND WHY IT CANNOT NOW.
 *
 * It delayed every outgoing packet, including the transaction replies. A 1.8
 * server times the round trip by sending a transaction and waiting for the
 * answer, and several -- Pika among them -- close the connection when the
 * answer is late rather than flagging it: "Unexpected pong response -380 -366
 * window: 0" is that happening. So the eligible set is an allow list. A deny
 * list is one forgotten packet away from a disconnect; an allow list is one
 * forgotten packet away from that packet not being delayed.
 *
 * It released from UpdateEvent, which the client fires from EntityPlayerSP
 * behind a check that the chunk under the player is loaded, while the hold ran
 * off the send path, which has no such condition. During a server transfer the
 * client went on cancelling movement while nothing released it: several
 * hundred packets accumulated, the player could not move because the server
 * had not heard from them, and when the chunks arrived the backlog went out at
 * once. Twenty-one lagbacks in three hundred milliseconds, then a kick. The
 * release now runs off TickEvent, which fires under exactly the condition the
 * hold path tests.
 *
 * Its ceiling was checked only by the release path -- which is no ceiling at
 * all on the one occasion it matters, because that is the path that had
 * stopped. The limits are now applied by the thread that fills the queue, so
 * whatever is growing it is also draining it. This is the fix that makes the
 * modes safe to have: none of them can hold anything for longer than the
 * ceiling allows, however confident their own reasoning is.
 *
 * It delayed placements and digs, which buys nothing -- holding a block
 * placement moves nobody's position -- while putting the delay on the one
 * thing in this game that has to feel immediate, and getting the placement
 * refused into the bargain, because the server judges it against the position
 * this module is deliberately keeping out of date. Three refusals inside one
 * second of the first test. Those actions now empty the queue and then go.
 *
 * And it flushed the held positions on a correction, when the server had just
 * finished saying where this player is. Sending them asks to be corrected
 * again; each correction triggered another flush. They are discarded instead.
 *
 * 2026-09-24: THE COMBAT RULES ARE NOW SHARED. The 09-23 test put all 31
 * corrections within a few ticks of a hit or of knockback. What LIQUID does
 * about that now applies to every mode: knockback, damage, a pushing
 * explosion, a placement or dig releases everything and starts a recoil
 * during which nothing is held; DYNAMIC and REPEL additionally stop holding
 * once an opponent is within melee distance of the server-side position, and
 * release on their own attack. What each mode does while it IS holding is
 * unchanged. Releases go through release-style (IN_ORDER or the old
 * COLLAPSE), so the two can be compared in play.
 *
 * 2026-09-25: NO MORE DUMPS (LIQUID). Every release used to put the whole
 * queue on the wire in one tick -- seven to seventeen positions -- and the
 * server does not take that: at 12:45:04 a ten-packet release on a slot
 * change was followed by a placement judged 5.7 blocks from where the
 * client was. Now the queue is let out, not dropped:
 *   - approaching, the delay shrinks to nothing over the last taper blocks
 *     before melee distance, so by the time the fight starts there is
 *     nothing left to send;
 *   - when holding stops for a reason the server has no need to hear about
 *     at once (retreating, standing still, no opponent, a screen, an item
 *     in use, water) the queue drains at catch-up positions a tick, and
 *     what is sent meanwhile waits its turn behind it so the order holds;
 *   - what does need the server current -- an attack, knockback, damage, a
 *     placement, a dig, a slot change, melee distance, a correction, death
 *     -- still empties it at once, as before.
 *
 * 2026-09-24: EVERY HOLD IS REPORTED (the log setting). A whole match could
 * not say whether LIQUID had held anything: the suffix shows a count for a
 * fraction of a second per approach, and FlagDetector names this module only
 * when a flag follows. Each hold now ends in one line -- how many packets,
 * for how long, against whom, and what released them -- and each engagement
 * ends in one line in the file saying why nothing was held the rest of the
 * time.
 */
public class FakeLag extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int LATENCY = 0;
    private static final int DYNAMIC = 1;
    private static final int REPEL = 2;
    /**
     * LiquidBounce's gating (legacy FakeLag.kt and nextgen ModuleFakeLag.kt).
     *
     * The other combat modes hold hardest exactly while hits are being traded,
     * which is where every one of their flags landed: 31 corrections in 9 s,
     * all 1-4 t after a hit or 1-7 t after knockback (2026-09-23). LiquidBounce
     * inverts that. It holds only on the approach, and goes honest -- releasing
     * everything, in order -- the moment the fight actually starts: an
     * opponent within melee distance of the server-side position, knockback,
     * damage, an attack. After any release it will not hold again for a recoil
     * period.
     *
     * Deliberately not copied: legacy LiquidBounce also flushes the queue when
     * an S08 arrives. NOTES §2 says to discard stale positions on a
     * correction, so this mode uses the existing S08/C06 handling instead.
     */
    private static final int LIQUID = 3;

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"LATENCY", "DYNAMIC", "REPEL", "LIQUID"});

    /**
     * Milliseconds each eligible packet waits.
     *
     * Exactly what it says. On a connection already past two hundred
     * milliseconds, two hundred more is a great deal -- start low and raise it
     * only while it is buying something.
     */
    public final IntProperty delay = new IntProperty("delay", 150, 0, 1000);

    /** Extra milliseconds REPEL may hold an attack beyond delay and delay-random. */
    public final IntProperty transmissionOffset = new IntProperty("transmission-offset",
            0, 0, 400, () -> this.mode.getValue() == REPEL);

    /** How near an opponent has to be for the two combat modes to engage. */
    public final FloatProperty range = new FloatProperty("range", 8.0F, 1.0F, 24.0F,
            () -> this.mode.getValue() != LATENCY);

    /**
     * Whether sending an attack empties the queue behind it.
     *
     * On. The held positions are what make the attack worth anything, and
     * sitting on the movement after the attack has gone means the server
     * resolves the hit against a position this client has already left and
     * then receives a burst of movement explaining how it got there -- louder
     * than the delay it is protecting.
     */
    public final BooleanProperty releaseOnAttack = new BooleanProperty("release-on-attack",
            true, () -> this.mode.getValue() != LATENCY);

    /**
     * The most packets that may be held, whatever any mode thinks.
     *
     * A hard stop rather than a tuning knob, and the reason the modes are safe
     * to have at all. Twenty is a second of movement; nothing good happens
     * past that.
     */
    public final IntProperty maxHeld = new IntProperty("max-held", 20, 4, 100);

    /**
     * LIQUID: an opponent this close to the server-side position ends the hold.
     *
     * LiquidBounce legacy's MinAllowedDistToEnemy upper bound (3.5). Inside
     * this, hits are being exchanged, and holding would mean the server
     * resolves them against a position the player has left.
     */
    public final FloatProperty meleeDistance = new FloatProperty("melee-distance", 3.5F, 0.0F, 6.0F,
            () -> this.mode.getValue() != LATENCY);

    /**
     * LIQUID: milliseconds after any release during which nothing is held.
     *
     * LiquidBounce legacy's RecoilTime (750; nextgen uses 250). This is what
     * stops the hold, release, hold, release cycle within one exchange: each
     * release is a burst the server has to accept, so there should be few.
     */
    public final IntProperty recoilTime = new IntProperty("recoil-time", 750, 0, 2000);

    /**
     * LIQUID: blocks before melee distance over which the delay runs down to
     * nothing, so the queue is already empty when the fight starts. 0 keeps
     * the full delay up to melee distance and then releases it all at once.
     */
    public final FloatProperty taper = new FloatProperty("taper", 1.5F, 0.0F, 4.0F,
            () -> this.mode.getValue() == LIQUID);

    /**
     * LIQUID: positions a tick while the queue is let out -- one is what the
     * client makes, so one more than that is the queue shrinking by one a
     * tick. Releases that must be immediate ignore it.
     */
    public final IntProperty catchUp = new IntProperty("catch-up", 2, 2, 5,
            () -> this.mode.getValue() == LIQUID);

    /**
     * How a release sends what was held.
     *
     * IN_ORDER sends every held packet as it was made (LiquidBounce's way):
     * the server sees the real path, several positions in one tick. COLLAPSE
     * keeps the actions but only the last position: one jump of the same
     * total distance. NOTES 1 says both cost something and nothing so far
     * says which Pika minds less -- this is here to be measured, not argued.
     */
    public final ModeProperty releaseStyle = new ModeProperty("release-style", 0,
            new String[]{"IN_ORDER", "COLLAPSE"});

    /* Up to this many milliseconds more than delay, drawn afresh for every
       hold. A hold that always lasts exactly the same is a constant a timing
       check can find; LiquidBounce draws each one from 300-600 ms, Vape's
       Repel adds 0-99 ms (2026-09-25). In LATENCY, where the queue never
       empties, it is redrawn every two seconds instead. */
    public final IntProperty delayRandom = new IntProperty("delay-random", 100, 0, 500);

    /* Hold only with a weapon in hand -- a sword, or anything with Sharpness
       (Slinky's "holding weapon" condition). With a bow, a rod, a pearl or
       food out, the fight is not at arm's length and holding buys nothing;
       the queue is let out as for the other quiet reasons. */
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true,
            () -> this.mode.getValue() != LATENCY);


    /**
     * Where holds are reported.
     *
     * One line per hold, from the first packet held to the release that
     * emptied the queue, in chat and in config/Myau/fakelag-<stamp>.txt. The
     * file also gets one line per engagement: how long an opponent was in
     * range, how much of it holding was allowed, and which condition ruled it
     * out the rest of the time. Not in LATENCY, whose queue is only empty
     * when something released it, so a hold there has no end to report.
     */
    public final ModeProperty log = new ModeProperty("log", 2,
            new String[]{"OFF", "FILE", "CHAT+FILE"}, () -> this.mode.getValue() != LATENCY);

    private final ConcurrentLinkedQueue<Held> queue = new ConcurrentLinkedQueue<Held>();

    /** The delay for the hold in progress (delay-random). */
    private volatile int rolledDelay = 150;
    private long rolledAt;

    /* REPEL: the one attack being held, whom it is for, and until when. */
    private C02PacketUseEntity heldHit;
    private net.minecraft.entity.EntityLivingBase heldHitTarget;
    private long heldHitAt;
    private long heldHitDeadline;
    private int heldHitHurt;
    private int hitsHeld;
    private int hitsMerged;

    private void rollDelay() {
        int spread = this.delayRandom.getValue();
        this.rolledDelay = this.delay.getValue() + (spread > 0 ? (int) (Math.random() * (spread + 1)) : 0);
        this.rolledAt = System.currentTimeMillis();
    }

    /** What a held packet waits: the delay drawn for this hold. */
    private int holdDelay() {
        return this.rolledDelay;
    }

    /** Outgoing packets currently held, for whatever is reporting on the connection. */
    public int heldCount() {
        return this.queue.size();
    }

    /** LIQUID: no holding before this wall-clock time. Written from the network thread too. */
    private volatile long recoilUntil;
    /** LIQUID: whether the conditions to hold were met at the last tick. */
    private boolean holdAllowed;
    /** LIQUID: the queue is being let out rather than dropped; new packets wait behind it. */
    private volatile boolean easing;

    /** Set from the network thread, acted on by the client thread. */
    private volatile boolean flushRequested;
    private volatile boolean attackQueued;
    /** True while this module is putting its own packets back on the wire. */
    private volatile boolean releasing;

    /** The last position the server was actually told about. Repel needs it. */
    private Vec3 lastSent;

    /**
     * Whether a position has already left this tick.
     *
     * The cap that was missing, and the cause of the lagbacks this module was
     * still producing after the queue stopped running away. A client emits one
     * position per tick, so a server receiving several in one tick is being
     * told the player covered several ticks of ground in one -- "LAGBACK x4,
     * max 2.8 blocks, sprint, air" is that, and so were the five placement
     * refusals a second later, because each placement flushed the queue ahead
     * of itself and was then judged against a position the server was still
     * catching up to.
     *
     * Exactly the same mistake as the one in Blink's release earlier today,
     * reached from the other direction and not recognised for what it was. It
     * is not about how the packets are grouped; it is that N ticks of travel
     * cannot be delivered in one tick by any arrangement.
     */
    private boolean sentPositionThisTick;

    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    /** An engagement ends once no opponent has been in range for this long. */
    private static final int ENGAGEMENT_GAP_TICKS = 60;
    /** Anything shorter is someone walking past, and is not written. */
    private static final int ENGAGEMENT_MIN_TICKS = 20;

    private File logTarget;

    /** Why the last tick did not allow holding; null when it did. */
    private String blockedBy;
    /** The opponent the last tick reasoned about, for the reports. */
    private EntityPlayer currentTarget;

    /* The hold in progress, from the first packet held to the release that
       empties the queue. No hold is in progress while episodeStart is 0. */
    private long episodeStart;
    private int episodeHeld;
    private int episodePeak;
    private String episodeTarget;
    private double episodeDistance;

    /* The engagement in progress: an opponent in range, until none has been
       for ENGAGEMENT_GAP_TICKS. Counted per tick by the reason holding was
       ruled out, so a match with no holds says why it had none. */
    private int tickCount;
    private String engagedWith;
    private int engagedLastSeen;
    private int engagedTicks;
    private int engagedOpen;
    private int engagedHolds;
    private int engagedPackets;
    private final Map<String, Integer> engagedBlocked = new LinkedHashMap<String, Integer>();

    public FakeLag() {
        super("FakeLag", false, false,
                "Delays outgoing movement so the server's copy of this player runs behind the real one");
        /* What is held, for PacketHolds (plan step 13). */
        myau.management.PacketHolds.register(() -> {
            int count = this.queue.size() + (this.heldHit != null ? 1 : 0);
            if (count == 0) {
                return null;
            }
            Held head = this.queue.peek();
            return new myau.management.PacketHolds.Hold("FakeLag", myau.management.PacketHolds.Direction.OUT, count, head == null ? 0L : head.stamp,
                    this.mode.getModeString() + (this.heldHit != null ? ", an attack held" : ""),
                    "after " + this.rolledDelay + "ms; at once for an attack, a placement or a catch");
        }, new myau.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            /* The longest delay the settings can draw, and a second more. */
            @Override
            public long ceilingMs() {
                return delay.getValue() + delayRandom.getValue() + myau.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseHit("lease");
                releaseAll("lease");
            }
        });
    }

    @Override
    public void onEnabled() {
        this.queue.clear();
        this.flushRequested = false;
        this.attackQueued = false;
        this.releasing = false;
        this.lastSent = null;
        this.holdAllowed = false;
        /* LiquidBounce does not lag on the first moments either: the
           recoil applies from the switch-on, not only after a release. */
        this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
        this.blockedBy = null;
        this.currentTarget = null;
        this.episodeStart = 0L;
        this.engagedWith = null;
        this.logTarget = null;
        this.heldHit = null;
        this.heldHitTarget = null;
        this.hitsHeld = 0;
        this.hitsMerged = 0;
        rollDelay();
    }

    @Override
    public void onDisabled() {
        releaseHit("disabled");
        releaseAll("disabled");
        endEngagement();
        this.lastSent = null;
        this.currentTarget = null;
    }

    /** Movement, the attack, and the swing that belongs with it. Nothing else. */
    private static boolean delayable(Packet<?> packet) {
        return packet instanceof C03PacketPlayer
                || packet instanceof C02PacketUseEntity
                || packet instanceof C0APacketAnimation;
    }

    /** Things the server must judge against where the player really is. */
    private static boolean mustBeCurrent(Packet<?> packet) {
        return packet instanceof C07PacketPlayerDigging
                || packet instanceof C08PacketPlayerBlockPlacement;
    }

    /* A slot change or a sprint/sneak toggle is not judged against a
       position, only against the order of the movement around it. Until
       2026-09-25 they emptied the queue at once like a click: 14:36:07 a
       stop-sprint sent ten held positions in one tick, the server pulled the
       player back four times and refused every block for three seconds.
       Queued behind what is held, in order, they are only late -- latency --
       and the queue drains at its own pace. */
    private static boolean keepsOrder(Packet<?> packet) {
        return packet instanceof C09PacketHeldItemChange
                || packet instanceof C0BPacketEntityAction;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();

        if (event.getType() == EventType.RECEIVE) {
            if (packet instanceof S08PacketPlayerPosLook || packet instanceof S07PacketRespawn) {
                /* Deferred to the tick: this runs on the network thread. */
                this.flushRequested = true;
                this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            } else if (hitsThisPlayer(packet)) {
                /* Knockback or damage: the server now expects to see this
                   player react, and every tick the reaction sits in the queue
                   is a tick it looks like it was ignored. This is the half of
                   the 2026-09-23 flags that said "kb 1t ago". */
                this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
                final String cause = packet instanceof S12PacketEntityVelocity ? "knockback"
                        : packet instanceof S27PacketExplosion ? "explosion" : "damage";
                /* The network thread must not send; the client thread runs
                   scheduled tasks before its next tick, so the queue is gone
                   before the first position that carries the reaction. */
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (FakeLag.this.isEnabled()) {
                            releaseAll(cause);
                        }
                    }
                });
            }
            return;
        }

        if (this.releasing || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.flushRequested && packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
            /* The client's answer to the correction. Vanilla sends it from the
               S08 handler, before the tick that acts on flushRequested, so it
               used to be queued here and then thrown away with the stale
               positions -- leaving the server waiting for a reply to its own
               teleport. Clear the stale state now and let the answer go. */
            discardStalePositions();
            /* Handled here, so the tick must not discard again: by then the
               queue holds positions from after the reply, which are current. */
            this.flushRequested = false;
            return;
        }
        if (keepsOrder(packet)) {
            if (!this.queue.isEmpty() && !Arbiter.catching()) {
                event.setCancelled(true);
                ActionLedger.note(this.getName(), "held-send");
                this.queue.offer(new Held(packet, System.currentTimeMillis()));
                noteHeld();
                enforceLimits();
            } else if (!this.queue.isEmpty()) {
                releaseAll(actionName(packet));
            }
            return;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement && !this.queue.isEmpty()
                && myau.util.ItemUtil.isHoldingBlock() && !Arbiter.catching()) {
            /* A block placed while positions are still held. Emptying the
               queue first sends them all in one tick, and the server answered
               every such burst on 2026-09-25 by pulling the player back and
               refusing the blocks (14:36:07, 14:55:16: switched to blocks
               mid-hold and clicked at once). With blocks in hand the queue is
               already being let out ("blocks"); the click takes its place
               at the back and reaches the server in order, behind the
               positions it was made from -- late, as with latency, but
               judged exactly where it was made. */
            event.setCancelled(true);
            ActionLedger.note(this.getName(), "held-send");
            this.queue.offer(new Held(packet, System.currentTimeMillis()));
            noteHeld();
            enforceLimits();
            return;
        }
        if (mustBeCurrent(packet)) {
            /* Emptied before this packet rather than after it. We are at the
               head of the send call, so anything released here reaches the
               server first and this one is judged against it. */
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            releaseAll(actionName(packet));
            return;
        }
        if (this.mode.getValue() == REPEL) {
            if (!(packet instanceof C02PacketUseEntity)
                    || ((C02PacketUseEntity) packet).getAction() != C02PacketUseEntity.Action.ATTACK
                    || Arbiter.catching()) {
                /* REPEL holds attacks and nothing else. */
                return;
            }
            net.minecraft.entity.Entity entity = ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
            if (this.heldHit != null) {
                if (entity != null && entity == this.heldHitTarget) {
                    /* Another click on the one already held for. It could
                       not land either -- the target is still immune -- and
                       sending the held one to make room for it is what made
                       the first version useless at 12-16 clicks a second:
                       every held attack went out a tick and a half later,
                       still too early. The held one stands; this one is
                       dropped (its swing has already gone, which is a miss
                       like any other). */
                    event.setCancelled(true);
                    this.hitsMerged++;
                    return;
                }
                /* Someone else: the held one goes first, as it was made. */
                releaseHit("switched target");
            }
            boolean armed = !this.weaponsOnly.getValue() || myau.util.ItemUtil.hasRawUnbreakingEnchant();
            if (armed && entity instanceof EntityPlayer) {
                EntityPlayer target = (EntityPlayer) entity;
                int early = target.hurtTime - myau.management.HitTimer.ticks();
                int spread = this.delayRandom.getValue();
                long window = this.delay.getValue() + this.transmissionOffset.getValue()
                        + (spread > 0 ? (long) (Math.random() * (spread + 1)) : 0L);
                if (early > 0 && early * 50L <= window) {
                    event.setCancelled(true);
                    ActionLedger.note(this.getName(), "held-send");
                    this.heldHit = (C02PacketUseEntity) packet;
                    this.heldHitTarget = target;
                    this.heldHitAt = System.currentTimeMillis();
                    this.heldHitDeadline = this.heldHitAt + window;
                    this.heldHitHurt = target.hurtTime;
                    this.hitsHeld++;
                }
            }
            return;
        }
        if (!delayable(packet)) {
            return;
        }
        if (Arbiter.catching()) {
            /* A fall is being caught (see Arbiter): the server has to have
               every position as it happens, so nothing is held and what was
               goes now. */
            if (!this.queue.isEmpty()) {
                releaseAll("catch");
                Arbiter.yielded(this.getName());
            }
            return;
        }
        /* The rules below are what LIQUID proved, applied to every mode
           (2026-09-24). The old DYNAMIC and REPEL held right through the
           exchange of hits and collapsed the queue on each attack, and every
           flag of the 09-23 test was 1-4 t after a hit or 1-7 t after
           knockback. */
        boolean attack = packet instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK;
        if (attack && this.mode.getValue() != LATENCY && this.releaseOnAttack.getValue()) {
            /* The attack goes out live, behind everything held, and the recoil
               keeps the rest of the exchange honest. */
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            releaseAll("attack");
            return;
        }
        if (!this.holdAllowed || System.currentTimeMillis() < this.recoilUntil) {
            /* Checked here as well as at the tick: a release on the network
               side sets the recoil between ticks, and the packets of the
               current tick must already respect it. Released before this
               packet goes, so nothing overtakes what was held. */
            if (!this.queue.isEmpty()) {
                if (this.easing && System.currentTimeMillis() >= this.recoilUntil) {
                    /* Being let out: this one takes its place at the back. */
                    event.setCancelled(true);
                    ActionLedger.note(this.getName(), "held-send");
                    this.queue.offer(new Held(packet, System.currentTimeMillis()));
                    noteHeld();
                    enforceLimits();
                    return;
                }
                releaseAll(this.holdAllowed ? "recoil" : String.valueOf(this.blockedBy));
            }
            return;
        }
        if (!mc.theWorld.isBlockLoaded(new BlockPos(mc.thePlayer.posX, 0.0, mc.thePlayer.posZ))) {
            /* Mid-transfer, or the chunk under the player has not arrived.
               Nothing is gained by delaying movement at a moment when the
               player is not really anywhere, and holding here is what turned a
               server change into a disconnect. */
            return;
        }

        /* Held by cancelling the event rather than inside the mixin, which is
           what lets the ledger see it and name this module for the corrections
           it causes. */
        event.setCancelled(true);
        ActionLedger.note(this.getName(), "held-send");
        this.queue.offer(new Held(packet, System.currentTimeMillis()));
        noteHeld();
        enforceLimits();
    }

    /** The name a release is reported under when an action forced it. */
    private static String actionName(Packet<?> packet) {
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            /* Every right-click against a block or with an item in hand, not
               only placements. */
            return "right-click";
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return "dig";
        }
        if (packet instanceof C09PacketHeldItemChange) {
            return "slot";
        }
        if (packet instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) packet).getAction();
            return action == null ? "entity-action" : action.name().toLowerCase();
        }
        return packet.getClass().getSimpleName();
    }

    /**
     * The limits, applied by the thread that just added to the queue.
     *
     * Cheap, and touches nothing outside the queue, so it is safe to run on
     * every outgoing packet -- which is the point. Whatever fills the queue
     * also drains it, so no mode's reasoning, and no event failing to arrive,
     * can let it run away.
     */
    private void enforceLimits() {
        while (this.queue.size() > this.maxHeld.getValue()) {
            sendOne();
        }
        /* A ceiling in time as well as in count. No mode may hold a packet
           longer than this, whatever it believes it is achieving. */
        long deadline = holdDelay() + 500L;
        long now = System.currentTimeMillis();
        while (!this.queue.isEmpty() && now - this.queue.peek().stamp > deadline) {
            sendOne();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
            this.queue.clear();
            this.heldHit = null;
            this.heldHitTarget = null;
            /* Nothing to report: the connection these were for is gone. */
            this.episodeStart = 0L;
            return;
        }
        /* A hold the delay or the limits drained rather than a release. */
        closeEpisode("drained");
        if (this.heldHit != null) {
            net.minecraft.entity.EntityLivingBase target = this.heldHitTarget;
            if (Arbiter.catching()) {
                releaseHit("catch");
            } else if (target == null || target.isDead || mc.theWorld.getEntityByID(target.getEntityId()) != target) {
                /* Dead, out of sight, or another world: an attack on nobody
                   is dropped, not sent. */
                this.heldHit = null;
                this.heldHitTarget = null;
            } else if (target.hurtTime <= myau.management.HitTimer.ticks()) {
                releaseHit("hurtable");
            } else if (System.currentTimeMillis() >= this.heldHitDeadline) {
                releaseHit("window over");
            }
        }
        if (this.mode.getValue() == LATENCY && System.currentTimeMillis() - this.rolledAt > 2000L) {
            rollDelay();
        }
        if (this.flushRequested) {
            this.flushRequested = false;
            discardStalePositions();
            return;
        }
        if (mc.thePlayer.isDead) {
            this.heldHit = null;
            releaseAll("dead");
            return;
        }

        this.sentPositionThisTick = false;
        enforceLimits();

        int current = this.mode.getValue();
        if (current == LIQUID) {
            liquidTick();
            return;
        }
        if (current == LATENCY) {
            /* A constant delay, except around knockback and actions (above):
               those release and start the recoil. */
            this.blockedBy = null;
            this.holdAllowed = true;
            releaseOlderThan(holdDelay());
            return;
        }

        if (current == REPEL) {
            /* Attacks only (above, and in onPacket); no movement is held. */
            this.holdAllowed = false;
            if (!this.queue.isEmpty()) {
                releaseAll("repel");
            }
            return;
        }

        /* DYNAMIC: hold only with an opponent in range who is not
           yet within melee distance of the server-side position. Out of a
           fight, or once it has started, the connection is honest; reaching
           melee distance starts the recoil, as in LIQUID. */
        EntityPlayer target = nearestTarget();
        boolean armed = !this.weaponsOnly.getValue() || myau.util.ItemUtil.hasRawUnbreakingEnchant();
        boolean hold = target != null && armed && !inMelee(target);
        String blocked = target == null ? "no-target" : !armed ? "no-weapon" : hold ? null : "melee";
        if (hold && System.currentTimeMillis() < this.recoilUntil) {
            blocked = "recoil";
        }
        observe(target, blocked);
        this.holdAllowed = hold;
        if (!hold) {
            if (!this.queue.isEmpty()) {
                releaseAll(blocked);
            }
            return;
        }

        releaseOlderThan(holdDelay());
    }

    /**
     * LIQUID, once a tick: decide whether the next tick may hold, and release
     * what is due.
     *
     * Holds only while all of these are true -- each is a LiquidBounce
     * condition:
     *   moving, not using an item, no screen, not in water, not hurt, not in
     *   singleplayer;
     *   an opponent within range of the real position;
     *   that opponent further than melee distance from the server-side
     *   position (legacy MinAllowedDistToEnemy);
     *   the server-side position no nearer to them than the real one, i.e.
     *   approaching, not retreating (nextgen's serverDistance < clientDistance).
     *
     * Reaching melee distance counts as the fight starting and starts the
     * recoil. The other conditions failing just releases: nothing happened
     * that the server needs to see promptly.
     *
     * Releases are everything in order, as LiquidBounce does, not collapsed
     * to the last position. Several positions in one tick is a known §1 cost;
     * the recoil is what keeps it rare.
     */
    private void liquidTick() {
        long now = System.currentTimeMillis();
        boolean hold = false;
        double taperFactor = 1.0;
        /* The target is looked up whether or not the player is eligible, so
           the engagement report can count the ticks an opponent was in range
           but holding was ruled out. The decision below uses it only when
           eligible, exactly as before. */
        String blocked = ineligibility();
        EntityPlayer target = nearestTarget();
        if (target == null) {
            if (blocked == null) {
                blocked = "no-target";
            }
        } else if (blocked == null) {
            double eye = mc.thePlayer.getEyeHeight();
            Vec3 server = heldServerPosition().addVector(0.0, eye, 0.0);
            Vec3 client = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + eye, mc.thePlayer.posZ);
            double serverDistance = RotationUtil.distanceToBox(target, server);
            double clientDistance = RotationUtil.distanceToBox(target, client);
            if (serverDistance <= this.meleeDistance.getValue()) {
                this.recoilUntil = now + this.recoilTime.getValue();
                blocked = "melee";
            } else {
                hold = serverDistance >= clientDistance;
                if (!hold) {
                    blocked = "retreating";
                }
                float span = this.taper.getValue();
                if (span > 0.0F) {
                    taperFactor = Math.max(0.0, Math.min(1.0,
                            (serverDistance - this.meleeDistance.getValue()) / span));
                }
            }
        }
        if (hold && now < this.recoilUntil) {
            blocked = "recoil";
        }
        observe(target, blocked);
        this.holdAllowed = hold;
        boolean recoiling = now < this.recoilUntil;
        this.easing = !hold && !recoiling && easesOut(blocked);
        if (!hold || recoiling) {
            if (!this.queue.isEmpty()) {
                if (this.easing) {
                    releaseDue(Long.MAX_VALUE, this.catchUp.getValue());
                    closeEpisode(blocked + ", let out");
                } else {
                    releaseAll(blocked);
                }
            }
            return;
        }
        /* Closing in: the delay runs down with the distance still to go. */
        long cutoff = now - (long) (holdDelay() * taperFactor);
        releaseDue(cutoff, taperFactor < 1.0 ? this.catchUp.getValue() : Integer.MAX_VALUE);
        if (taperFactor < 1.0) {
            closeEpisode("tapered");
        }
    }

    /** Reasons to stop holding that the server has no need to hear about this tick. */
    private static boolean easesOut(String reason) {
        return "retreating".equals(reason) || "not-moving".equals(reason) || "no-target".equals(reason)
                || "screen".equals(reason) || "using-item".equals(reason) || "water".equals(reason)
                || "blocks".equals(reason) || "no-weapon".equals(reason);
    }

    /**
     * Sends what was held before the cutoff, in order, with at most this many
     * positions; anything else goes along with them.
     */
    private void releaseDue(long cutoff, int positions) {
        int sent = 0;
        while (!this.queue.isEmpty() && this.queue.peek().stamp <= cutoff) {
            if (this.queue.peek().packet instanceof C03PacketPlayer) {
                if (sent >= positions) {
                    return;
                }
                sent++;
            }
            sendOne();
        }
    }

    /**
     * Where the server last put this player, as far as the queue can say.
     *
     * The oldest held position, as LiquidBounce uses; with nothing held the
     * server is up to date and the real position is the answer.
     */
    private Vec3 heldServerPosition() {
        for (Held held : this.queue) {
            if (held.packet instanceof C03PacketPlayer && ((C03PacketPlayer) held.packet).isMoving()) {
                C03PacketPlayer position = (C03PacketPlayer) held.packet;
                return new Vec3(position.getPositionX(), position.getPositionY(), position.getPositionZ());
            }
        }
        return new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    /** Opponent within melee distance of the server-side position; starts the recoil if so. */
    private boolean inMelee(EntityPlayer target) {
        Vec3 server = heldServerPosition().addVector(0.0, mc.thePlayer.getEyeHeight(), 0.0);
        if (RotationUtil.distanceToBox(target, server) <= this.meleeDistance.getValue()) {
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            return true;
        }
        return false;
    }

    /**
     * LIQUID: the first of LiquidBounce's player conditions that rules holding
     * out, or null when none does.
     */
    private String ineligibility() {
        if (mc.isSingleplayer()) {
            return "singleplayer";
        }
        if (mc.currentScreen != null) {
            return "screen";
        }
        if (mc.thePlayer.isUsingItem()) {
            return "using-item";
        }
        if (mc.thePlayer.isInWater()) {
            return "water";
        }
        /* Blocks in hand: building, not fighting. Every block placed while
           positions are held empties the queue in one tick first (a click is
           judged where the player really is), and those bursts were behind
           the refused blocks of 2026-09-25 14:36. */
        if (myau.util.ItemUtil.isHoldingBlock()) {
            return "blocks";
        }
        if (this.weaponsOnly.getValue() && !myau.util.ItemUtil.hasRawUnbreakingEnchant()) {
            return "no-weapon";
        }
        if (mc.thePlayer.hurtTime != 0) {
            return "hurt";
        }
        if (mc.thePlayer.movementInput.moveForward == 0.0F
                && mc.thePlayer.movementInput.moveStrafe == 0.0F) {
            return "not-moving";
        }
        return null;
    }

    /** Every release goes through here, in the style the setting chooses. */
    private void releaseAll(String reason) {
        this.easing = false;
        if (this.releaseStyle.getValue() == 1) {
            flush();
        } else {
            flushInOrder();
        }
        closeEpisode(reason);
    }

    /** Knockback, an explosion that pushes, or lost health. Runs on the network thread. */
    private static boolean hitsThisPlayer(Packet<?> packet) {
        if (mc.thePlayer == null) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            return velocity.getEntityID() == mc.thePlayer.getEntityId()
                    && (velocity.getMotionX() != 0 || velocity.getMotionY() != 0 || velocity.getMotionZ() != 0);
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F;
        }
        if (packet instanceof S06PacketUpdateHealth) {
            return ((S06PacketUpdateHealth) packet).getHealth() < mc.thePlayer.getHealth();
        }
        return false;
    }

    /**
     * Everything held, in the order it was made. LiquidBounce's blink().
     *
     * Unlike flush(), no position is skipped: the server gets the whole path
     * rather than one jump to its end.
     */
    private void flushInOrder() {
        int remaining = this.queue.size();
        for (int i = 0; i < remaining && !this.queue.isEmpty(); i++) {
            sendOne();
        }
        this.attackQueued = false;
    }

    /** The nearest opponent worth holding packets against. */
    private EntityPlayer nearestTarget() {
        EntityPlayer best = null;
        double bestDistance = this.range.getValue();
        Vec3 eyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player == mc.thePlayer.ridingEntity
                    || player.isDead || player.deathTime > 0) {
                continue;
            }
            if (!TargetFilter.accepts(player)) {
                continue;
            }
            if (TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player)
                    || TeamUtil.isBot(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(player, eyes);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /**
     * Releases what is due, at the rate a client produces it.
     *
     * One position per tick and no more. Non-movement packets are not travel
     * and pass without counting, because a server measuring speed has no
     * opinion on how many swings arrive together.
     */
    private void releaseOlderThan(int millis) {
        long now = System.currentTimeMillis();
        while (!this.queue.isEmpty()) {
            Held head = this.queue.peek();
            if (now - head.stamp < millis) {
                return;
            }
            if (head.packet instanceof C03PacketPlayer) {
                if (this.sentPositionThisTick) {
                    return;
                }
                this.sentPositionThisTick = true;
            }
            sendOne();
        }
    }

    /**
     * Empties the queue in one pass, keeping only the last of the positions.
     *
     * For the moments that cannot wait several ticks to drain -- an attack, a
     * placement, being switched off, dying. Replaying every held position
     * claims each one as travel inside this tick; sending only the last claims
     * the same total distance in a single move event, which is strictly less
     * to object to and is what latency already explains at this delay. The
     * intermediate ones describe a path the server does not need.
     *
     * Everything that is not a position is kept and sent in order, because
     * those are things the player did.
     */
    private void flush() {
        Held lastPosition = null;
        for (Held held : this.queue) {
            if (held.packet instanceof C03PacketPlayer) {
                lastPosition = held;
            }
        }
        int remaining = this.queue.size();
        for (int i = 0; i < remaining; i++) {
            Held held = this.queue.poll();
            if (held == null) {
                break;
            }
            if (held.packet instanceof C03PacketPlayer && held != lastPosition) {
                continue;
            }
            send(held.packet);
            if (held == lastPosition) {
                C03PacketPlayer position = (C03PacketPlayer) held.packet;
                if (position.isMoving()) {
                    this.lastSent = new Vec3(position.getPositionX(),
                            position.getPositionY(), position.getPositionZ());
                }
                this.sentPositionThisTick = true;
            }
        }
        this.attackQueued = false;
    }

    /**
     * Empties the queue after a correction, throwing the positions away.
     *
     * The client has already answered the teleport with its own confirming
     * position, so nothing is missing. Actions -- an attack, a swing -- are
     * still sent, because those are things the player did and dropping them
     * looks to them like the client ignoring input.
     */
    private void discardStalePositions() {
        releaseHit("correction");
        int remaining = this.queue.size();
        for (int i = 0; i < remaining; i++) {
            Held held = this.queue.poll();
            if (held == null) {
                break;
            }
            if (held.packet instanceof C03PacketPlayer) {
                continue;
            }
            send(held.packet);
        }
        this.attackQueued = false;
        /* The server's position is authoritative now and this module's idea of
           where it last put the player is not. */
        this.lastSent = null;
        closeEpisode("correction");
    }

    private void sendOne() {
        Held held = this.queue.poll();
        if (held == null) {
            return;
        }
        send(held.packet);
        if (held.packet instanceof C03PacketPlayer) {
            C03PacketPlayer position = (C03PacketPlayer) held.packet;
            if (position.isMoving()) {
                this.lastSent = new Vec3(position.getPositionX(),
                        position.getPositionY(), position.getPositionZ());
            }
        }
    }

    /**
     * Sent without an event, so it cannot be caught by the handler above and
     * queued a second time. It still passes through the blink and lag
     * managers, which is correct: if one of those is also holding packets it
     * should get this one too.
     */
    /**
     * Sends the held attack, behind whatever positions are held and with a
     * swing of its own in front of it. The positions go first when the
     * attack would have released them anyway (release-on-attack).
     */
    private void releaseHit(String reason) {
        C02PacketUseEntity hit = this.heldHit;
        if (hit == null) {
            return;
        }
        this.heldHit = null;
        net.minecraft.entity.EntityLivingBase target = this.heldHitTarget;
        this.heldHitTarget = null;
        if (mc.thePlayer == null || mc.getNetHandler() == null) {
            return;
        }
        if (!this.queue.isEmpty() && this.mode.getValue() != LATENCY && this.releaseOnAttack.getValue()) {
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            releaseAll("attack");
        }
        send(new C0APacketAnimation());
        send(hit);
        if (this.log.getValue() != 0) {
            writeLog(String.format("HIT held %dms | %s hurt %d->%d | %s",
                    System.currentTimeMillis() - this.heldHitAt,
                    target == null ? "?" : target.getName(), this.heldHitHurt,
                    target == null ? -1 : target.hurtTime, reason));
        }
    }

    private void send(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK
                && mc.theWorld != null) {
            /* Sent without an event: tell the timer when it really left. */
            net.minecraft.entity.Entity attacked = ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
            myau.management.HitTimer.noteAttackSent(attacked);
            FightLog.noteAttackSent(attacked);
        }
        this.releasing = true;
        try {
            /* The release, stamped when it happens (plan step 12). */
            ActionLedger.note(this.getName(), "release");
            PacketUtil.sendPacketNoEvent(packet);
        } catch (Exception ignored) {
            /* A disconnect part-way through. The rest describes a connection
               that no longer exists. */
            this.queue.clear();
        } finally {
            this.releasing = false;
        }
    }

    /** A packet was just held: starts the hold's record if it is the first. */
    private void noteHeld() {
        if (this.episodeStart == 0L) {
            if (this.mode.getValue() != LATENCY) {
                rollDelay();
            }
            this.episodeStart = System.currentTimeMillis();
            this.episodeHeld = 0;
            this.episodePeak = 0;
            EntityPlayer target = this.currentTarget;
            this.episodeTarget = target == null ? null : target.getName();
            this.episodeDistance = target == null ? -1.0 : eyeDistance(target);
        }
        this.episodeHeld++;
        this.episodePeak = Math.max(this.episodePeak, this.queue.size());
    }

    /**
     * Reports the hold in progress, if the queue is now empty.
     *
     * Called after every release, so it is the emptying of the queue that
     * ends a hold, whichever path emptied it; a release that left packets
     * behind has not ended anything.
     */
    private void closeEpisode(String reason) {
        if (this.episodeStart == 0L || !this.queue.isEmpty()) {
            return;
        }
        long lasted = System.currentTimeMillis() - this.episodeStart;
        this.episodeStart = 0L;
        if (this.engagedWith != null) {
            this.engagedHolds++;
            this.engagedPackets += this.episodeHeld;
        }
        int where = this.log.getValue();
        if (where == 0 || this.mode.getValue() == LATENCY) {
            return;
        }
        String against = "no target";
        if (this.episodeTarget != null) {
            EntityPlayer target = this.currentTarget;
            String now = target != null && this.episodeTarget.equals(target.getName())
                    ? String.format("%.1f", eyeDistance(target)) : "?";
            against = String.format("%s %.1f->%s", this.episodeTarget, this.episodeDistance, now);
        }
        writeLog(String.format("HOLD %dp %dms peak %d delay %d | %s | %s",
                this.episodeHeld, lasted, this.episodePeak, holdDelay(), against, reason));
        if (where == 2) {
            chat(String.format("&7[&bFakeLag&7] held &f%dp&7 for &f%dms&7 (peak %d) &8| &7%s &8| &7released: &f%s",
                    this.episodeHeld, lasted, this.episodePeak, against, reason));
        }
    }

    /** One tick of the engagement report. Also records what the tick decided. */
    private void observe(EntityPlayer target, String blocked) {
        this.blockedBy = blocked;
        this.currentTarget = target;
        this.tickCount++;
        if (target == null) {
            if (this.engagedWith != null
                    && this.tickCount - this.engagedLastSeen > ENGAGEMENT_GAP_TICKS) {
                endEngagement();
            }
            return;
        }
        String name = target.getName();
        if (this.engagedWith != null && !this.engagedWith.equals(name)) {
            endEngagement();
        }
        if (this.engagedWith == null) {
            this.engagedWith = name;
            this.engagedTicks = 0;
            this.engagedOpen = 0;
            this.engagedHolds = 0;
            this.engagedPackets = 0;
            this.engagedBlocked.clear();
        }
        this.engagedLastSeen = this.tickCount;
        this.engagedTicks++;
        if (blocked == null) {
            this.engagedOpen++;
        } else {
            Integer seen = this.engagedBlocked.get(blocked);
            this.engagedBlocked.put(blocked, seen == null ? 1 : seen + 1);
        }
    }

    /** Writes the engagement in progress, most frequent reason first, and ends it. */
    private void endEngagement() {
        if (this.engagedWith == null) {
            return;
        }
        String name = this.engagedWith;
        this.engagedWith = null;
        if (this.engagedTicks < ENGAGEMENT_MIN_TICKS || this.log.getValue() == 0
                || this.mode.getValue() == LATENCY) {
            return;
        }
        StringBuilder sb = new StringBuilder(String.format(
                "ENGAGE %s %.1fs in range | holding allowed %dt | holds %d (%dp)",
                name, this.engagedTicks / 20.0, this.engagedOpen, this.engagedHolds, this.engagedPackets));
        if (!this.engagedBlocked.isEmpty()) {
            List<Map.Entry<String, Integer>> reasons =
                    new ArrayList<Map.Entry<String, Integer>>(this.engagedBlocked.entrySet());
            Collections.sort(reasons, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            sb.append(" | ruled out:");
            for (Map.Entry<String, Integer> reason : reasons) {
                sb.append(' ').append(reason.getKey()).append(' ').append(reason.getValue()).append('t');
            }
        }
        writeLog(sb.toString());
    }

    private double eyeDistance(EntityPlayer target) {
        return RotationUtil.distanceToBox(target, new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ));
    }

    /** Chat belongs to the client thread; a release can be reached from another. */
    private static void chat(final String line) {
        if (mc.isCallingFromMinecraftThread()) {
            ChatUtil.sendFormatted(line);
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                ChatUtil.sendFormatted(line);
            }
        });
    }

    /** One file per enable, appended to, like FlagDetector's and HitCheck's. */
    private void writeLog(String line) {
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "fakelag-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        /* Written on AsyncLog's thread: a file opened mid-tick is a stall. */
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @Override
    public String[] getSuffix() {
        String label = this.mode.getModeString().toLowerCase();
        int held = this.queue.size();
        String hits = this.mode.getValue() == REPEL ? this.hitsHeld + " held, " + this.hitsMerged + " merged" : null;
        if (held == 0) {
            return hits == null ? new String[]{label} : new String[]{label, hits};
        }
        return hits == null ? new String[]{label, held + "p"} : new String[]{label, held + "p", hits};
    }

    private static final class Held {
        final Packet<?> packet;
        final long stamp;

        Held(Packet<?> packet, long stamp) {
            this.packet = packet;
            this.stamp = stamp;
        }
    }
}
