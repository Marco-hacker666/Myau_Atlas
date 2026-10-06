package myau.ui.impl.clickgui.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Random;

/**
 * The space behind the Atlas window, in the style of the Myau Atlas banner
 * (2026-10-06): drifting, twinkling stars, comets, and a ringed planet in the
 * lower right.
 *
 * Second version, after "it looks fake and nothing moves": the planet is no
 * longer built from rounded rectangles. Its disc is rendered per pixel every
 * ~40 ms into a small texture -- a sphere lit from the upper left, with a soft
 * terminator, atmosphere scattering on the rim, a highlight, and banded clouds
 * that rotate (faster at the equator than at the poles). The rings are smooth
 * triangle strips with a density profile (gaps included), and dust in them
 * orbits at Keplerian speed. Comet tails and the glint are tapered strips, so
 * there are no overlapping round caps to show up as beads.
 *
 * Drawn in screen space, after the world dim and before the window.
 */
final class Cosmos {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long EPOCH = System.nanoTime();
    private static final Random RANDOM = new Random();

    // ---- stars ---------------------------------------------------------
    private static final int MAX_STARS = 170;
    private static final float[] STAR_X = new float[MAX_STARS];
    private static final float[] STAR_Y = new float[MAX_STARS];
    private static final float[] STAR_R = new float[MAX_STARS];
    private static final float[] STAR_PHASE = new float[MAX_STARS];
    private static final float[] STAR_SPEED = new float[MAX_STARS];
    private static final boolean[] STAR_TINTED = new boolean[MAX_STARS];

    // ---- comets --------------------------------------------------------
    private static final int COMETS = 3;
    private static final float[] COMET_START = new float[COMETS];
    private static final float[] COMET_NEXT = new float[COMETS];
    private static final float[] COMET_X = new float[COMETS];
    private static final float[] COMET_Y = new float[COMETS];
    private static final float[] COMET_ANGLE = new float[COMETS];
    private static final float[] COMET_SPEED = new float[COMETS];
    private static final float COMET_LIFE = 1.9F;

    // ---- ring dust -----------------------------------------------------
    private static final int DUST = 170;
    private static final float[] DUST_T = new float[DUST];
    private static final float[] DUST_A = new float[DUST];
    private static final float[] DUST_SIZE = new float[DUST];
    private static final float[] DUST_PHASE = new float[DUST];

    // ---- planet --------------------------------------------------------
    /** The cloud map: longitude across, latitude down, values 0..1. */
    private static final int MAP_W = 512;
    private static final int MAP_H = 256;
    private static float[] map;
    /** The rendered disc. */
    private static final int SIZE = 160;
    private static DynamicTexture disc;
    private static ResourceLocation discLocation;
    private static float discDrawnAt = -1.0F;
    private static int paletteFor = -1;
    private static final float[] PAL_R = new float[256];
    private static final float[] PAL_G = new float[256];
    private static final float[] PAL_B = new float[256];

    private static final float TILT = (float) Math.toRadians(-14.0F);
    private static final float TILT_COS = (float) Math.cos(TILT);
    private static final float TILT_SIN = (float) Math.sin(TILT);

    static {
        /* A fixed seed, so the sky is the same every time the menu opens. */
        Random fixed = new Random(0x41544C4153L);
        for (int i = 0; i < MAX_STARS; i++) {
            STAR_X[i] = fixed.nextFloat();
            STAR_Y[i] = fixed.nextFloat();
            float size = fixed.nextFloat();
            STAR_R[i] = 0.35F + size * size * 1.05F;
            STAR_PHASE[i] = fixed.nextFloat() * 6.2832F;
            STAR_SPEED[i] = 0.6F + fixed.nextFloat() * 1.8F;
            STAR_TINTED[i] = fixed.nextInt(6) == 0;
        }
        for (int i = 0; i < COMETS; i++) {
            COMET_START[i] = -1.0F;
            COMET_NEXT[i] = 0.4F + i * 1.1F;
        }
        for (int i = 0; i < DUST; i++) {
            /* More dust where the ring is dense. */
            float t;
            do {
                t = fixed.nextFloat();
            } while (fixed.nextFloat() > ringDensity(t));
            DUST_T[i] = t;
            DUST_A[i] = fixed.nextFloat() * 6.2832F;
            DUST_SIZE[i] = 0.45F + fixed.nextFloat() * 0.75F;
            DUST_PHASE[i] = fixed.nextFloat() * 6.2832F;
        }
    }

