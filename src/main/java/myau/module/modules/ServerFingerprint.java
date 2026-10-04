package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.network.play.server.S38PacketPlayerListItem;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Records everything a server reveals about itself, and guesses what is
 * checking movement on it.
 *
 * Which anticheat a server runs decides what is safe on it, and the usual way
 * of finding out is to be caught by it. A server volunteers a great deal
 * before that happens: it announces a brand, registers plugin channels, sends
 * keep-alives and transactions on a cadence chosen by whatever wants them, and
 * corrects positions in a shape that differs between implementations. None of
 * that requires probing -- it is all in traffic the client receives anyway,
 * and nothing here sends a packet of its own.
 *
 * The observations are kept separately from the guess, and written out in full.
 * A verdict is a summary of evidence and worth far less than the evidence: the
 * numbers below are what makes a wrong guess correctable later, and what lets
 * two servers be compared without either being identified at all.
 *
 * The signals, and what each is worth:
 *
 *   BRAND. {@code MC|Brand} is whatever the server chose to call itself. A
 *   proxy usually overwrites it, so on a network this names the proxy rather
 *   than the backend -- still useful, rarely decisive.
 *
 *   PLUGIN CHANNELS. Registered channels name the plugins that use them, and
 *   several anticheats register one. This is the strongest single signal there
 *   is, and it costs nothing.
 *
 *   TRANSACTION CADENCE. 1.8 anticheats use the transaction round trip to
 *   place client events in time. Vanilla sends them only when a window needs
 *   confirming; a steady stream on a tick boundary means something is timing
 *   the client, and the interval says how finely.
 *
 *   CORRECTION SHAPE. How large a setback is, whether the fields arrive
 *   relative or absolute, and how many repeat before the client is believed.
 *   Implementations differ visibly here.
 *
 *   TICK HEALTH. Time updates are sent once a server second; drift in their
 *   spacing measures how far behind the server is running, which changes what
 *   every other measurement means.
 */
