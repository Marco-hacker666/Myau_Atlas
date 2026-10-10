package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): speeds up eating and blocking by finishing
// the use action early.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorEntityPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;

public class FastUse extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Instant", "NCP", "Matrix"});
    public final IntProperty packets = new IntProperty("packets", 20, 3, 35);

    public FastUse() {
        super("FastUse", false, false, "Speeds up eating and blocking by finishing use faster.");
    }

    private boolean using() {
        return mc.thePlayer.isEating() || mc.thePlayer.isUsingItem();
    }

    private void resetTimer() {
        try {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        } catch (Exception ignored) {
        }
    }

    private void finishUse() {
        ItemStack held = mc.thePlayer.getCurrentEquippedItem();
        if (held != null) {
            ((IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(held.getMaxItemUseDuration() - 1);
        }
        mc.playerController.onStoppedUsingItem(mc.thePlayer);
    }

    private void spamGround(int count) {
        boolean ground = mc.thePlayer.onGround;
        for (int i = 0; i < count; i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(ground));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.using()) {
            if (this.mode.getValue() == 2) {
                this.resetTimer();
            }
            return;
        }
        switch (this.mode.getValue()) {
            case 0:
                this.spamGround(this.packets.getValue());
                this.finishUse();
                break;
            case 1:
                if (mc.thePlayer.getItemInUseDuration() > 14) {
                    this.spamGround(this.packets.getValue());
                    this.finishUse();
                }
                break;
            default:
                ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.18F;
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer(mc.thePlayer.onGround));
                break;
        }
    }

    @Override
    public void onDisabled() {
        this.resetTimer();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
