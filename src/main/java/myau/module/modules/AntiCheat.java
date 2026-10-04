package myau.module.modules;

import myau.util.Ping;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports other players for the two things a client can actually measure.
 *
 * A client sees other players through packets that have already lost most of
 * what a detector would want: rotations arrive quantised to a byte (1.4
 * degrees), positions to a thirty-second of a block, and swings only as an
 * animation the server may not forward. Checks built on those -- clicks per
 * second, movement speed, whether someone is blocking -- produce noise, which
 * is why the ones in this module are limited to measurements taken against
 * this client's own position. That position is computed locally and is exact,
 * so a distance derived from it has no error term beyond the opponent's.
 *
 * REACH. When this player takes a hit, the attacker's distance is measured
 * across the whole latency window rather than at the moment the damage
 * arrives, and the smallest value found is the one tested. A player who was
 * ever legitimately in range inside that window is therefore never flagged,
 * whatever the packet timing did to the numbers.
 *
 * WALL. Same trigger, but asking whether any line of sight existed at all
 * during the window. Unlike distance this has no tolerance to argue about:
 * either a block was between the two players for every sample or it was not.
 *
 * Attribution is the weak point and is treated as such. Damage carries no
 * attacker, so the attacker is inferred from who swung recently and nearby,
 * and when more than one player fits, the event is discarded rather than
 * guessed at. Reports also need several separate hits, so a single unlucky
 * measurement never produces an accusation.
 *
 * The output is advisory. It is evidence for a report, not a verdict.
 */
