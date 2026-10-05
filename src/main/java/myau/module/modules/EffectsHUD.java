package myau.module.modules;

import myau.ui.UiMode;
import myau.ui.hud.HudLayout;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** A compact, right-aligned list of active potion effects and timers. */
public class EffectsHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] AMPLIFIERS = {"", " II", " III", " IV", " V", " VI", " VII", " VIII"};

    public final IntProperty right = new IntProperty("right", 8, 0, 500);
    public final IntProperty y = new IntProperty("y", 32, 0, 400);
    public final BooleanProperty background = new BooleanProperty("background", true);

    public EffectsHUD() {
        super("EffectsHUD", false, false, "Shows active potion effects and their remaining time.");
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) {
            if (HudLayout.isEditing()) {
                /* Nothing to show, but still somewhere to put it. */
                int edge = new ScaledResolution(mc).getScaledWidth() - this.right.getValue();
                HudLayout.report("EffectsHUD", "EffectsHUD (no effects)", edge - 93, this.y.getValue() - 2, 96,
                        mc.fontRendererObj.FONT_HEIGHT + 4, HudLayout.ints(this.right, -1, this.y, 1));
            }
            return;
        }

        List<PotionEffect> effects = new ArrayList<PotionEffect>(active);
        effects.sort(Comparator.comparingInt(PotionEffect::getPotionID));
        ScaledResolution resolution = new ScaledResolution(mc);
        int rightEdge = resolution.getScaledWidth() - this.right.getValue();
        int drawY = this.y.getValue();
        int widest = 0;
        for (PotionEffect effect : effects) {
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) continue;

            Potion potion = Potion.potionTypes[id];
            String name = I18n.format(potion.getName());
            int amplifier = effect.getAmplifier();
            String level = amplifier >= 0 && amplifier < AMPLIFIERS.length
                    ? AMPLIFIERS[amplifier] : " " + (amplifier + 1);
            String text = name + level + "  " + StringUtils.ticksToElapsedTime(effect.getDuration());
            int textWidth = mc.fontRendererObj.getStringWidth(text);
            widest = Math.max(widest, textWidth);
            int textX = rightEdge - textWidth;
            int color = 0xFF000000 | (potion.getLiquidColor() & 0xFFFFFF);
            if (this.background.getValue()) {
                Gui.drawRect(textX - 3, drawY - 2, rightEdge + 3,
                        drawY + mc.fontRendererObj.FONT_HEIGHT + 2, UiMode.adapt(0x70000000));
            }
            mc.fontRendererObj.drawStringWithShadow(text, textX, drawY, color);
            drawY += mc.fontRendererObj.FONT_HEIGHT + 3;
            HudLayout.report("EffectsHUD", "EffectsHUD", rightEdge - widest - 3, this.y.getValue() - 2, widest + 6,
                    drawY - this.y.getValue() + 1, HudLayout.ints(this.right, -1, this.y, 1));
        }
    }
}
