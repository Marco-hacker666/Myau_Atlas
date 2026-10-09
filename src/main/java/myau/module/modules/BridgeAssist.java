package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): sneaks at block edges while bridging, with a
// Normal / Silent / Telly split and the source's direction, jump, pitch and
// telly guards. Silent mode holds the pitch through UpdateEvent.
import java.util.Objects;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public class BridgeAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int sneakDelay = 0;
    private int holdTicks = 0;
    private int tellyJumpTicks = 0;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Normal", "Silent", "Telly"});
    public final FloatProperty edgeDistance = new FloatProperty("edge-distance", 0.8F, 0.2F, 2.0F);
    public final IntProperty minDelay = new IntProperty("min-delay", 2, 0, 10, () -> mode.getValue() != 1);
    public final IntProperty maxDelay = new IntProperty("max-delay", 3, 0, 10, () -> mode.getValue() != 1);
    public final IntProperty sneakTicks = new IntProperty("sneak-ticks", 3, 0, 10, () -> mode.getValue() != 1);
    public final FloatProperty silentPitch = new FloatProperty("silent-pitch", 80.0F, 70.0F, 90.0F, () -> mode.getValue() == 1);
    public final BooleanProperty blocksOnly = new BooleanProperty("blocks-only", true);
    public final BooleanProperty tellyFix = new BooleanProperty("telly-fix", false);
    public final BooleanProperty directionCheck = new BooleanProperty("direction-check", true);
    public final BooleanProperty jumpCheck = new BooleanProperty("jump-check", true);
    public final BooleanProperty pitchCheck = new BooleanProperty("pitch-check", true);
    public final BooleanProperty sneakingOnly = new BooleanProperty("sneaking-only", false);
    public final IntProperty tellyJumpDelay = new IntProperty("telly-jump-delay", 6, 2, 20, () -> mode.getValue() == 2);

    public BridgeAssist() {
        super("BridgeAssist", false, false, "Automatically sneaks at block edges while bridging.");
    }

    private boolean canMoveSafely() {
        double[] offset = MoveUtil.predictMovement();
        float scale = this.edgeDistance.getValue();
        return PlayerUtil.canMove(mc.thePlayer.motionX + offset[0] * scale, mc.thePlayer.motionZ + offset[1] * scale);
    }

    private boolean isTellying() {
        return mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F;
    }

    private boolean shouldSneak() {
        if (this.directionCheck.getValue() && mc.gameSettings.keyBindForward.isKeyDown()) {
            return false;
        } else if (this.jumpCheck.getValue() && mc.gameSettings.keyBindJump.isKeyDown()) {
            return false;
        } else if (this.pitchCheck.getValue() && mc.thePlayer.rotationPitch < 69.0F) {
            return false;
        } else if (this.sneakingOnly.getValue() && !Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
            return false;
        } else if (this.tellyFix.getValue() && this.isTellying()) {
            return false;
        } else {
            return (!this.blocksOnly.getValue() || ItemUtil.isHoldingBlock()) && mc.thePlayer.onGround;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE && this.mode.getValue() != 1) {
            if (this.sneakDelay > 0) {
                this.sneakDelay--;
            }
            if (this.holdTicks > 0) {
                this.holdTicks--;
            }
            if (this.sneakDelay == 0 && this.canMoveSafely()) {
                this.sneakDelay = myau.util.RandomUtil.nextInt(this.minDelay.getValue(), this.maxDelay.getValue());
            }
            if (!this.canMoveSafely() && this.sneakTicks.getValue() > 0) {
                this.holdTicks = this.sneakTicks.getValue();
            }
            if (this.mode.getValue() == 2) {
                if (this.tellyJumpTicks > 0) {
                    this.tellyJumpTicks--;
                }
                if (this.tellyJumpTicks == 0 && mc.thePlayer.onGround && this.canMoveSafely()) {
                    mc.thePlayer.jump();
                    this.tellyJumpTicks = this.tellyJumpDelay.getValue();
                }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.currentScreen != null) {
            return;
        }
        if (this.sneakingOnly.getValue() && Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()) && this.shouldSneak()) {
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.movementInput.moveForward /= 0.3F;
            mc.thePlayer.movementInput.moveStrafe /= 0.3F;
        }
        if (!mc.thePlayer.movementInput.sneak && this.shouldSneak()) {
            boolean edge = this.mode.getValue() == 1 ? !this.canMoveSafely() : (this.sneakDelay > 0 || this.holdTicks > 0 || this.canMoveSafely());
            if (edge) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.mode.getValue() != 1 || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null || !ItemUtil.isHoldingBlock()) {
            return;
        }
        if (mc.thePlayer.rotationPitch < 70.0F || mc.thePlayer.movementInput.moveForward > 0.0F) {
            return;
        }
        float targetPitch = Math.min(this.silentPitch.getValue(), 90.0F);
        event.setRotation(mc.thePlayer.rotationYaw, targetPitch, 2);
    }

    @Override
    public void onDisabled() {
        this.sneakDelay = 0;
        this.holdTicks = 0;
        this.tellyJumpTicks = 0;
    }

    @Override
    public void verifyValue(String name) {
        if (Objects.equals(name, "min-delay") && this.minDelay.getValue() > this.maxDelay.getValue()) {
            this.maxDelay.setValue(this.minDelay.getValue());
        } else if (Objects.equals(name, "max-delay") && this.minDelay.getValue() > this.maxDelay.getValue()) {
            this.minDelay.setValue(this.maxDelay.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 1) {
            return new String[]{"Silent"};
        }
        return Objects.equals(this.minDelay.getValue(), this.maxDelay.getValue())
                ? new String[]{this.minDelay.getValue().toString()}
                : new String[]{String.format("%d-%d", this.minDelay.getValue(), this.maxDelay.getValue())};
    }
}
