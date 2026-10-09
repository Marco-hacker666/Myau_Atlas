package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): places blocks to defend your bed, with
// neighbour-cover target selection, turn speed limits and a progress readout.
// OpenSkid's ScaffoldSessionState slot bookkeeping is inlined here as two
// private helpers, since Myau has no equivalent shared session state.
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.Render2DEvent;
import myau.events.SwapItemEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

public class BedDefender extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final FloatProperty bedRadius = new FloatProperty("bed-radius", 6.0F, 3.0F, 10.0F);
    public final IntProperty placeDelay = new IntProperty("place-delay", 80, 0, 300);
    public final IntProperty speed = new IntProperty("speed", 100, 30, 100);
    public final IntProperty rotTol = new IntProperty("rot-tolerance", 35, 5, 180);
    public final ModeProperty layers = new ModeProperty("layers", 0, new String[]{"SINGLE", "DOUBLE"});
    public final BooleanProperty swapBack = new BooleanProperty("swap-back", true);
    public final BooleanProperty refillCheck = new BooleanProperty("refill-check", true);
    public final IntProperty refillMin = new IntProperty("refill-min", 8, 0, 64, () -> this.refillCheck.getValue());
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty showProgress = new BooleanProperty("show-progress", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"});

    private static final int ROT_PRIORITY = 6;

    private float serverYaw;
    private float serverPitch;
    private float aimYaw;
    private float aimPitch;
    private float lastSteppedYaw;
    private float lastSteppedPitch;

    private BlockPos targetBlock;
    private EnumFacing targetFacing;
    private Vec3 targetHitVec;

    private long lastPlaceTime = 0;
    private int lastSlot = -1;
    private float progress = 0.0F;

    private BlockPos lockedBedFoot = null;
    private EnumFacing lockedBedFacing = null;

    public BedDefender() {
        super("BedDefender", false, false, "Automatically places blocks to defend your bed.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.layers.getModeString()};
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            this.serverYaw = mc.thePlayer.rotationYaw;
            this.serverPitch = mc.thePlayer.rotationPitch;
            this.aimYaw = this.serverYaw;
            this.aimPitch = this.serverPitch;
            this.lastSteppedYaw = this.serverYaw;
            this.lastSteppedPitch = this.serverPitch;
            this.lastSlot = saveSlotOnce(-1, mc.thePlayer.inventory.currentItem);
        }
        this.progress = 0.0F;
        this.resetTarget();
        this.lockedBedFoot = null;
        this.lockedBedFacing = null;
    }

    @Override
    public void onDisabled() {
        if (this.swapBack.getValue() && hasSavedSlot(this.lastSlot) && mc.thePlayer != null && mc.thePlayer.inventory.currentItem != this.lastSlot) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.progress = 0.0F;
        this.resetTarget();
        this.lockedBedFoot = null;
        this.lockedBedFacing = null;
    }

    /** The slot to return to: the one already saved, else the current one. */
    private static int saveSlotOnce(int saved, int current) {
        return saved >= 0 ? saved : current;
    }

    private static boolean hasSavedSlot(int slot) {
        return slot >= 0;
    }

    private void resetTarget() {
        this.targetBlock = null;
        this.targetFacing = null;
        this.targetHitVec = null;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.getModule(AutoBlockIn.class);
        if (autoBlockIn != null && autoBlockIn.isEnabled()) {
            this.resetTarget();
            return;
        }

        this.serverYaw = event.getYaw();
        this.serverPitch = event.getPitch();

        this.updateProgress();

        if (!this.isValidBed(this.lockedBedFoot)) {
            this.lockedBedFoot = null;
            this.lockedBedFacing = null;
            this.findNearestBed();
        }
        if (this.lockedBedFoot == null) {
            return;
        }

        if (this.refillCheck.getValue() && this.countBlocks() < this.refillMin.getValue()) {
            this.resetTarget();
            return;
        }

        int blockSlot = this.findBlockSlot();
        if (blockSlot == -1) {
            this.resetTarget();
            return;
        }
        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        List<BlockPos> defensePositions = this.getDefensePositions();
        int nextIdx = this.findNextDefensePosIndex(defensePositions);
        if (nextIdx == -1) {
            this.resetTarget();
            if (this.swapBack.getValue() && hasSavedSlot(this.lastSlot) && mc.thePlayer.inventory.currentItem != this.lastSlot) {
                mc.thePlayer.inventory.currentItem = this.lastSlot;
            }
            return;
        }
        BlockPos nextTarget = defensePositions.get(nextIdx);

        boolean isRoof = nextIdx >= 6;
        this.computePlacement(nextTarget, isRoof);
        if (this.targetBlock == null) {
            return;
        }

        float yawDiff = MathHelper.wrapAngleTo180_float(this.aimYaw - this.serverYaw);
        float pitchDiff = this.aimPitch - this.serverPitch;
        float maxTurn = (float) this.speed.getValue();

        this.lastSteppedYaw = this.serverYaw + MathHelper.clamp_float(yawDiff, -maxTurn, maxTurn);
        this.lastSteppedPitch = MathHelper.clamp_float(
                this.serverPitch + MathHelper.clamp_float(pitchDiff, -maxTurn, maxTurn), -90.0F, 90.0F);

        event.setRotation(this.lastSteppedYaw, this.lastSteppedPitch, ROT_PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? this.lastSteppedYaw : mc.thePlayer.rotationYaw, ROT_PRIORITY);
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (this.targetBlock == null || this.targetFacing == null || this.targetHitVec == null) {
            return;
        }

        float useYaw = RotationState.isActived() && RotationState.getPriority() == ROT_PRIORITY
                ? RotationState.getSmoothedYaw() : this.lastSteppedYaw;
        float usePitch = this.lastSteppedPitch;

        if (!this.withinRotationTolerance(useYaw, usePitch, this.aimYaw, this.aimPitch)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - this.lastPlaceTime < this.placeDelay.getValue()) {
            return;
        }

        double reach = this.range.getValue();
        MovingObjectPosition mop = this.rayTrace(useYaw, usePitch, reach);

        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(this.targetBlock)
                || mop.sideHit != this.targetFacing) {
            return;
        }

        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }

        float savedYaw = mc.thePlayer.rotationYaw;
        float savedPitch = mc.thePlayer.rotationPitch;
        boolean wasSneaking = mc.thePlayer.isSneaking();

        mc.thePlayer.rotationYaw = useYaw;
        mc.thePlayer.rotationPitch = usePitch;
        mc.thePlayer.setSneaking(true);

        mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, this.targetBlock, this.targetFacing, mop.hitVec);

        mc.thePlayer.setSneaking(wasSneaking);
        mc.thePlayer.rotationYaw = savedYaw;
        mc.thePlayer.rotationPitch = savedPitch;

        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            try {
                mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
            } catch (Exception ignored) {
            }
        }

        this.lastPlaceTime = now;
        this.resetTarget();
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == ROT_PRIORITY
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.currentScreen != null) {
            return;
        }
        if (!this.showProgress.getValue()) {
            return;
        }
        if (mc.fontRendererObj == null) {
            return;
        }

        String text = String.format("Defending: %.0f%%", this.progress * 100.0F);

        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        ScaledResolution sr = new ScaledResolution(mc);
        int width = mc.fontRendererObj.getStringWidth(text);

        mc.fontRendererObj.drawString(
                text,
                (float) sr.getScaledWidth() / 2.0F - (float) width / 2.0F,
                (float) sr.getScaledHeight() / 5.0F * 2.0F,
                this.getProgressColor().getRGB() & 16777215 | -1090519040,
                true
        );

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GL11.glPopMatrix();
    }

    private void findNearestBed() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        BlockPos origin = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ));

        int searchRadius = Math.max(1, Math.round(this.bedRadius.getValue()));
        double maxDistSq = (double) this.bedRadius.getValue() * (double) this.bedRadius.getValue();
        double bestDist = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (!this.isBedFoot(p)) {
                        continue;
                    }

                    EnumFacing facing = this.getBedFacing(p);
                    if (facing == null) {
                        continue;
                    }

                    double dist = mc.thePlayer.getDistanceSq(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (dist > maxDistSq) {
                        continue;
                    }
                    if (dist < bestDist) {
                        bestDist = dist;
                        this.lockedBedFoot = p;
                        this.lockedBedFacing = facing;
                    }
                }
            }
        }
    }

    private boolean isBedFoot(BlockPos pos) {
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        if (!(b instanceof BlockBed)) {
            return false;
        }
        int meta = b.getMetaFromState(mc.theWorld.getBlockState(pos));
        return (meta & 8) == 0;
    }

    private boolean isValidBed(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed;
    }

    private EnumFacing getBedFacing(BlockPos pos) {
        int meta = mc.theWorld.getBlockState(pos).getBlock().getMetaFromState(mc.theWorld.getBlockState(pos));
        switch (meta & 3) {
            case 0:
                return EnumFacing.SOUTH;
            case 1:
                return EnumFacing.WEST;
            case 2:
                return EnumFacing.NORTH;
            case 3:
                return EnumFacing.EAST;
            default:
                return null;
        }
    }

    private List<BlockPos> getDefensePositions() {
        List<BlockPos> list = new ArrayList<BlockPos>();
        if (this.lockedBedFoot == null || this.lockedBedFacing == null) {
            return list;
        }

        BlockPos foot = this.lockedBedFoot;
        BlockPos head = foot.offset(this.lockedBedFacing);
        EnumFacing fwd = this.lockedBedFacing;
        EnumFacing back = fwd.getOpposite();
        EnumFacing left = this.leftOf(fwd);
        EnumFacing right = left.getOpposite();

        list.add(foot.offset(left));
        list.add(foot.offset(right));
        list.add(head.offset(left));
        list.add(head.offset(right));
        list.add(foot.offset(back));
        list.add(head.offset(fwd));
        if (this.layers.getValue() <= 0) {
            return list;
        }
        list.add(foot.up(1));
        list.add(head.up(1));
        list.add(foot.offset(left).up(1));
        list.add(foot.offset(right).up(1));
        list.add(head.offset(left).up(1));
        list.add(head.offset(right).up(1));
        list.add(foot.offset(back).up(1));
        list.add(head.offset(fwd).up(1));
        list.add(foot.up(2));
        list.add(head.up(2));

        return list;
    }

    private int findNextDefensePosIndex(List<BlockPos> positions) {
        for (int i = 0; i < positions.size(); i++) {
            if (this.isReplaceable(positions.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private void computePlacement(BlockPos wantPlace, boolean isRoof) {
        this.resetTarget();

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double reach = this.range.getValue();
        double reachSq = reach * reach;

        for (EnumFacing face : EnumFacing.values()) {
            BlockPos support = wantPlace.offset(face);

            if (this.isReplaceable(support)) {
                continue;
            }
            Block supportBlock = mc.theWorld.getBlockState(support).getBlock();
            if (!isRoof && supportBlock instanceof BlockBed) {
                continue;
            }

            double dx = support.getX() + 0.5 - eyes.xCoord;
            double dy = support.getY() + 0.5 - eyes.yCoord;
            double dz = support.getZ() + 0.5 - eyes.zCoord;
            if (dx * dx + dy * dy + dz * dz > (reach + 1.5) * (reach + 1.5)) {
                continue;
            }

            EnumFacing clickFace = face.getOpposite();
            double[] offsets = {0.2, 0.4, 0.5, 0.6, 0.8};

            float bestDiff = isRoof ? Float.MIN_VALUE : Float.MAX_VALUE;
            float bestYaw = Float.NaN;
            float bestPitch = Float.NaN;
            Vec3 bestHit = null;

            for (double u : offsets) {
                for (double v : offsets) {
                    Vec3 hitPos = this.getFacePoint(support, clickFace, u, v);

                    double hdx = hitPos.xCoord - eyes.xCoord;
                    double hdy = hitPos.yCoord - eyes.yCoord;
                    double hdz = hitPos.zCoord - eyes.zCoord;
                    if (hdx * hdx + hdy * hdy + hdz * hdz > reachSq) {
                        continue;
                    }

                    float[] rot = this.computeRotations(hdx, hdy, hdz);

                    MovingObjectPosition mop = this.rayTrace(rot[0], rot[1], reach);
                    if (mop == null
                            || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !mop.getBlockPos().equals(support)
                            || mop.sideHit != clickFace) {
                        continue;
                    }

                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - this.serverYaw)) + Math.abs(rot[1] - this.serverPitch);

                    boolean better = isRoof ? (diff > bestDiff) : (diff < bestDiff);
                    if (better) {
                        bestDiff = diff;
                        bestYaw = rot[0];
                        bestPitch = rot[1];
                        bestHit = mop.hitVec;
                    }
                }
            }

            if (bestHit != null) {
                this.aimYaw = bestYaw;
                this.aimPitch = bestPitch;
                this.targetBlock = support;
                this.targetFacing = clickFace;
                this.targetHitVec = bestHit;
                return;
            }
        }
    }

    private Vec3 getFacePoint(BlockPos block, EnumFacing face, double u, double v) {
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        switch (face) {
            case DOWN:
                return new Vec3(x + u, y, z + v);
            case UP:
                return new Vec3(x + u, y + 1.0, z + v);
            case NORTH:
                return new Vec3(x + u, y + v, z);
            case SOUTH:
                return new Vec3(x + u, y + v, z + 1.0);
            case WEST:
                return new Vec3(x, y + v, z + u);
            case EAST:
                return new Vec3(x + 1.0, y + v, z + u);
            default:
                return new Vec3(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    private float[] computeRotations(double dx, double dy, double dz) {
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = MathHelper.wrapAngleTo180_float((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, hd)));
        return new float[]{yaw, pitch};
    }

    private MovingObjectPosition rayTrace(float yaw, float pitch, double distance) {
        float yr = (float) Math.toRadians(yaw);
        float pr = (float) Math.toRadians(pitch);
        double lx = -Math.sin(yr) * Math.cos(pr);
        double ly = -Math.sin(pr);
        double lz = Math.cos(yr) * Math.cos(pr);
        Vec3 start = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 end = start.addVector(lx * distance, ly * distance, lz * distance);
        return mc.theWorld.rayTraceBlocks(start, end);
    }

    private boolean withinRotationTolerance(float useYaw, float usePitch, float targetYaw, float targetPitch) {
        float dy = Math.abs(MathHelper.wrapAngleTo180_float(useYaw - targetYaw));
        float dp = Math.abs(MathHelper.wrapAngleTo180_float(usePitch - targetPitch));
        return dy <= this.rotTol.getValue() && dp <= this.rotTol.getValue();
    }

    private int findBlockSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize == 0) {
                continue;
            }
            if (!(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    private int countBlocks() {
        int total = 0;
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
            if (stack == null || stack.stackSize == 0) {
                continue;
            }
            if (!(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            total += stack.stackSize;
        }
        return total;
    }

    private boolean isReplaceable(BlockPos pos) {
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        return b == Blocks.air
                || b == Blocks.water
                || b == Blocks.flowing_water
                || b == Blocks.lava
                || b == Blocks.flowing_lava
                || b == Blocks.fire;
    }

    private EnumFacing leftOf(EnumFacing f) {
        switch (f) {
            case NORTH:
                return EnumFacing.WEST;
            case WEST:
                return EnumFacing.SOUTH;
            case SOUTH:
                return EnumFacing.EAST;
            case EAST:
                return EnumFacing.NORTH;
            default:
                return EnumFacing.NORTH;
        }
    }

    private void updateProgress() {
        if (this.lockedBedFoot == null || this.lockedBedFacing == null) {
            this.progress = 0.0F;
            return;
        }
        List<BlockPos> positions = this.getDefensePositions();
        int filled = 0;
        for (BlockPos pos : positions) {
            if (!this.isReplaceable(pos)) {
                filled++;
            }
        }
        this.progress = positions.isEmpty() ? 0.0F : (float) filled / (float) positions.size();
    }

    private Color getProgressColor() {
        if (this.progress <= 0.33F) {
            return new Color(255, 85, 85);
        } else if (this.progress <= 0.66F) {
            return new Color(255, 255, 85);
        } else {
            return new Color(85, 255, 85);
        }
    }

    public int getSlot() {
        return this.lastSlot;
    }
}
