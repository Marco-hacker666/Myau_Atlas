package myau.ui.impl.clickgui.riselb;

import myau.Myau;
import myau.module.modules.ClickGUIModule;
import myau.util.RenderUtil;
import myau.util.font.FontManager;
import myau.util.font.impl.FontRenderer;
import myau.util.shader.ShadowShader;

import java.awt.Color;

/**
 * Palette and drawing primitives ported from the RiseLB LiquidBounce theme
 * (Documents/RiseLB-project, src/colors.scss and the clickgui routes).
 *
 * The values here are the SCSS tokens read straight across, so the two
 * clients look like the same product:
 *
 *   $rise-gui-bg          #0d141c    window body
 *   $rise-gui-sidebar     #0a1017    nav column
 *   $rise-gui-card        #141d27    module card
 *   $rise-gui-card-hover  #1b2733
 *   $rise-gui-text        #e8eef5
 *   $rise-gui-text-dim    #7d8b9a
 *   $rise-gui-border      rgba(255,255,255,.06)
 *
 * These are opaque surfaces, not glass: the design reads as a solid app
 * window sitting above the game, so there is deliberately no backdrop blur
 * pass here. Depth comes from the shadow and the three-step surface ramp
 * (sidebar darker than body, card lighter than body) rather than from
 * translucency.
 *
 * The accent still comes from the ClickGUI module's own Color setting so the
 * theme follows whatever the user picked, with the SCSS default as fallback.
 */
public final class RiseLBTheme {

    private RiseLBTheme() {
    }

    public static final Color BG = new Color(0x0D141C);
    public static final Color SIDEBAR = new Color(0x0A1017);
    public static final Color CARD = new Color(0x141D27);
    public static final Color CARD_HOVER = new Color(0x1B2733);
    public static final Color TEXT = new Color(0xE8EEF5);
    public static final Color TEXT_DIM = new Color(0x7D8B9A);
    private static final Color FALLBACK_ACCENT = new Color(0x5FCDF2);

    /** rgba(255,255,255,.06) */
    public static final int BORDER = new Color(255, 255, 255, 15).getRGB();
    /** Search field / switch track: rgba(255,255,255,.05) and .14 */
    public static final int FIELD_BG = new Color(255, 255, 255, 13).getRGB();
    public static final int FIELD_BG_FOCUS = new Color(255, 255, 255, 18).getRGB();
    public static final int SWITCH_OFF = new Color(255, 255, 255, 36).getRGB();

    // Metrics, matching the SCSS.
    public static final float WINDOW_RADIUS = 12.0f;
    public static final float CARD_RADIUS = 10.0f;
    public static final float CONTROL_RADIUS = 7.0f;
    public static final float SIDEBAR_WIDTH = 132.0f;
    public static final float SIDEBAR_PAD_X = 10.0f;
    public static final float SIDEBAR_PAD_Y = 14.0f;
    public static final float CONTENT_PAD_X = 13.0f;
    public static final float CONTENT_PAD_Y = 12.0f;
    public static final float CARD_GAP = 8.0f;
    public static final float NAV_ITEM_HEIGHT = 24.0f;
    public static final float NAV_GAP = 2.0f;
    public static final float SWITCH_W = 32.0f;
    public static final float SWITCH_H = 18.0f;
    public static final float KNOB = 14.0f;

    public static ClickGUIModule module() {
        return (ClickGUIModule) Myau.moduleManager.getModule("ClickGUI");
    }

    public static Color accent() {
        ClickGUIModule m = module();
        return m != null ? m.getAccentColor() : FALLBACK_ACCENT;
    }

    public static int clamp(int a) {
        return a < 0 ? 0 : (a > 255 ? 255 : a);
    }

