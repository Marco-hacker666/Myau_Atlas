package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.RightClickMouseEvent;
import myau.events.SwapItemEvent;
import myau.events.UpdateEvent;
import myau.management.Arbiter;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.BlockUtil;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.PlaceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.block.Block;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walls the player in: the ring around the body first, then whatever support
 * a ring cell needs, then the roof, then the roof's support.
 *
 * Each tick (UpdateEvent PRE, before this tick's movement packet): plan the
 * cheapest placement in the first tier that has one, take one turn toward it,
 * and click if a ray along that turned rotation hits it -- the rotation the
 * movement packet right after the click carries, which is what the server
 * (Grim RotationPlace) judges the click by. See PlaceUtil for the timing.
 *
 * A click that onPlayerRightClick answers false placed nothing: its support
 * face is set aside for a moment and the next plan picks another. A cell
 * failing that way max-retries times, or refused by the server that many
 * times, is left alone for retry-cooldown.
 */
public class AutoBlockIn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int PRIORITY = 6;
    private static final EnumFacing[] HORIZONTAL = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
    /** Ticks a support face is skipped after a click on it was answered false. */
    private static final int FAILED_FACE_TICKS = 10;
    /** Ticks a placement waits for the server's answer before it is forgotten. */
    private static final int ANSWER_TICKS = 30;

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    /** Degrees of turn per tick. */
    public final IntProperty speed = new IntProperty("speed", 45, 5, 180);
    public final IntProperty placeInterval = new IntProperty("place-interval", 1, 1, 5);
    public final IntProperty swapDelay = new IntProperty("swap-delay", 1, 0, 3);
    public final IntProperty maxRetries = new IntProperty("max-retries", 3, 1, 10);
    /** Milliseconds a refused cell is left alone. */
    public final IntProperty retryCooldown = new IntProperty("retry-cooldown", 2000, 250, 10000);
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true);
    public final BooleanProperty showProgress = new BooleanProperty("show-progress", true);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    /** Placements sent and not yet answered: cell -> tick sent. */
    private final Map<BlockPos, Integer> pending = new ConcurrentHashMap<BlockPos, Integer>();
    /** Failures per cell, client (click false) and server (stated back as air). */
    private final Map<BlockPos, Integer> attempts = new ConcurrentHashMap<BlockPos, Integer>();
    /** Cells left alone until this time (ms). */
    private final Map<BlockPos, Long> refusedUntil = new ConcurrentHashMap<BlockPos, Long>();
    /** Support faces whose click was answered false: key -> tick they may be used again. */
    private final Map<PlaceUtil.FaceKey, Integer> failedFaces = new ConcurrentHashMap<PlaceUtil.FaceKey, Integer>();

    private final PlaceUtil.Stats stats = new PlaceUtil.Stats();
    private final PlaceUtil.Trace trace = new PlaceUtil.Trace("[AB]", this.debug);

    private volatile int tick;
    private volatile int pauseTicks;
    private PlaceUtil.Aim locked;
    private int lastPlaceTick = -100;
    private int swapWait;
    private int lastSlot = -1;
    private float progress;
    /* Yaw steps, for Grim DuplicateRotPlace (its memory is server-side, so
       these are kept across enables): the last non-zero one sent, and the one
       that was last when a block was placed. */
    private float lastTurn;
    private float placedTurn = -1.0F;

    public AutoBlockIn() {
        super("AutoBlockIn", false);
    }

    @Override
    public void onEnabled() {
        this.resetState();
        this.trace.reset();
        this.lastSlot = mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1;
        this.trace.log(this.tick, "enabled", "");
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && this.lastSlot >= 0 && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
        this.resetState();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
    }

    private void resetState() {
        this.locked = null;
        this.pending.clear();
        this.attempts.clear();
        this.refusedUntil.clear();
        this.failedFaces.clear();
        this.pauseTicks = 0;
        this.swapWait = 0;
        this.lastPlaceTick = -100;
        this.progress = 0.0F;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            return;
        }
        try {
            this.update(event);
        } catch (RuntimeException e) {
            this.trace.log(this.tick, "EXCEPTION", e.toString());
            throw e;
        }
    }

    private void update(UpdateEvent event) {
        final int now = ++this.tick;
        this.trace.log(now, "tick", "");
        this.pending.entrySet().removeIf(entry -> now - entry.getValue() > ANSWER_TICKS);
        this.failedFaces.entrySet().removeIf(entry -> now >= entry.getValue());
        final long ms = System.currentTimeMillis();
        this.refusedUntil.entrySet().removeIf(entry -> ms >= entry.getValue());

        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            this.locked = null;
            this.trace.log(now, "stopped", "server moved us, pausing");
            return;
        }
        if (Arbiter.catching()) {
            this.locked = null;
            this.trace.log(now, "stopped", "standing aside for " + Arbiter.catcher());
            return;
        }

        List<BlockPos> body = this.bodyCells();
        Set<BlockPos> bodySet = new HashSet<BlockPos>(body);
        List<BlockPos> sides = this.sideGoals(body, bodySet);
        List<BlockPos> roofs = this.roofGoals(body);
        this.updateProgress(body, bodySet);
        if (sides.isEmpty() && roofs.isEmpty()) {
            this.locked = null;
            if (this.pending.isEmpty()) {
                this.trace.log(now, "done", "no open goal, nothing pending");
                if (this.autoDisable.getValue()) {
                    ChatUtil.sendFormatted("&7[&aAutoBlockIn&7] &fblocked in, switching off");
                    this.setEnabled(false);
                }
            } else {
                this.trace.log(now, "waiting", this.pending.size() + " placement(s) unanswered");
            }
            return;
        }
        this.trace.log(now, "activation conditions passed", "sides " + sides.size() + ", roof " + roofs.size()
                + ", body " + body.size() + " cells");

        int slot = this.findBestBlockSlot();
        if (slot < 0) {
            this.locked = null;
            this.trace.log(now, "stopped", "no usable blocks in hotbar");
            return;
        }
        if (mc.thePlayer.inventory.currentItem != slot) {
            mc.thePlayer.inventory.currentItem = slot;
            this.swapWait = this.swapDelay.getValue();
            this.trace.log(now, "swap", "slot " + slot + ", waiting " + this.swapWait);
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();

        /* The rotation and eye position the server holds. */
        float serverYaw = event.getYaw();
        float serverPitch = event.getPitch();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double reach = this.range.getValue();

        PlaceUtil.Aim aim = this.plan(body, bodySet, sides, roofs, eye, reach, serverYaw, serverPitch, stack, now);
        this.locked = aim;
        if (aim == null) {
            if (this.swapWait > 0) {
                this.swapWait--;
            }
            return;
        }
        /* The rotation this tick's movement packet will carry. */
        float[] rotation = aim.current ? new float[]{serverYaw, serverPitch}
                : PlaceUtil.step(serverYaw, serverPitch, aim.yaw, aim.pitch, this.speed.getValue());
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));
        /* Also against the server's own memory (PlaceRotations, 2026-10-04). */
        if (turn > 2.0F && (Math.abs(turn - this.placedTurn) < 1.0E-3F
                || myau.management.PlaceRotations.wouldDuplicate(rotation[0]))) {
            /* Grim DuplicateRotPlace flags a placement whose yaw step equals the
               one before the previous placement; symmetric walls make equal
               steps likely. One mouse count more breaks the tie. */
            double gcd = myau.util.RotationUtil.gcd();
            rotation[0] += Math.signum(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw))
                    * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));
        }
        if (turn > 0.0F) {
            this.lastTurn = turn;
        }

        /* The click goes out now, before this tick's movement packet, and the
           server judges it by the rotation that packet carries, from where the
           player is now (Grim RotationPlace "post-flying"; vanilla does the
           same: the click uses the look about to be sent). So the ray is cast
           along `rotation`. Clicking along the rotation the server already had
           and then turning toward the next cell in the same packet got every
           block but the last flagged (2026-10-02 22:07 and 22:11). */
        if (this.swapWait <= 0 && now - this.lastPlaceTick >= this.placeInterval.getValue()) {
            MovingObjectPosition hit = PlaceUtil.verify(eye, reach, rotation[0], rotation[1], aim.support, aim.face);
            if (hit != null) {
                PlaceUtil.Aim shot = aim.verified(hit.hitVec, true);
                boolean placed = PlaceUtil.click(shot);
                this.trace.log(now, "controller click result", placed + " " + shot);
                if (placed) {
                    this.lastPlaceTick = now;
                    this.pending.put(aim.cell, now);
                    this.locked = null;
                    this.placedTurn = this.lastTurn;
                    this.trace.log(now, "success", "placed at " + fmt(aim.cell));
                } else {
                    this.invalidate(shot, now, "click returned false");
                }
            }
        }
        if (this.swapWait > 0) {
            this.swapWait--;
        }
        this.trace.log(now, "rotation calculated", String.format("%.1f/%.1f -> %.1f/%.1f (target %.1f/%.1f)",
                serverYaw, serverPitch, rotation[0], rotation[1], aim.yaw, aim.pitch));
        event.setRotation(rotation[0], rotation[1], PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? rotation[0] : mc.thePlayer.rotationYaw, PRIORITY);
        if (this.moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Myau.rotationManager.setRotation(rotation[0], rotation[1], PRIORITY, false);
        }
    }

    /**
     * The cheapest valid aim in the first tier that has one:
     * walls, support for walls, roof, support for the roof.
     */
    private PlaceUtil.Aim plan(List<BlockPos> body, Set<BlockPos> bodySet, List<BlockPos> sides, List<BlockPos> roofs,
                               Vec3 eye, double reach, float yaw, float pitch, ItemStack stack, int now) {
        Set<PlaceUtil.FaceKey> excluded = this.failedFaces.keySet();
        String[] names = {"sides", "side-support", "roof", "roof-support"};
        for (int tier = 0; tier < 4; tier++) {
            List<BlockPos> goals;
            switch (tier) {
                case 0:
                    goals = sides;
                    break;
                case 1:
                    goals = this.supportGoals(sides, bodySet);
                    break;
                case 2:
                    goals = roofs;
                    break;
                default:
                    goals = this.supportGoals(roofs, bodySet);
                    break;
            }
            if (goals.isEmpty()) {
                continue;
            }
            this.stats.reset();
            PlaceUtil.Aim aim = PlaceUtil.bestAim(goals, eye, reach, yaw, pitch, this.locked, stack, excluded, this.stats);
            this.trace.log(now, "target " + names[tier], goals.size() + " goal(s), candidate count = "
                    + this.stats.candidates + ", rays " + this.stats.rays + ", result " + this.stats.source);
            if (aim != null) {
                this.trace.log(now, "best candidate selected", names[tier] + " " + aim);
                return aim;
            }
        }
        this.trace.log(now, "no candidate", "nothing reachable in any tier");
        return null;
    }

    /** A click answered false: drop this face for a moment, count it against the cell. */
    private void invalidate(PlaceUtil.Aim aim, int now, String why) {
        this.failedFaces.put(aim.key(), now + FAILED_FACE_TICKS);
        this.locked = null;
        int failed = this.attempts.merge(aim.cell, 1, Integer::sum);
        if (failed >= this.maxRetries.getValue()) {
            this.refuse(aim.cell);
        }
        this.trace.log(now, "failure", why + " at " + fmt(aim.cell) + " (" + failed + "x), re-planning");
    }

    private void refuse(BlockPos cell) {
        this.refusedUntil.put(cell, System.currentTimeMillis() + this.retryCooldown.getValue());
        this.attempts.remove(cell);
        /* onPacket calls this on the network thread; the trace writes chat,
           which must only be touched from the client thread. */
        if (mc.isCallingFromMinecraftThread()) {
            this.trace.log(this.tick, "refused", fmt(cell) + " for " + this.retryCooldown.getValue() + "ms");
        }
    }

    /**
     * The player's own right click while walling in is a second placement
     * between two movement packets (Grim MultiPlace), often against a block
     * the server has not applied yet (AirLiquidPlace).
     */
    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    public int getSlot() {
        return this.lastSlot;
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() && this.moveFix.getValue() == 1 && RotationState.isActived()
                && RotationState.getPriority() == PRIORITY && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    /**
     * The server's answers. Runs on the network thread, so it reads the new
     * state from the packet and touches only the concurrent maps.
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.pauseTicks = 2;
            this.pending.clear();
            return;
        }
        if (!(event.getPacket() instanceof S23PacketBlockChange)) {
            return;
        }
        S23PacketBlockChange change = (S23PacketBlockChange) event.getPacket();
        BlockPos pos = change.getBlockPosition();
        if (pos == null || this.pending.remove(pos) == null) {
            return;
        }
        if (change.getBlockState() != null && BlockUtil.isReplaceable(change.getBlockState().getBlock())) {
            int failed = this.attempts.merge(pos, 1, Integer::sum);
            if (failed >= this.maxRetries.getValue()) {
                this.refuse(pos);
            }
        } else {
            this.attempts.remove(pos);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.showProgress.getValue() || mc.currentScreen != null || mc.fontRendererObj == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        String text = String.format("Blocking: %.0f%%", this.progress * 100.0F);
        int color = this.progress <= 0.33F ? 0xFF5555 : this.progress <= 0.66F ? 0xFFFF55 : 0x55FF55;
        mc.fontRendererObj.drawString(text,
                sr.getScaledWidth() / 2.0F - mc.fontRendererObj.getStringWidth(text) / 2.0F,
                sr.getScaledHeight() / 5.0F * 2.0F, color | 0xBF000000, true);
    }

    /* ---- material ------------------------------------------------------- */

    /* Keyed by the block itself, lower preferred. It was once keyed by
       getUnlocalizedName() strings and five of nine never matched (whiteStone,
       wood, stainedGlass, clayHardened, clayHardenedStained), so end stone,
       planks and clay were never chosen. A constant that does not exist is a
       compile error; a string that does not exist is silence (NOTES 4.5). */
    private static final Map<Block, Integer> BLOCK_SCORE = new HashMap<Block, Integer>();
    /** Anything else PlaceUtil.usableBlock accepts, used only when nothing listed is in the hotbar. */
    private static final int FALLBACK_SCORE = 100;

    static {
        BLOCK_SCORE.put(Blocks.obsidian, 0);
        BLOCK_SCORE.put(Blocks.end_stone, 1);
        BLOCK_SCORE.put(Blocks.planks, 2);
        BLOCK_SCORE.put(Blocks.log, 2);
        BLOCK_SCORE.put(Blocks.log2, 2);
        BLOCK_SCORE.put(Blocks.glass, 3);
        BLOCK_SCORE.put(Blocks.stained_glass, 3);
        BLOCK_SCORE.put(Blocks.hardened_clay, 4);
        BLOCK_SCORE.put(Blocks.stained_hardened_clay, 4);
        BLOCK_SCORE.put(Blocks.wool, 5);
    }

    /** The hotbar slot with the best-scored usable block, the held one on a tie; -1 if none. */
    private int findBestBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!PlaceUtil.usableBlock(stack)) {
                continue;
            }
            Integer listed = BLOCK_SCORE.get(((ItemBlock) stack.getItem()).getBlock());
            int score = listed != null ? listed : FALLBACK_SCORE;
            if (score < bestScore || score == bestScore && i == current) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    /* ---- goals ---------------------------------------------------------- */

    /** Cells the player's bounding box occupies. */
    private List<BlockPos> bodyCells() {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        int x0 = MathHelper.floor_double(box.minX);
        int x1 = MathHelper.floor_double(box.maxX - 1.0E-4);
        int y0 = MathHelper.floor_double(box.minY);
        int y1 = MathHelper.floor_double(box.maxY - 1.0E-4);
        int z0 = MathHelper.floor_double(box.minZ);
        int z1 = MathHelper.floor_double(box.maxZ - 1.0E-4);
        List<BlockPos> cells = new ArrayList<BlockPos>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    /** The open ring cells around the body, at every level it spans. */
    private List<BlockPos> sideGoals(List<BlockPos> body, Set<BlockPos> bodySet) {
        Set<BlockPos> goals = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : body) {
            for (EnumFacing facing : HORIZONTAL) {
                BlockPos side = cell.offset(facing);
                if (!bodySet.contains(side) && this.isOpenGoal(side)) {
                    goals.add(side);
                }
            }
        }
        return new ArrayList<BlockPos>(goals);
    }

    /** The open cells directly above the top of the body. */
    private List<BlockPos> roofGoals(List<BlockPos> body) {
        int top = Integer.MIN_VALUE;
        for (BlockPos cell : body) {
            top = Math.max(top, cell.getY());
        }
        List<BlockPos> roofs = new ArrayList<BlockPos>();
        for (BlockPos cell : body) {
            if (cell.getY() == top && this.isOpenGoal(cell.up())) {
                roofs.add(cell.up());
            }
        }
        return roofs;
    }

    /**
     * One step of support: open cells next to a goal, outside the body, that
     * can themselves be placed against something. Bounded at six per goal --
     * this replaces the old 8964-node path search.
     */
    private List<BlockPos> supportGoals(List<BlockPos> goals, Set<BlockPos> bodySet) {
        Set<BlockPos> goalSet = new HashSet<BlockPos>(goals);
        Set<BlockPos> supports = new LinkedHashSet<BlockPos>();
        for (BlockPos goal : goals) {
            for (EnumFacing facing : EnumFacing.values()) {
                BlockPos cell = goal.offset(facing);
                if (goalSet.contains(cell) || bodySet.contains(cell) || supports.contains(cell)
                        || !this.isOpenGoal(cell)) {
                    continue;
                }
                for (EnumFacing next : EnumFacing.values()) {
                    if (PlaceUtil.clickable(cell.offset(next))) {
                        supports.add(cell);
                        break;
                    }
                }
            }
        }
        return new ArrayList<BlockPos>(supports);
    }

    private boolean isOpenGoal(BlockPos pos) {
        return BlockUtil.isReplaceable(pos)
                && !PlaceUtil.boxIntersects(mc.thePlayer.getEntityBoundingBox(), pos)
                && !this.pending.containsKey(pos)
                && !this.refusedUntil.containsKey(pos);
    }

    private void updateProgress(List<BlockPos> body, Set<BlockPos> bodySet) {
        int total = 0;
        int filled = 0;
        Set<BlockPos> seen = new HashSet<BlockPos>();
        int top = Integer.MIN_VALUE;
        for (BlockPos cell : body) {
            top = Math.max(top, cell.getY());
        }
        for (BlockPos cell : body) {
            List<BlockPos> around = new ArrayList<BlockPos>();
            for (EnumFacing facing : HORIZONTAL) {
                around.add(cell.offset(facing));
            }
            if (cell.getY() == top) {
                around.add(cell.up());
            }
            for (BlockPos pos : around) {
                if (bodySet.contains(pos) || !seen.add(pos)) {
                    continue;
                }
                total++;
                if (!BlockUtil.isReplaceable(pos)) {
                    filled++;
                }
            }
        }
        this.progress = total == 0 ? 0.0F : (float) filled / total;
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
