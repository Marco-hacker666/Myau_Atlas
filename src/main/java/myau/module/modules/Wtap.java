package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Random;

/**
 * W-tap (rewritten 2026-10-09 after Vape 4.21's WTap): on a hit, really let go
 * of the forward key and press it again, so the game itself ends and restarts
 * the sprint and the next hit deals sprint knockback. Everything sent is what
 * a player letting go of W sends.
 *
 * What the old version did differently: it zeroed the movement input inside
 * the game (not the key), on every hit at most every 500ms, always 5.5 ticks
 * after the hit for 1.5 ticks -- a fixed rhythm -- and also on hits that could
 * not deal damage (the target still in its hurt time), where a W-tap gains
 * nothing.
 *
 *  chance          how often a hit is followed by a W-tap;
 *  release-delay   ms from the hit to letting go of W;
 *  re-press-delay  ms W stays up; each varies by +-20% so no two are alike;
 *  select-hits     only after hits that can deal damage (the target is out of
 *                  most of its hurt time; 14 of 20 as Vape, for ping).
 * The key goes back to what the player is really holding when the tap ends,
 * when a menu opens, and when the module is turned off.
 */
public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance = new PercentProperty("chance", 90);
    public final IntProperty releaseDelay = new IntProperty("release-delay", 0, 0, 500);
    public final IntProperty rePressDelay = new IntProperty("re-press-delay", 50, 0, 500);
    public final BooleanProperty selectHits = new BooleanProperty("select-hits", true);

    private final Random random = new Random();
    private boolean releasePending;
    private boolean rePressPending;
    private long releaseAt;
    private long rePressAt;
    private boolean released;

    public Wtap() {
        super("WTap", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND
                || !(event.getPacket() instanceof C02PacketUseEntity) || mc.thePlayer == null) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != Action.ATTACK || this.releasePending || this.rePressPending) {
            return;
        }
        Entity target = packet.getEntityFromWorld(mc.theWorld);
        if (!(target instanceof EntityLivingBase) || !mc.thePlayer.isSprinting()
                || !physicallyDown(mc.gameSettings.keyBindForward)) {
            return;
        }
        if (this.selectHits.getValue() && ((EntityLivingBase) target).hurtResistantTime > 14) {
            return;
        }
        if (this.random.nextInt(100) >= this.chance.getValue()) {
            return;
        }
        this.releasePending = true;
        this.releaseAt = System.currentTimeMillis() + vary(this.releaseDelay.getValue());
        this.handleRelease();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null) {
            if (this.releasePending || this.rePressPending) {
                this.restoreForwardKey();
            }
            return;
        }
        if (this.releasePending) {
            this.handleRelease();
        } else if (this.rePressPending) {
            this.handleRePress();
        }
    }

    private void handleRelease() {
        if (System.currentTimeMillis() < this.releaseAt) {
            return;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        this.released = true;
        this.releasePending = false;
        this.rePressPending = true;
        this.rePressAt = System.currentTimeMillis() + vary(this.rePressDelay.getValue());
    }

    private void handleRePress() {
        if (System.currentTimeMillis() < this.rePressAt) {
            return;
        }
        this.restoreForwardKey();
    }

    /** The forward key as the player is really holding it. */
    private void restoreForwardKey() {
        if (this.released) {
            KeyBinding forward = mc.gameSettings.keyBindForward;
            KeyBinding.setKeyBindState(forward.getKeyCode(), physicallyDown(forward));
        }
        this.released = false;
        this.releasePending = false;
        this.rePressPending = false;
    }

    private long vary(int ms) {
        return ms <= 0 ? 0L : Math.round(ms * (0.8 + this.random.nextDouble() * 0.4));
    }

    private static boolean physicallyDown(KeyBinding key) {
        int code = key.getKeyCode();
        if (code == 0) {
            return false;
        }
        try {
            return code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
        } catch (Exception e) {
            return key.isKeyDown();
        }
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            this.restoreForwardKey();
        }
    }
}