    public static int rgba(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp(alpha)).getRGB();
    }

    /** Scales a packed ARGB colour's alpha by the screen fade. */
    public static int fade(int argb, float progress) {
        int a = clamp((int) (((argb >> 24) & 0xFF) * progress));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    public static int solid(Color c, float progress) {
        return rgba(c, (int) (255 * progress));
    }

    /**
     * cubic-bezier(0.22, 1, 0.36, 1) -- the $spring easing the SCSS uses on
     * hover lifts and the knob slide. Approximated closely enough for UI
     * motion by a quintic ease-out, which shares the same fast-start,
     * settle-without-overshoot shape.
     */
    public static float spring(float t) {
        if (t <= 0.0f) {
            return 0.0f;
        }
        if (t >= 1.0f) {
            return 1.0f;
        }
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv * inv * inv;
    }

    public static FontRenderer font(int size) {
        switch (size) {
            case 12:
                return FontManager.sfPro12 != null ? FontManager.sfPro12 : FontManager.productSans12;
            case 20:
                return FontManager.sfPro20 != null ? FontManager.sfPro20 : FontManager.productSans20;
            case 24:
                return FontManager.sfPro24 != null ? FontManager.sfPro24 : FontManager.productSans24;
            default:
                return FontManager.sfPro16 != null ? FontManager.sfPro16 : FontManager.productSans16;
        }
    }

    public static void draw(int size, String text, float x, float y, int color) {
        FontRenderer f = font(size);
        if (f != null) {
            f.drawString(text, x, y, color);
        }
    }

    public static float width(int size, String text) {
        FontRenderer f = font(size);
        return f != null ? (float) f.getStringWidth(text) : 0.0f;
    }

    public static float height(int size) {
        FontRenderer f = font(size);
        return f != null ? (float) f.getHeight() : 8.0f;
    }

    /** Centres text vertically inside a row of the given height. */
    public static float textY(float rowY, float rowHeight, int size) {
        return rowY + (rowHeight - height(size)) / 2.0f;
    }

    public static void panel(float x, float y, float w, float h, float radius, Color color, float progress) {
        RenderUtil.drawRoundedRect(x, y, w, h, radius, solid(color, progress), true, true, true, true);
    }

    public static void border(float x, float y, float w, float h, float radius, int color, float progress) {
        RenderUtil.drawRoundedRectOutline(x, y, w, h, radius, 1.0f, fade(color, progress), true, true, true, true);
    }

    /** box-shadow: 0 18px 50px rgba(0,0,0,.55) */
    public static void windowShadow(float x, float y, float w, float h, float progress) {
        ClickGUIModule m = module();
        if (m != null && !m.shadow.getValue()) {
            return;
        }
        ShadowShader.drawShadow(x, y + 6.0f, w, h, WINDOW_RADIUS, 18.0f,
                rgba(Color.BLACK, (int) (140 * progress)));
    }

    /**
     * The card switch: 32x18 track, 14px knob, accent when on.
     * {@code on} is the animated 0..1 position, not the raw boolean, so the
     * knob slides instead of snapping.
     */
    public static void toggle(float x, float y, float on, float progress) {
        int track = fade(SWITCH_OFF, progress);
        if (on > 0.0f) {
            int lit = rgba(accent(), (int) (255 * progress));
            track = myau.util.AnimationUtil.interpolateColor(track, lit, on);
        }
        RenderUtil.drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H / 2.0f, track, true, true, true, true);
        float knobX = x + 2.0f + on * (SWITCH_W - KNOB - 4.0f);
        RenderUtil.drawRoundedRect(knobX, y + 2.0f, KNOB, KNOB, KNOB / 2.0f,
                rgba(Color.WHITE, (int) (255 * progress)), true, true, true, true);
    }

    /** Chevron for the card expand control, rotated by {@code open} (0..1). */
    public static void chevron(float cx, float cy, float size, float open, int color) {
        float half = size / 2.0f;
        // -90deg when closed, 0deg when open: interpolate the arm endpoints.
        double angle = Math.toRadians(-90.0 + 90.0 * open);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int side = -1; side <= 1; side += 2) {
            double ax = -half * side;
            double ay = -half * 0.5;
            double bx = 0.0;
            double by = half * 0.5;
            float x1 = cx + (float) (ax * cos - ay * sin);
            float y1 = cy + (float) (ax * sin + ay * cos);
            float x2 = cx + (float) (bx * cos - by * sin);
            float y2 = cy + (float) (bx * sin + by * cos);
            RenderUtil.drawLine(x1, y1, x2, y2, 1.4f, color);
        }
    }
}
