package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself adapted from MiauMinus
// ghost/BlockLadder: predicts the landing while falling and puts a ladder on
// the nearest wall face along the way.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.TimerUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class BlockLadder extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final EnumFacing[] SIDES = new EnumFacing[]{EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
    public final FloatProperty fallDistance = new FloatProperty("fall-distance", 4.0F, 2.0F, 10.0F);
    public final FloatProperty reach = new FloatProperty("reach", 4.5F, 2.0F, 4.5F);
    public final IntProperty placeDelay = new IntProperty("place-delay", 70, 0, 200);
    private final TimerUtil placeTimer = new TimerUtil();
    private int savedSlot = -1;

    public BlockLadder() {
        super("BlockLadder", false, false, "Automatically places ladders on walls while falling.");
    }

    @Override
    public void onEnabled() {
        this.placeTimer.reset();
        this.savedSlot = -1;
    }

    @Override
    public void onDisabled() {
        if (this.savedSlot != -1 && mc.thePlayer != null
                && mc.thePlayer.inventory.currentItem != this.savedSlot) {
            mc.thePlayer.inventory.currentItem = this.savedSlot;
            if (mc.playerController != null) {
                mc.playerController.updateController();
            }
        }
        this.savedSlot = -1;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.fallDistance.getValue())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (mc.thePlayer.onGround || mc.thePlayer.motionY >= 0.0
                || mc.thePlayer.isInWater() || mc.thePlayer.isOnLadder()) {
            return;
        }
        if (mc.thePlayer.fallDistance < this.fallDistance.getValue()) {
            return;
        }
        if (!this.placeTimer.hasTimeElapsed(this.placeDelay.getValue().longValue())) {
            return;
        }
        int ladderSlot = this.findLadderSlot();
        if (ladderSlot == -1) {
            return;
        }
        BlockPos landing = this.predictLanding();
        if (landing == null) {
            return;
        }
        if (!this.tryPlaceOnWall(ladderSlot, landing)) {
            return;
        }
        this.placeTimer.reset();
    }

    private BlockPos predictLanding() {
        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY;
        double pz = mc.thePlayer.posZ;
        double vx = mc.thePlayer.motionX;
        double vy = mc.thePlayer.motionY;
        double vz = mc.thePlayer.motionZ;
        for (int t = 0; t < 100; t++) {
            vy -= 0.08;
            px += vx;
            py += vy;
            pz += vz;
            vy *= 0.98;
            vx *= 0.91;
            vz *= 0.91;
            if (py < 0.0) {
                return null;
            }
            BlockPos pos = new BlockPos(MathHelper.floor_double(px), MathHelper.floor_double(py), MathHelper.floor_double(pz));
            if (this.isSolid(mc.theWorld.getBlockState(pos).getBlock())) {
                return pos;
            }
        }
        return null;
    }

    private boolean tryPlaceOnWall(int ladderSlot, BlockPos landing) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double maxReach = this.reach.getValue();
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cell = landing.add(dx, dy, dz);
                    if (!mc.theWorld.isAirBlock(cell)) {
                        continue;
                    }
                    for (EnumFacing face : SIDES) {
                        BlockPos support = cell.offset(face);
                        if (!this.isSolid(mc.theWorld.getBlockState(support).getBlock())) {
                            continue;
                        }
                        EnumFacing clickFace = face.getOpposite();
                        Vec3 hitVec = new Vec3(support.getX() + 0.5 + clickFace.getFrontOffsetX() * 0.5,
                                support.getY() + 0.5 + clickFace.getFrontOffsetY() * 0.5,
                                support.getZ() + 0.5 + clickFace.getFrontOffsetZ() * 0.5);
                        if (eyes.squareDistanceTo(hitVec) > maxReach * maxReach) {
                            continue;
                        }
                        int prevSlot = mc.thePlayer.inventory.currentItem;
                        mc.thePlayer.inventory.currentItem = ladderSlot;
                        mc.playerController.updateController();
                        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
                        boolean placed = stack != null && mc.playerController.onPlayerRightClick(
                                mc.thePlayer, mc.theWorld, stack, support, clickFace, hitVec);
                        if (placed) {
                            mc.thePlayer.swingItem();
                        }
                        mc.thePlayer.inventory.currentItem = prevSlot;
                        mc.playerController.updateController();
                        return placed;
                    }
                }
            }
        }
        return false;
    }

    private int findLadderSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() != null
                    && stack.getItem().getUnlocalizedName().toLowerCase().contains("ladder")) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSolid(Block block) {
        if (block == null || block instanceof BlockAir || block instanceof BlockLiquid || block instanceof BlockFire) {
            return false;
        }
        return true;
    }
}
