package myau.module.modules;

import myau.util.Ping;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.AsyncLog;
import myau.util.ChatUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One line per fight, to config/Myau/fights-<stamp>.txt: who, how long, who
 * hit first, hits each way, combos, how many of our hits carried sprint and
 * how far they pushed, how far we were pushed, and how it ended.
 *
 * Asked for 2026-09-25 so that changes meant to win fights can be judged by
 * numbers. HitCheck already showed 98% of swings landing (2026-09-24), so
 * accuracy is not what loses them; on BedFight a fight is lost by going
 * over the edge, which is knockback, dealt and taken. This counts both.
 *
 * - A hit of ours: a swing (C02 ATTACK, as sent) on a player whose hurt time
 *   then starts within 12 ticks. Its sprint is the sprint the server had
 *   when it arrived, from the last C0B sent -- the one that decides the
 *   extra knockback -- not the client's flag.
 * - A hit on us: our hurt time starting with an opponent within 6 blocks,
 *   given to the one we are already fighting, else the nearest.
 * - Knockback: the horizontal speed of the S12 the server sends for the
 *   player hit, within 4 ticks of the hit, when it sends one; and, always,
 *   how far the player hit was carried away from us over the next 6 ticks.
 * - The end: someone dies, drops 10 blocks below where they last took a hit
 *   (the void), or disappears while falling; 8 seconds without a hit either
 *   way; or a world change.
 */