public class ServerFingerprint extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public final IntProperty reportAfter = new IntProperty("report-after-seconds", 45, 10, 600);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty logFile = new BooleanProperty("log-file", true);
    /** Re-report when a new signal arrives after the first verdict. */
    public final BooleanProperty followUp = new BooleanProperty("follow-up", true);

    // ---- observations -------------------------------------------------

    private String address = "";
    private String brand = "";
    private final TreeSet<String> channels = new TreeSet<String>();
    private final TreeSet<String> hits = new TreeSet<String>();
    private final Map<String, Integer> chatMarkers = new LinkedHashMap<String, Integer>();

    private long lastKeepAlive;
    private final Stats keepAlive = new Stats();
    private long lastTransaction;
    private final Stats transaction = new Stats();
    private int transactionCount;
    private final TreeMap<Integer, Integer> transactionWindows = new TreeMap<Integer, Integer>();

    private long lastTimeUpdate;
    private final Stats timeUpdate = new Stats();

    private final Stats correction = new Stats();
    private int corrections;
    private int correctionsRelative;
    private int corrections0Ground;
    private long lastCorrectionAt;
    private int correctionBursts;
    /** Corrections excluded as placements rather than policing. */
    private int teleports;
    private int lastJoinTick = Integer.MIN_VALUE / 2;
    private int lastTeleportTick = Integer.MIN_VALUE / 2;
    /** Beyond this a correction is a send, not a setback. */
    private static final double TELEPORT_BLOCKS = 8.0;

    private final Stats velocityHorizontal = new Stats();
    private final Stats velocityVertical = new Stats();
    private int velocities;

    /* ---- discriminators ------------------------------------------------
       These are the measurements chosen because implementations differ on
       them, as opposed to the ones above, which mostly describe the server.
       An anticheat is identified by how it reacts, not by how it idles. */

    /** Ticks between this client's last outgoing action and the correction. */
    private final Stats reactionTicks = new Stats();
    /** Corrections that also rewrote the player's rotation. */
    private int correctionsWithLook;
    /** Corrections that snapped Y to a whole block -- a ground re-anchor. */
    private int correctionsYSnapped;
    /** Corrections arriving in the same tick as a zeroing velocity packet. */
    private int correctionsWithVelocity;
    /** Longest train of corrections before the client was believed. */
    private int longestTrain;
    private int currentTrain;
    /** Placements refused by an explicit air block-change, rather than ignored. */
    private int placementsRefusedExplicitly;
    private int placementsSent;
    /** Attacks sent beyond vanilla reach that drew no correction at all. */
    private int silentAttacks;

    private int tickCounter;
    private int lastActionTick = Integer.MIN_VALUE / 2;
    private int lastVelocityTick = Integer.MIN_VALUE / 2;

    private int joinCount;
    private long joinedAt;
    private boolean reported;
    private int reportedSignals = -1;
    private File logTarget;

    public ServerFingerprint() {
        super("ServerFingerprint", false, false,
                "Records what a server reveals about itself and names its anticheat");
    }

    /** Running mean and spread without keeping the samples. */
    private static final class Stats {
        int n;
        double sum;
        double sumSquares;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        void add(double value) {
            this.n++;
            this.sum += value;
            this.sumSquares += value * value;
            if (value < this.min) {
                this.min = value;
            }
            if (value > this.max) {
                this.max = value;
            }
        }

        double mean() {
            return this.n == 0 ? 0.0 : this.sum / this.n;
        }

        double deviation() {
            if (this.n < 2) {
                return 0.0;
            }
            double mean = mean();
            double variance = this.sumSquares / this.n - mean * mean;
            return variance <= 0.0 ? 0.0 : Math.sqrt(variance);
        }

        void reset() {
            this.n = 0;
            this.sum = 0.0;
            this.sumSquares = 0.0;
            this.min = Double.MAX_VALUE;
            this.max = -Double.MAX_VALUE;
        }

        @Override
        public String toString() {
            if (this.n == 0) {
                return "-";
            }
            return String.format("%.1f +/-%.1f [%.1f..%.1f] n=%d",
                    mean(), deviation(), this.min, this.max, this.n);
        }
    }

    @Override
    public void onEnabled() {
        resetAll();
    }

    private void resetAll() {
        this.address = "";
        this.brand = "";
        this.channels.clear();
        this.hits.clear();
        this.chatMarkers.clear();
        this.keepAlive.reset();
        this.transaction.reset();
        this.timeUpdate.reset();
        this.correction.reset();
        this.velocityHorizontal.reset();
        this.velocityVertical.reset();
        this.transactionWindows.clear();
        this.lastKeepAlive = 0L;
        this.lastTransaction = 0L;
        this.lastTimeUpdate = 0L;
        this.lastCorrectionAt = 0L;
        this.transactionCount = 0;
        this.corrections = 0;
        this.correctionsRelative = 0;
        this.corrections0Ground = 0;
        this.correctionBursts = 0;
        this.teleports = 0;
        this.lastJoinTick = Integer.MIN_VALUE / 2;
        this.lastTeleportTick = Integer.MIN_VALUE / 2;
        this.velocities = 0;
        this.reactionTicks.reset();
        this.correctionsWithLook = 0;
        this.correctionsYSnapped = 0;
        this.correctionsWithVelocity = 0;
        this.longestTrain = 0;
        this.currentTrain = 0;
        this.placementsRefusedExplicitly = 0;
        this.placementsSent = 0;
        this.silentAttacks = 0;
        this.tickCounter = 0;
        this.lastActionTick = Integer.MIN_VALUE / 2;
        this.lastVelocityTick = Integer.MIN_VALUE / 2;
        this.joinCount = 0;
        this.joinedAt = System.currentTimeMillis();
        this.reported = false;
        this.reportedSignals = -1;
        this.logTarget = null;
    }

    // ---- collection ---------------------------------------------------

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            /* When this client last did something the server could object to.
               The gap between that and the correction is the single most
               telling number here: an implementation that mitigates in place
               answers within a round trip, one that accumulates violations
               before acting answers much later or not at all. */
            Object outgoing = event.getPacket();
            if (outgoing instanceof net.minecraft.network.play.client.C02PacketUseEntity
                    || outgoing instanceof net.minecraft.network.play.client.C07PacketPlayerDigging
                    || outgoing instanceof net.minecraft.network.play.client.C08PacketPlayerBlockPlacement) {
                this.lastActionTick = this.tickCounter;
                if (outgoing instanceof net.minecraft.network.play.client.C08PacketPlayerBlockPlacement) {
                    this.placementsSent++;
                }
            }
            return;
        }
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        /* Everything below changes state that the client thread reads and
           reports from, so it runs there (F-33, 2026-09-28): it used to run
           right here on the network thread, with the report iterating the same
           sets and maps. Only the reading happens here -- the arrival time,
           and a payload's bytes, which may be gone by the time the client
           thread gets to them -- and the task is queued ahead of vanilla's own
           handling of the packet, so a correction is still compared with the
           position it corrects. */
        final Object packet = event.getPacket();
        final long now = System.currentTimeMillis();

        if (packet instanceof S3FPacketCustomPayload) {
            final Payload payload = readPayload((S3FPacketCustomPayload) packet);
            if (payload != null) {
                onClientThread(new Runnable() {
                    @Override
                    public void run() {
                        applyPayload(payload);
                    }
                });
            }
            return;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            /* Most are for other entities; only our own is recorded. */
            net.minecraft.client.entity.EntityPlayerSP player = mc.thePlayer;
            if (player == null || ((S12PacketEntityVelocity) packet).getEntityID() != player.getEntityId()) {
                return;
            }
        } else if (!(packet instanceof S01PacketJoinGame || packet instanceof S00PacketKeepAlive
                || packet instanceof S32PacketConfirmTransaction || packet instanceof S03PacketTimeUpdate
                || packet instanceof S08PacketPlayerPosLook || packet instanceof S02PacketChat
                || packet instanceof net.minecraft.network.play.server.S23PacketBlockChange)) {
            return;
        }
        onClientThread(new Runnable() {
            @Override
            public void run() {
                if (isEnabled()) {
                    handleReceived(packet, now);
                }
            }
        });
    }

    private void onClientThread(Runnable task) {
        mc.addScheduledTask(task);
    }

    /**
     * A new session on a different server starts the record over. A proxy
     * network sends a join for every backend, and those are hops within the
     * same session; ServerSession tells the two apart (2026-09-28). Before,
     * this compared addresses itself -- which is how leaving Pika for Hypixel
     * with the module on filed Hypixel's numbers under Pika until 08:39.
     */
    @EventTarget
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() == myau.events.SessionEvent.Type.START && event.serverChanged()) {
            resetAll();
        }
    }

    /** Client thread: one received packet, with the time it arrived. */
    private void handleReceived(Object packet, long now) {
        if (packet instanceof S01PacketJoinGame) {
            /* The first join of the record starts its clock; the rest are
               proxy hops, counted. */
            if (this.joinCount == 0) {
                this.joinedAt = now;
                this.address = address();
            }
            this.joinCount++;
            this.lastJoinTick = this.tickCounter;
            return;
        }

        if (packet instanceof S00PacketKeepAlive) {
            if (this.lastKeepAlive > 0L) {
                this.keepAlive.add(now - this.lastKeepAlive);
            }
            this.lastKeepAlive = now;
            return;
        }

        if (packet instanceof S32PacketConfirmTransaction) {
            S32PacketConfirmTransaction transactionPacket = (S32PacketConfirmTransaction) packet;
            this.transactionCount++;
            /* Window 0 is the player's own inventory, which is what an
               anticheat uses for timing; a real container has its own id. */
            Integer seen = this.transactionWindows.get((int) transactionPacket.getWindowId());
            this.transactionWindows.put((int) transactionPacket.getWindowId(),
                    seen == null ? 1 : seen + 1);
            if (this.lastTransaction > 0L) {
                this.transaction.add(now - this.lastTransaction);
            }
            this.lastTransaction = now;
            return;
        }

        if (packet instanceof S03PacketTimeUpdate) {
            if (this.lastTimeUpdate > 0L) {
                this.timeUpdate.add(now - this.lastTimeUpdate);
            }
            this.lastTimeUpdate = now;
            return;
        }

        if (packet instanceof S12PacketEntityVelocity && mc.thePlayer != null) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            if (velocity.getEntityID() == mc.thePlayer.getEntityId()) {
                double x = velocity.getMotionX() / 8000.0;
                double y = velocity.getMotionY() / 8000.0;
                double z = velocity.getMotionZ() / 8000.0;
                this.velocities++;
                this.velocityHorizontal.add(Math.sqrt(x * x + z * z));
                this.velocityVertical.add(y);
                this.lastVelocityTick = this.tickCounter;
            }
            return;
        }

        if (packet instanceof S08PacketPlayerPosLook && mc.thePlayer != null) {
            readCorrection((S08PacketPlayerPosLook) packet, now);
            return;
        }

        if (packet instanceof net.minecraft.network.play.server.S23PacketBlockChange) {
            /* Whether a refusal is stated or merely implied. Some
               implementations answer a rejected placement by setting the
               position back to air; others simply never place it and let the
               client find out by desyncing. */
            net.minecraft.network.play.server.S23PacketBlockChange change =
                    (net.minecraft.network.play.server.S23PacketBlockChange) packet;
            if (this.tickCounter - this.lastActionTick <= 20
                    && change.getBlockState() != null
                    && change.getBlockState().getBlock() == net.minecraft.init.Blocks.air) {
                this.placementsRefusedExplicitly++;
            }
            return;
        }

        if (packet instanceof S38PacketPlayerListItem) {
            return;
        }

        if (packet instanceof S02PacketChat) {
            readChat((S02PacketChat) packet);
        }
    }

    /**
     * Plugin channels and the brand. Channel registration arrives as a single
     * payload of null-separated names, and several anticheats register one --
     * the cheapest identification there is.
     */
    /** What one payload said: a brand, or channel names. */
    private static final class Payload {
        String brand;
        final List<String> channels = new ArrayList<String>();
    }

    /** Network thread: reads the payload now, while its buffer is still there. */
    private Payload readPayload(S3FPacketCustomPayload packet) {
        String channel = packet.getChannelName();
        if (channel == null) {
            return null;
        }
        Payload payload = new Payload();
        try {
            /* Read from a duplicate: same bytes, own reader index. This runs
               before vanilla's handleCustomPayload, which reads the same
               buffer; consuming it here is what produced the "readerIndex(23)
               + length(1) exceeds writerIndex(23)" error on every join that
               the notes had written off as Pika's malformed payload. */
            if ("MC|Brand".equals(channel)) {
                PacketBuffer buffer = packet.getBufferData() == null ? null
                        : new PacketBuffer(packet.getBufferData().duplicate());
                if (buffer != null && buffer.readableBytes() > 0) {
                    payload.brand = buffer.readStringFromBuffer(64);
                }
                return payload;
            }
            if ("REGISTER".equals(channel)) {
                PacketBuffer buffer = packet.getBufferData() == null ? null
                        : new PacketBuffer(packet.getBufferData().duplicate());
                if (buffer == null) {
                    return null;
                }
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                for (String name : new String(bytes, "UTF-8").split("\0")) {
                    if (!name.trim().isEmpty()) {
                        payload.channels.add(name.trim());
                    }
                }
                return payload;
            }
            payload.channels.add(channel);
            return payload;
        } catch (Exception ignored) {
            // A payload that does not parse is simply not a signal.
            return null;
        }
    }

    /** Client thread: records what readPayload found. */
    private void applyPayload(Payload payload) {
        if (!isEnabled()) {
            return;
        }
        if (payload.brand != null) {
            this.brand = payload.brand;
        }
        for (String name : payload.channels) {
            this.channels.add(name);
            matchName(name);
        }
    }

    private void readCorrection(S08PacketPlayerPosLook packet, long now) {
        java.util.Set<S08PacketPlayerPosLook.EnumFlags> flags = packet.func_179834_f();
        boolean relative = flags != null && !flags.isEmpty();
        double dx = (flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.X))
                ? packet.getX() : packet.getX() - mc.thePlayer.posX;
        double dz = (flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Z))
                ? packet.getZ() : packet.getZ() - mc.thePlayer.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        /* A correction only describes how this server polices movement if it
           was policing movement. Being placed after a death, an arena change or
           a lobby send arrives as the same packet and is the majority of them
           on a practice network -- fifteen join packets in eight minutes here.
           Counting those put the mean setback at 578 blocks, which is not a
           measurement of anything. */
        if (mc.thePlayer.isSpectator() || mc.thePlayer.isDead
                || mc.thePlayer.getHealth() <= 0.0F
                || this.tickCounter - this.lastJoinTick < 60
                || mc.thePlayer.ridingEntity != null) {
            this.teleports++;
            return;
        }
        if (horizontal >= TELEPORT_BLOCKS || Math.abs(packet.getY()) >= TELEPORT_BLOCKS) {
            this.teleports++;
            this.lastTeleportTick = this.tickCounter;
            return;
        }
        if (this.tickCounter - this.lastTeleportTick < 20) {
            /* The train of small corrections that settles a teleport belongs to
               the teleport. */
            this.teleports++;
            return;
        }

        this.corrections++;
        if (relative) {
            this.correctionsRelative++;
        }
        if (horizontal < 0.03) {
            this.corrections0Ground++;
        } else {
            this.correction.add(horizontal);
        }
        /* Corrections arriving in a train mean the server kept restating a
           position the client had not yet accepted; how readily that happens
           separates implementations. */
        boolean newTrain = this.lastCorrectionAt == 0L || now - this.lastCorrectionAt > 1000L;
        if (newTrain) {
            this.correctionBursts++;
            this.currentTrain = 1;
        } else {
            this.currentTrain++;
        }
        if (this.currentTrain > this.longestTrain) {
            this.longestTrain = this.currentTrain;
        }
        this.lastCorrectionAt = now;

        /* How long after this client last did something the answer came. */
        int since = this.tickCounter - this.lastActionTick;
        if (newTrain && since >= 0 && since <= 100) {
            this.reactionTicks.add(since);
        }
        /* A correction that also rewrites rotation is doing something
           different from one that only moves the body. */
        if (packet.getYaw() != 0.0F || packet.getPitch() != 0.0F) {
            this.correctionsWithLook++;
        }
        /* Y landing exactly on a block boundary is a ground re-anchor rather
           than a movement rejection. */
        double targetY = (flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Y))
                ? mc.thePlayer.posY + packet.getY() : packet.getY();
        if (Math.abs(targetY - Math.floor(targetY)) < 1.0E-4) {
            this.correctionsYSnapped++;
        }
        /* Paired with a velocity packet in the same tick: the server is
           stopping the player as well as moving them. */
        if (this.tickCounter - this.lastVelocityTick <= 1) {
            this.correctionsWithVelocity++;
        }
    }

    private void readChat(S02PacketChat packet) {
        String text;
        try {
            text = packet.getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase();
        for (String[] signature : SIGNATURES) {
            for (int i = 1; i < signature.length; i++) {
                if (lower.contains(signature[i])) {
                    this.hits.add(signature[0]);
                    Integer seen = this.chatMarkers.get(signature[0]);
                    this.chatMarkers.put(signature[0], seen == null ? 1 : seen + 1);
                    return;
                }
            }
        }
    }

    private void matchName(String name) {
        String lower = name.toLowerCase();
        for (String[] signature : SIGNATURES) {
            for (int i = 1; i < signature.length; i++) {
                if (lower.contains(signature[i])) {
                    this.hits.add(signature[0]);
                    return;
                }
            }
        }
    }

    /**
     * Name first, then the strings that imply it. Kept as plain substrings so
     * a channel, a brand and a chat line can all be matched by the same table.
     */
    private static final String[][] SIGNATURES = {
            {"Polar", "polar"},
            {"Grim", "grim", "grimac"},
            {"NoCheatPlus", "nocheatplus", "ncp"},
            {"AAC", "aac ", "advancedanticheat"},
            {"Matrix", "matrix"},
            {"Spartan", "spartan"},
            {"Vulcan", "vulcan"},
            {"Themis", "themis"},
            {"Karhu", "karhu"},
            {"Intave", "intave"},
            {"Verus", "verus"},
            {"Horizon", "horizon"},
            {"Watchdog", "watchdog"},
            {"Hawk", "hawkeye", "hawk anticheat"},
            {"Witherac", "witherac"},
            {"Antihaxerman", "antihaxerman"},
            {"Bukkit", "bukkit", "spigot", "paper", "purpur"},
            {"BungeeCord", "bungeecord", "waterfall", "velocity"}
    };

    private String address() {
        ServerData data = mc.getCurrentServerData();
        return data == null || data.serverIP == null ? "singleplayer" : data.serverIP;
    }

    // ---- reporting ----------------------------------------------------

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tickCounter++;
        long elapsed = System.currentTimeMillis() - this.joinedAt;
        if (!this.reported) {
            if (elapsed < this.reportAfter.getValue() * 1000L) {
                return;
            }
            this.reported = true;
            this.reportedSignals = signalCount();
            report();
            return;
        }
        /* Evidence that arrives later is worth as much as evidence that
           arrived first, and on a network the interesting backend is usually
           the second one joined. */
        if (this.followUp.getValue() && signalCount() > this.reportedSignals) {
            this.reportedSignals = signalCount();
            report();
        }
    }

    private int signalCount() {
        return this.channels.size() + this.hits.size() + this.chatMarkers.size()
                + (this.brand.isEmpty() ? 0 : 1);
    }

    /**
     * What the server's behaviour looks like, independently of what anything
     * calls itself.
     *
     * A brand and a plugin channel are hard evidence but easy to hide, and on a
     * proxy network they usually describe the proxy rather than the backend.
     * Behaviour cannot be hidden from the client being acted on: whether
     * movement is timed with transactions, how quickly an objection arrives,
     * whether a refusal is stated or left for the client to discover, and
     * whether a correction stops the player as well as moving them. Those are
     * what actually differ between implementations, and what makes two servers
     * comparable when neither has named itself.
     *
     * Deliberately not a brand name. Guessing "this is Polar" from timing would
     * be a guess wearing the clothes of a measurement; the point of writing the
     * numbers out is that the naming can be done later, by someone holding
     * several servers' worth of them.
     */
    private String behaviourClass() {
        StringBuilder sb = new StringBuilder();
        Integer ownWindow = this.transactionWindows.get(0);
        boolean timed = ownWindow != null && ownWindow > 20 && this.transaction.n > 10;
        sb.append(timed
                ? String.format("transaction-timed@%.0fms", this.transaction.mean())
                : "untimed");

        if (this.reactionTicks.n > 0) {
            double mean = this.reactionTicks.mean();
            sb.append(mean <= 6.0 ? "  immediate" : (mean <= 20.0 ? "  delayed" : "  accumulating"));
            sb.append(String.format("(%.0ft)", mean));
        }
        if (this.corrections > 0) {
            sb.append(this.correctionsRelative * 2 >= this.corrections ? "  relative" : "  absolute");
            if (this.correctionsWithVelocity * 2 >= this.corrections) {
                sb.append("  stops-you");
            }
            if (this.correctionsYSnapped * 2 >= this.corrections) {
                sb.append("  ground-anchor");
            }
            if (this.longestTrain >= 5) {
                sb.append("  insists(x").append(this.longestTrain).append(')');
            }
        }
        if (this.placementsSent > 0) {
            sb.append(this.placementsRefusedExplicitly > 0 ? "  states-refusals" : "  silent-refusals");
        }
        return sb.toString();
    }

    /** The guess, with what it rests on, so it can be disagreed with. */
    private String verdict() {
        if (this.hits.isEmpty()) {
            return anonymousVerdict();
        }
        StringBuilder sb = new StringBuilder();
        for (String name : this.hits) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * What can still be said when nothing named itself.
     *
     * A steady stream of transactions on the player's own window is the clearest
     * unnamed signal there is: vanilla has no reason to send those, so something
     * is timing the client with them.
     */
    private String anonymousVerdict() {
        Integer ownWindow = this.transactionWindows.get(0);
        boolean timing = ownWindow != null && ownWindow > 20 && this.transaction.n > 10;
        if (timing) {
            return String.format("unnamed, transaction-timed (~%.0fms)", this.transaction.mean());
        }
        if (this.corrections > 0) {
            return "unnamed, corrects movement";
        }
        return "nothing observed";
    }

    /**
     * One row per report, so several servers can be held side by side.
     *
     * The long report is for reading one server; comparison is a different job
     * and a paragraph is the wrong shape for it. Same columns every time, in a
     * file that only ever grows -- because a single server's numbers mean very
     * little until there is another server's to hold them against, and the
     * naming of what is behind them is done by whoever reads the collection.
     */
    private String csvRow() {
        Integer ownWindow = this.transactionWindows.get(0);
        return String.format(
                "%s,%s,%s,%.0f,%d,%.0f,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%s",
                new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()),
                (this.address.isEmpty() ? address() : this.address).replace(',', ' '),
                (this.brand.isEmpty() ? "-" : this.brand).replace(',', ' '),
                this.transaction.n == 0 ? -1.0 : this.transaction.mean(),
                ownWindow == null ? 0 : ownWindow.intValue(),
                this.reactionTicks.n == 0 ? -1.0 : this.reactionTicks.mean(),
                this.corrections,
                this.correctionsRelative,
                this.correctionsWithLook,
                this.correctionsYSnapped,
                this.correctionsWithVelocity,
                this.longestTrain,
                this.correction.n == 0 ? -1.0 : this.correction.mean(),
                this.placementsSent,
                this.placementsRefusedExplicitly,
                (this.hits.isEmpty() ? "unnamed" : this.hits.toString()).replace(',', ';'));
    }

    private static final String CSV_HEADER =
            "when,address,brand,transaction_ms,transaction_window0,reaction_ticks,"
                    + "corrections,relative,with_look,y_snapped,with_velocity,longest_train,"
                    + "setback_blocks,placements,refused_explicitly,named";

    private void writeCsv() {
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            File csv = new File(LOG_DIR, "server-profiles.csv");
            boolean fresh = !csv.exists();
            writer = new PrintWriter(new FileWriter(csv, true));
            if (fresh) {
                writer.println(CSV_HEADER);
            }
            writer.println(csvRow());
            writer.flush();
        } catch (Exception ignored) {
            // A missing row is not worth interrupting play for.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }


    private void report() {
        String text = buildReport();
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bFingerprint&7] &f" + verdict()
                    + " &8(&f" + signalCount() + "&7 signals&8)");
        }
        if (this.logFile.getValue()) {
            writeLog(text);
            writeCsv();
        }
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("================ ").append(new Date()).append(" ================\n");
        sb.append("address        ").append(this.address.isEmpty() ? address() : this.address).append('\n');
        sb.append("brand          ").append(this.brand.isEmpty() ? "-" : this.brand).append('\n');
        sb.append("verdict        ").append(verdict()).append('\n');
        sb.append("observed for   ")
                .append((System.currentTimeMillis() - this.joinedAt) / 1000).append("s, ")
                .append(this.joinCount).append(" join packet(s)\n");
        sb.append("channels       ").append(this.channels.isEmpty() ? "-" : this.channels).append('\n');
        sb.append("name hits      ").append(this.hits.isEmpty() ? "-" : this.hits).append('\n');
        sb.append("chat markers   ").append(this.chatMarkers.isEmpty() ? "-" : this.chatMarkers).append('\n');
        sb.append("keepalive ms   ").append(this.keepAlive).append('\n');
        sb.append("transaction ms ").append(this.transaction)
                .append("  total=").append(this.transactionCount)
                .append("  windows=").append(this.transactionWindows).append('\n');
        sb.append("timeupdate ms  ").append(this.timeUpdate).append('\n');
        sb.append("corrections    ").append(this.corrections)
                .append("  relative=").append(this.correctionsRelative)
                .append("  zero-move=").append(this.corrections0Ground)
                .append("  bursts=").append(this.correctionBursts).append('\n');
        sb.append("setback blocks ").append(this.correction).append('\n');
        sb.append("own velocity   n=").append(this.velocities)
                .append("  h=").append(this.velocityHorizontal)
                .append("  v=").append(this.velocityVertical).append('\n');
        sb.append("-- discriminators --").append((char) 10);
        sb.append("behaviour      ").append(behaviourClass()).append((char) 10);
        sb.append("reaction ticks ").append(this.reactionTicks).append((char) 10);
        sb.append("corr. w/look   ").append(this.correctionsWithLook)
                .append("  y-snapped=").append(this.correctionsYSnapped)
                .append("  w/velocity=").append(this.correctionsWithVelocity)
                .append("  longest-train=").append(this.longestTrain).append((char) 10);
        sb.append("placements     sent=").append(this.placementsSent)
                .append("  refused-explicitly=").append(this.placementsRefusedExplicitly).append((char) 10);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * One file for every server ever seen, appended to. The point of this
     * module is the collection: a single server's numbers mean little until
     * there is another server's to hold them against.
     */
    private void writeLog(String text) {
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            if (this.logTarget == null) {
                this.logTarget = new File(LOG_DIR, "servers.txt");
            }
            writer = new PrintWriter(new FileWriter(this.logTarget, true));
            writer.print(text);
            writer.flush();
        } catch (Exception ignored) {
            // Never interrupt play for a log line.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        if (!this.reported) {
            return new String[]{"listening"};
        }
        return new String[]{this.hits.isEmpty() ? behaviourClass() : verdict()};
    }
}
