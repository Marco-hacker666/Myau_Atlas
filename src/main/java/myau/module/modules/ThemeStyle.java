package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ColorUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import java.awt.Color;

/**
 * One group of extra rendering and how it is coloured: the base of every
 * module in the Theme tab (2026-09-25).
 *
 * Before this, each render module had its own idea of colour -- a mode list
 * of its own, sometimes RGB sliders, sometimes a fixed palette, sometimes
 * "same as the HUD" -- and no module had all of: rainbow, gradients, speed,
 * shade, saturation, transparency. Rather than adding those to twenty
 * modules one by one, a render module asks its group here for a colour,
 * passing the colour it would have used; when the group is off (the
 * default) that colour comes back untouched, so nothing looks different
 * until the user turns theming on.
 *
 * A group in THEME mode follows the master Theme module's colours, so one
 * place recolours everything, while any group can still be set apart. The
 * transparency and line width are always the group's own: they are about
 * how much a thing should stand out, not what colour it is.
 */
public abstract class ThemeStyle extends Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    static final String[] GROUP_MODES = {"THEME", "ORIGINAL", "STATIC", "RAINBOW", "GRADIENT", "TRIPLE",
            "PULSE", "ASTOLFO", "TEAM", "HEALTH", "DISTANCE", "HUD"};
    static final String[] MASTER_MODES = {"ORIGINAL", "STATIC", "RAINBOW", "GRADIENT", "TRIPLE",
            "PULSE", "ASTOLFO", "TEAM", "HEALTH", "DISTANCE", "HUD"};

    /** Set while a HUD-mode colour is being resolved, so the HUD's own group cannot loop back into it. */
    private static boolean resolvingHud;

    private final boolean master;

    public final ModeProperty mode;
    public final ColorProperty color1;
    public final ColorProperty color2;
    public final ColorProperty color3;
    /** Cycles a second-ish at 1.0; the animated modes only. */
    public final FloatProperty speed;
    /** How far apart in the cycle neighbouring lines or entities are. */
    public final PercentProperty spread;
    public final PercentProperty saturation;
    /** Darker below zero, lighter above, by mixing toward black or white. */
    public final IntProperty shade;
    /** Brightness rising and falling on top of any mode. */
    public final BooleanProperty breathe;
    public final PercentProperty breatheDepth;
    /** Filled areas: boxes, backgrounds. */
    public final PercentProperty fillAlpha;
    /** Lines: outlines, tracers, arrows, text accents. */
    public final PercentProperty lineAlpha;
    public final FloatProperty lineWidth;

    protected ThemeStyle(String name, boolean master, int defaultFill, int defaultLine, float defaultWidth) {
        super(name, false, true);
        this.master = master;
        this.mode = new ModeProperty("mode", 0, master ? MASTER_MODES : GROUP_MODES);
        this.color1 = new ColorProperty("color-1", 0x55CDFC, () -> this.uses("STATIC", "GRADIENT", "TRIPLE", "PULSE",
                "TEAM", "HEALTH", "DISTANCE"));
        this.color2 = new ColorProperty("color-2", 0xF7A8B8, () -> this.uses("GRADIENT", "TRIPLE"));
        this.color3 = new ColorProperty("color-3", 0xFFFFFF, () -> this.uses("TRIPLE"));
        this.breathe = new BooleanProperty("breathe", false, () -> !this.uses("THEME"));
        this.speed = new FloatProperty("speed", 1.0F, 0.05F, 5.0F, () -> this.uses("RAINBOW", "GRADIENT", "TRIPLE",
                "PULSE", "ASTOLFO") || this.breathe.getValue());
        this.spread = new PercentProperty("spread", 25, () -> this.uses("RAINBOW", "GRADIENT", "TRIPLE", "PULSE",
                "ASTOLFO", "HUD"));
        this.saturation = new PercentProperty("saturation", 100, () -> !this.uses("THEME"));
        this.shade = new IntProperty("shade", 0, -100, 100, () -> !this.uses("THEME"));
        this.breatheDepth = new PercentProperty("breathe-depth", 50, () -> !this.uses("THEME") && this.breathe.getValue());
        this.fillAlpha = new PercentProperty("fill-alpha", defaultFill);
        this.lineAlpha = new PercentProperty("line-alpha", defaultLine);
        this.lineWidth = new FloatProperty("line-width", defaultWidth, 0.5F, 5.0F);
    }

    private boolean uses(String... modes) {
        String current = this.mode.getModeString();
        for (String m : modes) {
            if (m.equals(current)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ access

    /**
     * The group's style if it should be applied now -- its module on, and the
     * master Theme on -- or null, in which case the caller keeps its colour.
     */
    public static ThemeStyle active(Class<? extends ThemeStyle> type) {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(type);
        if (!(module instanceof ThemeStyle) || !module.isEnabled()) {
            return null;
        }
        Theme theme = Theme.instance();
        return theme != null && theme.isEnabled() ? (ThemeStyle) module : null;
    }

    /** The group's colour, opaque. */
    public Color color(Color original, Entity entity, int index) {
        ThemeStyle source = this;
        if (!this.master && "THEME".equals(this.mode.getModeString())) {
            Theme theme = Theme.instance();
            if (theme != null) {
                source = theme;
            }
        }
        return source.shape(original == null ? Color.WHITE : original, entity, index);
    }

    /** For filled areas, at the group's fill transparency. */
    public Color fill(Color original, Entity entity, int index) {
        return withAlpha(color(original, entity, index), this.fillAlpha.getValue());
    }

    /** For lines, at the group's line transparency. */
    public Color line(Color original, Entity entity, int index) {
        return withAlpha(color(original, entity, index), this.lineAlpha.getValue());
    }

    /** Whether the colour is actually changed -- not ORIGINAL, here or through the master. */
    public boolean recolours() {
        String current = this.mode.getModeString();
        if (!this.master && "THEME".equals(current)) {
            Theme theme = Theme.instance();
            return theme != null && !"ORIGINAL".equals(theme.mode.getModeString());
        }
        return !"ORIGINAL".equals(current);
    }

    public int fillAlpha255() {
        return scaleAlpha(this.fillAlpha.getValue());
    }

    public int lineAlpha255() {
        return scaleAlpha(this.lineAlpha.getValue());
    }

    public float width() {
        return this.lineWidth.getValue();
    }

    private static Color withAlpha(Color color, int percent) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), scaleAlpha(percent));
    }

    private static int scaleAlpha(int percent) {
        Theme theme = Theme.instance();
        float scale = theme == null ? 1.0F : theme.alphaScale.getValue() / 100.0F;
        return MathHelper.clamp_int(Math.round(percent / 100.0F * scale * 255.0F), 0, 255);
    }

    // ------------------------------------------------------------------ colour

    private Color shape(Color original, Entity entity, int index) {
        double seconds = (System.currentTimeMillis() % 3_600_000L) / 1000.0;
        double offset;
        if (index >= 0) {
            offset = index * this.spread.getValue() / 100.0 * 0.12;
        } else if (entity != null) {
            /* Entities have no order; the golden ratio spreads their ids evenly round the cycle. */
            offset = frac(entity.getEntityId() * 0.6180339887) * this.spread.getValue() / 100.0;
        } else {
            offset = 0.0;
        }
        double phase = seconds * this.speed.getValue() * 0.35 + offset;
        Color c1 = new Color(this.color1.getValue());
        Color color;
        switch (this.mode.getModeString()) {
            case "STATIC":
                color = c1;
                break;
            case "RAINBOW":
                color = Color.getHSBColor((float) frac(phase), 1.0F, 1.0F);
                break;
            case "GRADIENT":
                color = ColorUtil.interpolate((float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0)), c1,
                        new Color(this.color2.getValue()));
                break;
            case "TRIPLE": {
                double p = frac(phase) * 3.0;
                Color c2 = new Color(this.color2.getValue());
                Color c3 = new Color(this.color3.getValue());
                if (p < 1.0) {
                    color = ColorUtil.interpolate((float) p, c1, c2);
                } else if (p < 2.0) {
                    color = ColorUtil.interpolate((float) (p - 1.0), c2, c3);
                } else {
                    color = ColorUtil.interpolate((float) (p - 2.0), c3, c1);
                }
                break;
            }
            case "PULSE":
                color = scaleBrightness(c1, (float) (0.3 + 0.7 * (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0))));
                break;
            case "ASTOLFO": {
                /* Pink through purple to cyan and back: the hue bounces in the cool half. */
                double h = frac(phase);
                h = h < 0.5 ? h : 1.0 - h;
                color = Color.getHSBColor((float) (0.5 + h), 0.5F, 1.0F);
                break;
            }
            case "TEAM":
                color = entity instanceof EntityPlayer ? TeamUtil.getTeamColor((EntityPlayer) entity, 1.0F) : c1;
                break;
            case "HEALTH":
                if (entity instanceof EntityLivingBase && ((EntityLivingBase) entity).getMaxHealth() > 0.0F) {
                    EntityLivingBase living = (EntityLivingBase) entity;
                    color = ColorUtil.getHealthBlend(MathHelper.clamp_float(living.getHealth() / living.getMaxHealth(),
                            0.0F, 1.0F));
                } else {
                    color = c1;
                }
                break;
            case "DISTANCE":
                if (entity != null && mc.thePlayer != null) {
                    float near = MathHelper.clamp_float(mc.thePlayer.getDistanceToEntity(entity) / 24.0F, 0.0F, 1.0F);
                    color = Color.getHSBColor(near * 0.33F, 1.0F, 1.0F);
                } else {
                    color = c1;
                }
                break;
            case "HUD":
                color = hudColor(original, Math.max(0, index));
                break;
            default:
                /* ORIGINAL: the render module's own colour, with the adjustments below. */
                color = original;
                break;
        }
        return adjust(color, phase);
    }

    private Color hudColor(Color original, int index) {
        if (resolvingHud || this instanceof InterfaceColors) {
            return original;
        }
        Module hud = Myau.moduleManager.modules.get(HUD.class);
        if (!(hud instanceof HUD)) {
            return original;
        }
        resolvingHud = true;
        try {
            return ((HUD) hud).getColor(System.currentTimeMillis(), index);
        } finally {
            resolvingHud = false;
        }
    }

    private Color adjust(Color color, double phase) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float brightness = hsb[2];
        if (this.breathe.getValue()) {
            float depth = this.breatheDepth.getValue() / 100.0F;
            brightness *= 1.0F - depth * (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
        }
        Color out = Color.getHSBColor(hsb[0], hsb[1] * this.saturation.getValue() / 100.0F, brightness);
        int shade = this.shade.getValue();
        if (shade < 0) {
            out = ColorUtil.interpolate(-shade / 100.0F, out, Color.BLACK);
        } else if (shade > 0) {
            out = ColorUtil.interpolate(shade / 100.0F, out, Color.WHITE);
        }
        return out;
    }

    private static Color scaleBrightness(Color color, float factor) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1], hsb[2] * factor);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    // ------------------------------------------------------------------ presets

    /** Sets this style's colouring from a preset; transparency and width are left as the user set them. */
    void applyPreset(String mode, int c1, int c2, int c3, float speed, int spread, int saturation, int shade,
                     boolean breathe) {
        this.mode.parseString(mode);
        this.color1.setValue(c1);
        this.color2.setValue(c2);
        this.color3.setValue(c3);
        this.speed.setValue(speed);
        this.spread.setValue(spread);
        this.saturation.setValue(saturation);
        this.shade.setValue(shade);
        this.breathe.setValue(breathe);
    }

    void follow(String mode) {
        this.mode.parseString(mode);
    }
}