    private Cosmos() {
    }

    /**
     * @param accent  the colour of the planet's bands, the rings and tinted stars
     * @param density 0..1, how many of the stars are shown
     * @param alpha   the menu's fade
     */
    static void draw(float width, float height, int accent, float density, boolean comets, boolean planet,
                     float alpha) {
        float time = (System.nanoTime() - EPOCH) / 1.0E9F;
        int rgb = accent & 0x00FFFFFF;

        /* A faint wash of the accent from below, where the planet is. */
        Liquid.rect(0, height * 0.45F, width, height, 0.0F, 0x00000000, colour(rgb, 0.08F * alpha));

        drawStars(time, width, height, rgb, density, alpha);
        if (comets) {
            drawComets(time, width, height, rgb, alpha);
        }
        if (planet) {
            drawPlanet(time, width, height, rgb, alpha);
        }
    }

    // ---- stars -----------------------------------------------------------

    private static void drawStars(float time, float width, float height, int rgb, float density, float alpha) {
        int count = Math.round(MAX_STARS * Math.max(0.0F, Math.min(1.0F, density)));
        for (int i = 0; i < count; i++) {
            float wave = 0.5F + 0.5F * (float) Math.sin(time * STAR_SPEED[i] + STAR_PHASE[i]);
            float twinkle = 0.35F + 0.65F * wave * wave;
            int base = STAR_TINTED[i] ? rgb : 0xFFFFFF;
            /* A slow drift to the left; nearer (bigger) stars drift faster. */
            float x = STAR_X[i] - time * 0.0025F * STAR_R[i];
            x = (x - (float) Math.floor(x)) * width;
            float y = STAR_Y[i] * height;
            if (STAR_R[i] > 1.05F) {
                Liquid.shadow(x - 1.0F, y - 1.0F, x + 1.0F, y + 1.0F, 1.0F, 4.0F,
                        colour(base, 0.35F * twinkle * alpha), 0.0F);
            }
            Liquid.dot(x, y, STAR_R[i], colour(base, 0.9F * twinkle * alpha));
        }
    }

    // ---- comets ----------------------------------------------------------

