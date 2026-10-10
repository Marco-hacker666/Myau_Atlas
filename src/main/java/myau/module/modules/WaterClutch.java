package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): water-bucket MLG clutch, standalone and
// separate from Myau's own Clutch rework (untouched).
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public class WaterClutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastSlot = -1;
    private boolean placed = false;
    private float savedPitch = 0.0F;
    private boolean pitchSaved = false;
    private long lastActiveMs = 0L;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Timing", "Legit"});
    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 2.0F, 10.0F);
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", true);
    public final BooleanProperty pickupWater = new BooleanProperty("pickup-water", true);

    public WaterClutch() {
        super("WaterClutch", false, false, "Places a water bucket automatically to clutch falls.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying || mc.thePlayer.isInWater() || mc.thePlayer.isOnLadder()) {
            if (this.placed && this.pickupWater.getValue() && mc.thePlayer.isInWater()) {
                this.pickupPlacedWater(event);
            } else if (mc.thePlayer.onGround) {
                this.resetClutch();
            } else if (this.lastSlot != -1 && System.currentTimeMillis() - this.lastActiveMs > 500L) {
                this.resetClutch();
            }
            return;
        }
        if (mc.thePlayer.fallDistance < this.minFall.getValue() || mc.thePlayer.motionY >= -0.1) {
            return;
        }
        int waterSlot = this.findWaterBucketSlot();
        if (waterSlot == -1) {
            return;
        }
        BlockPos target = this.findMlgTarget();
        if (target == null) {
            return;
        }
        if (this.lastSlot == -1) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = waterSlot;
        mc.playerController.updateController();
        this.lastActiveMs = System.currentTimeMillis();
        if (this.silentAim.getValue()) {
            event.setRotation(mc.thePlayer.rotationYaw, 90.0F, 2);
        } else {
            if (!this.pitchSaved) {
                this.savedPitch = mc.thePlayer.rotationPitch;
                this.pitchSaved = true;
            }
            mc.thePlayer.rotationPitch = 90.0F;
        }
        if (!this.placed && mc.thePlayer.getDistance(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= mc.playerController.getBlockReachDistance() + 1.5F) {
            Vec3 hitVec = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
            ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
            if (stack != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, target, EnumFacing.UP, hitVec)) {
                mc.thePlayer.swingItem();
                this.placed = true;
            }
        }
    }

    private void pickupPlacedWater(UpdateEvent event) {
        if (this.silentAim.getValue()) {
            event.setRotation(mc.thePlayer.rotationYaw, 90.0F, 2);
        } else {
            if (!this.pitchSaved) {
                this.savedPitch = mc.thePlayer.rotationPitch;
                this.pitchSaved = true;
            }
            mc.thePlayer.rotationPitch = 90.0F;
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null) {
            BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
            mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, below, EnumFacing.UP,
                    new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5));
            mc.thePlayer.swingItem();
        }
        this.resetClutch();
    }

    private int findWaterBucketSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.water_bucket) {
                return i;
            }
        }
        return -1;
    }

    private BlockPos findMlgTarget() {
        BlockPos playerPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        for (int y = 1; y <= 6; y++) {
            BlockPos pos = playerPos.down(y);
            if (!mc.theWorld.isAirBlock(pos) && mc.theWorld.isAirBlock(pos.up())) {
                return pos;
            }
        }
        MovingObjectPosition ray = mc.theWorld.rayTraceBlocks(
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ),
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY - mc.playerController.getBlockReachDistance() - 2.0, mc.thePlayer.posZ), false, true, false);
        return ray != null && ray.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? ray.getBlockPos() : null;
    }

    private void resetClutch() {
        if (this.pitchSaved && mc.thePlayer != null) {
            mc.thePlayer.rotationPitch = this.savedPitch;
        }
        this.pitchSaved = false;
        if (this.lastSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
            mc.playerController.updateController();
        }
        this.lastSlot = -1;
        this.placed = false;
    }

    @Override
    public void onDisabled() {
        this.resetClutch();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
