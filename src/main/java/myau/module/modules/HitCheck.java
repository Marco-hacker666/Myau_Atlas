package myau.module.modules;

import myau.util.Ping;
import myau.util.AsyncLog;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LeftClickMouseEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.HealthUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Counts which swings actually dealt damage, and explains the ones that did not.
 *
 * "It feels wrong" is the hardest kind of bug report to act on, and this client
 * has produced several: an aim that would not settle, a reach that was silently
 * refused, an attack key that was being suppressed entirely by an unrelated
 * right-click setting. Each took several games and several wrong guesses to
 * pin down, and every one of them would have been a single number on screen.
 *
 * Three quantities are kept apart on purpose, because the interesting failures
 * live in the gaps between them:
 *
 *   EXPECTED   - clicks made with an entity under the crosshair.
 *   SWINGS     - how many attack packets actually left the client.
 *   HITS       - how many of those produced damage.
 *
 * Expected is deliberately not "every click". Vanilla only sends an attack
 * when its own raytrace found an entity; swinging at air, breaking blocks and
 * clicking through a screen are supposed to send nothing, and in a bridging
 * game they are most of the clicks there are. Counting those as attacks that
 * went missing produces a large number that means nothing.
 *
 * A gap between expected and swings is therefore the real signal: something
 * inside the client ate an attack that vanilla would have sent, which no amount
 * of aim tuning will fix. A gap between swings and hits means the server
 * refused them, and the reason is recorded per miss rather than guessed at.
 *
 * Landing is detected from the target's hurt timer rather than its health: the
 * timer is set to its maximum on the tick damage is taken and is visible for
 * every player, whereas health is only as current as whatever the server chose
 * to publish. Health is still consulted as a second opinion where it is
 * available.
 *
 * Nothing here is sent anywhere. The module only reads what the client already
 * has, and the one expensive test -- tracing for line of sight -- runs when a
 * miss is being explained, never once per player per tick.
 */
