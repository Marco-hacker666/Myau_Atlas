package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself adapted from Raven bS-16's clutch
// block logic: Block mode places blocks below while falling, Ladder mode
// prefers an adjacent wall face in radius. Separate from Myau's own Clutch
// rework, which is untouched.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class LadderClutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastSlot = -1;
    private long lastPlaceMs = 0L;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Block", "Ladder"});
    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 2.0F, 10.0F);
    public final IntProperty placeDelay = new IntProperty("place-delay", 50, 0, 500);
    public final BooleanProperty lookDown = new BooleanProperty("look-down", true);
    public final FloatProperty ladderRadius = new FloatProperty("ladder-radius", 3.0F, 1.0F, 6.0F, () -> mode.getValue() == 1);
    public final BooleanProperty sneakOnLand = new BooleanProperty("sneak-on-land", false);

    public LadderClutch() {
        super("LadderClutch", false, false, "Places blocks automatically to save you from falls.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.thePlayer.onGround) {
            if (this.sneakOnLand.getValue()) {
                mc.thePlayer.movementInput.sneak = true;
            }
            this.resetClutch();
            return;
        }
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.isInWater() || mc.thePlayer.isOnLadder()) {
            return;
        }
        if (mc.thePlayer.fallDistance < this.minFall.getValue() || mc.thePlayer.motionY >= -0.1) {
            return;
        }
        int blockSlot = this.findBlockSlot();
        if (blockSlot == -1) {
            return;
        }
        if (this.lastSlot == -1) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = blockSlot;
        mc.playerController.updateController();
        if (this.lookDown.getValue()) {
            event.setRotation(mc.thePlayer.rotationYaw, 90.0F, 2);
        }
        long now = System.currentTimeMillis();
        if (now - this.lastPlaceMs < this.placeDelay.getValue()) {
            return;
        }
        Placement placement = this.mode.getValue() == 1 ? this.findWallPlacement() : null;
        if (placement == null) {
            placement = this.findFloorPlacement();
        }
        if (placement == null) {
            return;
        }
        if (mc.thePlayer.getDistance(placement.pos.getX() + 0.5, placement.pos.getY() + 0.5, placement.pos.getZ() + 0.5)
                > mc.playerController.getBlockReachDistance() + 1.5F) {
            return;
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, placement.pos, placement.face, placement.hitVec)) {
            mc.thePlayer.swingItem();
            this.lastPlaceMs = now;
        }
    }

    private Placement findFloorPlacement() {
        BlockPos playerPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        for (int y = 2; y <= 6; y++) {
            BlockPos pos = playerPos.down(y);
            if (!mc.theWorld.isAirBlock(pos) && mc.theWorld.isAirBlock(pos.up())) {
                return new Placement(pos, EnumFacing.UP, new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
            }
        }
        return null;
    }

    private Placement findWallPlacement() {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int radius = MathHelper.floor_float(this.ladderRadius.getValue());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (pos.equals(feet)) {
                        continue;
                    }
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (block == Blocks.air) {
                        continue;
                    }
                    EnumFacing face = this.faceToward(pos, feet);
                    if (face != null && mc.theWorld.isAirBlock(pos.offset(face))) {
                        BlockPos placeOn = pos;
                        Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                                pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                                pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
                        return new Placement(placeOn, face.getOpposite(), hit);
                    }
                }
            }
        }
        return null;
    }

    private EnumFacing faceToward(BlockPos wall, BlockPos feet) {
        int dx = feet.getX() - wall.getX();
        int dz = feet.getZ() - wall.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? EnumFacing.WEST : EnumFacing.EAST;
        }
        if (dz != 0) {
            return dz > 0 ? EnumFacing.NORTH : EnumFacing.SOUTH;
        }
        return null;
    }

    private int findBlockSlot() {
        for (int i = 8; i >= 0; i--) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                return i;
            }
        }
        return -1;
    }

    private void resetClutch() {
        if (mc.thePlayer != null) {
            mc.thePlayer.movementInput.sneak = false;
        }
        if (this.lastSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
            mc.playerController.updateController();
        }
        this.lastSlot = -1;
    }

    @Override
    public void onDisabled() {
        this.resetClutch();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    private static class Placement {
        final BlockPos pos;
        final EnumFacing face;
        final Vec3 hitVec;

        Placement(BlockPos pos, EnumFacing face, Vec3 hitVec) {
            this.pos = pos;
            this.face = face;
            this.hitVec = hitVec;
        }
    }
}