    private static void drawComets(float time, float width, float height, int rgb, float alpha) {
        for (int i = 0; i < COMETS; i++) {
            if (COMET_START[i] < 0.0F) {
                if (time < COMET_NEXT[i]) {
                    continue;
                }
                /* From somewhere along the top or the right edge, heading down and to the left. */
                COMET_START[i] = time;
                if (RANDOM.nextBoolean()) {
                    COMET_X[i] = 0.35F + RANDOM.nextFloat() * 0.7F;
                    COMET_Y[i] = -0.05F;
                } else {
                    COMET_X[i] = 1.05F;
                    COMET_Y[i] = RANDOM.nextFloat() * 0.45F;
                }
                COMET_ANGLE[i] = (float) Math.toRadians(145.0F + RANDOM.nextFloat() * 25.0F);
                COMET_SPEED[i] = 0.5F + RANDOM.nextFloat() * 0.35F;
            }
            float age = time - COMET_START[i];
            if (age > COMET_LIFE) {
                COMET_START[i] = -1.0F;
                COMET_NEXT[i] = time + 1.0F + RANDOM.nextFloat() * 3.0F;
                continue;
            }
            float fade = Math.min(1.0F, Math.min(age / 0.3F, (COMET_LIFE - age) / 0.5F)) * alpha;
            float scale = Math.max(width, height);
            float dx = (float) Math.cos(COMET_ANGLE[i]);
            float dy = (float) Math.sin(COMET_ANGLE[i]);
            float travelled = age * COMET_SPEED[i] * scale;
            float hx = COMET_X[i] * width + dx * travelled;
            float hy = COMET_Y[i] * height + dy * travelled;
            float tail = 0.16F * scale;

            /* The tail: one strip that narrows and fades, white at the head into the accent. */
            begin();
            WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
            buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            int points = 20;
            for (int k = 0; k <= points; k++) {
                float t = (float) k / points;
                float left = 1.0F - t;
                float w = 1.9F * (float) Math.pow(left, 1.2) + 0.05F;
                float px = hx - dx * tail * t;
                float py = hy - dy * tail * t;
                int c = mix(0xFFFFFF, rgb, Math.min(1.0F, t * 2.5F));
                float a = fade * left * left * 0.9F;
                vertex(buffer, px - dy * w, py + dx * w, c, a);
                vertex(buffer, px + dy * w, py - dx * w, c, a);
            }
            Tessellator.getInstance().draw();
            end();
            Liquid.shadow(hx - 1.5F, hy - 1.5F, hx + 1.5F, hy + 1.5F, 1.5F, 7.0F, colour(rgb, 0.45F * fade), 0.0F);
            Liquid.dot(hx, hy, 1.7F, colour(0xFFFFFF, fade));
        }
    }

    // ---- the planet ------------------------------------------------------