public class HitCheck extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY = 64;
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    public final FloatProperty rangeThreshold = new FloatProperty("range-threshold", 3.0F, 2.5F, 6.0F);
    public final IntProperty windowPad = new IntProperty("window-pad", 6, 0, 20);
    /**
     * Above this the target was still in its invulnerability window. In 1.8 a
     * second hit inside the ten-tick window is refused outright unless it is
     * stronger than the first, so any non-zero timer already explains the
     * miss; the setting exists because the client's copy of that timer can be
     * a tick or two behind the server's.
     */
    public final IntProperty invulnHurtTime = new IntProperty("invuln-hurt-time", 1, 0, 10);

    public final ModeProperty hud = new ModeProperty("hud", 2, new String[]{"NONE", "COMPACT", "LIST"});
    public final IntProperty hudX = new IntProperty("hud-x", 4, 0, 800, () -> this.hud.getValue() != 0);
    public final IntProperty hudY = new IntProperty("hud-y", 120, 0, 600, () -> this.hud.getValue() != 0);
    public final IntProperty hudLines = new IntProperty("hud-lines", 4, 1, 10, () -> this.hud.getValue() == 2);
    public final IntProperty hudFade = new IntProperty("hud-fade", 15, 3, 120, () -> this.hud.getValue() == 2);

    public final BooleanProperty explainMisses = new BooleanProperty("explain-misses", true);
    public final BooleanProperty chatSummary = new BooleanProperty("chat-summary", false);
    public final IntProperty summarySeconds = new IntProperty("summary-seconds", 30, 5, 300);

    /** One file per session under config/Myau, so a game can be read back after it. */
    public final BooleanProperty logFile = new BooleanProperty("log-file", true);
    /** Every miss and every suppressed click, not just the periodic totals. */
    public final BooleanProperty logEvents = new BooleanProperty("log-events", true,
            this.logFile::getValue);

    // Own path, for the distance the server could have seen.
    private final double[] selfX = new double[HISTORY];
    private final double[] selfY = new double[HISTORY];
    private final double[] selfZ = new double[HISTORY];
    private int selfIndex;
    private int selfFilled;

    /**
     * Ticks at which the server sent a hurt animation for an entity.
     *
     * This is the signal that separates the two things {@code unknown} was
     * lumping together. When the server accepts an attack it broadcasts the
     * hurt animation, and it does so even where the damage itself came to
     * nothing -- absorbed, armoured, or reduced. When the server discards the
     * attack packet instead, nothing comes back at all.
     *
     * So an attack that produced no damage AND no animation was not a miss in
     * any sense the client can see: it was not refused for range, or for the
     * invulnerability window, or for line of sight, all of which are checked
     * first. It was dropped. Whether that is mitigation, a plugin, or
     * something else is not decidable from here -- but "the server did not
     * react at all" is a different fact from "the server said no", and they
     * should not share a label.
     */
    private final Map<Integer, Integer> hurtAnimations = new HashMap<Integer, Integer>();

    private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<Pending>();
    /* A click and the packet it should produce are a tick or two apart at most;
       anything longer is a different click. */
    private static final int EXPECT_TICKS = 2;
    private final ArrayDeque<Expectation> awaiting = new ArrayDeque<Expectation>();
    private final ArrayDeque<int[]> attacks = new ArrayDeque<int[]>();
    private final ArrayDeque<Miss> recent = new ArrayDeque<Miss>();
    private final Map<String, Integer> reasons = new LinkedHashMap<String, Integer>();

    private int clicks;
    private int expected;
    private int suppressed;
    private int swings;
    private int hits;
    private int misses;
    /** Swings whose target vanished before they could be judged. */
    private int undetermined;
    private int tickCounter;
    private long nextSummaryAt;

    private File logTarget;
    private long sessionStart;
    /* Set by explain() so the log line can carry the distance it already
       measured, rather than tracing the same path a second time. */
    private double lastClosest = -1.0;

    public HitCheck() {
        super("HitCheck", false, false,
                "Counts which swings landed and explains the ones that did not");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        /* The closing total is the one line worth having if nothing else was
           written: a session that ends without it was cut short. */
        if (this.swings > 0 || this.expected > 0) {
            writeLog("TOTAL  " + plainSummary());
        }
        this.tracks.clear();
        this.pending.clear();
    }

    private void reset() {
        /* A world change ends a round as far as these numbers are concerned, so
           the totals are closed off before they are thrown away -- otherwise a
           whole game's worth of counting disappears at the next lobby. */
        if (this.swings > 0 || this.expected > 0) {
            writeLog("TOTAL  " + plainSummary());
        }
        this.sessionStart = System.currentTimeMillis();
        writeLog("---- round start ----");
        this.tracks.clear();
        this.pending.clear();
        this.recent.clear();
        this.reasons.clear();
        this.hurtAnimations.clear();
        this.clicks = 0;
        this.expected = 0;
        this.suppressed = 0;
        this.swings = 0;
        this.awaiting.clear();
        this.attacks.clear();
        this.hits = 0;
        this.misses = 0;
        this.undetermined = 0;
        this.selfIndex = 0;
        this.selfFilled = 0;
        this.nextSummaryAt = System.currentTimeMillis() + this.summarySeconds.getValue() * 1000L;
    }

    /* LOWEST, and a cancelled click is skipped. The mixin fires this event at
       the head of clickMouse and only decides afterwards whether vanilla's
       click runs, so a click AutoHeal or KillAura had deliberately cancelled
       was still recorded as expected -- and then counted as suppressed when no
       attack followed. Running last means every other listener has already
       had its say. */
    @EventTarget(myau.event.types.Priority.LOWEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.currentScreen != null
                || event.isCancelled()) {
            return;
        }
        this.clicks++;
        /* The same condition vanilla uses to decide whether this click becomes
           an attack packet at all. Without it every swing at air and every
           block broken counts as an attack that went missing. */
        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null || over.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || over.entityHit == null) {
            return;
        }
        this.expected++;
        Expectation expectation = new Expectation();
        expectation.entityId = over.entityHit.getEntityId();
        expectation.tick = this.tickCounter;
        this.awaiting.addLast(expectation);
    }

    /** An attack packet satisfies any click aimed at the same entity. */
    private void noteAttack(int entityId) {
        this.attacks.addLast(new int[]{entityId, this.tickCounter});
        while (this.attacks.size() > 64) {
            this.attacks.removeFirst();
        }
    }

    private void resolveExpectations() {
        while (!this.awaiting.isEmpty()
                && this.tickCounter - this.awaiting.peekFirst().tick > EXPECT_TICKS) {
            Expectation expectation = this.awaiting.pollFirst();
            boolean satisfied = false;
            for (int[] attack : this.attacks) {
                if (attack[0] == expectation.entityId
                        && attack[1] >= expectation.tick
                        && attack[1] <= expectation.tick + EXPECT_TICKS) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                this.suppressed++;
                if (this.logEvents.getValue()) {
                    writeLog("SUPPRESSED  target=" + nameOf(expectation.entityId)
                            + "  held=" + heldItem());
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S01PacketJoinGame) {
                // Client thread: the tick iterates these collections.
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        reset();
                    }
                });
                return;
            }
            if (event.getPacket() instanceof S19PacketEntityStatus) {
                final S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
                if (status.getOpCode() == 2) {
                    /* This runs on the network thread; the entity lookup and
                       the map (read by the tick) belong to the client thread.
                       Scheduled ahead of vanilla's own handling of the packet. */
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (mc.theWorld == null) {
                                return;
                            }
                            Entity entity = status.getEntity(mc.theWorld);
                            if (entity != null) {
                                hurtAnimations.put(entity.getEntityId(), tickCounter);
                            }
                        }
                    });
                }
            }
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        Entity target = packet.getEntityFromWorld(mc.theWorld);
        if (!(target instanceof EntityLivingBase)) {
            return;
        }
        this.swings++;
        noteAttack(target.getEntityId());

        EntityLivingBase living = (EntityLivingBase) target;
        Pending shot = new Pending();
        shot.entityId = living.getEntityId();
        shot.tick = this.tickCounter;
        shot.deadline = this.tickCounter + this.window();
        shot.targetHurtTime = living.hurtTime;
        shot.usingItem = mc.thePlayer.isUsingItem();
        shot.healthBefore = HealthUtil.resolve(living);
        this.pending.addLast(shot);
    }

    private int window() {
        int ping = this.ping();
        int ticks = ping > 0 ? (int) Math.ceil(ping / 50.0) : 4;
        ticks += this.windowPad.getValue();
        return Math.min(Math.max(ticks, 2), HISTORY - 1);
    }

    private int ping() {
        return Ping.own();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tickCounter++;

        this.selfX[this.selfIndex] = mc.thePlayer.posX;
        this.selfY[this.selfIndex] = mc.thePlayer.posY;
        this.selfZ[this.selfIndex] = mc.thePlayer.posZ;
        this.selfIndex = (this.selfIndex + 1) % HISTORY;
        if (this.selfFilled < HISTORY) {
            this.selfFilled++;
        }

        Set<Integer> alive = new HashSet<Integer>();
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) object;
            if (living == mc.thePlayer) {
                continue;
            }
            alive.add(living.getEntityId());
            Track track = this.tracks.get(living.getEntityId());
            if (track == null) {
                track = new Track();
                this.tracks.put(living.getEntityId(), track);
            }
            track.push(living, this.tickCounter);
        }

        resolveExpectations();
        resolveDue();

        Iterator<Map.Entry<Integer, Track>> it = this.tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> entry = it.next();
            if (!alive.contains(entry.getKey()) && !hasPendingFor(entry.getKey())) {
                it.remove();
            }
        }

        long now = System.currentTimeMillis();
        if (now >= this.nextSummaryAt) {
            this.nextSummaryAt = now + this.summarySeconds.getValue() * 1000L;
            /* The periodic line goes to the log whether or not chat wants it:
               its whole point is being readable after the game. */
            if (this.swings > 0 || this.expected > 0) {
                writeLog("SUMMARY  " + plainSummary());
            }
            if (this.chatSummary.getValue()) {
                ChatUtil.sendFormatted("&7[&bHitCheck&7] " + summaryLine());
            }
        }
    }

    private boolean hasPendingFor(int entityId) {
        for (Pending shot : this.pending) {
            if (shot.entityId == entityId) {
                return true;
            }
        }
        return false;
    }

    /** Settles every attack whose window has closed, oldest first. */
    private void resolveDue() {
        while (!this.pending.isEmpty() && this.pending.peekFirst().deadline <= this.tickCounter) {
            Pending shot = this.pending.pollFirst();
            /* Taken off the queue before it is judged: whatever happens while
               explaining a miss, the same attack is never judged twice. */
            resolve(shot);
        }
    }

    private void resolve(Pending shot) {
        Track track = this.tracks.get(shot.entityId);
        Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(shot.entityId);
        EntityLivingBase living = entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;

        boolean landed = track != null && track.damagedBetween(shot.tick, shot.deadline);
        if (!landed && living != null && HealthUtil.resolve(living) < shot.healthBefore - 0.01F) {
            landed = true;
        }

        if (landed) {
            this.hits++;
            reportToFlagDetector(false, shot.entityId);
            return;
        }
        if (living == null) {
            /* The target left the world before the window closed -- killed,
               out of render distance, or a round change. Nothing can be said
               about the swing either way, and calling it a miss understates
               the rate by exactly the killing blows, which is the worst place
               to be wrong. */
            this.undetermined++;
            if (this.logEvents.getValue()) {
                writeLog("GONE  target=#" + shot.entityId + "  (killed or despawned)");
            }
            return;
        }
        this.misses++;
        String reason = explain(shot, track, living);
        if ("dropped".equals(reason)) {
            reportToFlagDetector(true, shot.entityId);
        }
        Integer seen = this.reasons.get(reason);
        this.reasons.put(reason, seen == null ? 1 : seen + 1);
        this.recent.addLast(new Miss(reason, System.currentTimeMillis()));
        while (this.recent.size() > 32) {
            this.recent.removeFirst();
        }
        if (this.logEvents.getValue()) {
            StringBuilder sb = new StringBuilder();
            sb.append("MISS  ").append(reason)
                    .append("  target=").append(nameOf(shot.entityId))
                    .append("  hurt=").append(shot.targetHurtTime)
                    .append("  ping=").append(this.ping()).append("ms")
                    .append("  window=").append(shot.deadline - shot.tick).append('t');
            if (this.lastClosest >= 0.0) {
                sb.append(String.format("  dist=%.2f", this.lastClosest));
            }
            if (shot.usingItem) {
                sb.append("  using-item");
            }
            writeLog(sb.toString());
        }
    }

    /** Landed or dropped, for FlagDetector's HITS-DROPPED (2026-09-28). */
    private void reportToFlagDetector(boolean dropped, int entityId) {
        myau.module.Module module = myau.Myau.moduleManager == null ? null
                : myau.Myau.moduleManager.modules.get(FlagDetector.class);
        if (module instanceof FlagDetector) {
            ((FlagDetector) module).noteHitResult(dropped, nameOf(entityId), Math.max(0.0, this.lastClosest));
        }
    }

    private String nameOf(int entityId) {
        Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(entityId);
        return entity == null ? ("#" + entityId) : entity.getName();
    }

    /** What was in hand, because a suppressed attack is usually an item's doing. */
    private String heldItem() {
        try {
            return mc.thePlayer.getHeldItem() == null
                    ? "empty" : mc.thePlayer.getHeldItem().getItem().getUnlocalizedName();
        } catch (Exception ignored) {
            return "?";
        }
    }

    /**
     * Why an attack produced nothing, in the order the causes rule each other
     * out. The cheap facts recorded at swing time settle most of it; the
     * raytrace is only reached when nothing else explains the miss.
     */
    private String explain(Pending shot, Track track, EntityLivingBase living) {
        this.lastClosest = -1.0;
        if (shot.usingItem) {
            return "using-item";
        }
        if (shot.targetHurtTime > this.invulnHurtTime.getValue()) {
            return "invuln";
        }
        if (track == null || living == null) {
            return "unknown";
        }
        int window = shot.deadline - shot.tick;
        double closest = Double.MAX_VALUE;
        boolean sawIt = false;
        for (int back = 0; back <= window; back++) {
            Vec3 mine = selfAt(back);
            Vec3 theirs = track.at(back);
            if (mine == null || theirs == null) {
                continue;
            }
            Vec3 eyes = new Vec3(mine.xCoord, mine.yCoord + mc.thePlayer.getEyeHeight(), mine.zCoord);
            double half = living.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(
                    theirs.xCoord - half, theirs.yCoord, theirs.zCoord - half,
                    theirs.xCoord + half, theirs.yCoord + living.height, theirs.zCoord + half);
            double distance = distanceToBox(eyes, box);
            if (distance < closest) {
                closest = distance;
            }
            if (!sawIt) {
                Vec3 theirEyes = new Vec3(theirs.xCoord,
                        theirs.yCoord + living.getEyeHeight(), theirs.zCoord);
                if (mc.theWorld.rayTraceBlocks(eyes, theirEyes, false, true, false) == null) {
                    sawIt = true;
                }
            }
        }
        if (closest != Double.MAX_VALUE) {
            this.lastClosest = closest;
        }
        if (closest != Double.MAX_VALUE && closest > this.rangeThreshold.getValue()) {
            return "out-of-range";
        }
        if (!sawIt) {
            return "blocked";
        }
        /* Everything the client can check has passed: in range, out of the
           invulnerability window, in line of sight, not using an item. If the
           server also never sent a hurt animation, it did not evaluate this
           attack and reject it -- it did not act on it at all. */
        Integer animated = this.hurtAnimations.get(shot.entityId);
        if (animated == null || animated < shot.tick || animated > shot.deadline) {
            return "dropped";
        }
        return "unknown";
    }

    private static double distanceToBox(Vec3 point, AxisAlignedBB box) {
        double dx = Math.max(Math.max(box.minX - point.xCoord, 0.0), point.xCoord - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.yCoord, 0.0), point.yCoord - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.zCoord, 0.0), point.zCoord - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Vec3 selfAt(int back) {
        if (back >= this.selfFilled) {
            return null;
        }
        int at = ((this.selfIndex - 1 - back) % HISTORY + HISTORY) % HISTORY;
        return new Vec3(this.selfX[at], this.selfY[at], this.selfZ[at]);
    }

    /** Swings that could actually be judged -- the honest denominator. */
    private int judged() {
        return Math.max(0, this.swings - this.undetermined);
    }

    private int rate() {
        return judged() <= 0 ? 0 : Math.round(this.hits * 100.0F / judged());
    }

    private String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("&fhits ").append(this.hits).append('/').append(judged())
                .append(" &7(").append(rate()).append("%)");
        if (this.suppressed > 0) {
            /* Clicks vanilla would have turned into an attack, that produced no
               packet. Nothing about aim or range applies to these. */
            sb.append(" &8| &c").append(this.suppressed).append('/').append(this.expected)
                    .append(" suppressed&7");
        }
        for (Map.Entry<String, Integer> entry : this.reasons.entrySet()) {
            sb.append(" &8| &7").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    /** Swings that could be judged, and how many landed. */
    public int judgedSwings() {
        return judged();
    }

    public int landedHits() {
        return this.hits;
    }

    public int suppressedClicks() {
        return this.suppressed;
    }

    /** Misses attributed to a given reason, for a caller comparing settings. */
    public int missesFor(String reason) {
        Integer seen = this.reasons.get(reason);
        return seen == null ? 0 : seen;
    }

    public void resetCounters() {
        reset();
    }

    /** The same figures as the chat line, without the colour codes. */
    private String plainSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("hits ").append(this.hits).append('/').append(judged())
                .append(" (").append(rate()).append("%)")
                .append("  swings ").append(this.swings)
                .append("  gone ").append(this.undetermined)
                .append("  expected ").append(this.expected)
                .append("  suppressed ").append(this.suppressed)
                .append("  same-tick ").append(myau.management.TickActions.refusals())
                .append("  clicks ").append(this.clicks)
                .append("  ping ").append(this.ping()).append("ms");
        double minutes = (System.currentTimeMillis() - this.sessionStart) / 60000.0;
        if (minutes >= 0.05) {
            sb.append(String.format("  %.1f min", minutes));
        }
        for (Map.Entry<String, Integer> entry : this.reasons.entrySet()) {
            sb.append("  | ").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * One file per session, appended to. Opened lazily, so a session in which
     * nothing was ever counted leaves no file behind, and never opened at all
     * while logging is switched off.
     */
    private void writeLog(String line) {
        if (!this.logFile.getValue()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "hits-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        /* Written on AsyncLog's thread: a file opened mid-tick is a stall. */
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.hud.getValue() == 0 || mc.fontRendererObj == null
                || mc.thePlayer == null || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
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

        String header = String.format("Hits %d/%d §7(%d%%)%s",
                this.hits, judged(), rate(),
                this.suppressed > 0 ? String.format("  §c-%d suppressed", this.suppressed) : "");
        mc.fontRendererObj.drawStringWithShadow(header, lineX(sr, header, margin, right), y, 0xFFFFFFFF);

        if (this.hud.getValue() == 2 && this.explainMisses.getValue()) {
            long now = System.currentTimeMillis();
            long fade = this.hudFade.getValue() * 1000L;
            int drawn = 0;
            Iterator<Miss> it = this.recent.descendingIterator();
            while (it.hasNext() && drawn < this.hudLines.getValue()) {
                Miss miss = it.next();
                long age = now - miss.at;
                if (age > fade) {
                    break;
                }
                int alpha = (int) (255 - 195 * ((double) age / fade));
                if (alpha < 60) {
                    alpha = 60;
                }
                String line = String.format("§c%s  §7%.1fs", miss.reason, age / 1000.0);
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

    /** Sits opposite the module list, the same way the flag readout does. */
    private boolean alignRight() {
        HUD hud = (HUD) myau.Myau.moduleManager.modules.get(HUD.class);
        return hud != null && hud.isEnabled() && hud.posX.getValue() == 0;
    }

    private int lineX(ScaledResolution sr, String line, int margin, boolean right) {
        if (!right) {
            return margin;
        }
        return sr.getScaledWidth() - margin - mc.fontRendererObj.getStringWidth(line);
    }

    @Override
    public String[] getSuffix() {
        if (this.swings <= 0) {
            return new String[]{"--"};
        }
        return new String[]{rate() + "%"};
    }

    /** Positions and damage moments for one entity. */
    private static class Track {
        final double[] x = new double[HISTORY];
        final double[] y = new double[HISTORY];
        final double[] z = new double[HISTORY];
        int index;
        int filled;
        int lastHurtTime;
        int lastDamageTick = Integer.MIN_VALUE;

        void push(EntityLivingBase living, int tick) {
            this.x[this.index] = living.posX;
            this.y[this.index] = living.posY;
            this.z[this.index] = living.posZ;
            this.index = (this.index + 1) % HISTORY;
            if (this.filled < HISTORY) {
                this.filled++;
            }
            /* The hurt timer is reloaded to its maximum on the tick damage
               lands and counts down from there, so a rise is a fresh hit --
               and unlike health it is published for every player. */
            if (living.hurtTime > this.lastHurtTime) {
                this.lastDamageTick = tick;
            }
            this.lastHurtTime = living.hurtTime;
        }

        boolean damagedBetween(int from, int to) {
            return this.lastDamageTick >= from && this.lastDamageTick <= to;
        }

        Vec3 at(int back) {
            if (back >= this.filled) {
                return null;
            }
            int at = ((this.index - 1 - back) % HISTORY + HISTORY) % HISTORY;
            return new Vec3(this.x[at], this.y[at], this.z[at]);
        }
    }

    private static class Pending {
        int entityId;
        int tick;
        int deadline;
        int targetHurtTime;
        boolean usingItem;
        float healthBefore;
    }

    private static class Expectation {
        int entityId;
        int tick;
    }

    private static class Miss {
        final String reason;
        final long at;

        Miss(String reason, long at) {
            this.reason = reason;
            this.at = at;
        }
    }
}
