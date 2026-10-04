package myau.module.modules;

import myau.util.Ping;
import myau.util.AsyncLog;
import myau.util.Attribution;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ActionLedger;
import myau.util.ChatUtil;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reports server position corrections (S08PacketPlayerPosLook) with enough
 * context to tell them apart, and attributes them to whatever the client was
 * doing at the time.
 *
 * The original version printed one line per S08 with no filtering: a respawn,
 * a pearl, a rounding sync and a real setback all looked identical, and one
 * correction repeated over several ticks looked like several violations.
 *
 * Classification, added earlier:
 *
 *   TELEPORT   - respawn, pearl, lobby send. Not a violation, off by default.
 *   RELOCATE   - the server put the player somewhere they have not been in
 *                the last ten seconds. A setback always returns the player to
 *                a position this client sent, so a target it never occupied
 *                was chosen by the server rather than refused by it: a match
 *                start moving everyone to their spawn is the case that made
 *                this necessary. Shown, never counted as a violation.
 *   VERTICAL   - correction mostly on Y with little horizontal movement,
 *                which is what fall / ground-state disagreements look like.
 *   LAGBACK    - horizontal correction. This is the movement violation.
 *   REJECT     - a block placement the server undid. Not a position packet at
 *                all, and previously invisible: a rejected placement produces
 *                no S08, so a scaffold or clutch fighting the server left no
 *                trace here even though it is the same class of problem.
 *
 * Three things make the output usable while actually playing rather than only
 * in a log read afterwards:
 *
 * BLAME. A flag on its own says the server disagreed; it does not say with
 * what. Every flag now carries a snapshot of the client state that could have
 * caused it -- how many packets a lag module was holding, how long since a
 * placement, a dig, an attack, or incoming knockback. These are recorded from
 * the packet stream rather than asked of the modules, so a module that acts
 * through some other module still shows up.
 *
 * SESSION COUNTERS. The question being asked of this module is almost always
 * comparative: is this config worse than the last one. That needs a rate, not
 * a stream of individual lines, so totals and flags-per-minute are kept and
 * reset on a server switch.
 *
 * HUD. Chat scrolls, and the flags that matter arrive during a fight, which
 * is exactly when chat is unreadable. The counters and the last few flags are
 * drawn on screen instead.
 */
