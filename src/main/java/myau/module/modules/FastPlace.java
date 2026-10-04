package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.util.BlockUtil;
import myau.util.ItemUtil;
import myau.util.RotationUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockObsidian;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class FastPlace extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("0.0#", new DecimalFormatSymbols(Locale.US));
    private long delayMS = 0L;
    public final FloatProperty delay = new FloatProperty("delay", 1.0F, 1.0F, 3.0F);
    public final BooleanProperty blocksOnly = new BooleanProperty("blocks-only", true);
    public final BooleanProperty placeFix = new BooleanProperty("place-fix", true);
    public final BooleanProperty skipObsidian = new BooleanProperty("skip-obsidian", true);
    public final BooleanProperty skipInteractable = new BooleanProperty("skip-interactable", true);
    /* While the server is pulling the player back, no placing at all.
       After a lagback Pika sends a position every tick for a second or
       more, moving the player along its own copy of the fall, and every
       block clicked meanwhile is judged from where it is about to put them:
       2026-09-25 14:36:10-11 and 14:47:56, seven blocks refused, each left
       on screen as a block that is not there. */
    public final BooleanProperty pauseOnCorrection = new BooleanProperty("pause-on-correction", true);

    /** Ticks after the last correction still treated as part of it. */
    private static final int CORRECTION_TICKS = 3;
    private volatile boolean corrected;
    private int ticksSinceCorrection = 1000;

    private boolean canPlace() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack != null) {
            Item item = stack.getItem();
            if (item instanceof ItemFishingRod) {
                return false;
            }
            if (item instanceof ItemBlock) {
                Block block = ((ItemBlock) item).getBlock();
                if (skipObsidian.getValue() && block instanceof BlockObsidian) {
                    return false;
                }
                if (skipInteractable.getValue() && BlockUtil.isInteractable(block)) {
                    return false;
                }
                if (!(Boolean) this.placeFix.getValue()) {
                    return true;
                }
                MovingObjectPosition mop = RotationUtil.rayTrace(
                        mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.playerController.getBlockReachDistance(), 1.0F
                );
                return mop != null
                        && mop.typeOfHit == MovingObjectType.BLOCK
                        && ((ItemBlock) item).canPlaceBlockOnSide(mc.theWorld, mop.getBlockPos(), mop.sideHit, mc.thePlayer, stack);
            }
        }
        return !(Boolean) this.blocksOnly.getValue();
    }

    public FastPlace() {
        super("FastPlace", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook) {
            this.corrected = true;
        }
    }

    /** The server is moving the player itself (see pauseOnCorrection). */
    public boolean correcting() {
        return this.pauseOnCorrection.getValue() && this.ticksSinceCorrection <= CORRECTION_TICKS;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.corrected) {
                this.corrected = false;
                this.ticksSinceCorrection = 0;
            } else if (this.ticksSinceCorrection < 1000) {
                this.ticksSinceCorrection++;
            }
            if (correcting() && ItemUtil.isHoldingBlock()) {
                /* Two, not one: the timer is counted down before this
                   tick's click, so one would still let it through. */
                IAccessorMinecraft accessor = (IAccessorMinecraft) mc;
                if (accessor.getRightClickDelayTimer() < 2) {
                    accessor.setRightClickDelayTimer(2);
                }
                return;
            }
            int rightClickDelayTimer = ((IAccessorMinecraft) mc).getRightClickDelayTimer();
            if (rightClickDelayTimer == 4) {
                this.delayMS = this.delayMS + (long) (50.0F * this.delay.getValue());
            }
            if (this.delayMS > 0L) {
                this.delayMS = this.delayMS - 50;
            }
            if (this.delayMS <= 0L && rightClickDelayTimer > 1 && this.canPlace()) {
                ((IAccessorMinecraft) mc).setRightClickDelayTimer(0);
            }
        }
    }

    @Override
    public void onDisabled() {
        this.delayMS = 0L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{df.format(this.delay.getValue())};
    }
}
