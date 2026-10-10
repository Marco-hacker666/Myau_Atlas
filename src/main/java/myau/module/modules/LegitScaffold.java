package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): sneak-at-edge bridging helper. It only
// sneaks at an edge and places a block under the feet -- no rotations.
import com.google.common.base.CaseFormat;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.KeyBindUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class LegitScaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BOTH", "SNEAK", "PLACE"});
    public final IntProperty placeDelay = new IntProperty("place-delay", 150, 0, 500, () -> mode.getValue() != 1);
    public final BooleanProperty sneak = new BooleanProperty("sneak", true, () -> mode.getValue() != 2);
    private final TimerUtil placeTimer = new TimerUtil();
    private boolean sneaking = false;

    public LegitScaffold() {
        super("LegitScaffold", false, false, "Sneaks at edges and places blocks while bridging.");
    }

    @Override
    public void onEnabled() {
        this.placeTimer.reset();
        this.sneaking = false;
    }

    @Override
    public void onDisabled() {
        this.setSneak(false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
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
            this.setSneak(false);
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            this.setSneak(false);
            return;
        }
        if (!mc.thePlayer.onGround) {
            this.setSneak(false);
            return;
        }
        boolean edge = this.isOverEdge();
        if (this.sneak.getValue() && this.mode.getValue() != 2) {
            this.setSneak(edge);
        } else {
            this.setSneak(false);
        }
        if (this.mode.getValue() == 1 || !edge) {
            return;
        }
        if (!this.placeTimer.hasTimeElapsed(this.placeDelay.getValue().longValue())) {
            return;
        }
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!mc.theWorld.isAirBlock(below)) {
            return;
        }
        BlockPos support = below.down();
        if (mc.theWorld.isAirBlock(support)) {
            return;
        }
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, support, EnumFacing.UP,
                new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5))) {
            mc.thePlayer.swingItem();
            this.placeTimer.reset();
        }
    }

    private boolean isOverEdge() {
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        return mc.theWorld.isAirBlock(below);
    }

    private void setSneak(boolean flag) {
        if (this.sneaking == flag) {
            return;
        }
        this.sneaking = flag;
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), flag);
    }
}
