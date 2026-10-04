package myau.util;

import myau.property.properties.BooleanProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block placement for AutoBlockIn. (Clutch has its own, older and measured;
 * see ENGINEERING-NOTES "Clutch".)
 *
 * History (2026-10-02): a first version of this class, AutoBlockIn and a
 * reduced Clutch was compiled outside the Gradle build, against hand-written
 * stand-ins for the Minecraft classes, and patched straight into the
 * installed jar. In those stand-ins AxisAlignedBB.minX..maxZ and
 * Vec3.xCoord..zCoord were constant fields, so javac inlined every read of
 * them as the literal 0.0 (29 sites), and both modules did nothing. Never
 * compile these classes any other way than through Gradle.
 *
 * Timing. UpdateEvent PRE runs at the head of EntityPlayerSP.onUpdate: the
 * player has not moved yet this tick, so the eyes are where the server has
 * them, and the movement packet of this tick has not gone out. A click sent
 * here is followed by that packet, and Grim's RotationPlace ("post-flying")
 * judges the click by the rotation in it, from the position at the click --
 * as vanilla does, whose click always uses the look about to be sent.
 *
 * Corrected 2026-10-02 (22:07-22:11 on test.ccbluex.net, every AutoBlockIn
 * block but the last flagged): this used to click along the rotation the
 * server already had and then send the turn toward the next target in the
 * same tick's packet. The caller now casts the ray along the rotation it is
 * about to send, and clicks only if that ray hits.
 */
