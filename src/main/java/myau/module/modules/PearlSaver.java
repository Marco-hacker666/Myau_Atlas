package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): throws an ender pearl at the nearest surface
// when knocked into the void. One throw per fall, slot restored after.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

public class PearlSaver extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastSlot = -1;
    private boolean thrown = false;

    public final FloatProperty minFall = new FloatProperty("min-fall", 4.0F, 2.0F, 12.0F);
    public final FloatProperty reach = new FloatProperty("reach", 5.0F, 3.0F, 8.0F);
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", true);

    public PearlSaver() {
        super("PearlSaver", false, false, "Throws an ender pearl to save you from void falls.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            this.thrown = false;
            this.resetSlot();
            return;
        }
        if (this.thrown || mc.thePlayer.fallDistance < this.minFall.getValue() || mc.thePlayer.motionY >= 0.0) {
            return;
        }
        if (this.groundBelow()) {
            return;
        }
        int pearlSlot = this.findPearlSlot();
        if (pearlSlot == -1) {
            return;
        }
        Placement placement = this.findSurface();
        if (placement == null) {
            return;
        }
        if (this.lastSlot == -1) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = pearlSlot;
        mc.playerController.updateController();
        float[] rotations = RotationUtil.getRotationsTo(placement.hitVec.xCoord, placement.hitVec.yCoord, placement.hitVec.zCoord,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        if (this.silentAim.getValue()) {
            event.setRotation(rotations[0], rotations[1], 2);
        } else {
            mc.thePlayer.rotationYaw = rotations[0];
            mc.thePlayer.rotationPitch = rotations[1];
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack,
                placement.pos, placement.face, placement.hitVec)) {
            mc.thePlayer.swingItem();
            this.thrown = true;
        }
        this.resetSlot();
    }

    private boolean groundBelow() {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int scan = this.reach.getValue().intValue() + 4;
        for (int y = 1; y <= scan; y++) {
            if (!mc.theWorld.isAirBlock(feet.down(y))) {
                return true;
            }
        }
        return false;
    }

    private Placement findSurface() {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int radius = this.reach.getValue().intValue();
        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (pos.equals(feet) || mc.theWorld.isAirBlock(pos)) {
                        continue;
                    }
                    for (EnumFacing face : EnumFacing.values()) {
                        if (!mc.theWorld.isAirBlock(pos.offset(face))) {
                            double dist = mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (dist < bestDist && dist <= mc.playerController.getBlockReachDistance() + 1.5F) {
                                bestDist = dist;
                                Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                                        pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                                        pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
                                best = new Placement(pos, face, hit);
                            }
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    private int findPearlSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemEnderPearl && stack.stackSize > 0) {
                return i;
            }
        }
        return -1;
    }

    private void resetSlot() {
        if (this.lastSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
            mc.playerController.updateController();
        }
        this.lastSlot = -1;
    }

    @Override
    public void onDisabled() {
        this.thrown = false;
        this.resetSlot();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.thrown ? "spent" : "ready"};
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
