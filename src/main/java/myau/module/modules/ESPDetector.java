package myau.module.modules;

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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Reports players who are aiming at this client through walls.
 *
 * There is no such thing as hiding from ESP. The server broadcasts this
 * player's position to everyone nearby and the packets carrying it never
 * touch this client, so nothing here can stop another client from drawing a
 * box. What can be done is the reverse: other players' head rotations arrive
 * in the same stream, so their aim can be compared against where this player
 * actually is, and against whether they could possibly see it.
 *
 * The signal is aim held on an occluded target. Any single tick of that is
 * meaningless -- a player facing a wall is pointed at whatever is behind it,
 * and in a corridor that is often someone. What is not explainable by
 * coincidence is aim that *follows*: this player moves several blocks while
 * occluded, and the other player's crosshair stays on them the whole way.
 * That is why the movement requirement exists and why it is the setting to
 * raise first if the reports are wrong.
 *
 * Occlusion is decided by a block raytrace between the two eye positions,
 * which is the same test the game uses to decide whether an entity is
 * targetable. It is deliberately the strict version: if there is any line of
 * sight at all the sample is discarded, so a player who is genuinely watching
 * through a doorway never accumulates evidence.
 *
 * Reports are advisory. A high sample count is strong evidence of ESP or an
 * aura holding a target, but a player tracking a sound cue, or an entity
 * standing between the two, can produce a lower one.
 */
