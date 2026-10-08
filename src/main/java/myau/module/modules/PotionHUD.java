/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/PotionHUD.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

import myau.ui.impl.clickgui.atlas.HudPanel;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.ui.hud.HudLayout;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.StatCollector;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// Active potion effect list with durations. Concept adapted from the raven-bS PotionHUD module.
public class PotionHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;

    public final ModeProperty posX = new ModeProperty("position-x", 1, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final IntProperty offX = new IntProperty("offset-x", 0, -500, 500);
    public final IntProperty offY = new IntProperty("offset-y", 40, -500, 500);
    public final FloatProperty scale = new FloatProperty("scale", 1.0f, 0.5f, 2.0f);
    public final ModeProperty timeFormat = new ModeProperty("time-format", 0, new String[]{"SECONDS", "MIN:SEC"});
    public final BooleanProperty amplifier = new BooleanProperty("amplifier", true);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty background = new BooleanProperty("background", true);

    private final List<String> lines = new ArrayList<>();
    private int activeCount = 0;

    public PotionHUD() {
        super("PotionHUD", false, false, "Displays active potion effects with durations on screen.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(activeCount)};
    }

    @Override
    public void onEnabled() {
        resetState();
    }

    @Override
    public void onDisabled() {
        resetState();
    }

    private void resetState() {
        lines.clear();
        activeCount = 0;
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null) {
            return;
        }
        lines.clear();
        List<PotionEffect> effects = new ArrayList<>(mc.thePlayer.getActivePotionEffects());
        Collections.sort(effects, new Comparator<PotionEffect>() {
            @Override
            public int compare(PotionEffect a, PotionEffect b) {
                return Integer.compare(b.getDuration(), a.getDuration());
            }
        });
        for (PotionEffect effect : effects) {
            String name = StatCollector.translateToLocal(effect.getEffectName());
            if (amplifier.getValue() && effect.getAmplifier() > 0) {
                name += " " + toRoman(effect.getAmplifier() + 1);
            }
            lines.add(name + " §7" + formatDuration(effect.getDuration()));
        }
        activeCount = lines.size();
        /* Without effects nothing is drawn -- but while the HUD editor is open
           a placeholder of one line is measured and reported, so it can still
           be found and placed (2026-10-05). */
        boolean placeholder = lines.isEmpty();
        if (placeholder) {
            if (!HudLayout.isEditing()) {
                return;
            }
            lines.add("Potion effects \u00a777s");
        }

        float sc = scale.getValue();
        int blockW = 0;
        for (String line : lines) {
            blockW = Math.max(blockW, mc.fontRendererObj.getStringWidth(line));
        }
        int blockH = lines.size() * LINE_HEIGHT + PADDING * 2;
        int boxW = blockW + PADDING * 2;

        ScaledResolution sr = new ScaledResolution(mc);
        float x = offX.getValue().floatValue();
        switch (posX.getValue()) {
            case 1:
                x += sr.getScaledWidth() / 2.0f - boxW * sc / 2.0f;
                break;
            case 2:
                x = sr.getScaledWidth() - boxW * sc - x;
                break;
            default:
                break;
        }
        float y = offY.getValue().floatValue();
        switch (posY.getValue()) {
            case 1:
                y += sr.getScaledHeight() / 2.0f - blockH * sc / 2.0f;
                break;
            case 2:
                y = sr.getScaledHeight() - blockH * sc - y;
                break;
            default:
                break;
        }

        /* HUD editor (2026-10-05): report where this is drawn, so the editor can find and drag it. */
        HudLayout.report("PotionHUD", placeholder ? "PotionHUD (no effects)" : "PotionHUD", x, y, boxW * sc,
                blockH * sc, HudLayout.ints(offX, posX.getValue() == 2 ? -1 : 1, offY, posY.getValue() == 2 ? -1 : 1));
        if (placeholder) {
            lines.clear();
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(sc, sc, 1.0f);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        if (background.getValue()) {
            if (HudPanel.active()) {
                HudPanel.panel(0, 0, boxW, blockH);
            } else {
                RenderUtil.drawRect(0, 0, boxW, blockH, new Color(0, 0, 0, 110).getRGB());
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            mc.fontRendererObj.drawString(lines.get(i), PADDING, PADDING + i * LINE_HEIGHT, 0xFFFFFF, shadow.getValue());
        }
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private String formatDuration(int ticks) {
        int total = Math.max(0, ticks / 20);
        if (timeFormat.getValue() == 1) {
            return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
        }
        return total + "s";
    }

    private String toRoman(int value) {
        if (value <= 0) {
            return String.valueOf(value);
        }
        int[] values = {10, 9, 5, 4, 1};
        String[] numerals = {"X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < values.length; i++) {
            while (remaining >= values[i]) {
                sb.append(numerals[i]);
                remaining -= values[i];
            }
        }
        return sb.toString();
    }
}
