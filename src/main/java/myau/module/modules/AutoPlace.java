package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): automatically places blocks when aiming at
// a valid face, after the aim has held still for a set number of frames.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;

public class AutoPlace extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty frame = new IntProperty("frame", 8, 0, 30);
    public final IntProperty minDelay = new IntProperty("min-delay", 60, 1, 500);
    public final BooleanProperty pitchCheck = new BooleanProperty("pitch-check", false);
    public final FloatProperty minPitch = new FloatProperty("min-pitch", 40.0F, 0.0F, 90.0F, () -> pitchCheck.getValue());
    private int tickCounter = 0;
    private int samePosFrames = 0;
    private long lastPlaceTime = 0L;
    private BlockPos lastPos = null;

    public AutoPlace() {
        super("AutoPlace", false, false, "Automatically places blocks when aiming at a valid face.");
    }

    @Override
    public void onEnabled() {
        this.tickCounter = 0;
        this.samePosFrames = 0;
        this.lastPlaceTime = 0L;
        this.lastPos = null;
    }

    @Override
    public void onDisabled() {
        this.tickCounter = 0;
        this.samePosFrames = 0;
        this.lastPos = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.frame.getValue())};
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
        this.tickCounter++;
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            this.lastPos = null;
            this.samePosFrames = 0;
            return;
        }
        if (this.pitchCheck.getValue() && mc.thePlayer.rotationPitch < this.minPitch.getValue()) {
            return;
        }
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mop.sideHit == null) {
            this.lastPos = null;
            this.samePosFrames = 0;
            return;
        }
        if (mop.sideHit == EnumFacing.UP || mop.sideHit == EnumFacing.DOWN) {
            return;
        }
        BlockPos pos = mop.getBlockPos();
        if (this.lastPos != null && this.lastPos.equals(pos)) {
            this.samePosFrames++;
        } else {
            this.lastPos = pos;
            this.samePosFrames = 0;
        }
        if (this.samePosFrames < this.frame.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastPlaceTime < this.minDelay.getValue().longValue()) {
            return;
        }
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, pos, mop.sideHit, mop.hitVec)) {
            mc.thePlayer.swingItem();
            ((IAccessorMinecraft) mc).setRightClickDelayTimer(0);
            this.lastPlaceTime = now;
            this.samePosFrames = 0;
        }
    }
}
