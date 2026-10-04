package myau.ui.impl.clickgui.atlas;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * The material the Atlas menu is made of.
 *
 * Three attempts, and each failure is worth keeping written down because each
 * was the obvious thing to do:
 *
 * WHITE ON TOP OF BLUR. A blurred background is already bright -- blurring a
 * daylit scene averages towards white -- so a translucent white fill over it
 * lands on light grey, and white text on light grey is unreadable. Frosted
 * material in a dark interface is the opposite: a heavily darkened layer over
 * the blur, with white appearing only in the hairline at the edge. There is no
 * white fill anywhere below.
 *
 * A RENDERER THAT CLAMPS RADIUS TO FOUR PIXELS. Everything was asking for nine
 * or thirteen and silently getting four, which at this resolution is a square.
 * A generous corner radius is most of what separates a panel that reads as a
 * physical object from a rectangle of colour, so the geometry is drawn here.
 *
 * ARCS EMITTED IN THE WRONG ORDER. The first version of that geometry swept
 * each corner from its own starting angle, which put two of the four corners'
 * vertices inside the rectangle. A triangle fan with self-crossing edges does
 * not draw a wrong shape -- it collapses, and the panel simply was not there.
 * The winding is now a single continuous sweep from 180 degrees down to -180,
 * changing centre at each quadrant boundary, which is the only arrangement
 * where consecutive vertices never cross.
 *
 * With vertices at {@code (cx + sin a, cy + cos a)} and screen Y growing
 * downward, that sweep is:
 *
 *   top-right     180 -> 90    around (x2-r, y+r)
 *   bottom-right   90 -> 0     around (x2-r, y2-r)
 *   bottom-left     0 -> -90   around (x+r,  y2-r)
 *   top-left      -90 -> -180  around (x+r,  y+r)
 *
 * Four behaviours have to be present together before any of this reads as
 * glass: a genuinely blurred background, a dark fill rather than a light one, a
 * bright hairline along the top edge fading by the bottom, and a soft shadow
 * underneath. Any three without the fourth is a flat rectangle.
 */
final class Glass {

    private Glass() {
    }

    /* Obsidian to midnight indigo. Neutral grey over a blurred colourful scene
       reads as dirty; the indigo cast is what reads as glass rather than as a
       dimmed rectangle. */
    static final int MATERIAL_TOP = 0xDC161B2E;
    static final int MATERIAL_BOTTOM = 0xEA0B0E14;
    /** Thinner material for surfaces meant to sit closer to the viewer. */
    static final int VEIL_TOP = 0xB01A2038;
    static final int VEIL_BOTTOM = 0xC40D1119;

    static final int ACCENT_COOL = 0xFF7FDBFF;
    static final int ACCENT_DEEP = 0xFF0A4D68;
    static final int ACCENT_MINT = 0xFF5FFFC1;
    static final int ACCENT_AMBER = 0xFFFFB347;
    /** Rim light from behind, which is what lifts the panel off the backdrop. */
    static final int BACKLIGHT = 0xFFB9A7FF;

    private static final int SEGMENTS = 8;

    // ---- geometry -----------------------------------------------------

