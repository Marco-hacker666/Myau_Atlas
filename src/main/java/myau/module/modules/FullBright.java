package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

public class FullBright extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private float prevGamma = Float.NaN;
    private boolean appliedNightVision = false;
    /* Night vision the player already had when EFFECT mode took over, and
       when; put back (with whatever time it would have left) on switch-off,
       instead of removing every night vision regardless of whose it was. */
    private PotionEffect savedNightVision;
    private int savedAtTick;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"GAMMA", "EFFECT"});

    public FullBright() {
        super("Fullbright", true, true);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            switch (this.mode.getValue()) {
                case 0:
                    mc.gameSettings.gammaSetting = 1000.0F;
                    break;
                case 1:
                    mc.thePlayer.addPotionEffect(new PotionEffect(Potion.nightVision.id, 25940, 0));
            }
        }
    }

    @Override
    public void onEnabled() {
        switch (this.mode.getValue()) {
            case 0:
                this.prevGamma = mc.gameSettings.gammaSetting;
                break;
            case 1:
                this.appliedNightVision = true;
                this.savedNightVision = null;
                if (mc.thePlayer != null) {
                    PotionEffect existing = mc.thePlayer.getActivePotionEffect(Potion.nightVision);
                    if (existing != null) {
                        this.savedNightVision = new PotionEffect(existing);
                        this.savedAtTick = mc.thePlayer.ticksExisted;
                    }
                }
        }
    }

    @Override
    public void onDisabled() {
        if (!Float.isNaN(this.prevGamma)) {
            mc.gameSettings.gammaSetting = this.prevGamma;
            this.prevGamma = Float.NaN;
        }
        if (this.appliedNightVision) {
            if (mc.thePlayer != null) {
                mc.thePlayer.removePotionEffectClient(Potion.nightVision.id);
                if (this.savedNightVision != null) {
                    int left = this.savedNightVision.getDuration()
                            - (mc.thePlayer.ticksExisted - this.savedAtTick);
                    if (left > 0) {
                        mc.thePlayer.addPotionEffect(new PotionEffect(Potion.nightVision.id, left,
                                this.savedNightVision.getAmplifier()));
                    }
                }
            }
            this.savedNightVision = null;
            this.appliedNightVision = false;
        }
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
            this.onEnabled();
        }
    }
}
