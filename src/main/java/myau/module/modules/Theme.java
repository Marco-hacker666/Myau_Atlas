package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ChatUtil;

/**
 * The master switch and colours of the Theme tab.
 *
 * Off, nothing is themed and every render module draws as it always did. On,
 * each group that is also on recolours its rendering; groups in THEME mode
 * (the default) take this module's colours, so changing them here changes
 * everything at once.
 *
 * A preset is a quick start: picking one sets these colours, switches this
 * and every group on, and points the groups at THEME -- except Items and
 * Chests, which keep their own per-type colours (an emerald should still look
 * like an emerald) and only take the transparency. Transparency and line
 * width are never touched by a preset. The preset keeps showing its name
 * until one of this module's colours is changed by hand, which turns it back
 * to CUSTOM. Reading a config never applies a preset (Config.loading): the
 * saved colours are the ones that count, preset or not.
 */
public class Theme extends ThemeStyle {

    private static final String[] PRESETS = {"CUSTOM", "RAINBOW", "ASTOLFO", "OCEAN", "SUNSET", "NEON", "MINT",
            "BLOOD", "LAVENDER", "GOLD", "PASTEL", "MONO", "TEAM", "HEALTH", "OFF"};

    public final ModeProperty preset = new ModeProperty("preset", 0, PRESETS);
    /** Every group's transparency scaled at once. */
    public final PercentProperty alphaScale = new PercentProperty("alpha-scale", 100, 0, 200, null);

    private static Theme instance;
    private boolean applying;

    public Theme() {
        super("Theme", true, 25, 100, 1.5F);
        instance = this;
    }

    static Theme instance() {
        return instance;
    }

    /** Every group module, in the order the tab lists them. */
    static final Class<?>[] GROUPS = {PlayerColors.class, TracerColors.class, TargetColors.class,
            BacktrackColors.class, BedColors.class, ChestColors.class, ItemColors.class, BlockColors.class,
            ProjectileColors.class, InterfaceColors.class, ChamsColors.class, NameTagColors.class,
            WidgetColors.class, EffectColors.class};

    @Override
    public void verifyValue(String name) {
        if (this.applying || myau.config.Config.loading) {
            return;
        }
        if (!"preset".equals(name)) {
            /* A colour of the master changed by hand: no longer the preset. */
            if (!"CUSTOM".equals(this.preset.getModeString()) && isColouring(name)) {
                this.applying = true;
                try {
                    this.preset.setValue(0);
                } finally {
                    this.applying = false;
                }
            }
            return;
        }
        String chosen = this.preset.getModeString();
        if ("CUSTOM".equals(chosen)) {
            return;
        }
        this.applying = true;
        try {
            apply(chosen);
            if ("OFF".equals(chosen)) {
                this.preset.setValue(0);
            }
        } finally {
            this.applying = false;
        }
        ChatUtil.sendFormatted(String.format("%s&7Theme: &f%s &7applied", Myau.clientName, chosen));
    }

    private static boolean isColouring(String name) {
        return "mode".equals(name) || name.startsWith("color-") || "speed".equals(name) || "spread".equals(name)
                || "saturation".equals(name) || "shade".equals(name) || name.startsWith("breathe");
    }

    private void apply(String name) {
        if ("OFF".equals(name)) {
            this.setEnabled(false);
            return;
        }
        switch (name) {
            case "RAINBOW":
                applyPreset("RAINBOW", 0xFF0000, 0x00FF00, 0x0000FF, 1.0F, 30, 80, 0, false);
                break;
            case "ASTOLFO":
                applyPreset("ASTOLFO", 0xF7A8B8, 0x55CDFC, 0xFFFFFF, 0.8F, 30, 100, 0, false);
                break;
            case "OCEAN":
                applyPreset("GRADIENT", 0x00C6FF, 0x0052D4, 0xFFFFFF, 0.6F, 25, 100, 0, false);
                break;
            case "SUNSET":
                applyPreset("TRIPLE", 0xFF512F, 0xF09819, 0xDD2476, 0.5F, 25, 100, 0, false);
                break;
            case "NEON":
                applyPreset("GRADIENT", 0xFF00E6, 0x00FFF0, 0xFFFFFF, 1.2F, 35, 100, 0, true);
                break;
            case "MINT":
                applyPreset("GRADIENT", 0x00F5A0, 0x00D9F5, 0xFFFFFF, 0.6F, 25, 90, 10, false);
                break;
            case "BLOOD":
                applyPreset("PULSE", 0xE01010, 0x400000, 0xFFFFFF, 0.8F, 20, 100, 0, false);
                break;
            case "LAVENDER":
                applyPreset("GRADIENT", 0xB57EDC, 0x7F7FD5, 0xFFFFFF, 0.5F, 25, 80, 15, false);
                break;
            case "GOLD":
                applyPreset("GRADIENT", 0xFFD700, 0xFF8C00, 0xFFFFFF, 0.6F, 25, 100, 0, false);
                break;
            case "PASTEL":
                applyPreset("RAINBOW", 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0.7F, 30, 45, 20, false);
                break;
            case "MONO":
                applyPreset("STATIC", 0xFFFFFF, 0x808080, 0xFFFFFF, 1.0F, 0, 0, 0, false);
                break;
            case "TEAM":
                applyPreset("TEAM", 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 1.0F, 0, 100, 0, false);
                break;
            case "HEALTH":
                applyPreset("HEALTH", 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 1.0F, 0, 100, 0, false);
                break;
            default:
                return;
        }
        for (Class<?> type : GROUPS) {
            Module module = Myau.moduleManager.modules.get(type);
            if (!(module instanceof ThemeStyle)) {
                continue;
            }
            ThemeStyle group = (ThemeStyle) module;
            group.follow(module instanceof ItemColors || module instanceof ChestColors ? "ORIGINAL" : "THEME");
            if (!group.isEnabled()) {
                group.setEnabled(true);
            }
        }
        if (!this.isEnabled()) {
            this.setEnabled(true);
        }
    }
}
