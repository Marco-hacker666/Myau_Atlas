package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): a small, legit-only reach widening with
// conservative caps (never past 3.4/3.5) and an optional chance gate.
import java.util.Random;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PickEvent;
import myau.events.RaytraceEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

public class LegitReach extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();
    private boolean expanding = true;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Stable", "Jitter"});
    public final FloatProperty fixed = new FloatProperty("fixed", 3.2F, 3.0F, 3.5F, () -> this.mode.getValue() == 0);
    public final FloatProperty minRange = new FloatProperty("min-range", 3.0F, 3.0F, 6.0F, () -> this.mode.getValue() == 1);
    public final FloatProperty maxRange = new FloatProperty("max-range", 3.3F, 3.0F, 6.0F, () -> this.mode.getValue() == 1);
    public final PercentProperty chance = new PercentProperty("chance", 50);
    public final BooleanProperty sprintOnly = new BooleanProperty("sprint-only", true);

    public LegitReach() {
        super("LegitReach", false, false, "Slightly extends your attack reach.");
    }

    @Override
    public void onEnabled() {
        this.expanding = true;
    }

    @Override
    public void onDisabled() {
        this.expanding = true;
    }

    private double getEffectiveRange() {
        if (this.mode.getValue() == 1) {
            float lo = Math.min(this.minRange.getValue(), this.maxRange.getValue());
            float hi = Math.max(this.minRange.getValue(), this.maxRange.getValue());
            hi = Math.min(hi, 3.5F);
            lo = Math.min(lo, hi);
            return (double) lo + this.random.nextDouble() * Math.max(0.0F, hi - lo);
        }
        return Math.min(this.fixed.getValue(), 3.4F);
    }

    private boolean passesGates() {
        if (!this.expanding) {
            return false;
        }
        if (this.sprintOnly.getValue() && (mc.thePlayer == null || !mc.thePlayer.isSprinting())) {
            return false;
        }
        return true;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.expanding = this.random.nextInt(100) < this.chance.getValue();
        }
    }

    @EventTarget
    public void onPick(PickEvent event) {
        if (this.isEnabled() && this.passesGates()) {
            event.setRange(this.getEffectiveRange());
        }
    }

    @EventTarget
    public void onRaytrace(RaytraceEvent event) {
        if (this.isEnabled() && this.passesGates()) {
            event.setRange(Math.max(event.getRange(), this.getEffectiveRange()));
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 1) {
            return new String[]{this.mode.getModeString() + " " + this.chance.getValue() + "%"};
        }
        return new String[]{String.format("%.2f", (double) Math.min(this.fixed.getValue(), 3.4F))};
    }
}