    private static void drawPlanet(float time, float width, float height, int rgb, float alpha) {
        float r = Math.min(width, height) * 0.13F;
        float cx = width - r * 2.0F;
        float cy = height - r * 1.65F;
        float breathe = 0.88F + 0.12F * (float) Math.sin(time * 0.7F);

        /* Halo: soft layers of the accent, strongest on the lit side. */
        Liquid.shadow(cx - r, cy - r, cx + r, cy + r, r, r * 1.8F, colour(rgb, 0.07F * breathe * alpha), 0.0F);
        Liquid.shadow(cx - r * 1.04F, cy - r * 1.04F, cx + r * 0.9F, cy + r * 0.9F, r, r * 0.7F,
                colour(rgb, 0.14F * breathe * alpha), 0.0F);

        drawRings(time, cx, cy, r, true, rgb, alpha);
        drawDust(time, cx, cy, r, true, rgb, alpha);

        renderDisc(time, rgb);
        if (discLocation != null) {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.color(1.0F, 1.0F, 1.0F, alpha * Liquid.alpha);
            mc.getTextureManager().bindTexture(discLocation);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(cx - r, cy + r, 0.0D).tex(0.0D, 1.0D).endVertex();
            buffer.pos(cx + r, cy + r, 0.0D).tex(1.0D, 1.0D).endVertex();
            buffer.pos(cx + r, cy - r, 0.0D).tex(1.0D, 0.0D).endVertex();
            buffer.pos(cx - r, cy - r, 0.0D).tex(0.0D, 0.0D).endVertex();
            Tessellator.getInstance().draw();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        drawRings(time, cx, cy, r, false, rgb, alpha);
        drawDust(time, cx, cy, r, false, rgb, alpha);
        drawMoon(time, cx, cy, r, rgb, alpha);

        /* A glint on the lit limb, like a lens catching it. */
        float glint = (0.7F + 0.3F * (float) Math.sin(time * 1.3F)) * alpha;
        sparkle(cx - r * 0.66F, cy - r * 0.70F, r * 0.42F, rgb, glint);

        /* A small, far planet in the upper left. */
        float fr = r * 0.18F;
        float fx = width * 0.09F;
        float fy = height * 0.16F;
        Liquid.shadow(fx - fr, fy - fr, fx + fr, fy + fr, fr, fr * 2.2F, colour(0xBFD8FF, 0.10F * alpha), 0.0F);
        Liquid.rect(fx - fr, fy - fr, fx + fr, fy + fr, fr, colour(0xA9BEDF, 0.55F * alpha), colour(0x1A2233, 0.85F * alpha));
        Liquid.rectH(fx - fr, fy - fr, fx + fr, fy + fr, fr, colour(0x000000, 0.0F), colour(0x000000, 0.6F * alpha));
    }

    /** Renders the lit, rotating sphere into the disc texture, at most 25 times a second. */
    private static void renderDisc(float time, int rgb) {
        if (disc == null) {
            try {
                buildMap();
                disc = new DynamicTexture(SIZE, SIZE);
                discLocation = mc.getTextureManager().getDynamicTextureLocation("myau_cosmos_planet", disc);
            } catch (Exception e) {
                disc = null;
                discLocation = null;
                return;
            }
        }
        if (time - discDrawnAt < 0.04F && paletteFor == rgb) {
            return;
        }
        discDrawnAt = time;
        if (paletteFor != rgb) {
            buildPalette(rgb);
        }
        float ar = ((rgb >> 16) & 0xFF) / 255.0F;
        float ag = ((rgb >> 8) & 0xFF) / 255.0F;
        float ab = (rgb & 0xFF) / 255.0F;
        /* Light from the upper left and a little in front; screen y points down. */
        float lx = -0.62F;
        float ly = -0.55F;
        float lz = 0.56F;
        float hx = -0.346F;
        float hy = -0.307F;
        float hz = 0.871F;
        int[] data = disc.getTextureData();
        float half = SIZE / 2.0F;
        for (int py = 0; py < SIZE; py++) {
            float y = (py + 0.5F - half) / half;
            for (int px = 0; px < SIZE; px++) {
                float x = (px + 0.5F - half) / half;
                float d2 = x * x + y * y;
                float edge = (1.0F - (float) Math.sqrt(d2)) * half;
                if (edge <= 0.0F) {
                    data[py * SIZE + px] = 0;
                    continue;
                }
                float coverage = Math.min(1.0F, edge);
                float z = (float) Math.sqrt(Math.max(0.0F, 1.0F - d2));

                /* Into the planet's own frame, tilted like its rings. */
                float bx = x * TILT_COS + y * TILT_SIN;
                float by = -x * TILT_SIN + y * TILT_COS;
                float lat = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, by)));
                /* Differential rotation: the equator turns faster than the poles. */
                float spin = time * (0.05F + 0.025F * (float) Math.cos(lat * 2.0F))
                        + 0.04F * (float) Math.sin(lat * 7.0F + time * 0.3F);
                float lon = (float) Math.atan2(bx, z) + spin;
                float u = lon / 6.2832F;
                u -= (float) Math.floor(u);
                float v = lat / 3.1416F + 0.5F;
                int mi = Math.min(MAP_H - 1, (int) (v * (MAP_H - 1))) * MAP_W + Math.min(MAP_W - 1, (int) (u * MAP_W));
                int band = Math.min(255, (int) (map[mi] * 255.0F));

                float diffuse = lx * x + ly * y + lz * z;
                float terminator = smooth((diffuse + 0.10F) / 0.55F);
                float shade = 0.04F + terminator * (0.40F + 0.75F * Math.max(0.0F, diffuse));
                float rim = (float) Math.pow(1.0F - z, 2.4);
                float atmosphere = rim * (0.08F + 0.92F * smooth((diffuse + 0.35F) / 0.9F)) * 1.15F;
                float spec = (float) Math.pow(Math.max(0.0F, hx * x + hy * y + hz * z), 28.0) * 0.32F * terminator;

                float red = PAL_R[band] * shade + ar * atmosphere + spec;
                float green = PAL_G[band] * shade + ag * atmosphere + spec;
                float blue = PAL_B[band] * shade + ab * atmosphere + spec;
                int a = (int) (coverage * 255.0F);
                data[py * SIZE + px] = (a << 24) | (clamp255(red) << 16) | (clamp255(green) << 8) | clamp255(blue);
            }
        }
        disc.updateDynamicTexture();
    }

    /** Deep near-black for the dark belts, the accent (a little muted) for the bright zones. */
    private static void buildPalette(int rgb) {
        paletteFor = rgb;
        float ar = ((rgb >> 16) & 0xFF) / 255.0F;
        float ag = ((rgb >> 8) & 0xFF) / 255.0F;
        float ab = (rgb & 0xFF) / 255.0F;
        for (int i = 0; i < 256; i++) {
            float t = i / 255.0F;
            float r;
            float g;
            float b;
            if (t < 0.55F) {
                float k = t / 0.55F;
                r = lerp(0.035F, ar * 0.22F, k);
                g = lerp(0.045F, ag * 0.24F, k);
                b = lerp(0.030F, ab * 0.20F, k);
            } else {
                float k = (t - 0.55F) / 0.45F;
                k = k * k;
                r = lerp(ar * 0.22F, lerp(ar, 1.0F, 0.15F) * 0.78F, k);
                g = lerp(ag * 0.24F, lerp(ag, 1.0F, 0.15F) * 0.78F, k);
                b = lerp(ab * 0.20F, lerp(ab, 1.0F, 0.15F) * 0.70F, k);
            }
            PAL_R[i] = r;
            PAL_G[i] = g;
            PAL_B[i] = b;
        }
    }

    /** Turbulent bands: latitude stripes, warped by noise that wraps around the planet. */
    private static void buildMap() {
        if (map != null) {
            return;
        }
        float[] out = new float[MAP_W * MAP_H];
        for (int y = 0; y < MAP_H; y++) {
            float v = y / (float) (MAP_H - 1);
            for (int x = 0; x < MAP_W; x++) {
                double angle = x / (double) MAP_W * Math.PI * 2.0;
                float cx = (float) Math.cos(angle) * 1.6F;
                float cz = (float) Math.sin(angle) * 1.6F;
                float warp = fbm(cx * 1.2F, v * 3.0F, cz * 1.2F);
                float bands = 0.5F + 0.5F * (float) Math.sin(v * Math.PI * 11.0 + warp * 3.4);
                float fine = fbm(cx * 3.5F + 7.0F, v * 26.0F, cz * 3.5F);
                float value = 0.66F * bands * bands + 0.42F * fine - 0.08F;
                out[y * MAP_W + x] = Math.max(0.0F, Math.min(1.0F, value));
            }
        }
        map = out;
    }

    // ---- rings and dust --------------------------------------------------

    /** How dense the ring is across its width, t = 0 at the inner edge: bands, two gaps, soft edges. */
    private static float ringDensity(float t) {
        if (t < 0.0F || t > 1.0F) {
            return 0.0F;
        }
        float d = 0.55F + 0.20F * (float) Math.sin(t * 37.0F) + 0.12F * (float) Math.sin(t * 91.0F + 1.0F)
                + 0.10F * (float) Math.sin(t * 13.0F);
        if (t > 0.58F && t < 0.645F) {
            d *= 0.05F;
        }
        if (t > 0.855F && t < 0.875F) {
            d *= 0.3F;
        }
        d *= smooth(t / 0.08F) * smooth((1.0F - t) / 0.07F);
        return Math.max(0.0F, Math.min(1.0F, d));
    }

    private static float ringInner(float r) {
        return r * 1.30F;
    }

    private static float ringOuter(float r) {
        return r * 2.25F;
    }

    /** The far (upper) or near half of the rings as smooth strips, brighter on the lit left. */
    private static void drawRings(float time, float cx, float cy, float r, boolean far, int rgb, float alpha) {
        float inner = ringInner(r);
        float outer = ringOuter(r);
        float flat = 0.21F;
        int slices = 34;
        int steps = 90;
        float from = far ? (float) Math.PI : 0.0F;
        /* A slow shimmer travelling round the ring. */
        float shimmerAt = time * 0.25F;
        begin();
        WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
        for (int s = 0; s < slices; s++) {
            float t0 = (float) s / slices;
            float t1 = (float) (s + 1) / slices;
            float density = ringDensity((t0 + t1) / 2.0F);
            if (density < 0.02F) {
                continue;
            }
            int c = mix(rgb, 0xFFFFFF, 0.25F * (1.0F - t0));
            buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= steps; i++) {
                double a = from + Math.PI * i / steps;
                float light = 0.25F + 0.75F * (float) (0.5 - 0.5 * Math.cos(a));
                float shimmer = 0.85F + 0.15F * (float) Math.cos(a * 3.0 - shimmerAt * 6.0 + t0 * 4.0);
                /* Behind the planet's night side the rings lie in its shadow. */
                float shadow = 1.0F;
                if (!far) {
                    float sx = (float) Math.cos(a);
                    shadow = 1.0F - 0.40F * smooth((sx - 0.15F) / 0.5F) * (1.0F - t0 * 0.6F);
                }
                float a1 = alpha * density * 0.72F * light * shimmer * shadow * (far ? 0.7F : 1.0F);
                ringVertex(buffer, cx, cy, (float) a, inner + (outer - inner) * t1, flat, c, a1);
                ringVertex(buffer, cx, cy, (float) a, inner + (outer - inner) * t0, flat, c, a1);
            }
            Tessellator.getInstance().draw();
        }
        end();
    }

    private static void ringVertex(WorldRenderer buffer, float cx, float cy, float angle, float radius, float flat,
                                   int c, float a) {
        float ex = (float) Math.cos(angle) * radius;
        float ey = (float) Math.sin(angle) * radius * flat;
        vertex(buffer, cx + ex * TILT_COS - ey * TILT_SIN, cy + ex * TILT_SIN + ey * TILT_COS, c, a);
    }

    /** Specks in the rings, orbiting faster near the planet (Kepler), twinkling as they turn. */
    private static void drawDust(float time, float cx, float cy, float r, boolean far, int rgb, float alpha) {
        float inner = ringInner(r);
        float outer = ringOuter(r);
        begin();
        WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < DUST; i++) {
            float radius = inner + (outer - inner) * DUST_T[i];
            float omega = 0.22F / (float) Math.pow(radius / inner, 1.5);
            float angle = DUST_A[i] + time * omega;
            boolean isFar = Math.sin(angle) < 0.0;
            if (isFar != far) {
                continue;
            }
            float ex = (float) Math.cos(angle) * radius;
            float ey = (float) Math.sin(angle) * radius * 0.21F;
            float x = cx + ex * TILT_COS - ey * TILT_SIN;
            float y = cy + ex * TILT_SIN + ey * TILT_COS;
            float light = 0.3F + 0.7F * (float) (0.5 - 0.5 * Math.cos(angle));
            float twinkle = 0.5F + 0.5F * (float) Math.sin(time * 2.3F + DUST_PHASE[i]);
            float a = alpha * light * (0.35F + 0.65F * twinkle) * (far ? 0.6F : 1.0F);
            float s = DUST_SIZE[i] * 0.5F;
            int c = twinkle > 0.85F ? 0xFFFFFF : mix(rgb, 0xFFFFFF, 0.4F);
            vertex(buffer, x - s, y - s, c, a);
            vertex(buffer, x - s, y + s, c, a);
            vertex(buffer, x + s, y + s, c, a);
            vertex(buffer, x + s, y - s, c, a);
        }
        Tessellator.getInstance().draw();
        end();
    }

    /** A small grey moon outside the rings, lit like the planet; hidden while it is behind it. */
    private static void drawMoon(float time, float cx, float cy, float r, int rgb, float alpha) {
        float angle = time * 0.16F + 2.2F;
        float radius = ringOuter(r) * 1.12F;
        float ex = (float) Math.cos(angle) * radius;
        float ey = (float) Math.sin(angle) * radius * 0.21F;
        float x = cx + ex * TILT_COS - ey * TILT_SIN;
        float y = cy + ex * TILT_SIN + ey * TILT_COS;
        if (Math.sin(angle) < 0.0 && Math.abs(x - cx) < r) {
            return;
        }
        float mr = Math.max(2.2F, r * 0.075F);
        Liquid.shadow(x - mr, y - mr, x + mr, y + mr, mr, mr * 2.0F, colour(rgb, 0.18F * alpha), 0.0F);
        Liquid.rect(x - mr, y - mr, x + mr, y + mr, mr, colour(0xE8EDE0, alpha), colour(0x5A6050, alpha));
        Liquid.rectH(x - mr, y - mr, x + mr, y + mr, mr, colour(0x000000, 0.0F), colour(0x000000, 0.72F * alpha));
    }

    /** A four-pointed glint: tapered rays that fade from a bright core, and a soft glow. */
    private static void sparkle(float x, float y, float size, int rgb, float alpha) {
        Liquid.shadow(x - 1.5F, y - 1.5F, x + 1.5F, y + 1.5F, 1.5F, size * 0.30F, colour(rgb, 0.45F * alpha), 0.0F);
        begin();
        WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        float[][] rays = {{1.0F, 0.0F, 1.0F}, {-1.0F, 0.0F, 1.0F}, {0.0F, 1.0F, 0.75F}, {0.0F, -1.0F, 0.75F}};
        for (float[] ray : rays) {
            float length = size * ray[2];
            float w = 0.9F;
            float px = -ray[1] * w;
            float py = ray[0] * w;
            vertex(buffer, x + px, y + py, 0xFFFFFF, alpha * 0.95F);
            vertex(buffer, x - px, y - py, 0xFFFFFF, alpha * 0.95F);
            vertex(buffer, x + ray[0] * length, y + ray[1] * length, rgb, 0.0F);
        }
        Tessellator.getInstance().draw();
        end();
        Liquid.dot(x, y, 1.4F, colour(0xFFFFFF, alpha));
    }

    // ---- plain GL for the strips ----------------------------------------

    private static void begin() {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
    }

    private static void end() {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static void vertex(WorldRenderer buffer, float x, float y, int rgb, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(a * Liquid.alpha * 255.0F)));
        buffer.pos(x, y, 0.0D).color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha).endVertex();
    }

    // ---- maths -----------------------------------------------------------

    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255.0F)));
    }

    private static int mix(int a, int b, float t) {
        int r = (int) lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t);
        int g = (int) lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t);
        int bl = (int) lerp(a & 0xFF, b & 0xFF, t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int colour(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static float hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / 16777215.0F;
    }

    private static float noise(float x, float y, float z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        float fx = x - x0;
        float fy = y - y0;
        float fz = z - z0;
        float sx = fx * fx * (3.0F - 2.0F * fx);
        float sy = fy * fy * (3.0F - 2.0F * fy);
        float sz = fz * fz * (3.0F - 2.0F * fz);
        float a = lerp(hash(x0, y0, z0), hash(x0 + 1, y0, z0), sx);
        float b = lerp(hash(x0, y0 + 1, z0), hash(x0 + 1, y0 + 1, z0), sx);
        float c = lerp(hash(x0, y0, z0 + 1), hash(x0 + 1, y0, z0 + 1), sx);
        float d = lerp(hash(x0, y0 + 1, z0 + 1), hash(x0 + 1, y0 + 1, z0 + 1), sx);
        return lerp(lerp(a, b, sy), lerp(c, d, sy), sz);
    }

    private static float fbm(float x, float y, float z) {
        float sum = 0.0F;
        float amplitude = 0.5F;
        for (int i = 0; i < 4; i++) {
            sum += noise(x, y, z) * amplitude;
            x *= 2.03F;
            y *= 2.03F;
            z *= 2.03F;
            amplitude *= 0.5F;
        }
        return sum / 0.9375F;
    }
}