    /**
     * Emits the outline as one continuous sweep, optionally shading each
     * vertex by its height so a single call can fill flat or graduated.
     */
    private static void sweep(float x, float y, float x2, float y2, float radius,
                              int top, int bottom, boolean shaded) {
        float height = Math.max(0.0001F, y2 - y);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            float cx;
            float cy;
            double from;
            switch (quadrant) {
                case 0: cx = x2 - radius; cy = y + radius; from = 180.0; break;
                case 1: cx = x2 - radius; cy = y2 - radius; from = 90.0; break;
                case 2: cx = x + radius; cy = y2 - radius; from = 0.0; break;
                default: cx = x + radius; cy = y + radius; from = -90.0; break;
            }
            for (int i = 0; i <= SEGMENTS; i++) {
                double angle = Math.toRadians(from - 90.0 * i / (double) SEGMENTS);
                double vx = cx + Math.sin(angle) * radius;
                double vy = cy + Math.cos(angle) * radius;
                if (shaded) {
                    colour(blend(top, bottom, (float) ((vy - y) / height)));
                }
                GL11.glVertex2d(vx, vy);
            }
        }
    }

    /** Solid rounded fill. */
    static void fill(float x, float y, float x2, float y2, float radius, int colour) {
        fillVertical(x, y, x2, y2, radius, colour, colour);
    }

    /** Rounded fill whose colour runs top to bottom. */
    static void fillVertical(float x, float y, float x2, float y2, float radius,
                             int top, int bottom) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        radius = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        begin();
        boolean shaded = top != bottom;
        if (shaded) {
            GL11.glShadeModel(GL11.GL_SMOOTH);
        } else {
            colour(top);
        }
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        if (shaded) {
            colour(blend(top, bottom, 0.5F));
        }
        GL11.glVertex2f((x + x2) / 2.0F, (y + y2) / 2.0F);
        sweep(x, y, x2, y2, radius, top, bottom, shaded);
        /* Close the fan onto the first outline vertex, or the last wedge is
           missing and the shape has a notch in one corner. */
        if (shaded) {
            colour(blend(top, bottom, 0.0F));
        }
        GL11.glVertex2f(x2 - radius, y);
        GL11.glEnd();
        if (shaded) {
            GL11.glShadeModel(GL11.GL_FLAT);
        }
        end();
    }

    /**
     * The specular edge: a hairline bright along the top and nearly gone by the
     * bottom. This is what communicates thickness; without it the same shape is
     * a coloured rectangle.
     */
    static void rim(float x, float y, float x2, float y2, float radius, int top, int bottom) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        radius = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        begin();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        sweep(x + 0.5F, y + 0.5F, x2 - 0.5F, y2 - 0.5F, radius, top, bottom, true);
        GL11.glEnd();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        end();
    }

    /**
     * The whole pane: material, inner bevel, dispersive edge, and grain.
     *
     * The order matters. Grain goes on last and over everything, because it is
     * standing in for sensor noise -- laid under the highlights it reads as a
     * dirty texture instead.
     */
    static void pane(float x, float y, float x2, float y2, float radius) {
        fillVertical(x, y, x2, y2, radius, MATERIAL_TOP, MATERIAL_BOTTOM);
        /* Inner bevel: a bright hairline just inside the top edge, and a
           matching dark one just inside the bottom, which is what a thick
           slab does to a key light from above. */
        fill(x + radius, y + 1.0F, x2 - radius, y + 2.0F, 0.0F, 0x1AFFFFFF);
        fill(x + radius, y2 - 2.0F, x2 - radius, y2 - 1.0F, 0.0F, 0x30000000);
        dispersiveRim(x, y, x2, y2, radius);
        grain(x, y, x2, y2);
    }

    /**
     * The edge, drawn three times with sub-pixel offsets in red, green and
     * blue.
     *
     * Real glass separates wavelengths at a steep edge, and that coloured
     * fringe is the single most recognisable thing about a rendered glass
     * bevel -- more than the transparency, more than the blur. One white
     * outline cannot produce it at any opacity. The offsets are a third of a
     * pixel, enough to tint the antialiased edge without reading as three
     * separate lines.
     */
    static void dispersiveRim(float x, float y, float x2, float y2, float radius) {
        rim(x - 0.34F, y - 0.34F, x2 - 0.34F, y2 - 0.34F, radius, 0x3300E0FF, 0x0A00E0FF);
        rim(x + 0.34F, y + 0.34F, x2 + 0.34F, y2 + 0.34F, radius, 0x33FF77CC, 0x0AFF77CC);
        rim(x, y, x2, y2, radius, 0x5CFFFFFF, 0x0EFFFFFF);
    }

    /**
     * A faint ordered-dither grain over the panel.
     *
     * Two jobs. It breaks the banding that any large smooth gradient shows at
     * eight bits per channel, which on a panel this size is visible as stripes.
     * And it gives the surface a texture to catch light on, without which a
     * gradient reads as a flat vector shape rather than as a material.
     *
     * Drawn as points on a coarse lattice with a deterministic pattern, so it
     * does not shimmer between frames -- animated noise on a static panel is
     * far more distracting than the banding it replaces.
     */
    static void grain(float x, float y, float x2, float y2) {
        begin();
        GL11.glBegin(GL11.GL_POINTS);
        int seed = 0;
        for (float gy = y + 2.0F; gy < y2 - 2.0F; gy += 3.0F) {
            for (float gx = x + 2.0F; gx < x2 - 2.0F; gx += 3.0F) {
                seed = seed * 1103515245 + 12345;
                int v = (seed >>> 16) & 0xFF;
                if (v < 96) {
                    continue;
                }
                colour(v > 200 ? 0x0EFFFFFF : 0x0A000000);
                GL11.glVertex2f(gx + ((v & 1) == 0 ? 0.0F : 1.0F), gy + ((v & 2) == 0 ? 0.0F : 1.0F));
            }
        }
        GL11.glEnd();
        end();
    }

    /**
     * A cool halo behind the panel, so it is lit from the back as well as the
     * front. Drawn before the pane; without it the shadow alone makes the
     * panel look stuck onto the background rather than floating in front of it.
     */
    static void backlight(float x, float y, float x2, float y2, float radius, int strength) {
        for (int i = 6; i > 0; i--) {
            float spread = i * 2.4F;
            int alpha = Math.max(1, strength / (i * 3));
            fill(x - spread, y - spread, x2 + spread, y2 + spread, radius + spread,
                    (alpha << 24) | (BACKLIGHT & 0x00FFFFFF));
        }
    }

    /** A lighter sheet for something resting on the pane. */
    static void sheet(float x, float y, float x2, float y2, float radius, int alpha) {
        fill(x, y, x2, y2, radius, (alpha << 24) | 0x00FFFFFF);
    }

    /** Light gathering under the glass, for whatever is active. */
    static void glow(float x, float y, float x2, float y2, float radius, int colour, int alpha) {
        int strong = (alpha << 24) | (colour & 0x00FFFFFF);
        int weak = ((alpha / 3) << 24) | (colour & 0x00FFFFFF);
        fillVertical(x, y, x2, y2, radius, strong, weak);
    }

    /**
     * A soft shadow, as expanding rounded rects of falling alpha. Small, easy
     * to leave out, and without it the panel sits flat on the background
     * however good everything else is.
     */
    static void shadow(float x, float y, float x2, float y2, float radius, int layers, int strength) {
        for (int i = layers; i > 0; i--) {
            float spread = i * 1.7F;
            int alpha = Math.max(1, (int) (strength * (1.0F - i / (layers + 1.0F)) / layers));
            fill(x - spread, y - spread + 2.0F, x2 + spread, y2 + spread + 2.0F,
                    radius + spread, alpha << 24);
        }
    }

    /** An edge in the material rather than a line drawn on it. */
    static void divider(float x, float y, float x2) {
        fill(x, y, x2, y + 0.5F, 0.0F, 0x40000000);
        fill(x, y + 0.5F, x2, y + 1.0F, 0.0F, 0x12FFFFFF);
    }

    static int alpha(int colour, float factor) {
        int a = (int) (((colour >>> 24) & 0xFF) * factor);
        return (Math.max(0, Math.min(255, a)) << 24) | (colour & 0x00FFFFFF);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int ab = a & 0xFF;
        int bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static void colour(int c) {
        GL11.glColor4f(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F,
                (c & 0xFF) / 255.0F, ((c >>> 24) & 0xFF) / 255.0F);
    }

    /**
     * Polygon smoothing is deliberately not enabled: with ordinary alpha
     * blending it seams every triangle in the fan, which on a large panel is a
     * visible star of lighter lines from the centre.
     */
    private static void begin() {
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        /* Face culling is still on from world rendering when a screen draws,
           and this fan sweeps the opposite way round to the client's other
           drawing helpers -- so every triangle in it faced away and was
           discarded. The panel was not mis-shaped or mis-coloured; it was
           simply never rasterised, which is why only the text appeared.
           Rather than depend on which winding happens to be front-facing,
           two-sided drawing is asked for explicitly. */
        GlStateManager.disableCull();
    }

    private static void end() {
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