public class AntiCheat extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY = 64;

    public final BooleanProperty checkReach = new BooleanProperty("reach", true);
    /* Vanilla lets the server accept up to 6 blocks; survival clients raytrace
       to 3. This sits above every honest value the measurement can produce at
       high latency, so what it catches is deliberate extension rather than
       jitter. */
    public final FloatProperty reachThreshold = new FloatProperty("reach-threshold", 3.7F, 3.2F, 6.0F,
            this.checkReach::getValue);
    public final BooleanProperty checkWall = new BooleanProperty("wall-hits", true);

    /** Separate hits needed before a player is named. */
    public final IntProperty hitsRequired = new IntProperty("hits-required", 2, 1, 10);
    public final IntProperty cooldown = new IntProperty("cooldown", 10, 1, 300);
    /** Extra ticks searched either side of the estimated round trip. */
    public final IntProperty windowPad = new IntProperty("window-pad", 4, 0, 20);
    public final FloatProperty candidateRange = new FloatProperty("candidate-range", 12.0F, 4.0F, 32.0F);

    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final double[] selfX = new double[HISTORY];
    private final double[] selfY = new double[HISTORY];
    private final double[] selfZ = new double[HISTORY];
    private int selfIndex;
    private int selfFilled;

    private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();
    private final Map<String, Violation> violations = new HashMap<String, Violation>();
    private final Set<String> named = new HashSet<String>();

    private int lastHurtTime;
    private int tickCounter;

    public AntiCheat() {
        super("AntiCheat", false, false,
                "Reports players who hit you from out of range or through blocks");
    }

    @Override
    public void onEnabled() {
        this.tracks.clear();
        this.violations.clear();
        this.named.clear();
        this.selfIndex = 0;
        this.selfFilled = 0;
        this.lastHurtTime = 0;
        this.tickCounter = 0;
    }

    @Override
    public void onDisabled() {
        this.tracks.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null || mc.isSingleplayer()) {
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
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer) {
                continue;
            }
            alive.add(player.getEntityId());
            Track track = this.tracks.get(player.getEntityId());
            if (track == null) {
                track = new Track();
                this.tracks.put(player.getEntityId(), track);
            }
            track.push(player, this.tickCounter);
        }
        Iterator<Map.Entry<Integer, Track>> it = this.tracks.entrySet().iterator();
        while (it.hasNext()) {
            if (!alive.contains(it.next().getKey())) {
                it.remove();
            }
        }

        /* hurtTime is set to its maximum the tick damage lands and counts down
           from there, so the rising edge is the event. */
        int hurtTime = mc.thePlayer.hurtTime;
        if (hurtTime > this.lastHurtTime && this.lastHurtTime == 0) {
            this.onDamaged();
        }
        this.lastHurtTime = hurtTime;
    }

    private int windowTicks() {
        int ping = this.ping();
        int ticks = ping > 0 ? (int) Math.ceil(ping / 50.0) : 4;
        ticks += this.windowPad.getValue();
        if (ticks < 2) {
            ticks = 2;
        }
        return Math.min(ticks, HISTORY - 1);
    }

    private int ping() {
        return Ping.own();
    }

    private void onDamaged() {
        int window = windowTicks();
        if (this.selfFilled <= window) {
            return;
        }

        /* Damage packets name no attacker, so it has to be inferred. Anything
           that swung inside the window and is close enough to have reached is
           a candidate; more than one and the event is dropped, because naming
           the wrong player is worse than missing a real one. */
        List<EntityPlayer> candidates = new ArrayList<EntityPlayer>();
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player.isDead) {
                continue;
            }
            Track track = this.tracks.get(player.getEntityId());
            if (track == null || track.filled <= window) {
                continue;
            }
            /* lastSwingTick starts at Integer.MIN_VALUE for "never swung", and
               tickCounter - MIN_VALUE overflows to a negative number, so a
               player who had never swung passed as a recent swinger. */
            if (track.lastSwingTick == Integer.MIN_VALUE
                    || (long) this.tickCounter - track.lastSwingTick > window + 4) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) > this.candidateRange.getValue()) {
                continue;
            }
            candidates.add(player);
        }
        if (candidates.size() != 1) {
            return;
        }

        EntityPlayer attacker = candidates.get(0);
        Track track = this.tracks.get(attacker.getEntityId());

        double closest = Double.MAX_VALUE;
        boolean sawEachOther = false;
        for (int back = 0; back <= window; back++) {
            Vec3 mine = this.selfAt(back);
            Vec3 theirs = track.at(back);
            if (mine == null || theirs == null) {
                continue;
            }
            Vec3 myEyes = new Vec3(mine.xCoord, mine.yCoord + mc.thePlayer.getEyeHeight(), mine.zCoord);

            double half = attacker.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(
                    theirs.xCoord - half, theirs.yCoord, theirs.zCoord - half,
                    theirs.xCoord + half, theirs.yCoord + attacker.height, theirs.zCoord + half);
            double distance = distanceToBox(myEyes, box);
            if (distance < closest) {
                closest = distance;
            }

            if (!sawEachOther) {
                Vec3 theirEyes = new Vec3(theirs.xCoord,
                        theirs.yCoord + attacker.getEyeHeight(), theirs.zCoord);
                if (mc.theWorld.rayTraceBlocks(theirEyes, myEyes, false, true, false) == null) {
                    sawEachOther = true;
                }
            }
        }
        if (closest == Double.MAX_VALUE) {
            return;
        }

        if (this.checkReach.getValue() && closest > this.reachThreshold.getValue()) {
            this.record(attacker.getName(), "Reach",
                    String.format("closest %.2f blocks over %dt", closest, window));
        }
        /* Blocks can be broken and placed between the samples and now, so a
           wall reported here is the wall as it stands, not necessarily as it
           stood. Requiring several hits is what keeps that honest. */
        if (this.checkWall.getValue() && !sawEachOther) {
            this.record(attacker.getName(), "WallHit",
                    String.format("no line of sight for %dt", window));
        }
    }

    /** Shortest distance from a point to a box, zero when inside it. */
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

    private void record(String name, String check, String detail) {
        String key = name + "/" + check;
        Violation violation = this.violations.get(key);
        if (violation == null) {
            violation = new Violation();
            this.violations.put(key, violation);
        }
        violation.count++;
        if (violation.count < this.hitsRequired.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - violation.lastReport < this.cooldown.getValue() * 1000L) {
            return;
        }
        violation.lastReport = now;
        this.named.add(name);

        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&cAntiCheat&7] &f%s&7 &c%s&7 &8(&f%dx&7, %s&8)",
                    name, check, violation.count, detail));
        }
        if (this.sound.getValue()) {
            SoundUtil.playSound("note.pling");
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.named.isEmpty()) {
            return new String[]{"clear"};
        }
        return new String[]{this.named.size() + " flagged"};
    }

    /** Position history and swing timing for one player. */
    private static class Track {
        final double[] x = new double[HISTORY];
        final double[] y = new double[HISTORY];
        final double[] z = new double[HISTORY];
        int index;
        int filled;
        int lastSwingTick = Integer.MIN_VALUE;
        float prevSwing;

        void push(EntityPlayer player, int tick) {
            this.x[this.index] = player.posX;
            this.y[this.index] = player.posY;
            this.z[this.index] = player.posZ;
            this.index = (this.index + 1) % HISTORY;
            if (this.filled < HISTORY) {
                this.filled++;
            }
            if (player.swingProgress > 0.0F && this.prevSwing == 0.0F) {
                this.lastSwingTick = tick;
            }
            this.prevSwing = player.swingProgress;
        }

        Vec3 at(int back) {
            if (back >= this.filled) {
                return null;
            }
            int at = ((this.index - 1 - back) % HISTORY + HISTORY) % HISTORY;
            return new Vec3(this.x[at], this.y[at], this.z[at]);
        }
    }

    private static class Violation {
        int count;
        long lastReport;
    }
}
