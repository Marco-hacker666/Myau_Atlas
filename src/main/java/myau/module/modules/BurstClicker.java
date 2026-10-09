package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): fires a short burst of drag-style clicks on
// enable, then switches itself off.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;

public class BurstClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty burstCPS = new IntProperty("burst-cps", 20, 1, 60);
    public final IntProperty burstDuration = new IntProperty("burst-duration", 500, 50, 2000);

    private long burstEndMs = 0L;
    private long nextClickMs = 0L;
    private boolean clickPending = false;

    public BurstClicker() {
        super("BurstClicker", false, false, "Fires a short burst of drag-style clicks.");
    }

    @Override
    public void onEnabled() {
        long now = System.currentTimeMillis();
        this.burstEndMs = now + (long) this.burstDuration.getValue();
        this.nextClickMs = now;
        this.clickPending = false;
    }

    @Override
    public void onDisabled() {
        this.clickPending = false;
        this.burstEndMs = 0L;
        if (mc.gameSettings != null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.clickPending) {
            this.clickPending = false;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            this.setEnabled(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= this.burstEndMs) {
            this.setEnabled(false);
            return;
        }
        long gap = 1000L / Math.max(1, this.burstCPS.getValue());
        while (this.nextClickMs <= now && now < this.burstEndMs) {
            this.clickPending = true;
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
            this.nextClickMs += gap;
        }
    }
}