public final class PlaceUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** How far inside a face the aim points sit. */
    public static final double INSET = 0.1;
    /** Aim points per face edge minus one: a 5x5 grid. */
    public static final int GRID = 4;
    /** Rays one search may cast before it gives up for this tick. */
    public static final int MAX_RAYS = 96;
    /** Rays one support face may fail before its remaining points are skipped. */
    private static final int MAX_FACE_MISSES = 4;

    private PlaceUtil() {
    }

    /* ---- geometry ------------------------------------------------------- */

    public static MovingObjectPosition ray(Vec3 eye, float yaw, float pitch, double reach) {
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        Vec3 end = eye.addVector(sinYaw * cosPitch * reach, sinPitch * reach, cosYaw * cosPitch * reach);
        return mc.theWorld.rayTraceBlocks(eye, end, false, false, false);
    }

    /** Rotation from the eye to a point, the yaw kept within 180 of refYaw. */
    public static float[] rotationsTo(Vec3 eye, Vec3 target, float refYaw) {
        double dx = target.xCoord - eye.xCoord;
        double dy = target.yCoord - eye.yCoord;
        double dz = target.zCoord - eye.zCoord;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        yaw = refYaw + MathHelper.wrapAngleTo180_float(yaw - refYaw);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    /** Angular distance between two rotations, in degrees. */
    public static double angle(float yaw, float pitch, float toYaw, float toPitch) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - yaw);
        float dPitch = toPitch - pitch;
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    /**
     * One turn toward a rotation, at most maxStep degrees along the straight
     * line: yaw and pitch are scaled together, so both arrive on the same
     * tick (proportional, no randomness). Snapped to the mouse grid.
     */
    public static float[] step(float yaw, float pitch, float toYaw, float toPitch, float maxStep) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - yaw);
        float dPitch = toPitch - pitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (dist < 1.0E-4F) {
            return new float[]{yaw, pitch};
        }
        float scale = dist <= maxStep ? 1.0F : maxStep / dist;
        return quantize(yaw, pitch, yaw + dYaw * scale, pitch + dPitch * scale);
    }

    public static float[] quantize(float fromYaw, float fromPitch, float yaw, float pitch) {
        double gcd = RotationUtil.gcd();
        float outYaw = yaw;
        float outPitch = pitch;
        if (gcd > 0.0) {
            outYaw = fromYaw + (float) (Math.round(MathHelper.wrapAngleTo180_float(yaw - fromYaw) / gcd) * gcd);
            outPitch = fromPitch + (float) (Math.round((pitch - fromPitch) / gcd) * gcd);
        }
        return new float[]{outYaw, MathHelper.clamp_float(outPitch, -90.0F, 90.0F)};
    }

    public static boolean boxIntersects(AxisAlignedBB box, BlockPos pos) {
        return box.intersectsWith(new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
    }

    /** A block that can be clicked to place against without opening anything. */
    public static boolean clickable(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && !BlockUtil.isInteractable(pos);
    }

    /* ---- items ---------------------------------------------------------- */

    public static boolean usableBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        /* Never TNT, sand/gravel, or anything with a tile entity (chests and
           the like): an ItemBlock alone is not something safe to wall with. */
        return block.isFullCube() && block.getMaterial().isSolid() && block != Blocks.tnt
                && !(block instanceof BlockFalling) && !(block instanceof ITileEntityProvider)
                && !BlockUtil.isInteractable(block);
    }

    /** ItemBlock.canPlaceBlockOnSide: the support/face pair is accepted and nothing (no entity) is in the way. */
    public static boolean canPlace(ItemStack stack, BlockPos support, EnumFacing face) {
        return usableBlock(stack)
                && ((ItemBlock) stack.getItem()).canPlaceBlockOnSide(mc.theWorld, support, face, mc.thePlayer, stack);
    }

    /* ---- aims ----------------------------------------------------------- */

    /** A support block, the face to click on it, and the rotation that hits it. */
    public static final class Aim {
        public final BlockPos support;
        public final EnumFacing face;
        /** Where the block appears: support offset by face. */
        public final BlockPos cell;
        public final float yaw;
        public final float pitch;
        /** Degrees of turning from the rotation the search started at. */
        public final double cost;
        /** True when the starting rotation itself hits this face: no turn needed. */
        public final boolean current;
        /** Set once verified by a ray; null for an unverified candidate. */
        public final Vec3 hitVec;

        Aim(BlockPos support, EnumFacing face, float yaw, float pitch, double cost, boolean current, Vec3 hitVec) {
            this.support = support;
            this.face = face;
            this.cell = support.offset(face);
            this.yaw = yaw;
            this.pitch = pitch;
            this.cost = cost;
            this.current = current;
            this.hitVec = hitVec;
        }

        public Aim verified(Vec3 hit, boolean current) {
            return new Aim(this.support, this.face, this.yaw, this.pitch, current ? 0.0 : this.cost, current, hit);
        }

        public FaceKey key() {
            return new FaceKey(this.support, this.face);
        }

        @Override
        public String toString() {
            return String.format("%d,%d,%d %s -> %d,%d,%d @%.1f/%.1f cost %.1f%s",
                    support.getX(), support.getY(), support.getZ(), face,
                    cell.getX(), cell.getY(), cell.getZ(), yaw, pitch, cost, current ? " (current)" : "");
        }
    }

    /** A support block and face, the unit a failed click invalidates. */
    public static final class FaceKey {
        public final BlockPos support;
        public final EnumFacing face;

        public FaceKey(BlockPos support, EnumFacing face) {
            this.support = support;
            this.face = face;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FaceKey)) {
                return false;
            }
            FaceKey k = (FaceKey) o;
            return k.face == this.face && k.support.equals(this.support);
        }

        @Override
        public int hashCode() {
            return this.support.hashCode() * 31 + this.face.ordinal();
        }
    }

    /** What the last search did, for the debug trace. */
    public static final class Stats {
        public int candidates;
        public int rays;
        public String source = "none";

        public void reset() {
            this.candidates = 0;
            this.rays = 0;
            this.source = "none";
        }
    }

    /**
     * The aim a ray from this rotation already lands on, if it places into
     * one of the goals and is placeable; null otherwise.
     */
    public static Aim fromRotation(Collection<BlockPos> goals, Vec3 eye, double reach, float yaw, float pitch,
                                   ItemStack stack, Set<FaceKey> excluded) {
        MovingObjectPosition hit = ray(eye, yaw, pitch, reach);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        BlockPos support = hit.getBlockPos();
        EnumFacing face = hit.sideHit;
        if (!goals.contains(support.offset(face)) || !clickable(support)
                || excluded.contains(new FaceKey(support, face)) || !canPlace(stack, support, face)) {
            return null;
        }
        return new Aim(support, face, yaw, pitch, 0.0, true, hit.hitVec);
    }

    /** Whether a ray at this rotation hits exactly this support block and face. */
    public static MovingObjectPosition verify(Vec3 eye, double reach, float yaw, float pitch, BlockPos support, EnumFacing face) {
        MovingObjectPosition hit = ray(eye, yaw, pitch, reach);
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(support) && hit.sideHit == face) {
            return hit;
        }
        return null;
    }

    /**
     * The cheapest placement into any of the goals, measured as turning from
     * (yaw, pitch):
     *
     *  1. if a ray at (yaw, pitch) already places into a goal, that, at cost 0
     *     -- the rotation is kept;
     *  2. else the locked aim, if it is still valid (its cell still a goal,
     *     its face not excluded, still placeable and its ray still hitting) --
     *     so a target is not swapped for a marginally cheaper one every tick;
     *  3. else every grid point on every visible support face next to a goal,
     *     in increasing turn cost, raycast in that order: the first whose ray
     *     hits its own support block and face is the cheapest valid one.
     *
     * Bounded: at most MAX_RAYS rays, and a face is dropped after
     * MAX_FACE_MISSES misses.
     */
    public static Aim bestAim(Collection<BlockPos> goalList, Vec3 eye, double reach, float yaw, float pitch,
                              Aim locked, ItemStack stack, Set<FaceKey> excluded, Stats stats) {
        if (goalList.isEmpty()) {
            return null;
        }
        Set<BlockPos> goals = goalList instanceof Set ? (Set<BlockPos>) goalList : new HashSet<BlockPos>(goalList);

        stats.rays++;
        Aim now = fromRotation(goals, eye, reach, yaw, pitch, stack, excluded);
        if (now != null) {
            stats.source = "current";
            return now;
        }

        if (locked != null && goals.contains(locked.cell) && clickable(locked.support)
                && !excluded.contains(locked.key()) && canPlace(stack, locked.support, locked.face)) {
            stats.rays++;
            MovingObjectPosition hit = verify(eye, reach, locked.yaw, locked.pitch, locked.support, locked.face);
            if (hit != null) {
                stats.source = "locked";
                return new Aim(locked.support, locked.face, locked.yaw, locked.pitch,
                        angle(yaw, pitch, locked.yaw, locked.pitch), false, hit.hitVec);
            }
        }

        List<Aim> candidates = new ArrayList<Aim>();
        for (BlockPos goal : goals) {
            for (EnumFacing toSupport : EnumFacing.values()) {
                BlockPos support = goal.offset(toSupport);
                EnumFacing face = toSupport.getOpposite();
                if (!clickable(support) || excluded.contains(new FaceKey(support, face))
                        || !canPlace(stack, support, face)) {
                    continue;
                }
                addFace(candidates, support, face, eye, reach, yaw, pitch);
            }
        }
        stats.candidates = candidates.size();
        Collections.sort(candidates, new Comparator<Aim>() {
            @Override
            public int compare(Aim a, Aim b) {
                return Double.compare(a.cost, b.cost);
            }
        });

        Map<FaceKey, Integer> misses = new HashMap<FaceKey, Integer>();
        int rays = 0;
        for (Aim candidate : candidates) {
            if (rays >= MAX_RAYS) {
                break;
            }
            FaceKey key = candidate.key();
            Integer missed = misses.get(key);
            if (missed != null && missed >= MAX_FACE_MISSES) {
                continue;
            }
            rays++;
            MovingObjectPosition hit = verify(eye, reach, candidate.yaw, candidate.pitch, candidate.support, candidate.face);
            if (hit != null) {
                stats.rays += rays;
                stats.source = "search";
                return candidate.verified(hit.hitVec, false);
            }
            misses.put(key, missed == null ? 1 : missed + 1);
        }
        stats.rays += rays;
        stats.source = "none";
        return null;
    }

    /** Grid points on one face of a support block, if the face looks toward the eye. */
    private static void addFace(List<Aim> out, BlockPos support, EnumFacing face, Vec3 eye, double reach,
                                float yaw, float pitch) {
        int fx = face.getFrontOffsetX();
        int fy = face.getFrontOffsetY();
        int fz = face.getFrontOffsetZ();
        double planeX = fx > 0 ? support.getX() + 1 : support.getX();
        double planeY = fy > 0 ? support.getY() + 1 : support.getY();
        double planeZ = fz > 0 ? support.getZ() + 1 : support.getZ();
        /* A face can only be hit from the side it faces. */
        if (fx != 0 && (eye.xCoord - planeX) * fx <= 0.0
                || fy != 0 && (eye.yCoord - planeY) * fy <= 0.0
                || fz != 0 && (eye.zCoord - planeZ) * fz <= 0.0) {
            return;
        }
        double reachSq = reach * reach;
        for (int i = 0; i <= GRID; i++) {
            double u = INSET + (1.0 - 2.0 * INSET) * i / GRID;
            for (int j = 0; j <= GRID; j++) {
                double v = INSET + (1.0 - 2.0 * INSET) * j / GRID;
                double x = fx != 0 ? planeX : support.getX() + u;
                double y = fy != 0 ? planeY : support.getY() + (fx != 0 ? u : v);
                double z = fz != 0 ? planeZ : support.getZ() + v;
                Vec3 point = new Vec3(x, y, z);
                if (eye.squareDistanceTo(point) > reachSq) {
                    continue;
                }
                float[] rot = rotationsTo(eye, point, yaw);
                out.add(new Aim(support, face, rot[0], rot[1], angle(yaw, pitch, rot[0], rot[1]), false, null));
            }
        }
    }

    /**
     * Sends the placement. Returns PlayerControllerMP.onPlayerRightClick's
     * own answer; the arm is swung only when it is true. False means nothing
     * was placed and the caller must treat the aim as failed.
     */
    public static boolean click(Aim aim) {
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (aim.hitVec == null || !canPlace(stack, aim.support, aim.face)) {
            return false;
        }
        boolean placed = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack,
                aim.support, aim.face, aim.hitVec);
        if (placed) {
            mc.thePlayer.swingItem();
        }
        return placed;
    }

    /* ---- debug trace ---------------------------------------------------- */

    /**
     * Stage-by-stage trace, only while the module's debug switch is on.
     *
     * Every line goes to config/Myau/placedebug-<time>.txt. Chat gets at most
     * CHAT_PER_SECOND lines a second. A stage repeating the same message is
     * written again only after REPEAT_TICKS, so a module sitting in one state
     * leaves one line every two seconds, not twenty.
     */
    public static final class Trace {
        private static final File LOG_DIR = new File("./config/Myau/");
        private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
        private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
        private static final int REPEAT_TICKS = 40;
        private static final int CHAT_PER_SECOND = 4;

        private final String tag;
        private final BooleanProperty enabled;
        private final Map<String, String> lastMessage = new HashMap<String, String>();
        private final Map<String, Integer> lastTick = new HashMap<String, Integer>();
        private File target;
        private int chatWindow = Integer.MIN_VALUE;
        private int chatLines;

        public Trace(String tag, BooleanProperty enabled) {
            this.tag = tag;
            this.enabled = enabled;
        }

        public boolean on() {
            return this.enabled.getValue();
        }

        public void log(int tick, String stage, String message) {
            if (!this.on()) {
                return;
            }
            String previous = this.lastMessage.get(stage);
            Integer at = this.lastTick.get(stage);
            if (message.equals(previous) && at != null && tick - at < REPEAT_TICKS) {
                return;
            }
            this.lastMessage.put(stage, message);
            this.lastTick.put(stage, tick);
            String line = this.tag + " " + stage + (message.isEmpty() ? "" : ": " + message);
            if (this.target == null) {
                this.target = new File(LOG_DIR, "placedebug-" + FILE_STAMP.format(new Date()) + ".txt");
            }
            AsyncLog.append(this.target, LINE_STAMP.format(new Date()) + "  t" + tick + "  " + line);
            if (tick - this.chatWindow >= 20) {
                this.chatWindow = tick;
                this.chatLines = 0;
            }
            if (this.chatLines < CHAT_PER_SECOND) {
                this.chatLines++;
                ChatUtil.sendFormatted("&8" + line.replace("&", ""));
            }
        }

        /** Forgets what was said, so the next run's stages print again. */
        public void reset() {
            this.lastMessage.clear();
            this.lastTick.clear();
        }
    }
}
