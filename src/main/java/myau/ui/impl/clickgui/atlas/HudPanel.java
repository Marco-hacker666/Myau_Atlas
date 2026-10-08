package myau.ui.impl.clickgui.atlas;

import myau.Myau;
import myau.module.modules.HUD;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * The HUD's default look (2026-10-08): LiquidBounce's JelloBounce theme
 * (CCBlueX/LiquidBounce-Theme-JelloBounce, colors.scss), read from its source
 * and redrawn here with the Atlas menu's distance-field shapes.
 *
 *   panels    black at 45% ($opacity: 0.45), no border, no blur;
 *   shadow    a wide soft dark halo ($primary-shadow: 0 0 50px rgba(0,0,0,.5));
 *   corners   12px on cards (TargetHUD, notifications, effects), 7px on keys,
 *             none on module-list rows -- halved here, the HUD being drawn at
 *             GUI scale where the theme's CSS pixels are at about twice that.
 *
 * On by default; HUD's "hud-theme" CLASSIC turns every HUD back to its own
 * drawing. HUDs draw inside their own translate/scale, which the shaders have
 * to know: the modelview is read once per shape, a handful of reads a frame.
 * The GL state each HUD had set up is put back exactly afterwards.
 */
public final class HudPanel {

    /** rgba(black, 0.45) */
    public static final int BACKGROUND = 0x73000000;
    /** Corners of a card, in GUI pixels (12 CSS px). */
    public static final float CARD_RADIUS = 6.0F;
    /** Corners of the small boxes. */
    public static final float SMALL_RADIUS = 3.5F;

    private static final int SHADOW = 0x40000000;
    private static final float SHADOW_SPREAD = 12.0F;
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    private HudPanel() {
    }

    /** True while the HUD's theme is JELLO (the default). */
    public static boolean active() {
        if (Myau.moduleManager == null) {
            return false;
        }
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        return hud != null && hud.hudTheme.getValue() == 0;
    }

    /** A box with the small corners, shadowed. */
    public static void panel(float x, float y, float x2, float y2) {
        rounded(x, y, x2, y2, SMALL_RADIUS, 1.0F, true);
    }

    public static void panel(float x, float y, float x2, float y2, boolean shadow) {
        rounded(x, y, x2, y2, SMALL_RADIUS, 1.0F, shadow);
    }

    /** A card (the bigger corners), faded by {@code fade} (0..1). */
    public static void panel(float x, float y, float x2, float y2, boolean shadow, float fade) {
        rounded(x, y, x2, y2, CARD_RADIUS, fade, shadow);
    }

    /**
     * One module-list row, flush with its neighbours and shadowed like the
     * theme's rows. Rounded at the small radius (2026-10-08, the owner's
     * wish; the theme itself draws them square).
     */
    public static void row(float x, float y, float x2, float y2) {
        rounded(x, y, x2, y2, SMALL_RADIUS, 1.0F, true);
    }

    /** A row sliding in or out, faded by {@code fade} (0..1). */
    public static void row(float x, float y, float x2, float y2, float fade) {
        rounded(x, y, x2, y2, SMALL_RADIUS, fade, true);
    }

    /** The theme's panel at any radius, faded by {@code fade} (0..1). */
    public static void rounded(float x, float y, float x2, float y2, float radius, float fade, boolean shadow) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        int state = saveState();
        float savedAlpha = Liquid.alpha;
        try {
            Liquid.ensureCompiled();
            view();
            Liquid.alpha = Math.max(0.0F, Math.min(1.0F, fade));
            if (shadow) {
                Liquid.shadow(x, y, x2, y2, Math.max(r, 2.0F), SHADOW_SPREAD, SHADOW, 0.0F);
            }
            Liquid.rect(x, y, x2, y2, r, BACKGROUND);
        } catch (Throwable ignored) {
            /* A HUD is never worth a crash: the text still draws. */
        } finally {
            Liquid.alpha = savedAlpha;
            restoreState(state);
        }
    }

    /** A plain rounded fill (a pressed key, a slot, a health bar). */
    public static void fill(float x, float y, float x2, float y2, int argb) {
        fill(x, y, x2, y2, SMALL_RADIUS, argb);
    }

    public static void fill(float x, float y, float x2, float y2, float radius, int argb) {
        if (x2 <= x || y2 <= y || (argb >>> 24) == 0) {
            return;
        }
        int state = saveState();
        float savedAlpha = Liquid.alpha;
        try {
            Liquid.ensureCompiled();
            view();
            Liquid.alpha = 1.0F;
            Liquid.rect(x, y, x2, y2, Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F)), argb);
        } catch (Throwable ignored) {
            // Nothing to draw then.
        } finally {
            Liquid.alpha = savedAlpha;
            restoreState(state);
        }
    }

    /** Tells Liquid the transform the HUD is drawing under. */
    private static void view() {
        Liquid.refreshScale();
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        Liquid.setView(MATRIX.get(0), MATRIX.get(12), MATRIX.get(13));
    }

    /*
     * The GL state the HUD had, put back exactly: HUDs draw their own quads
     * with RenderUtil after a background and rely on whatever they set up
     * before it (texture off for plain colour quads, or on for text).
     */
    private static int saveState() {
        int bits = 0;
        bits |= GL11.glIsEnabled(GL11.GL_TEXTURE_2D) ? 1 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_BLEND) ? 2 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 4 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 8 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 16 : 0;
        return bits;
    }

    private static void restoreState(int bits) {
        if ((bits & 1) != 0) {
            GlStateManager.enableTexture2D();
        } else {
            GlStateManager.disableTexture2D();
        }
        if ((bits & 2) != 0) {
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        if ((bits & 4) != 0) {
            GlStateManager.enableAlpha();
        } else {
            GlStateManager.disableAlpha();
        }
        if ((bits & 8) != 0) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if ((bits & 16) != 0) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