public class FlagDetector extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Vanilla sprint is ~0.28 blocks/tick; anything near it is full speed. */
    private static final double SPRINT_SPEED = 0.28;

    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    public final FloatProperty minDistance = new FloatProperty("min-distance", 0.05F, 0.0F, 2.0F);
    public final FloatProperty teleportDistance = new FloatProperty("teleport-distance", 8.0F, 2.0F, 64.0F);
    public final BooleanProperty showTeleports = new BooleanProperty("show-teleports", false);
    public final BooleanProperty showContext = new BooleanProperty("context", true);
    public final BooleanProperty blame = new BooleanProperty("blame", true);
    public final BooleanProperty groupBursts = new BooleanProperty("group-bursts", true);
    public final FloatProperty burstWindow = new FloatProperty("burst-window", 1.0F, 0.2F, 5.0F);

    public final BooleanProperty detectRejects = new BooleanProperty("detect-rejects", true);
    /* Every block placement, accepted or not, one line each with what the
       server saw when it judged it, to config/Myau/places-<stamp>.txt.
       REJECTs alone could not say what refused placements have in common
       (2026-09-25: 674 of them in 15 days, no pattern), because nothing
       recorded the ones that went through. */
    public final BooleanProperty placeLog = new BooleanProperty("place-log", true, this.detectRejects::getValue);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", false);

    public final ModeProperty hud = new ModeProperty("hud", 2, new String[]{"NONE", "COMPACT", "LIST"});
    /* The module arraylist owns one top corner and grows downward as modules
       are enabled, so any fixed position on that side is eventually buried by
       it. AUTO takes the opposite corner and stays clear however long the
       list gets. */
    public final ModeProperty hudAlign = new ModeProperty("hud-align", 0,
            new String[]{"AUTO", "LEFT", "RIGHT"}, () -> this.hud.getValue() != 0);
    public final IntProperty hudX = new IntProperty("hud-x", 4, 0, 800, () -> this.hud.getValue() != 0);
    public final IntProperty hudY = new IntProperty("hud-y", 60, 0, 600, () -> this.hud.getValue() != 0);
    public final IntProperty hudLines = new IntProperty("hud-lines", 5, 1, 12, () -> this.hud.getValue() == 2);
    public final IntProperty hudFade = new IntProperty("hud-fade", 20, 3, 120, () -> this.hud.getValue() == 2);

    public final BooleanProperty logFile = new BooleanProperty("log-file", false);
    /**
     * LOW-DAMAGE: hits that land (the target flinches) but each take a small
     * part of the health hits usually take -- a server cutting damage instead
     * of refusing the hit, a "silent" flag (util/DamageWatch, 2026-09-28).
     */
    public final BooleanProperty detectLowDamage = new BooleanProperty("detect-low-damage", true);
    /* Every landed hit's damage in chat. KillAura's "Debug: Health" shows the
       health this player loses, not what its hits take off the target. */
    public final BooleanProperty damageChat = new BooleanProperty("damage-chat", false,
            this.detectLowDamage::getValue);
    /**
     * How far back a module's actions still count as coinciding with a flag.
     * One round trip plus a little: shorter and a correction caused by a packet
     * already in flight has nothing to point at, longer and every module that
     * did anything at all gets named.
     */
    public final IntProperty blameWindow = new IntProperty("blame-window", 2, 1, 10,
            this.blame::getValue);

    private int ticksSinceAttack = 999;
    private int ticksSincePlace = 999;
    private int ticksSinceDig = 999;
    private int ticksSinceKnockback = 999;

    /* Position history, one sample a tick. A correction can only be compared
       against how far the client actually travelled while the server's view
       was stale, and that distance cannot be reconstructed from the speed at
       the moment the packet lands: after knockback you are already
       decelerating, so the arrival-time speed is far below the speed that
       accumulated the divergence. Recording the path avoids the estimate
       entirely, and it needs no gravity model for the vertical axis.

       That needs one round trip. The rest -- ten seconds in all -- is for
       visited(): a setback can return the player to a position several
       seconds old, and one older than the history would be filed as a
       RELOCATE. */
    private static final int HISTORY = 200;

    /* How near a correction's target must be to a recorded position to count
       as somewhere the player has been. The samples are the positions this
       client sent, give or take the 0.03 blocks below which vanilla does not
       send one; the rest is margin. Every match-start move on record was
       0.42 blocks or more. */
    private static final double VISITED_TOLERANCE = 0.3;
    private final double[] histX = new double[HISTORY];
    private final double[] histY = new double[HISTORY];
    private final double[] histZ = new double[HISTORY];
    private int histIndex = 0;
    private int histFilled = 0;

    /* Positions this client asked the server to place a block at, with the
       tick it asked. A server that disagrees answers by setting the position
       back to air, so the pair identifies a refused placement. Bounded by age
       rather than by count so a burst of placements cannot evict the entry
       that is about to be answered. */
    private static final int REJECT_WINDOW_TICKS = 20;
    private final Map<BlockPos, Placement> pendingPlacements = new HashMap<BlockPos, Placement>();

    /**
     * A placement waiting to be confirmed, and how close anyone came to
     * standing in it.
     *
     * A refused placement has two entirely different causes that used to be
     * reported as one. Vanilla will not let a block be placed inside an
     * entity's box -- {@code canBlockBePlaced} ends in
     * {@code checkNoEntityCollision} -- and the server applies that rule to
     * where the opponent really is, not to where this client last saw them.
     * Blocking someone off at 230ms therefore gets refused routinely, by the
     * ordinary rules, with no anticheat involved and nothing to fix.
     *
     * The other cause is a placement the server rejected on its own terms,
     * which is the one worth knowing about. The two look identical in the
     * packet stream, so the distinction has to be measured: the nearest
     * opponent is sampled every tick while the placement is outstanding, and
     * the closest approach decides which of the two it was.
     */
    private static final class Placement {
        final int tick;
        double closestOpponent = Double.MAX_VALUE;
        /** The place-log line up to its outcome; null when not logging. */
        String context;
        /** The module whose click sent it, from the stack; null for a click by hand (2026-10-04). */
        String module;

        Placement(int tick) {
            this.tick = tick;
        }
    }

    /** Within this many blocks of the target, a body is a plausible explanation. */
    private static final double OCCUPIED_DISTANCE = 1.2;
    private int tickCounter = 0;

    // Pending burst being accumulated.
    private String burstLabel = null;
    private int burstCount = 0;
    private double burstMaxDistance = 0.0;
    private long burstStartedAt = 0L;
    private long burstLastAt = 0L;
    private String burstContext = "";

    // Session statistics, reset on enable and on a server switch.
    private long sessionStart;
    private int countLagback;
    private int countVertical;
    private int countTeleport;
    private int countReject;
    /** Not a violation; kept out of the rate, shown so the cause is visible. */
    private int countBlockedByBody;
    /** The server placing the player; kept out of the rate for the same reason. */
    private int countRelocated;
    /** Runs of landed hits doing a fraction of their usual damage. */
    private int countLowDamage;
    /** Runs of hits the server did not act on at all. */
    private int countHitsDropped;
    private final myau.util.DamageWatch damageWatch = new myau.util.DamageWatch();
    private boolean hiddenHealthReported;
    /* The mitigation check was active at the last look: to say so once when it starts. */
    private boolean mitigationSaid;

    /* Client faults: this client contradicting itself, with no server
       involved. Everything above is the server objecting to something; these
       are the client doing something no switched-on module asked for. They
       are kept out of the flag rate, the blame and Adaptive for the same
       reason BLOCKED-BY-BODY is -- counting them would teach the long-term
       record that a server objected when none did.

       Both exist because of bugs that ran for days unseen (BUG-AUDIT
       2026-09-23): TimerRange changing the timer while switched off, and
       Blink's drain re-queueing every packet it "released". Neither produced
       a flag of its own at the moment it went wrong -- the flags came later,
       blamed on whatever was nearby. */
    private int countClientFault;

    /** Ticks the timer must stay off 1.0 before it counts; covers a one-tick handover between owners. */
    private static final int ORPHAN_TICKS = 5;
    private int timerOffTicks;
    private boolean timerOrphanReported;

    /** A second with no progress. The drain releases at least one position a tick, so a working one never gets near this. */
    private static final int STALL_TICKS = 20;
    private int drainSmallest = Integer.MAX_VALUE;
    private int drainStallTicks;
    private boolean drainStallReported;
    private int strandedTicks;
    private boolean strandedReported;

    private final ArrayDeque<HudEntry> recent = new ArrayDeque<HudEntry>();
    private File logTarget;

    /** Ticks a correction stays attributable to the event that caused it. */
    private static final int RESPAWN_GRACE = 60;
    private static final int TELEPORT_GRACE = 20;
    private int lastRespawnTick = Integer.MIN_VALUE / 2;
    private int lastTeleportTick = Integer.MIN_VALUE / 2;
    private final Map<String, Integer> ignoredReasons = new LinkedHashMap<String, Integer>();
    private int ignoredTotal;
    /** Set while building context; read when the flag's kind is keyed. */
    private boolean lastExceededTravel;

    public FlagDetector() {
        super("FlagDetector", false, true, "Reports server position corrections with distance, context and blame");
    }

    @Override
    public void onEnabled() {
        resetBurst();
        resetSession();
        this.ticksSinceAttack = 999;
        this.ticksSincePlace = 999;
        this.ticksSinceDig = 999;
        this.ticksSinceKnockback = 999;
        this.logTarget = null;
    }

    @Override
    public void onDisabled() {
        flushBurst();
        this.recent.clear();
        this.pendingPlacements.clear();
    }

    private void resetSession() {
        this.sessionStart = System.currentTimeMillis();
        this.countLagback = 0;
        this.countVertical = 0;
        this.countTeleport = 0;
        this.countReject = 0;
        this.countBlockedByBody = 0;
        this.countRelocated = 0;
        this.countLowDamage = 0;
        this.countHitsDropped = 0;
        this.countClientFault = 0;
        this.timerOffTicks = 0;
        this.timerOrphanReported = false;
        this.drainSmallest = Integer.MAX_VALUE;
        this.drainStallTicks = 0;
        this.drainStallReported = false;
        this.strandedTicks = 0;
        this.strandedReported = false;
        this.recent.clear();
        this.pendingPlacements.clear();
        this.ignoredReasons.clear();
        this.ignoredTotal = 0;
    }

    private void resetBurst() {
        this.burstLabel = null;
        this.burstCount = 0;
        this.burstMaxDistance = 0.0;
        this.burstStartedAt = 0L;
        this.burstLastAt = 0L;
        this.burstContext = "";
        this.burstAttribution = null;
    }

    private int total() {
        return this.countLagback + this.countVertical + this.countTeleport + this.countReject + this.countLowDamage
                + this.countHitsDropped;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        this.tickCounter++;
        if (this.ticksSinceAttack < 999) {
            this.ticksSinceAttack++;
        }
        if (this.ticksSincePlace < 999) {
            this.ticksSincePlace++;
        }
        if (this.ticksSinceDig < 999) {
            this.ticksSinceDig++;
        }
        if (this.ticksSinceKnockback < 999) {
            this.ticksSinceKnockback++;
        }
        watchDamage();
        if (mc.thePlayer != null) {
            histX[histIndex] = mc.thePlayer.posX;
            histY[histIndex] = mc.thePlayer.posY;
            histZ[histIndex] = mc.thePlayer.posZ;
            histIndex = (histIndex + 1) % HISTORY;
            if (histFilled < HISTORY) {
                histFilled++;
            }
        }

        checkTimerOrphan();
        checkDrainStall();

        Iterator<Map.Entry<BlockPos, Placement>> it = this.pendingPlacements.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Placement> entry = it.next();
            if (this.tickCounter - entry.getValue().tick > REJECT_WINDOW_TICKS) {
                logPlacement(entry.getValue(), "OK");
                it.remove();
                continue;
            }
            /* Sampled here rather than when the refusal arrives: by then the
               opponent has had a round trip to walk out of the space they were
               standing in, which is exactly the evidence being looked for. On
               the client thread, where walking the entity list is safe. */
            trackOccupancy(entry.getKey(), entry.getValue());
        }

        if (burstCount > 0 && System.currentTimeMillis() - burstLastAt >= (long) (burstWindow.getValue() * 1000.0F)) {
            flushBurst();
        }
    }

    /**
     * TIMER-ORPHAN: the game clock is not at 1.0 and nothing that is switched
     * on is allowed to have changed it.
     *
     * The owners are named by class literal, not by string, so renaming one is
     * a compile error rather than a list entry that silently matches nothing
     * (NOTES 4.5). Reported once per episode; a new episode starts when the
     * timer returns to 1.0.
     */
    private void checkTimerOrphan() {
        float speed;
        try {
            speed = ((myau.mixin.IAccessorMinecraft) mc).getTimer().timerSpeed;
        } catch (Exception ignored) {
            return;
        }
        if (Math.abs(speed - 1.0F) < 0.001F
                || anyEnabled(Timer.class, TimerRange.class, NoFall.class)) {
            this.timerOffTicks = 0;
            this.timerOrphanReported = false;
            return;
        }
        this.timerOffTicks++;
        if (this.timerOrphanReported || this.timerOffTicks < ORPHAN_TICKS) {
            return;
        }
        this.timerOrphanReported = true;
        emit("TIMER-ORPHAN", 1, 0.0, String.format(
                " &8| &7timer &f%.2f&7 for &f%d&7t with Timer, TimerRange and NoFall all off",
                speed, this.timerOffTicks));
    }

    /**
     * DRAIN-STALL: a queue that should be emptying is not.
     *
     * Two shapes. Blink in its release phase whose queue has not reached a new
     * low for a second -- a working drain sends more positions than the game
     * produces, so its queue only ever shrinks. And packets left in the blink
     * queue with nothing blinking: they are sent at whichever release happens
     * next, by whichever module, describing a moment long gone.
     */
    private void checkDrainStall() {
        if (Myau.blinkManager == null) {
            return;
        }
        int size = Myau.blinkManager.blinkedPackets.size();

        Module module = Myau.moduleManager.modules.get(Blink.class);
        boolean releasing = module instanceof Blink && module.isEnabled() && ((Blink) module).isReleasing();
        if (!releasing) {
            this.drainSmallest = Integer.MAX_VALUE;
            this.drainStallTicks = 0;
            this.drainStallReported = false;
        } else if (size < this.drainSmallest) {
            this.drainSmallest = size;
            this.drainStallTicks = 0;
        } else {
            this.drainStallTicks++;
            if (!this.drainStallReported && this.drainStallTicks >= STALL_TICKS) {
                this.drainStallReported = true;
                emit("DRAIN-STALL", 1, 0.0, String.format(
                        " &8| &7Blink releasing, queue &f%d&7 (low &f%d&7), no progress for &f%d&7t",
                        size, this.drainSmallest, this.drainStallTicks));
            }
        }

        if (size == 0 || Myau.blinkManager.isBlinking()) {
            this.strandedTicks = 0;
            this.strandedReported = false;
            return;
        }
        this.strandedTicks++;
        if (!this.strandedReported && this.strandedTicks >= STALL_TICKS) {
            this.strandedReported = true;
            emit("DRAIN-STALL", 1, 0.0, String.format(
                    " &8| &f%d&7 packets stranded in the blink queue with nothing blinking &8(owner %s)",
                    size, Myau.blinkManager.getBlinkingModule()));
        }
    }

    private static boolean anyEnabled(Class<?>... types) {
        for (Class<?> type : types) {
            Module module = Myau.moduleManager.modules.get(type);
            if (module != null && module.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof net.minecraft.network.play.client.C03PacketPlayer) {
                net.minecraft.network.play.client.C03PacketPlayer c03 =
                        (net.minecraft.network.play.client.C03PacketPlayer) event.getPacket();
                if (c03.isMoving()) {
                    this.sentX = c03.getPositionX();
                    this.sentY = c03.getPositionY();
                    this.sentZ = c03.getPositionZ();
                }
                if (c03.getRotating()) {
                    this.sentYawBefore = this.sentYaw;
                    this.sentPitchBefore = this.sentPitch;
                    this.sentYaw = c03.getYaw();
                    this.sentPitch = c03.getPitch();
                }
                this.sentGround = c03.isOnGround();
                this.sentTick = this.tickCounter;
                return;
            }
            if (event.getPacket() instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
                this.ticksSinceAttack = 0;
                if (this.detectLowDamage.getValue() && !event.isCancelled() && mc.theWorld != null) {
                    net.minecraft.entity.Entity attacked =
                            ((C02PacketUseEntity) event.getPacket()).getEntityFromWorld(mc.theWorld);
                    if (attacked instanceof net.minecraft.entity.EntityLivingBase && attacked != mc.thePlayer) {
                        this.damageWatch.attack(attacked.getEntityId(), System.currentTimeMillis(),
                                leastDamage((net.minecraft.entity.EntityLivingBase) attacked));
                    }
                }
            } else if (event.getPacket() instanceof C07PacketPlayerDigging) {
                this.ticksSinceDig = 0;
                /* Breaking a block this client placed produces the same air
                   update from the server as a refused placement. Dropping the
                   pending entry keeps the two apart -- in a game spent
                   bridging and re-breaking, they would otherwise be the
                   majority of what gets reported. */
                C07PacketPlayerDigging dig = (C07PacketPlayerDigging) event.getPacket();
                if (dig.getPosition() != null) {
                    Placement dug = this.pendingPlacements.remove(dig.getPosition());
                    logPlacement(dug, "DUG");
                }
            } else if (event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
                C08PacketPlayerBlockPlacement place = (C08PacketPlayerBlockPlacement) event.getPacket();
                /* This packet is sent for every right-click against a block,
                   not only for placements: opening a chest, using a bucket,
                   striking a bed, right-clicking a wall with a sword. Direction
                   255 removes only the clicks that hit no block at all, so on
                   its own it leaves nearly every right-click looking like a
                   placement awaiting confirmation. What distinguishes a real
                   one is the item: nothing can be placed without a block in
                   hand. */
                ItemStack held = place.getStack();
                boolean placingBlock = held != null && held.getItem() instanceof ItemBlock;
                if (place.getPlacedBlockDirection() != 255 && place.getPosition() != null
                        && placingBlock) {
                    this.ticksSincePlace = 0;
                    if (this.detectRejects.getValue()) {
                        BlockPos target = place.getPosition().offset(
                                net.minecraft.util.EnumFacing.getFront(place.getPlacedBlockDirection()));
                        Placement placement = new Placement(this.tickCounter);
                        placement.module = ActionLedger.callerModuleExcept("FlagDetector");
                        if (this.placeLog.getValue()) {
                            placement.context = placementContext(place, target);
                        }
                        this.lastPlaceTick = this.tickCounter;
                        this.pendingPlacements.put(target, placement);
                    }
                }
            }
            return;
        }

        /* Everything below used to run right here, on the network thread:
           pendingPlacements (a HashMap the tick iterates and prunes) had
           entries removed underneath that iteration, implicated() wrote the
           map FlagResponder reads on its tick, and the reports touched chat,
           the HUD list and the log file. It is now handed to the client
           thread. Scheduled here, it still runs ahead of vanilla's own
           handling of the same packet -- which handlePosLook depends on, since
           it compares the correction with where the player is before the
           correction is applied. */
        final net.minecraft.network.Packet<?> received = event.getPacket();
        if (!(received instanceof S01PacketJoinGame || received instanceof S07PacketRespawn
                || received instanceof S12PacketEntityVelocity || received instanceof S23PacketBlockChange
                || received instanceof S08PacketPlayerPosLook)) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (isEnabled() && mc.thePlayer != null) {
                    handleReceived(received);
                }
            }
        });
    }

    private void handleReceived(net.minecraft.network.Packet<?> packet) {
        if (packet instanceof S01PacketJoinGame) {
            resetSession();
            this.lastRespawnTick = this.tickCounter;
            return;
        }

        if (packet instanceof S07PacketRespawn) {
            /* The respawn itself, and the world change a lobby send produces.
               Both are followed by the server placing the player. */
            this.lastRespawnTick = this.tickCounter;
            flushBurst();
            return;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            if (((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
                this.ticksSinceKnockback = 0;
            }
            return;
        }

        if (packet instanceof S23PacketBlockChange) {
            handleBlockChange((S23PacketBlockChange) packet);
            return;
        }

        if (!(packet instanceof S08PacketPlayerPosLook)) {
            return;
        }
        handlePosLook((S08PacketPlayerPosLook) packet);
    }

    /** Closest any other player's body came to the block being placed. */
    private void trackOccupancy(BlockPos pos, Placement placement) {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        double centreX = pos.getX() + 0.5;
        double centreY = pos.getY() + 0.5;
        double centreZ = pos.getZ() + 0.5;
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof net.minecraft.entity.player.EntityPlayer)) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) object;
            if (player == mc.thePlayer) {
                continue;
            }
            /* Vertical distance is measured against the body, not the feet: a
               player standing on the block below occupies the space a block
               would go into. */
            double dx = player.posX - centreX;
            double dz = player.posZ - centreZ;
            double dy = 0.0;
            if (centreY < player.posY) {
                dy = player.posY - centreY;
            } else if (centreY > player.posY + player.height) {
                dy = centreY - (player.posY + player.height);
            }
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < placement.closestOpponent) {
                placement.closestOpponent = distance;
            }
        }
    }

    /**
     * A placement the server answered with air at the same position is a
     * placement it refused. Anything else it might set there is a normal
     * world update and is ignored.
     */
    private void handleBlockChange(S23PacketBlockChange packet) {
        if (!this.detectRejects.getValue() || this.pendingPlacements.isEmpty()) {
            return;
        }
        BlockPos pos = packet.getBlockPosition();
        Placement placement = pos == null ? null : this.pendingPlacements.remove(pos);
        if (placement == null) {
            return;
        }
        if (packet.getBlockState() == null || packet.getBlockState().getBlock() != Blocks.air) {
            logPlacement(placement, "OK");
            return;
        }
        /* The client places its own block immediately and waits to be
           contradicted, so a refusal is only a refusal if there is something
           there to take back. Where the click never produced a block -- it
           opened a container, or the placement was against a face the client
           itself rejected -- the position is already air and the server saying
           so again changes nothing. */
        if (mc.theWorld == null || mc.theWorld.getBlockState(pos).getBlock() == Blocks.air) {
            logPlacement(placement, "NO-BLOCK");
            return;
        }
        /* Someone was standing where the block was going. Vanilla refuses
           that, the server judges it against where they actually are, and at
           this latency the client cannot know. Reported under its own name so
           it stops being counted against the client's behaviour. */
        boolean occupied = placement.closestOpponent <= OCCUPIED_DISTANCE;
        logPlacement(placement, occupied ? "BODY" : "REJECT");
        String where = " &8| &7at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (placement.closestOpponent < Double.MAX_VALUE) {
            where += String.format(" &8| &7body %.2f", placement.closestOpponent);
        }
        /* The refused block is known, and so is whose click sent it: that is
           the cause, not a guess from what else happened in the window
           (2026-10-04; the window capped a placing module at 52%, "unknown
           48%"). A click by hand still goes through the window. */
        Attribution.Result attribution = placement.module != null
                ? Attribution.direct(placement.module, "placed-this-block")
                : attributeNow();
        String context = this.showContext.getValue() ? where + blameSuffix(attribution) : "";
        this.emitAttribution = attribution;
        emit(occupied ? "BLOCKED-BY-BODY" : "REJECT", 1, 0.0, context);
    }

    /**
     * States in which a position correction says nothing about this client.
     *
     * A correction is evidence only when the server and the client were both
     * simulating the same moving player. Several ordinary states break that,
     * and each of them produces corrections continuously rather than once:
     *
     *   SPECTATING. The server moves a spectator directly and repeatedly --
     *   anchoring them on whoever they follow, passing them through blocks --
     *   and every one of those arrives as the same packet a violation would.
     *   Watching the end of a round therefore filled the log with flags for
     *   movement the client never made.
     *
     *   DEAD, OR JUST ALIVE AGAIN. The death screen and the respawn are both
     *   delivered as position packets, and the seconds after a respawn are the
     *   server placing the player rather than disagreeing with them.
     *
     *   RIDING, OR FLYING. A vehicle's position is the server's to decide, and
     *   a flying player is not being checked against the movement rules that
     *   produce a correction in the first place.
     *
     *   SETTLING AFTER A TELEPORT. A deliberate move is followed by a short
     *   train of smaller corrections while the two sides agree on where the
     *   player landed. Those belong to the teleport, not to what came next.
     */
    private String ignoreReason() {
        if (mc.thePlayer.isSpectator()) {
            return "spectator";
        }
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F) {
            return "dead";
        }
        if (this.tickCounter - this.lastRespawnTick < RESPAWN_GRACE) {
            return "respawn";
        }
        if (mc.thePlayer.ridingEntity != null) {
            return "riding";
        }
        if (mc.thePlayer.capabilities != null && mc.thePlayer.capabilities.isFlying) {
            return "flying";
        }
        if (this.tickCounter - this.lastTeleportTick < TELEPORT_GRACE) {
            return "post-teleport";
        }
        return null;
    }

    /** What the guards suppressed, so a quiet log can be told from a gagged one. */
    /** Violations only -- teleports and body-blocked placements excluded. */
    public int violationCount() {
        return this.countLagback + this.countVertical + this.countReject;
    }

    /** Milliseconds since the counters were last reset. */
    public long sessionMillis() {
        return System.currentTimeMillis() - this.sessionStart;
    }

    /** Starts a fresh measurement period; used by whatever is comparing configs. */
    public void resetCounters() {
        resetSession();
    }

    public String ignoredSummary() {
        if (this.ignoredTotal <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : this.ignoredReasons.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    private void handlePosLook(S08PacketPlayerPosLook packet) {
        String ignored = this.ignoreReason();
        if (ignored != null) {
            Integer seen = this.ignoredReasons.get(ignored);
            this.ignoredReasons.put(ignored, seen == null ? 1 : seen + 1);
            this.ignoredTotal++;
            /* Any burst in progress is closed rather than left open: the next
               real correction is a separate event, not a continuation of one
               interrupted by a death. */
            flushBurst();
            return;
        }
        /* Fields flagged relative carry a delta; the rest carry an absolute
           target. Treating a relative packet as absolute would report a
           displacement the size of the player's world coordinates. */
        Set<S08PacketPlayerPosLook.EnumFlags> flags = packet.func_179834_f();
        boolean relX = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.X);
        boolean relY = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Y);
        boolean relZ = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Z);
        double dx = relX ? packet.getX() : packet.getX() - mc.thePlayer.posX;
        double dy = relY ? packet.getY() : packet.getY() - mc.thePlayer.posY;
        double dz = relZ ? packet.getZ() : packet.getZ() - mc.thePlayer.posZ;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        String label;
        if (distance >= teleportDistance.getValue()) {
            /* Recorded whether or not teleports are shown: the grace period it
               opens is about what follows, not about reporting this one. */
            this.lastTeleportTick = this.tickCounter;
            if (!showTeleports.getValue()) {
                return;
            }
            label = "TELEPORT";
        } else if (distance < minDistance.getValue()) {
            return;
        } else if (!visited(mc.thePlayer.posX + dx, mc.thePlayer.posY + dy, mc.thePlayer.posZ + dz)) {
            /* Pika moves every player to their spawn two to three seconds
               after "Game starts in 1...", a few blocks from where they stood
               in the cage. Under teleport-distance, it used to be filed as a
               LAGBACK -- all six standing-still, purely horizontal LAGBACKs
               in the logs of 09-23 and 09-24 were exactly this -- and fed to
               the rate, the Brain and Adaptive as a violation. Like a
               teleport, it opens the settling grace for what follows. */
            this.lastTeleportTick = this.tickCounter;
            label = "RELOCATE";
        } else if (Math.abs(dy) > horizontal * 2.0 && horizontal < 0.3) {
            label = "VERTICAL";
        } else {
            label = "LAGBACK";
        }

        /* Weighed now, at the correction -- not when a burst is reported,
           which can be a second later, with a different window of actions. */
        Attribution.Result attribution = attributeNow();
        String context = showContext.getValue() ? buildContext(horizontal, Math.abs(dy), attribution) : "";

        if (!groupBursts.getValue()) {
            this.emitAttribution = attribution;
            emit(label, 1, distance, context);
            return;
        }

        long now = System.currentTimeMillis();
        if (burstCount > 0 && !label.equals(burstLabel)) {
            flushBurst();
        }
        if (burstCount == 0) {
            burstLabel = label;
            burstStartedAt = now;
            burstMaxDistance = 0.0;
            burstContext = context;
            burstAttribution = attribution;
        }
        burstCount++;
        burstLastAt = now;
        if (distance > burstMaxDistance) {
            burstMaxDistance = distance;
            burstContext = context;
            burstAttribution = attribution;
        }
    }

    /**
     * Whether the player has stood within VISITED_TOLERANCE of this point in
     * the last HISTORY ticks, or stands there now.
     *
     * The current position counts because the newest one this client sent is
     * not in the history until the next tick samples it, and it is the one a
     * setback most often returns to. With no history at all nothing can be
     * said, and the answer is yes: the correction keeps the kind it would
     * have had before this check existed.
     */
    private boolean visited(double x, double y, double z) {
        if (this.histFilled == 0) {
            return true;
        }
        double limit = VISITED_TOLERANCE * VISITED_TOLERANCE;
        if (squaredDistance(mc.thePlayer.posX - x, mc.thePlayer.posY - y, mc.thePlayer.posZ - z) <= limit) {
            return true;
        }
        /* The ring fills from index 0, so while it is not yet full the filled
           entries are exactly the first histFilled. */
        for (int i = 0; i < this.histFilled; i++) {
            if (squaredDistance(histX[i] - x, histY[i] - y, histZ[i] - z) <= limit) {
                return true;
            }
        }
        return false;
    }

    private static double squaredDistance(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    private int ping() {
        return Ping.own();
    }

    /* Which modules were implicated, and when. The chat suffix says the same
       thing in prose, but prose cannot be counted: anything that wants to act
       on a repeated cause -- rather than print it and hope somebody notices --
       needs the module named and the occurrence timestamped. */
    private final Map<String, ArrayDeque<Long>> implicated = new HashMap<String, ArrayDeque<Long>>();

    /** How many flags named this module in the last {@code millis}. */
    /** Every module that has been implicated at all, for a caller to filter. */
    public java.util.Set<String> implicatedNames() {
        return new java.util.HashSet<String>(this.implicated.keySet());
    }

    /**
     * Blame is about one connection (F-12). resetSession runs on every join,
     * proxy hops included, but never cleared this, so FlagResponder could act
     * on the last server's corrections on the next one. Cleared on every new
     * session, whether or not this module is on.
     */
    @EventTarget(whenDisabled = true)
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() == myau.events.SessionEvent.Type.START) {
            this.implicated.clear();
            /* Damage and whether health is shown are the server's. */
            this.damageWatch.reset();
            this.hiddenHealthReported = false;
        }
    }

    public int implicationsWithin(String module, long millis) {
        ArrayDeque<Long> stamps = this.implicated.get(module);
        if (stamps == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - millis;
        while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
            stamps.removeFirst();
        }
        return stamps.size();
    }

    public void clearImplications(String module) {
        this.implicated.remove(module);
    }

    private void implicate(String module) {
        ArrayDeque<Long> stamps = this.implicated.get(module);
        if (stamps == null) {
            stamps = new ArrayDeque<Long>();
            this.implicated.put(module, stamps);
        }
        stamps.addLast(System.currentTimeMillis());
        while (stamps.size() > 64) {
            stamps.removeFirst();
        }
    }

    /** The attribution for the last violation, for diagnostics. */
    private volatile Attribution.Result lastAttribution;
    /* Set just before emit, taken by it: the attribution that goes with the
       flag being emitted (a burst's is the one of its largest correction). */
    private Attribution.Result emitAttribution;
    private Attribution.Result burstAttribution;

    /** Weighs what every module did in the blame window before now (Attribution). */
    /**
     * Once a tick: each target attacked lately, its health and flinch, to
     * see what each landed hit took off it (util/DamageWatch).
     */
    private void watchDamage() {
        if (!this.detectLowDamage.getValue() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        /* A flinch answers an attack within a round trip and a little. */
        long window = Math.max(0, Ping.own()) + 350L;
        for (Integer id : this.damageWatch.watched()) {
            net.minecraft.entity.Entity entity = mc.theWorld.getEntityByID(id);
            if (!(entity instanceof net.minecraft.entity.EntityLivingBase)) {
                continue;
            }
            net.minecraft.entity.EntityLivingBase living = (net.minecraft.entity.EntityLivingBase) entity;
            /* HealthUtil, not getHealth(): minigame servers often never send
               other players' health in metadata, or send it once; the tab
               list or the objective under the name is what they keep current. */
            this.damageWatch.setPrecision(
                    myau.util.HealthUtil.SOURCE_ENTITY.equals(myau.util.HealthUtil.source(living)) ? 0.0F : 1.0F);
            myau.util.DamageWatch.Hit hit = this.damageWatch.tick(id,
                    myau.util.HealthUtil.resolve(living) + living.getAbsorptionAmount(), living.hurtTime, now, window);
            if (hit == null) {
                continue;
            }
            if (this.damageChat.getValue()) {
                ChatUtil.sendFormatted(String.format("&7[&cFlagDetector&7] &7dealt &f%.1f&7 to &f%s%s%s",
                        hit.dealt, living.getName(),
                        Float.isNaN(hit.usual) ? "" : String.format(" &8(usual %.1f)", hit.usual),
                        Float.isNaN(hit.expected) ? "" : String.format(" &8(vanilla at least %.1f)", hit.expected)));
            }
            myau.util.DamageWatch.Mitigation mitigation = this.damageWatch.mitigation(now, MITIGATION_SHOW_MS);
            if (mitigation.active && !this.mitigationSaid && this.chat.getValue()) {
                ChatUtil.sendFormatted(String.format(
                        "&7[&cFlagDetector&7] &cmitigation?&7 %d of the last %d hits took &f%.1f&7, vanilla at least &f%.1f&7 (target %s, health from %s)",
                        mitigation.low, mitigation.of, mitigation.dealt, mitigation.expected, living.getName(),
                        myau.util.HealthUtil.source(living)));
            }
            this.mitigationSaid = mitigation.active;
            myau.util.DamageWatch.Verdict verdict = this.damageWatch.verdict(hit);
            if (verdict != null) {
                Attribution.Result attribution = attributeNow();
                String context = String.format(" &8| &7dealt &f%.2f&7 a hit, usual &f%.2f&7 &8| &7target %s &8| &7health from %s &8| &7ping %dms",
                        verdict.dealt, verdict.usual, living.getName(), myau.util.HealthUtil.source(living), Ping.own());
                if (this.showContext.getValue()) {
                    context += blameSuffix(attribution);
                }
                this.emitAttribution = attribution;
                emit("LOW-DAMAGE", verdict.hits, 0.0, context);
            }
        }
        if (this.damageWatch.healthHidden() && !this.hiddenHealthReported) {
            this.hiddenHealthReported = true;
            ChatUtil.sendFormatted("&7[&cFlagDetector&7] &8low-damage check off here: this server does not show"
                    + " other players' health");
        }
        this.damageWatch.forget(now, 5000L);
    }

    /**
     * HitCheck's verdict on one swing: landed, or dropped -- in range, in
     * sight, out of the invulnerability window, and still no reaction from
     * the server at all. A run of dropped ones is the other shape a "silent"
     * flag takes: the hits are discarded instead of weakened.
     */
    public void noteHitResult(boolean dropped, String target, double distance) {
        if (!this.isEnabled() || !this.detectLowDamage.getValue()) {
            return;
        }
        if (!dropped) {
            this.damageWatch.landed();
            return;
        }
        int run = this.damageWatch.dropped(System.currentTimeMillis());
        if (run > 0) {
            Attribution.Result attribution = attributeNow();
            String context = String.format(" &8| &f%d&7 hits in a row with no reaction &8| &7target %s &8| &7dist %.2f &8| &7ping %dms",
                    run, target, distance, Ping.own());
            if (this.showContext.getValue()) {
                context += blameSuffix(attribution);
            }
            this.emitAttribution = attribution;
            emit("HITS-DROPPED", run, 0.0, context);
        }
    }

    /** How long after its last hit a mitigation is still shown. */
    public static final long MITIGATION_SHOW_MS = 20000L;

    /**
     * The least vanilla 1.8 lets a hit from this client take off this target
     * right now (util/DamageModel): the held weapon, sharpness, strength and
     * weakness, the target's armour and protection -- never a critical, so
     * that a crit the server did not grant cannot make a fair hit look low.
     * NaN when it cannot be known (the target is not a player).
     */
    private float leastDamage(net.minecraft.entity.EntityLivingBase target) {
        if (!(target instanceof net.minecraft.entity.player.EntityPlayer) || mc.thePlayer == null) {
            return Float.NaN;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        float weapon = 0.0F;
        int sharpness = 0;
        if (held != null) {
            for (net.minecraft.entity.ai.attributes.AttributeModifier modifier : held.getAttributeModifiers()
                    .get(net.minecraft.entity.SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName())) {
                if (modifier.getOperation() == 0) {
                    weapon += (float) modifier.getAmount();
                }
            }
            sharpness = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.enchantment.Enchantment.sharpness.effectId, held);
        }
        net.minecraft.potion.PotionEffect strength = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.damageBoost);
        net.minecraft.potion.PotionEffect weakness = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.weakness);
        int protection = 0;
        for (ItemStack armour : ((net.minecraft.entity.player.EntityPlayer) target).inventory.armorInventory) {
            if (armour != null) {
                protection += myau.util.DamageModel.protectionPoints(net.minecraft.enchantment.EnchantmentHelper
                        .getEnchantmentLevel(net.minecraft.enchantment.Enchantment.protection.effectId, armour));
            }
        }
        return myau.util.DamageModel.expected(weapon, sharpness,
                strength == null ? 0 : strength.getAmplifier() + 1, weakness == null ? 0 : weakness.getAmplifier() + 1,
                false, target.getTotalArmorValue(), protection)[0];
    }

    /** The mitigation check now (for the HUD); inactive when low-damage detection is off. */
    public myau.util.DamageWatch.Mitigation mitigation() {
        return this.damageWatch.mitigation(System.currentTimeMillis(), MITIGATION_SHOW_MS);
    }

    /** The current run of hits the server ignored outright. */
    public int droppedRun() {
        return this.damageWatch.droppedRun(System.currentTimeMillis());
    }

    private Attribution.Result attributeNow() {
        long window = this.blameWindow.getValue() * 1000L;
        return Attribution.attribute(ActionLedger.records(window), System.currentTimeMillis(), window);
    }

    public Attribution.Result lastAttribution() {
        return this.lastAttribution;
    }

    /**
     * Names the culprit for FlagResponder -- only when the evidence names one
     * (plan step 12). This used to name every module that did anything in the
     * window, once each, plus a list of modules this class knew by name if
     * they were merely switched on; FlagResponder then switched off whichever
     * collected the most names, which is whoever is busiest, not whoever
     * causes corrections. The ledger now carries everything that list did
     * (lag holds, knockback holds, Clutch and Scaffold placements, BedNuker
     * digs), weighed by kind and time. Two modules close together, or nothing
     * strong, names nobody.
     */
    private void recordSuspects(Attribution.Result attribution) {
        if (attribution == null) {
            return;
        }
        this.lastAttribution = attribution;
        String culprit = attribution.culprit();
        if (culprit != null) {
            implicate(culprit);
        }
    }

    /**
     * What the client was doing when the flag arrived.
     *
     * Read from the packet stream rather than from the modules themselves, so
     * a module acting indirectly -- Backtrack driving LagRange, Clutch
     * placing through the same path as Scaffold -- is still visible. The lag
     * queue is the one signal that names a culprit outright: nothing else in
     * the client holds outgoing packets.
     */
    private String blameSuffix(Attribution.Result attribution) {
        if (!this.blame.getValue()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        /* Everything held right now, by every holder (PacketHolds, plan step
           13). This used to know two of the seven holders by name. */
        String holds = myau.management.PacketHolds.describe();
        if (!holds.isEmpty()) {
            sb.append(" &8| &cholding ").append(holds).append("&7");
        }
        /* A hold its holder never ended, ended by its lease shortly before:
           the packets arrived at the server late and all at once. */
        myau.management.PacketHolds.Expiry expiry = myau.management.PacketHolds.lastExpiry();
        if (expiry != null && System.currentTimeMillis() - expiry.at <= 3000L) {
            sb.append(" &8| &clease ended ").append(System.currentTimeMillis() - expiry.at)
                    .append("ms ago: ").append(expiry).append("&7");
        }
        if (this.ticksSincePlace <= 10) {
            sb.append(" &8| &7placed ").append(this.ticksSincePlace).append("t ago");
        }
        if (this.ticksSinceDig <= 10) {
            sb.append(" &8| &7dug ").append(this.ticksSinceDig).append("t ago");
        }
        if (this.ticksSinceKnockback <= 10) {
            sb.append(" &8| &7kb ").append(this.ticksSinceKnockback).append("t ago");
        }
        /* Who did what in the moments before this, weighed (Attribution):
           the verdict first, then the strongest candidates with their share
           and the actions behind it, then the share left unexplained. */
        if (attribution != null && attribution.verdict != Attribution.Verdict.UNKNOWN) {
            sb.append(" &8| &e").append(attribution.describe(3)).append("&7");
        }
        return sb.toString();
    }

    private String buildContext(double horizontalPush, double verticalPush, Attribution.Result attribution) {
        /* Cleared per flag. Left standing, a correction with no travel data --
           no ping reading, or too little history -- inherited the previous
           flag's verdict and was filed under a kind it had not earned. */
        this.lastExceededTravel = false;
        StringBuilder sb = new StringBuilder();
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX
                + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
        sb.append(" &8| &7speed ").append(round(speed)).append("/t");

        /* Sprint speed is only a meaningful ceiling on the ground. Knockback
           routinely throws you faster than that through the air, so calling
           it "over sprint" while airborne would flag every hit you take. */
        if (speed > SPRINT_SPEED && mc.thePlayer.onGround) {
            sb.append(" &c(over sprint)&7");
        }
        sb.append(mc.thePlayer.isSprinting() ? " &8| &7sprint" : " &8| &7walk");
        sb.append(mc.thePlayer.onGround ? " &8| &7ground" : " &8| &7air");
        sb.append(" &8| &7push h").append(round(horizontalPush)).append(" v").append(round(verticalPush));

        /* Measured, not estimated: the distance actually covered while the
           server's view was one round trip old. A correction no larger than
           that is the server answering a position already superseded, which
           is latency rather than a rejected move. Both axes are compared
           separately because a fall diverges vertically far faster than
           horizontally. */
        int ping = ping();
        if (ping > 0) {
            int ticks = (int) Math.ceil(ping / 50.0);
            if (ticks < 1) {
                ticks = 1;
            } else if (ticks > HISTORY - 1) {
                ticks = HISTORY - 1;
            }
            sb.append(" &8| &7ping ").append(ping).append("ms");

            if (histFilled > ticks) {
                /* Path length, not displacement. A player knocked back and
                   running in again ends up near where they started, so the
                   straight line between the two samples understates how far
                   they actually went -- and understating travel is what makes
                   an ordinary correction look like it exceeded it. Summing the
                   per-tick steps measures the path the server was actually
                   sent. */
                double travelH = 0.0;
                double travelV = 0.0;
                for (int step = ticks; step > 0; step--) {
                    int from = ((histIndex - 1 - step) % HISTORY + HISTORY) % HISTORY;
                    int to = ((histIndex - step) % HISTORY + HISTORY) % HISTORY;
                    double stepX = histX[to] - histX[from];
                    double stepZ = histZ[to] - histZ[from];
                    travelH += Math.sqrt(stepX * stepX + stepZ * stepZ);
                    travelV += Math.abs(histY[to] - histY[from]);
                }
                sb.append(" &8| &7moved h").append(round(travelH))
                        .append(" v").append(round(travelV))
                        .append(" in ").append(ticks).append("t");

                // Small absolute allowance so a near-stationary player is not
                // flagged by sub-block rounding.
                boolean withinH = horizontalPush <= travelH * 1.2 + 0.2;
                boolean withinV = verticalPush <= travelV * 1.2 + 0.2;
                this.lastExceededTravel = !(withinH && withinV);
                sb.append(withinH && withinV ? " &a(latency)&7" : " &c(exceeds travel)&7");
            }
        }

        if (ticksSinceAttack <= 20) {
            sb.append(" &8| &c").append(ticksSinceAttack).append("t after hit");
        }
        return sb.append(blameSuffix(attribution)).toString();
    }

    private void flushBurst() {
        if (burstCount <= 0) {
            return;
        }
        /* The burst is cleared before it is reported, not after. Reporting
           touches chat, a sound handler, the HUD list and a file, and anything
           that throws in there used to leave the counter standing -- so the
           next packet flushed the same burst again, and the one after that,
           for as long as the failure lasted. Taking the state first means a
           failed report costs one line, not an unbounded stream of them. */
        String label = burstLabel;
        int count = burstCount;
        double distance = burstMaxDistance;
        String context = burstContext;
        Attribution.Result attribution = burstAttribution;
        long span = Math.max(1L, burstLastAt - burstStartedAt);
        resetBurst();
        this.emitAttribution = attribution;
        emit(label, count, distance, context, span);
    }

    /** Counts the flag, then routes it to chat, the HUD, a sound and a file. */
    private void emit(String label, int count, double distance, String context) {
        emit(label, count, distance, context, 1L);
    }

    private static boolean isViolation(String label) {
        /* A block refused because a body was in the way is the game's own rule
           being applied to a position this client could not have known. It is
           reported so the cause is visible, but it is not evidence of anything
           and must not reach the counters, the rate, the blame or the alert --
           counting it would make ordinary blocking-off at high latency look
           like a client that keeps being caught. */
        return !"BLOCKED-BY-BODY".equals(label) && !"TELEPORT".equals(label)
                && !"RELOCATE".equals(label) && !isClientFault(label);
    }

    /** The client contradicting itself; see countClientFault. */
    private static boolean isClientFault(String label) {
        return "TIMER-ORPHAN".equals(label) || "DRAIN-STALL".equals(label);
    }

    private void emit(String label, int count, double distance, String context, long span) {
        Attribution.Result attribution = this.emitAttribution;
        this.emitAttribution = null;
        if ("BLOCKED-BY-BODY".equals(label)) {
            this.countBlockedByBody += count;
        } else if (isClientFault(label)) {
            /* Before the else below, which would otherwise file these as
               REJECTs and put them in the flag rate. */
            this.countClientFault += count;
        } else if ("LAGBACK".equals(label)) {
            this.countLagback += count;
        } else if ("VERTICAL".equals(label)) {
            this.countVertical += count;
        } else if ("TELEPORT".equals(label)) {
            this.countTeleport += count;
        } else if ("RELOCATE".equals(label)) {
            /* Before the else below, which files anything it does not name as
               a REJECT. */
            this.countRelocated += count;
        } else if ("LOW-DAMAGE".equals(label)) {
            this.countLowDamage += count;
        } else if ("HITS-DROPPED".equals(label)) {
            this.countHitsDropped += count;
        } else {
            this.countReject += count;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(colourOf(label)).append(label).append("&r&7");
        if (count > 1) {
            sb.append(" &fx").append(count).append("&7 in ").append(round(span / 1000.0)).append("s, max ");
        } else {
            sb.append(" ");
        }
        if (!"REJECT".equals(label) && !"BLOCKED-BY-BODY".equals(label) && !"LOW-DAMAGE".equals(label)
                && !"HITS-DROPPED".equals(label) && !isClientFault(label)) {
            sb.append("&f").append(round(distance)).append("&7 blocks");
        }
        sb.append(context);
        String body = sb.toString();

        if (isViolation(label)) {
            recordSuspects(attribution);
            /* Handed to the long-term record under a key derived from what was
               observable, not from a list of known kinds. A server that starts
               objecting to something this client has never seen produces a new
               key by itself and begins accumulating evidence immediately. */
            Adaptive.observeFlag(signatureFor(label), count);
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&cFlagDetector&7] " + body);
        }

        this.recent.addLast(new HudEntry(label, count, distance, System.currentTimeMillis()));
        while (this.recent.size() > 32) {
            this.recent.removeFirst();
        }

        if (this.sound.getValue() && isViolation(label)) {
            SoundUtil.playSound("note.pling");
        }
        if (this.logFile.getValue()) {
            writeLog(label + " x" + count + " " + round(distance) + " "
                    + body.replaceAll("&[0-9a-fklmnor]", ""));
        }
    }

    /**
     * A compact key for what kind of flag this was.
     *
     * Built from the label and the circumstances rather than chosen from an
     * enumeration, so the set of kinds grows on its own. The features are the
     * ones that survive being compared across servers: what the player was
     * doing, whether a push preceded it, and whether the correction exceeded
     * what latency alone could explain. Anything finer -- exact distances,
     * coordinates, timings -- would make every flag its own kind and the
     * statistics would never accumulate.
     */
    private String signatureFor(String label) {
        StringBuilder sb = new StringBuilder(label);
        if (mc.thePlayer == null) {
            return sb.toString();
        }
        sb.append(mc.thePlayer.onGround ? "|ground" : "|air");
        if (mc.thePlayer.isSprinting()) {
            sb.append("|sprint");
        }
        if (this.ticksSinceKnockback <= 10) {
            sb.append("|kb");
        }
        if (this.ticksSinceAttack <= 10) {
            sb.append("|hit");
        }
        if (this.ticksSincePlace <= 10) {
            sb.append("|place");
        }
        if (this.ticksSinceDig <= 10) {
            sb.append("|dig");
        }
        if (this.lastExceededTravel) {
            sb.append("|beyond-latency");
        }
        return sb.toString();
    }

    private static String colourOf(String label) {
        if ("LAGBACK".equals(label)) {
            return "&c";
        }
        if ("TELEPORT".equals(label)) {
            return "&b";
        }
        if ("RELOCATE".equals(label)) {
            return "&3";
        }
        if ("REJECT".equals(label)) {
            return "&6";
        }
        if ("BLOCKED-BY-BODY".equals(label)) {
            return "&8";
        }
        if ("LOW-DAMAGE".equals(label) || "HITS-DROPPED".equals(label)) {
            return "&5";
        }
        if (isClientFault(label)) {
            return "&d";
        }
        return "&e";
    }

    /** One file per session, appended to, so runs can be compared afterwards. */
    private void writeLog(String line) {
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "flags-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        /* Written on AsyncLog's thread: a file opened mid-tick is a stall. */
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    /* What the server was last told: the position and rotation it judges a
       placement against. Vanilla's right-click placements are sent before the
       tick's movement packet, a module's in POST after it -- either way the
       last one sent is the one the server has. */
    private double sentX, sentY, sentZ;
    private float sentYaw, sentPitch, sentYawBefore, sentPitchBefore;
    private boolean sentGround;
    private int sentTick;
    private int lastPlaceTick = -1000;
    private java.io.File placeTarget;

    /**
     * One place-log line, up to the outcome: key=value fields, | separated.
     *
     * ray: whether the rotation the server has, from the eyes where it has
     * them, reaches the clicked block, and on which face -- what a placement
     * check can verify. face: the face the click claimed. rot: the turn in the
     * last rotation sent. gap: ticks since the previous placement. The rest is
     * the player's state and what was running.
     */
    private String placementContext(C08PacketPlayerBlockPlacement place, BlockPos target) {
        BlockPos clicked = place.getPosition();
        net.minecraft.util.EnumFacing face = net.minecraft.util.EnumFacing.getFront(place.getPlacedBlockDirection());
        double eyeY = this.sentY + (mc.thePlayer.isSneaking() ? 1.54 : 1.62);
        net.minecraft.util.Vec3 eyes = new net.minecraft.util.Vec3(this.sentX, eyeY, this.sentZ);
        net.minecraft.util.Vec3 hit = new net.minecraft.util.Vec3(clicked.getX() + place.getPlacedBlockOffsetX(),
                clicked.getY() + place.getPlacedBlockOffsetY(), clicked.getZ() + place.getPlacedBlockOffsetZ());
        float yawRad = -this.sentYaw * 0.017453292F - (float) Math.PI;
        float pitchRad = -this.sentPitch * 0.017453292F;
        float cosPitch = -net.minecraft.util.MathHelper.cos(pitchRad);
        net.minecraft.util.Vec3 look = new net.minecraft.util.Vec3(net.minecraft.util.MathHelper.sin(yawRad) * cosPitch,
                net.minecraft.util.MathHelper.sin(pitchRad), net.minecraft.util.MathHelper.cos(yawRad) * cosPitch);
        net.minecraft.util.Vec3 far = eyes.addVector(look.xCoord * 6.0, look.yCoord * 6.0, look.zCoord * 6.0);
        net.minecraft.util.AxisAlignedBB box = new net.minecraft.util.AxisAlignedBB(clicked, clicked.add(1, 1, 1));
        net.minecraft.util.MovingObjectPosition ray = box.calculateIntercept(eyes, far);
        String rayText = ray == null ? "miss"
                : ray.sideHit == face ? String.format("face %.2f", ray.hitVec.distanceTo(hit)) : "side:" + ray.sideHit.getName();
        float turn = Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(this.sentYaw - this.sentYawBefore))
                + Math.abs(this.sentPitch - this.sentPitchBefore);
        StringBuilder mods = new StringBuilder();
        for (Class<?> type : new Class<?>[]{FastPlace.class, Clutch.class, Scaffold.class, AutoBlockIn.class,
                KillAura.class, FakeLag.class, BackTrack.class, Blink.class, AntiVoid.class, SafeWalk.class, Eagle.class}) {
            myau.module.Module module = myau.Myau.moduleManager.modules.get(type);
            if (module != null && module.isEnabled()) {
                mods.append(mods.length() == 0 ? "" : ",").append(module.getName());
            }
        }
        /* self: the block against the player's box where the server has it
           (the last position sent) -- a block placed into it is refused --
           as the vertical clearance of the feet over the block's top, when
           the two overlap across; "-" when they do not. feet: the same from
           where the client has the player now. held: packets FakeLag and the
           blink manager were holding. */
        double half = 0.3;
        boolean across = this.sentX + half > target.getX() && this.sentX - half < target.getX() + 1
                && this.sentZ + half > target.getZ() && this.sentZ - half < target.getZ() + 1;
        String self = across ? String.format("%+.2f", this.sentY - (target.getY() + 1)) : "-";
        FakeLag fakeLag = (FakeLag) myau.Myau.moduleManager.modules.get(FakeLag.class);
        int fakeHeld = fakeLag != null && fakeLag.isEnabled() ? fakeLag.heldCount() : 0;
        int blinkHeld = myau.Myau.blinkManager == null ? 0 : myau.Myau.blinkManager.blinkedPackets.size();
        String extra = String.format(" | self %s | feet %+.2f | held fl=%d bl=%d", self,
                mc.thePlayer.posY - (target.getY() + 1), fakeHeld, blinkHeld);
        return String.format("%s | at %d,%d,%d | face %s | ray %s | dist %.2f | rot %.1f | gap %s | sentAge %dt"
                        + " | ground %s | v %.2f %.2f %.2f | sprint %s | sneak %s | hurt %d | ping %d | catching %s | mods %s"
                        + extra.replace("%", "%%"),
                LINE_STAMP.format(new java.util.Date()), target.getX(), target.getY(), target.getZ(), face.getName(),
                rayText, eyes.distanceTo(hit), turn,
                this.tickCounter - this.lastPlaceTick > 200 ? "-" : String.valueOf(this.tickCounter - this.lastPlaceTick),
                this.tickCounter - this.sentTick, this.sentGround,
                mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ,
                mc.thePlayer.isSprinting(), mc.thePlayer.isSneaking(), mc.thePlayer.hurtTime, ping(),
                myau.management.Arbiter.catching(), mods.length() == 0 ? "-" : mods.toString());
    }

    private void logPlacement(Placement placement, String outcome) {
        if (placement == null || placement.context == null) {
            return;
        }
        if (this.placeTarget == null) {
            this.placeTarget = new java.io.File(LOG_DIR, "places-" + FILE_STAMP.format(new java.util.Date()) + ".txt");
        }
        myau.util.AsyncLog.append(this.placeTarget, placement.context + " | result " + outcome);
        placement.context = null;
    }

    private double perMinute() {
        double minutes = (System.currentTimeMillis() - this.sessionStart) / 60000.0;
        return minutes < 0.05 ? 0.0 : total() / minutes;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.hud.getValue() == 0
                || mc.fontRendererObj == null || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        boolean right = alignRight();
        int margin = Math.min(this.hudX.getValue(), Math.max(0, sr.getScaledWidth() - 120));
        int y = Math.min(this.hudY.getValue(), Math.max(0, sr.getScaledHeight() - 20));

        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        String header = String.format("Flags %d  §cL%d §eV%d §6R%d §bT%d  §7%.1f/min",
                total(), this.countLagback, this.countVertical, this.countReject,
                this.countTeleport, perMinute());
        if (this.countClientFault > 0) {
            /* Only when non-zero: a clean session should look exactly as it
               did before these existed. */
            header += String.format("  §dBUG%d", this.countClientFault);
        }
        mc.fontRendererObj.drawStringWithShadow(header, lineX(sr, header, margin, right), y, 0xFFFFFFFF);

        if (this.hud.getValue() == 2) {
            long now = System.currentTimeMillis();
            long fade = this.hudFade.getValue() * 1000L;
            int drawn = 0;
            Iterator<HudEntry> it = this.recent.descendingIterator();
            while (it.hasNext() && drawn < this.hudLines.getValue()) {
                HudEntry entry = it.next();
                long age = now - entry.at;
                if (age > fade) {
                    break;
                }
                /* Newest lines are opaque and older ones dim, so a burst that
                   just happened is separable from one a while ago without
                   reading the timestamps. */
                int alpha = (int) (255 - 195 * ((double) age / fade));
                if (alpha < 60) {
                    alpha = 60;
                }
                String line = String.format("%s%s%s  §7%.1fs",
                        sectionOf(entry.label), entry.label,
                        entry.count > 1 ? " x" + entry.count : "", age / 1000.0);
                mc.fontRendererObj.drawStringWithShadow(line,
                        lineX(sr, line, margin + 2, right), y + 11 + drawn * 10,
                        (alpha << 24) | 0xFFFFFF);
                drawn++;
            }
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GL11.glPopMatrix();
    }

    /**
     * The side to draw on. AUTO mirrors the arraylist: the HUD module exposes
     * which corner it occupies, so taking the other one needs no measurement
     * of how tall the list currently is.
     */
    private boolean alignRight() {
        int mode = this.hudAlign.getValue();
        if (mode == 1) {
            return false;
        }
        if (mode == 2) {
            return true;
        }
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        return hud != null && hud.isEnabled() && hud.posX.getValue() == 0;
    }

    /** Left edge for one line, so right-aligned text ends at the margin. */
    private int lineX(ScaledResolution sr, String line, int margin, boolean right) {
        if (!right) {
            return margin;
        }
        return sr.getScaledWidth() - margin - mc.fontRendererObj.getStringWidth(line);
    }

    /** Chat colour codes rendered directly, for the HUD path. */
    private static String sectionOf(String label) {
        return colourOf(label).replace('&', '§');
    }

    @Override
    public String[] getSuffix() {
        String bugs = this.countClientFault > 0 ? " BUG" + this.countClientFault : "";
        if (total() == 0) {
            return new String[]{"clean" + bugs};
        }
        return new String[]{String.format("L%d V%d R%d", this.countLagback,
                this.countVertical, this.countReject) + bugs};
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class HudEntry {
        final String label;
        final int count;
        final double distance;
        final long at;

        HudEntry(String label, int count, double distance, long at) {
            this.label = label;
            this.count = count;
            this.distance = distance;
            this.at = at;
        }
    }
}