public class ESPDetector extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /* Every player inside this radius costs an angular test each tick, and
       the ones actually pointed at you cost a block raytrace on top. Sixty-four
       blocks is most of a Bedwars map; twenty covers the fights that matter. */
    public final FloatProperty range = new FloatProperty("range", 20.0F, 8.0F, 128.0F);
    /* Inside melee range everyone is pointed at everyone, and a wall between
       two players standing that close is usually a single block they are
       fighting around. */
    public final FloatProperty minDistance = new FloatProperty("min-distance", 6.0F, 0.0F, 32.0F);
    public final FloatProperty angle = new FloatProperty("angle", 8.0F, 1.0F, 45.0F);

    public final IntProperty window = new IntProperty("window", 20, 5, 100);
    public final IntProperty required = new IntProperty("required", 14, 2, 100);
    /** Blocks this player must cover inside the window for a report to count. */
    public final FloatProperty movement = new FloatProperty("movement", 1.5F, 0.0F, 10.0F);

    public final IntProperty cooldown = new IntProperty("cooldown", 15, 1, 300);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final Map<Integer, Tracker> trackers = new HashMap<Integer, Tracker>();
    private final Set<String> reported = new HashSet<String>();

    public ESPDetector() {
        super("ESPDetector", false, false,
                "Reports players whose aim follows you while they cannot see you");
    }

    @Override
    public void onEnabled() {
        this.trackers.clear();
        this.reported.clear();
    }

    @Override
    public void onDisabled() {
        this.trackers.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        Vec3 myEyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);
        double moved = Math.sqrt(
                Math.pow(mc.thePlayer.posX - mc.thePlayer.prevPosX, 2)
                        + Math.pow(mc.thePlayer.posY - mc.thePlayer.prevPosY, 2)
                        + Math.pow(mc.thePlayer.posZ - mc.thePlayer.prevPosZ, 2));

        Set<Integer> seen = new HashSet<Integer>();
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player.isDead || player.deathTime > 0) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance > this.range.getValue() || distance < this.minDistance.getValue()) {
                continue;
            }
            seen.add(player.getEntityId());
            this.sample(player, myEyes, moved);
        }

        Iterator<Map.Entry<Integer, Tracker>> it = this.trackers.entrySet().iterator();
        while (it.hasNext()) {
            if (!seen.contains(it.next().getKey())) {
                it.remove();
            }
        }
    }

    private void sample(EntityPlayer player, Vec3 myEyes, double moved) {
        Tracker tracker = this.trackers.get(player.getEntityId());
        if (tracker == null) {
            tracker = new Tracker(this.window.getValue());
            this.trackers.put(player.getEntityId(), tracker);
        }
        tracker.resize(this.window.getValue());

        Vec3 theirEyes = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);

        /* Order matters for cost, not for correctness. Comparing two angles is
           arithmetic; tracing a line through the world walks every block on the
           way and, run for every player every tick, is enough on its own to
           stall the client. Almost nobody is pointed at you at any given
           moment, so the cheap test decides whether the expensive one is worth
           running at all. */
        boolean aimed = this.aimError(player, theirEyes, myEyes) <= this.angle.getValue();
        boolean onTarget = false;
        if (aimed) {
            /* Any line of sight at all disqualifies the sample. rayTraceBlocks
               returns null when nothing was hit, which is the visible case. */
            onTarget = mc.theWorld.rayTraceBlocks(theirEyes, myEyes, false, true, false) != null;
        }
        /* Distance is only credited while they are actually tracking, which is
           the quantity the report is about: how far you went with their
           crosshair still on you. */
        tracker.push(onTarget, onTarget ? moved : 0.0);

        if (tracker.hits() < this.required.getValue()
                || tracker.movement() < this.movement.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - tracker.lastReport < this.cooldown.getValue() * 1000L) {
            return;
        }
        tracker.lastReport = now;
        this.report(player, tracker);
    }

    /** Angular distance between where they are looking and this player's eyes. */
    private double aimError(EntityPlayer player, Vec3 theirEyes, Vec3 myEyes) {
        double dx = myEyes.xCoord - theirEyes.xCoord;
        double dy = myEyes.yCoord - theirEyes.yCoord;
        double dz = myEyes.zCoord - theirEyes.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        /* Head yaw, not body yaw: the body lags the camera and in 1.8 is
           clamped relative to it, so comparing against it would both blur the
           signal and drift on every turn. */
        float yawError = MathHelper.wrapAngleTo180_float(wantYaw - player.rotationYawHead);
        float pitchError = MathHelper.wrapAngleTo180_float(wantPitch - player.rotationPitch);
        return Math.sqrt(yawError * yawError + pitchError * pitchError);
    }

    private void report(EntityPlayer player, Tracker tracker) {
        String name = player.getName();
        this.reported.add(name);
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&cESPDetector&7] &f%s&7 tracked you through walls &8(&f%d&7/%d samples, you moved &f%.1f&7 blocks&8)",
                    name, tracker.hits(), tracker.size(), tracker.movement()));
        }
        if (this.sound.getValue()) {
            SoundUtil.playSound("note.pling");
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.reported.isEmpty()) {
            return new String[]{"clear"};
        }
        return new String[]{this.reported.size() + " flagged"};
    }

    /** Rolling window of samples for one player. */
    private static class Tracker {
        private boolean[] samples;
        private double[] moved;
        private int index;
        private int filled;
        long lastReport;

        Tracker(int size) {
            this.samples = new boolean[Math.max(1, size)];
            this.moved = new double[Math.max(1, size)];
        }

        void resize(int size) {
            if (size == this.samples.length) {
                return;
            }
            this.samples = new boolean[Math.max(1, size)];
            this.moved = new double[Math.max(1, size)];
            this.index = 0;
            this.filled = 0;
        }

        void push(boolean onTarget, double distance) {
            this.samples[this.index] = onTarget;
            this.moved[this.index] = distance;
            this.index = (this.index + 1) % this.samples.length;
            if (this.filled < this.samples.length) {
                this.filled++;
            }
        }

        int hits() {
            int count = 0;
            for (int i = 0; i < this.filled; i++) {
                if (this.samples[i]) {
                    count++;
                }
            }
            return count;
        }

        /** Distance covered only while occluded, which is what needs following. */
        double movement() {
            double total = 0.0;
            for (int i = 0; i < this.filled; i++) {
                total += this.moved[i];
            }
            return total;
        }

        int size() {
            return this.filled;
        }
    }
}