public class FightLog extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    /* Room for a hit FakeLag holds back (hold-hits, up to ~7 ticks) on top of
       the round trip. */
    private static final int LAND_WINDOW = 16;
    private static final int KB_WINDOW = 4;
    private static final int PUSH_TICKS = 6;
    private static final int IDLE_TICKS = 160;
    private static final double FELL = 10.0;
    private static final double ATTRIBUTE_RANGE = 6.0;

    public final BooleanProperty chat = new BooleanProperty("chat", false);

    private static final class Swing {
        final int target;
        final int tick;
        final boolean sprint;

        Swing(int target, int tick, boolean sprint) {
            this.target = target;
            this.tick = tick;
            this.sprint = sprint;
        }
    }

    /** A hit of ours whose knockback is still being measured. */
    private static final class Push {
        final int target;
        final int tick;
        final boolean sprint;
        final double fromX, fromZ, awayX, awayZ;
        boolean velocitySeen;

        Push(int target, int tick, boolean sprint, double fromX, double fromZ, double awayX, double awayZ) {
            this.target = target;
            this.tick = tick;
            this.sprint = sprint;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.awayX = awayX;
            this.awayZ = awayZ;
        }
    }

    private static final class Fight {
        final String name;
        final long start = System.currentTimeMillis();
        final double ourStartY;
        String first;
        int lastAction;
        int landed, taken, combo, maxCombo, theirCombo, theirMaxCombo;
        int sprintHits;
        double velSprint, velPlain, pushSprint, pushPlain, velTaken;
        int velSprintN, velPlainN, pushSprintN, pushPlainN, velTakenN;
        double theirHitY = Double.NaN;
        int lastOurHit = -1000;
        double lastY;
        double lastMotionY;

        Fight(String name, double ourY, int tick) {
            this.name = name;
            this.ourStartY = ourY;
            this.lastAction = tick;
        }
    }

    private final Map<Integer, Fight> fights = new HashMap<Integer, Fight>();
    private final List<Swing> swings = new ArrayList<Swing>();
    private final List<Push> pushes = new ArrayList<Push>();
    private final Map<Integer, Integer> lastHurt = new HashMap<Integer, Integer>();
    /** Horizontal speed of each S12, by entity id, from the network thread. */
    private final ConcurrentLinkedQueue<double[]> velocities = new ConcurrentLinkedQueue<double[]>();
    private volatile boolean serverSprinting;
    private int tick;
    private int ourLastHurt;
    private int lastHitOnUs = -1000;
    private int lastAttacker = -1;
    private File target;
    private int won, lost, even;

    public FightLog() {
        super("FightLog", false, true, "One line per fight: hits, combos, knockback dealt and taken, and the outcome");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        endAll("log off");
    }

    private void reset() {
        this.fights.clear();
        this.swings.clear();
        this.pushes.clear();
        this.lastHurt.clear();
        this.velocities.clear();
        this.ourLastHurt = 0;
        this.lastAttacker = -1;
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        if (this.isEnabled()) {
            endAll("world change");
        }
        reset();
    }

    // -------------------------------------------------------------- packets

    /* LOWEST, and cancelled sends skipped (F-35, 2026-09-28): an attack that
       another module cancelled -- a REPEL click merged into a held one, an
       attack FakeLag queued -- was counted as a swing when it was made, with
       the sprint state of that moment, and again never when it really left.
       A held attack is counted when it is sent (noteAttackSent). */
    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
                double x = velocity.getMotionX() / 8000.0;
                double z = velocity.getMotionZ() / 8000.0;
                this.velocities.add(new double[]{velocity.getEntityID(), Math.sqrt(x * x + z * z)});
            }
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) event.getPacket()).getAction();
            if (action == C0BPacketEntityAction.Action.START_SPRINTING) {
                this.serverSprinting = true;
            } else if (action == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                this.serverSprinting = false;
            }
        } else if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }
            noteSwing(use.getEntityFromWorld(mc.theWorld));
        }
    }

    // ----------------------------------------------------------------- tick

    private void noteSwing(Entity entity) {
        if (entity instanceof EntityPlayer && opponent((EntityPlayer) entity)) {
            this.swings.add(new Swing(entity.getEntityId(), this.tick, this.serverSprinting));
        }
    }

    /** An attack sent without an event: a held one, released (FakeLag). Client thread. */
    public static void noteAttackSent(Entity entity) {
        Module module = myau.Myau.moduleManager == null ? null
                : myau.Myau.moduleManager.modules.get(FightLog.class);
        if (module instanceof FightLog && module.isEnabled() && mc.theWorld != null) {
            ((FightLog) module).noteSwing(entity);
        }
    }

    private static boolean opponent(EntityPlayer player) {
        return player != mc.thePlayer && !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player)
                && !TeamUtil.isBot(player);
    }

    private Fight fight(EntityPlayer player) {
        Fight fight = this.fights.get(player.getEntityId());
        if (fight == null) {
            fight = new Fight(player.getName(), mc.thePlayer.posY, this.tick);
            this.fights.put(player.getEntityId(), fight);
        }
        return fight;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tick++;
        ourHits();
        theirHits();
        knockback();
        endings();
    }

    /** Opponents whose hurt started this tick, matched to our swings. */
    private void ourHits() {
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            Integer before = this.lastHurt.put(player.getEntityId(), player.hurtTime);
            if (before == null || player.hurtTime <= before || !opponent(player)) {
                continue;
            }
            Swing swing = null;
            for (Swing candidate : this.swings) {
                if (candidate.target == player.getEntityId() && this.tick - candidate.tick <= LAND_WINDOW) {
                    swing = candidate;
                }
            }
            if (swing == null) {
                continue;
            }
            this.swings.remove(swing);
            Fight fight = fight(player);
            if (fight.first == null) {
                fight.first = "us";
            }
            fight.landed++;
            fight.combo++;
            fight.maxCombo = Math.max(fight.maxCombo, fight.combo);
            fight.theirCombo = 0;
            fight.lastAction = this.tick;
            fight.lastOurHit = this.tick;
            fight.theirHitY = player.posY;
            if (swing.sprint) {
                fight.sprintHits++;
            }
            double dx = player.posX - mc.thePlayer.posX;
            double dz = player.posZ - mc.thePlayer.posZ;
            double length = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
            this.pushes.add(new Push(player.getEntityId(), this.tick, swing.sprint, player.posX, player.posZ,
                    dx / length, dz / length));
        }
        Iterator<Swing> it = this.swings.iterator();
        while (it.hasNext()) {
            if (this.tick - it.next().tick > LAND_WINDOW) {
                it.remove();
            }
        }
    }

    /** Our hurt starting this tick, given to an opponent. */
    private void theirHits() {
        int hurt = mc.thePlayer.hurtTime;
        boolean started = hurt > this.ourLastHurt;
        this.ourLastHurt = hurt;
        if (!started) {
            return;
        }
        EntityPlayer attacker = null;
        double best = ATTRIBUTE_RANGE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || !opponent(player) || player.isDead) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance > ATTRIBUTE_RANGE) {
                continue;
            }
            if (this.fights.containsKey(player.getEntityId())) {
                distance -= ATTRIBUTE_RANGE;
            }
            if (distance < best) {
                best = distance;
                attacker = player;
            }
        }
        if (attacker == null) {
            return;
        }
        Fight fight = fight(attacker);
        if (fight.first == null) {
            fight.first = "them";
        }
        fight.taken++;
        fight.theirCombo++;
        fight.theirMaxCombo = Math.max(fight.theirMaxCombo, fight.theirCombo);
        fight.combo = 0;
        fight.lastAction = this.tick;
        this.lastHitOnUs = this.tick;
        this.lastAttacker = attacker.getEntityId();
    }

    private void knockback() {
        double[] velocity;
        while ((velocity = this.velocities.poll()) != null) {
            int id = (int) velocity[0];
            if (id == mc.thePlayer.getEntityId()) {
                Fight fight = this.fights.get(this.lastAttacker);
                if (fight != null && this.tick - this.lastHitOnUs <= KB_WINDOW) {
                    fight.velTaken += velocity[1];
                    fight.velTakenN++;
                }
                continue;
            }
            for (Push push : this.pushes) {
                if (push.target == id && !push.velocitySeen && this.tick - push.tick <= KB_WINDOW) {
                    push.velocitySeen = true;
                    Fight fight = this.fights.get(id);
                    if (fight != null) {
                        if (push.sprint) {
                            fight.velSprint += velocity[1];
                            fight.velSprintN++;
                        } else {
                            fight.velPlain += velocity[1];
                            fight.velPlainN++;
                        }
                    }
                    break;
                }
            }
        }
        Iterator<Push> it = this.pushes.iterator();
        while (it.hasNext()) {
            Push push = it.next();
            if (this.tick - push.tick < PUSH_TICKS) {
                continue;
            }
            it.remove();
            Entity entity = mc.theWorld.getEntityByID(push.target);
            Fight fight = this.fights.get(push.target);
            if (entity == null || fight == null) {
                continue;
            }
            double moved = (entity.posX - push.fromX) * push.awayX + (entity.posZ - push.fromZ) * push.awayZ;
            if (push.sprint) {
                fight.pushSprint += moved;
                fight.pushSprintN++;
            } else {
                fight.pushPlain += moved;
                fight.pushPlainN++;
            }
        }
    }

    private void endings() {
        boolean weDied = mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.isDead;
        Iterator<Map.Entry<Integer, Fight>> it = this.fights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Fight> entry = it.next();
            Fight fight = entry.getValue();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            String result = null;
            if (weDied) {
                result = "WE DIED";
            } else if (mc.thePlayer.posY < fight.ourStartY - FELL && nothingBelow(mc.thePlayer)) {
                result = "WE FELL";
            } else if (entity instanceof EntityPlayer && (((EntityPlayer) entity).getHealth() <= 0.0F
                    || ((EntityPlayer) entity).deathTime > 0)) {
                result = this.tick - fight.lastOurHit <= 60 ? "THEY DIED" : "THEY DIED (not our hit)";
            } else if (entity != null && !Double.isNaN(fight.theirHitY) && entity.posY < fight.theirHitY - FELL
                    && nothingBelow(entity)) {
                result = this.tick - fight.lastOurHit <= 100 ? "THEY FELL" : "THEY FELL (not our hit)";
            } else if (entity == null) {
                result = fight.lastMotionY < -0.5 && this.tick - fight.lastOurHit <= 100 ? "THEY FELL" : "OUT OF SIGHT";
            } else if (this.tick - fight.lastAction > IDLE_TICKS) {
                result = "BROKE OFF";
            }
            if (entity != null) {
                fight.lastY = entity.posY;
                fight.lastMotionY = entity.posY - entity.lastTickPosY;
            }
            if (result != null) {
                write(fight, result);
                it.remove();
            }
        }
    }

    /* 10 blocks down with ground under them is someone jumping off a tower,
       not someone going into the void (2026-09-25: "WE FELL" after a 3-0
       fight). Only an empty column all the way down counts. */
    private static boolean nothingBelow(Entity entity) {
        int x = net.minecraft.util.MathHelper.floor_double(entity.posX);
        int z = net.minecraft.util.MathHelper.floor_double(entity.posZ);
        for (int y = net.minecraft.util.MathHelper.floor_double(entity.posY); y >= 0; y--) {
            if (!mc.theWorld.isAirBlock(new net.minecraft.util.BlockPos(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private void endAll(String reason) {
        for (Fight fight : this.fights.values()) {
            write(fight, reason.toUpperCase());
        }
        this.fights.clear();
    }

    // --------------------------------------------------------------- output

    private static String avg(double sum, int n) {
        return n == 0 ? "-" : String.format("%.2f", sum / n);
    }

    private void write(Fight fight, String result) {
        if (fight.landed + fight.taken == 0) {
            return;
        }
        if (result.startsWith("THEY DIED") && !result.contains("not") || result.equals("THEY FELL")) {
            this.won++;
        } else if (result.startsWith("WE")) {
            this.lost++;
        } else {
            this.even++;
        }
        String line = String.format("%s  FIGHT vs %s | %.1fs | first %s | hits %d-%d | combo %d vs %d"
                        + " | sprint-hits %d/%d | kb dealt vel sprint %s plain %s, pushed sprint %s plain %s"
                        + " | kb taken vel %s | ping %d | %s | session won %d lost %d other %d",
                LINE_STAMP.format(new Date()), fight.name, (System.currentTimeMillis() - fight.start) / 1000.0,
                fight.first == null ? "-" : fight.first, fight.landed, fight.taken, fight.maxCombo, fight.theirMaxCombo,
                fight.sprintHits, fight.landed,
                avg(fight.velSprint, fight.velSprintN), avg(fight.velPlain, fight.velPlainN),
                avg(fight.pushSprint, fight.pushSprintN), avg(fight.pushPlain, fight.pushPlainN),
                avg(fight.velTaken, fight.velTakenN), ping(), result, this.won, this.lost, this.even);
        if (this.target == null) {
            this.target = new File(LOG_DIR, "fights-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.target, line);
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format("&7fight vs &f%s&7: hits &f%d-%d&7, sprint &f%d/%d &8| %s",
                    fight.name, fight.landed, fight.taken, fight.sprintHits, fight.landed,
                    result.startsWith("THEY") ? "&a" + result : result.startsWith("WE") ? "&c" + result : "&7" + result));
        }
    }

    private int ping() {
        return Ping.own();
    }
}
