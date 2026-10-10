package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): limits click speed with CPS control, jitter
// and an optional break-chance gate.
import java.util.Objects;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.util.ItemUtil;
import myau.util.RandomUtil;
import net.minecraft.client.Minecraft;

public class SmartClicking extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty minCPS = new IntProperty("min-cps", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-cps", 12, 1, 20);
    public final BooleanProperty jitter = new BooleanProperty("jitter", false);
    public final FloatProperty jitterStrength = new FloatProperty("jitter-strength", 0.3F, 0.0F, 2.0F, this.jitter::getValue);
    public final PercentProperty breakChance = new PercentProperty("break-chance", 5);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);

    private long lastClickMs = 0L;
    private long nextClickGapMs = 0L;

    public SmartClicking() {
        super("SmartClicking", false, false, "Limits click speed with CPS control and jitter.");
    }

    private long getNextClickGap() {
        int max = Math.max(this.minCPS.getValue(), this.maxCPS.getValue());
        int min = Math.min(this.minCPS.getValue(), max);
        return 1000L / RandomUtil.nextLong(min, max);
    }

    @EventTarget(Priority.LOWEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (this.weaponsOnly.getValue() && !ItemUtil.isHoldingSword()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.lastClickMs != 0L && now - this.lastClickMs < this.nextClickGapMs) {
            event.setCancelled(true);
            return;
        }
        this.lastClickMs = now;
        this.nextClickGapMs = this.getNextClickGap();
        if (RandomUtil.nextInt(0, 100) < this.breakChance.getValue()) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.jitter.getValue() && mc.thePlayer != null && mc.gameSettings.keyBindAttack.isKeyDown()) {
            float j = this.jitterStrength.getValue();
            mc.thePlayer.rotationYaw += RandomUtil.nextFloat(-j, j);
            mc.thePlayer.rotationPitch += RandomUtil.nextFloat(-j, j);
        }
    }

    @Override
    public void onDisabled() {
        this.lastClickMs = 0L;
        this.nextClickGapMs = 0L;
    }

    @Override
    public void verifyValue(String name) {
        if (Objects.equals(name, "min-cps") && this.minCPS.getValue() > this.maxCPS.getValue()) {
            this.maxCPS.setValue(this.minCPS.getValue());
        } else if (Objects.equals(name, "max-cps") && this.minCPS.getValue() > this.maxCPS.getValue()) {
            this.minCPS.setValue(this.maxCPS.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        return Objects.equals(this.minCPS.getValue(), this.maxCPS.getValue())
                ? new String[]{this.minCPS.getValue().toString()}
                : new String[]{String.format("%d-%d", this.minCPS.getValue(), this.maxCPS.getValue())};
    }
}
