package myau.module.modules;

import myau.util.Ping;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.RightClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Withholds outgoing movement, and gives it back in a shape the server can
 * accept.
 *
 * The previous version counted movement packets and dumped the lot when the
 * count was reached. That gets corrected, and it is worth being precise about
 * why, because the obvious answer is wrong: the server does not object to a
 * client that stops sending. Silence is indistinguishable from a stall and
 * every server tolerates it. It objects to the moment the silence ends, and
 * two separate things go wrong there.
 *
 * THE DISTANCE. While packets are held the client keeps walking and the
 * server's copy does not. On release the server learns all of it at once, and
 * if that is further than latency alone explains, it corrects. So the cost is
 * measured in blocks of drift, not ticks held -- forty ticks standing still
 * costs nothing, forty ticks sprinting is ten blocks and a guaranteed setback.
 * The hold therefore ends on distance, with the packet count only a ceiling.
 *
 * THE BURST. All the held packets land in one tick, so the server processes a
 * second of movement in fifty milliseconds. Even where the total distance is
 * fine, the rate is not -- that is a speed check failing, not a teleport check.
 *
 * So the queue drains a couple of packets per tick, which is the same
 * information arriving at the speed a client would have sent it. Catching up
 * takes about as long as the blink did, and that is the price of it not being
 * a burst.
 *
 * Neither makes this undetectable, and it should not be sold as such: a server
 * looking specifically for a movement stream that pauses and resumes can still
 * find one. What they remove is the part that gets acted on -- the correction,
 * the setback, the lost fight.
 *
 * The marker is the other half. Blink's real difficulty is that its cost is
 * invisible: nothing on screen says how far the server's idea of you has
 * fallen behind, so the first sign of trouble is being dragged backwards. A
 * box drawn at the last position the server actually received turns that into
 * something visible before it becomes something expensive.
 */
public class Blink extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /**
     * Which direction to hold.
     *
     * Outgoing is the useful one and the only one that is safe here: holding
     * received packets instead means acting on a world several hundred
     * milliseconds stale, which after {@link KnockbackDelay} we have measured
     * the price of. It is offered because it is what the word normally means,
     * not because it is recommended.
     */
    public final ModeProperty direction = new ModeProperty("direction", 0,
            new String[]{"OUTGOING", "BOTH"});
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"DEFAULT", "PULSE"});

    /**
     * Whether to release on a limit at all.
     *
     * Off, matching Vape: the module holds until it is switched off, and the
     * limits below only drive the warning colour. On, the first limit reached
     * ends the hold.
     */
    public final BooleanProperty autoSend = new BooleanProperty("auto-send", false);
    /** Packets held before the queue is released. Zero means no limit. */
    public final IntProperty threshold = new IntProperty("send-threshold", 50, 0, 600,
            this.autoSend::getValue);

    /**
     * Blocks of drift allowed before the hold ends, whatever the count says.
     *
     * The number that actually decides whether a correction follows. Around
     * three blocks is what a round trip at this latency already explains, so
     * staying under it means the release looks like ordinary lag.
     */
    public final FloatProperty maxDrift = new FloatProperty("max-drift", 3.0F, 0.5F, 20.0F);
    /** Total staleness the server's view may carry. The ping is already in it. */
    public final IntProperty latencyBudget = new IntProperty("latency-budget", 900, 200, 4000);
    public final BooleanProperty pingAware = new BooleanProperty("ping-aware", true);
    /** Drop the queue when the server states a position rather than arguing. */
    public final BooleanProperty releaseOnCorrection = new BooleanProperty("release-on-correction", true);

    /**
     * Switch Blink off by itself before the server does it for you.
     *
     * Every Blink rollback in the logs (09-19 to 09-22) landed 0-2 s after the
     * hold began; holds that were ended by hand inside that window mostly
     * went through. The rollback comes from the hold running long -- in time,
     * or in how far the player got from where the server last saw them -- so
     * either limit ends it. The release is the same one the keybind uses.
     * Only applies when auto-send is off; auto-send has its own limits.
     */
    public final BooleanProperty autoOff = new BooleanProperty("auto-off", true,
            () -> !this.autoSend.getValue());
    /** Milliseconds a hold may last before auto-off releases it. A starting point, not a measured edge. */
    public final IntProperty timeout = new IntProperty("timeout", 1000, 250, 10000,
            () -> !this.autoSend.getValue() && this.autoOff.getValue());
    private long anchoredAt;

    /**
     * Movement packets released per tick when catching up.
     *
     * One is the honest figure -- exactly the rate a client sends them. Two
     * halves the catch-up and is still a rate a stuttering connection could
     * produce. Much above that is the burst again.
     */
    public final IntProperty releaseRate = new IntProperty("release-per-tick", 2, 1, 20);

    /**
     * Hold transaction replies too, in order with the movement (every blink,
     * whoever owns it -- BlinkManager.holdTransactions).
     *
     * Grim times the client by its replies, so replies that keep flowing while
     * movement is held make any release "more movement than the time allows"
     * -- Timer, then a setback (22:10:49, 22:10:55, 2026-10-02). Replies queued
     * with the movement make the hold look like lag, which is what Raven Alter
     * does. On by default since the 22:51-22:59 games: Grim, 25 holds, Timer
     * 11 -> 0; Pika, 5 holds, no disconnect. The old "Pika kicks a late reply"
     * rule (ENGINEERING-NOTES §2) dates from the drain bug that held replies
     * for seconds without end; turn this off if that disconnect comes back.
     */
    public final BooleanProperty holdTransactions = new BooleanProperty("hold-transactions", true);

    /**
     * While holding, send the oldest held packet every this many ticks
     * (Raven Alter's "Release packet every"); 0 = never. The queue then grows
     * more slowly than one packet a tick and the final release is smaller.
     * It does not by itself stop Grim's Timer: that is hold-transactions.
     */
    public final IntProperty releaseEvery = new IntProperty("release-every", 0, 0, 20);

    /**
     * Placing a block ends the hold: everything held goes first, in order,
     * then the placement. A block placed inside a hold is judged by the server
     * against a position it learns late, and gets refused (22:52:42, 2026-10-02:
     * REJECT while "holding Blink/Blink out 14 for 21016ms", PULSE; the same as
     * AntiVoid's 09-25 bursts, which is why AntiVoid already lets go on a
     * right click with a block).
     */
    public final BooleanProperty releaseOnPlace = new BooleanProperty("release-on-place", true);


    public final BooleanProperty notify = new BooleanProperty("announce-release", true,
            this.autoSend::getValue);
    public final BooleanProperty marker = new BooleanProperty("server-position", true);
    public final BooleanProperty breadcrumbs = new BooleanProperty("breadcrumbs", true);

    /**
     * Set when something asks for this to stop, so the queue is flushed from
     * the tick rather than from wherever the request came from.
     *
     * Vape does this and it matters: switching off from a key handler, a
     * command, or another module's decision means the flush runs at that
     * moment -- possibly on the network thread, possibly mid-frame, possibly
     * while the queue is being appended to. Deferring it to the next tick puts
     * every release on the client thread in a known state, and costs at most
     * one tick of extra hold.
     */
    private boolean stopRequested;
    private String releaseReason = "";

    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private boolean anchored;
    private boolean releasing;
    private int heldTicks;
    private double drift;
    private final List<double[]> trail = new ArrayList<double[]>();

    public Blink() {
        super("Blink", false, false,
                "Holds outgoing movement and releases it at a rate the server accepts");
        /* Read by the manager for every blink, this module on or off. */
        myau.management.BlinkManager.holdTransactions = this.holdTransactions::getValue;
    }

    private int ping() {
        return Ping.own();
    }

    /** Milliseconds of hold the connection leaves available. */
    private int budget() {
        if (!this.pingAware.getValue()) {
            return Integer.MAX_VALUE;
        }
        int ping = ping();
        if (ping <= 0) {
            /* No reading is not the same as no budget.
            
               Returning zero here made the limit fire on the first tick, so
               every hold ended instantly with nothing queued -- "released on
               latency budget (0.0 blocks, 1 packet)". The tab list has no
               entry for this player for a moment after joining, and the
               reading is transiently absent at other times too, so a missing
               ping is ordinary rather than exceptional. Without a number to
               subtract, this limit simply has nothing to say and stands
               aside; the drift and the packet count still apply. */
            return Integer.MAX_VALUE;
        }
        return Math.max(0, this.latencyBudget.getValue() - ping);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!Myau.blinkManager.getBlinkingModule().equals(BlinkModules.BLINK)) {
            this.setEnabled(false);
            return;
        }

        /* A screen being open means the player is not driving any more, and
           holding movement they are not making has no purpose -- Vape stops
           here too. */
        if (mc.currentScreen != null) {
            this.stopRequested = true;
        }
        if (this.stopRequested) {
            this.stopRequested = false;
            this.releasing = true;
        }

        if (this.releasing) {
            drain();
            return;
        }
        if (!this.anchored) {
            anchor();
        }
        this.heldTicks++;
        if (this.releaseEvery.getValue() > 0 && this.heldTicks % this.releaseEvery.getValue() == 0) {
            /* The oldest held packet, whatever it is, as Raven Alter does. A
               position in it is where the server now has the player, so the
               marker and the drift move up to it. */
            Packet<?> oldest = Myau.blinkManager.blinkedPackets.poll();
            if (oldest != null) {
                Myau.blinkManager.release(oldest);
                if (oldest instanceof C03PacketPlayer && ((C03PacketPlayer) oldest).isMoving()) {
                    C03PacketPlayer moved = (C03PacketPlayer) oldest;
                    this.anchorX = moved.getPositionX();
                    this.anchorY = moved.getPositionY();
                    this.anchorZ = moved.getPositionZ();
                }
            }
        }

        if (this.breadcrumbs.getValue() && this.heldTicks % 3 == 0
                && (mc.thePlayer.motionX != 0.0 || mc.thePlayer.motionZ != 0.0)) {
            /* Offset alternately to either side of the facing, which turns the
               trail into a visible ribbon instead of a line hidden inside the
               player model. Vape's trick, and it is the difference between a
               trail you can follow and one you cannot see. */
            int sign = (this.trail.size() % 2 == 0) ? 1 : -1;
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            this.trail.add(new double[]{
                    mc.thePlayer.posX + 0.2 * sign * Math.cos(yaw),
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ + 0.2 * sign * Math.sin(yaw)});
            while (this.trail.size() > 200) {
                this.trail.remove(0);
            }
        }

        double dx = mc.thePlayer.posX - this.anchorX;
        double dy = mc.thePlayer.posY - this.anchorY;
        double dz = mc.thePlayer.posZ - this.anchorZ;
        this.drift = Math.sqrt(dx * dx + dy * dy + dz * dz);

        /* Nothing here releases unless asked to.
        
           Blink is a manual tool -- Vape's own description is "chokes packets
           until disabled" -- and the cases it is for are exactly the ones where
           an automatic release ruins it: held through a fall so the server
           never learns you left the edge, held through a burst you want to
           arrive late. A limit that fires on its own picks the moment for you,
           and it will pick the wrong one.
           
           The drift and the budget are still measured every tick, and the
           marker still reddens as they run out. Knowing when to let go and
           being made to let go are different things, and only the first is
           wanted by default. */
        if (!this.autoSend.getValue()) {
            if (this.autoOff.getValue()) {
                long held = System.currentTimeMillis() - this.anchoredAt;
                boolean timedOut = held >= this.timeout.getValue();
                boolean drifted = this.drift >= this.maxDrift.getValue();
                if (timedOut || drifted) {
                    /* Wall-clock, not ticks: a stuttering client runs fewer
                       ticks per second but the server's patience is measured
                       in real time. */
                    myau.util.ChatUtil.sendFormatted(String.format(
                            "&7[&bBlink&7] auto-off on &f%s &8(&f%dms&7, &f%.1f&7 blocks, &f%d&7 packets&8)",
                            timedOut ? "timeout" : "drift", held, this.drift,
                            Myau.blinkManager.blinkedPackets.size()));
                    this.setEnabled(false);
                }
            }
            return;
        }
        boolean driftReached = this.maxDrift.getValue() > 0.0F
                && this.drift >= this.maxDrift.getValue();
        boolean countReached = this.threshold.getValue() > 0
                && Myau.blinkManager.blinkedPackets.size() >= this.threshold.getValue();
        boolean budgetSpent = this.pingAware.getValue() && this.heldTicks * 50 >= budget();

        if (driftReached || countReached || budgetSpent) {
            /* Which limit ended it, said out loud.
            
               Releasing on its own is the right default for someone who wants
               the safety, but a hold that ends without explanation is
               indistinguishable from one that broke -- and the three limits
               want different responses. Drift means move less or raise the
               allowance; the count means the queue filled faster than expected;
               the budget means the connection is too slow for a hold this long
               and no setting will change that. */
            /* Which limit ended it, said out loud.
            
               Releasing on its own is the right default for someone who wants
               the safety, but a hold that ends without explanation is
               indistinguishable from one that broke -- and the three limits
               want different responses. Drift means move less or raise the
               allowance; the count means the queue filled faster than expected;
               the budget means the connection is too slow for a hold this long
               and no setting will change that. */
            this.releaseReason = driftReached ? "drift"
                    : (countReached ? "packet limit" : "latency budget");
            if (this.notify.getValue()) {
                myau.util.ChatUtil.sendFormatted(String.format(
                        "&7[&bBlink&7] released on &f%s &8(&f%.1f&7 blocks, &f%d&7 packets&8)",
                        this.releaseReason, this.drift,
                        Myau.blinkManager.blinkedPackets.size()));
            }
            this.releasing = true;
        }
    }

    /**
     * Sends a few held packets, at the rate a client would have produced them.
     *
     * Non-movement packets pass through without counting against the rate: an
     * attack or a placement stuck behind the movement is not what a speed check
     * measures, and holding it longer only makes it later than it already is.
     */
    private void drain() {
        int allowance = this.releaseRate.getValue();
        while (allowance > 0) {
            Packet<?> packet = Myau.blinkManager.blinkedPackets.poll();
            if (packet == null) {
                finishRelease();
                return;
            }
            Myau.blinkManager.release(packet);
            if (packet instanceof C03PacketPlayer) {
                allowance--;
            }
        }
    }

    private void anchor() {
        this.anchorX = mc.thePlayer.posX;
        this.anchorY = mc.thePlayer.posY;
        this.anchorZ = mc.thePlayer.posZ;
        this.anchored = true;
        this.anchoredAt = System.currentTimeMillis();
        this.heldTicks = 0;
        this.drift = 0.0;
        this.trail.clear();
    }

    /**
     * Sends a few held packets, at the rate a client would have produced them.
     *
     * Non-movement packets pass through without counting against the rate: an
     * attack or a placement stuck behind the movement is not what a speed check
     * measures, and holding it longer only makes it later than it already is.
     */
    private void finishRelease() {
        this.releasing = false;
        this.anchored = false;
        this.trail.clear();
        if (this.mode.getValue() == 1) {
            anchor();
            return;
        }
        this.setEnabled(false);
    }

    /**
     * Vanilla's right click, before it sends anything: with a block in hand and
     * the crosshair on a block it is a placement, and the hold ends first, here
     * on the client thread, so the queue goes out ahead of the click and the
     * click itself is not held (see releaseOnPlace).
     */
    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled() || !this.releaseOnPlace.getValue() || mc.thePlayer == null
                || Myau.blinkManager.getBlinkingModule() != BlinkModules.BLINK
                || !myau.util.ItemUtil.isHoldingBlock() || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (this.notify.getValue()) {
            myau.util.ChatUtil.sendFormatted(String.format(
                    "&7[&bBlink&7] released on &fplacing a block &8(&f%.1f&7 blocks, &f%d&7 packets&8)",
                    this.drift, Myau.blinkManager.blinkedPackets.size()));
        }
        this.setEnabled(false);
    }

    /**
     * A correction means the server has already decided where this client is.
     * Everything still queued describes a journey from a place that no longer
     * exists, and sending it argues with a decision already made -- which is
     * what turns one correction into a train of them.
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !this.releaseOnCorrection.getValue()) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            /* The movement is dropped; held transaction replies still go. */
            Myau.blinkManager.discardHeld();
            this.setEnabled(false);
        }
    }

    /**
     * Where the server still thinks this player is, and the path it has not
     * been told about.
     *
     * A box rather than a spawned entity. Putting a real player into the world
     * to stand at that spot would render more convincingly and would also mean
     * the client's own entity list disagreeing with the server's, which is a
     * class of bug this does not need and a thing other modules would then see
     * and target.
     */
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !this.anchored) {
            return;
        }
        double renderX = mc.getRenderManager().viewerPosX;
        double renderY = mc.getRenderManager().viewerPosY;
        double renderZ = mc.getRenderManager().viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GL11.glLineWidth(1.6F);

        if (this.breadcrumbs.getValue() && this.trail.size() > 1) {
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int i = 0; i < this.trail.size(); i++) {
                double[] point = this.trail.get(i);
                /* The first crumb is where the server still believes you are,
                   so it gets its own colour; the rest fade forward to now. */
                float along = i / (float) Math.max(1, this.trail.size() - 1);
                if (i == 0) {
                    GL11.glColor4f(1.0F, 0.9F, 0.25F, 0.9F);
                } else {
                    GL11.glColor4f(0.4F + 0.6F * along, 0.8F - 0.5F * along, 1.0F - 0.6F * along, 0.55F);
                }
                GL11.glVertex3d(point[0] - renderX, point[1] - renderY, point[2] - renderZ);
            }
            GL11.glEnd();
        }

        if (this.marker.getValue()) {
            /* Reddens as the drift approaches the limit, so the warning
               arrives before the correction does. */
            float heat = (float) Math.min(1.0, this.drift / Math.max(0.1F, this.maxDrift.getValue()));
            GL11.glColor4f(0.35F + 0.65F * heat, 1.0F - 0.75F * heat, 0.45F - 0.35F * heat, 0.75F);
            double half = mc.thePlayer.width / 2.0;
            double minX = this.anchorX - half - renderX;
            double minY = this.anchorY - renderY;
            double minZ = this.anchorZ - half - renderZ;
            double maxX = this.anchorX + half - renderX;
            double maxY = this.anchorY + mc.thePlayer.height - renderY;
            double maxZ = this.anchorZ + half - renderZ;
            outline(minX, minY, minZ, maxX, maxY, maxZ);
        }

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    /** The twelve edges of a box, drawn directly so no helper's colour or
        depth settings have to be guessed at. */
    private static void outline(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glBegin(GL11.GL_LINES);
        double[][] edges = {
                {x1, y1, z1, x2, y1, z1}, {x2, y1, z1, x2, y1, z2},
                {x2, y1, z2, x1, y1, z2}, {x1, y1, z2, x1, y1, z1},
                {x1, y2, z1, x2, y2, z1}, {x2, y2, z1, x2, y2, z2},
                {x2, y2, z2, x1, y2, z2}, {x1, y2, z2, x1, y2, z1},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1},
                {x2, y1, z2, x2, y2, z2}, {x1, y1, z2, x1, y2, z2}
        };
        for (double[] edge : edges) {
            GL11.glVertex3d(edge[0], edge[1], edge[2]);
            GL11.glVertex3d(edge[3], edge[4], edge[5]);
        }
        GL11.glEnd();
    }

    @EventTarget(whenDisabled = true)
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        this.anchored = false;
        this.releasing = false;
        this.heldTicks = 0;
        this.drift = 0.0;
        this.stopRequested = false;
        this.releaseReason = "";
        this.trail.clear();
        Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
        Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
        this.anchored = false;
        this.releasing = false;
        this.trail.clear();
    }

    /** True while the held queue is being handed back; read by FlagDetector's DRAIN-STALL. */
    public boolean isReleasing() {
        return this.releasing;
    }

    @Override
    public String[] getSuffix() {
        if (this.releasing) {
            return new String[]{(this.releaseReason.isEmpty() ? "catching up " : this.releaseReason + " ")
                    + Myau.blinkManager.blinkedPackets.size()};
        }
        return new String[]{String.format("%.1f/%.1f  %d", this.drift, this.maxDrift.getValue(),
                Myau.blinkManager.blinkedPackets.size())};
    }
}
