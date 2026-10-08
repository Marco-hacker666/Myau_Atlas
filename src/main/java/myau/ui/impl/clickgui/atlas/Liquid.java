package myau.ui.impl.clickgui.atlas;

import myau.util.shader.BlurUtils;
import myau.util.shader.ShaderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Liquid glass, drawn by the graphics card rather than approximated with
 * triangle fans.
 *
 * Everything the menu shows is one of four things, each a small fragment
 * shader working from the signed distance to a rounded rectangle:
 *
 *   SHAPES. Fills, rims and soft shadows. A distance field gives every corner
 *   a one-pixel antialiased edge at any radius, which the fan geometry in
 *   Glass never could -- it drew corners out of eight straight segments and
 *   relied on line smoothing for the rim.
 *
 *   THE PANE. The world behind the menu is copied, blurred by a dual-filter
 *   Kawase chain, and drawn back through the window's shape: saturated and
 *   darkened in the middle, and near the edge bent through a circular bevel
 *   with the three colour channels bent by slightly different amounts. The
 *   rim samples the unblurred copy, so the edge of the window is clear glass
 *   that visibly bends whatever is behind it while the middle is frosted.
 *   That combination -- frosted body, clear refracting rim, light caught on
 *   the edge facing the pointer -- is what reads as Liquid Glass.
 *
 *   LENSES. The same material over the menu's own content instead of the
 *   world: the selected category and module sit under a droplet of clear
 *   glass that refracts the text under its edge as it slides.
 *
 *   LINES. Round-capped segments, for the icons.
 *
 * All of it falls back to the older Glass geometry when shaders or
 * framebuffers are not available, so the menu is never missing -- only
 * plainer.
 *
 * Coordinates are taken in GUI pixels and converted through the current
 * modelview matrix, so the whole window can be scaled for its opening
 * animation and every shader still knows where it is drawing.
 */
final class Liquid {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Settings for one piece of glass. */
    static final class Style {
        float bezel;
        float refract;
        float dispersion;
        float magnify = 1.0F;
        float frost;
        float saturation = 1.0F;
        float brightness = 1.0F;
        int tint;
        float specular = 0.5F;
        float shade;
        float clear;
        /** Marks the selection droplets, which the theme can draw solid or as an outline instead. */
        boolean selection;

        Style(float bezel, float refract, float dispersion, float saturation, float brightness,
              int tint, float specular, float shade, float clear) {
            this.bezel = bezel;
            this.refract = refract;
            this.dispersion = dispersion;
            this.saturation = saturation;
            this.brightness = brightness;
            this.tint = tint;
            this.specular = specular;
            this.shade = shade;
            this.clear = clear;
        }
    }

    /** Multiplies every alpha drawn, for the menu fading in. */
    static float alpha = 1.0F;
    /** Where the light is, in GUI pixels. The pointer, eased. */
    static float lightX;
    static float lightY;

    /** Blur strength: how many halvings (0 = none) and how far each samples. Set by the theme. */
    static int blurLevels = 4;
    static float blurOffset = 3.2F;
    /** The texture the pane samples this frame: the blurred copy, or the plain copy at no blur. */
    private static int blurred;

    private static boolean tried;
    private static boolean compiled;
    private static boolean frameReady;
    private static ShaderUtil shapeShader;
    private static ShaderUtil glassShader;
    private static ShaderUtil downShader;
    private static ShaderUtil upShader;

    private static Framebuffer capture;
    private static Framebuffer content;
    private static final Framebuffer[] CHAIN = new Framebuffer[4];
    private static int fbWidth;
    private static int fbHeight;
    private static int scale = 2;

    /** Draw calls since the last frame began, for the Ctrl+P readout. */
    static int draws;

    private static float viewScaleX = 1.0F;
    private static float viewScaleY = 1.0F;
    private static float viewX;
    private static float viewY;

    private Liquid() {
    }

    // ---- shaders ------------------------------------------------------

    private static final String COMMON = "#version 120\n"
            + "float sdRB(vec2 p, vec2 b, float r) {\n"
            + "    vec2 q = abs(p) - b + vec2(r);\n"
            + "    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "vec2 gradRB(vec2 p, vec2 b, float r) {\n"
            + "    vec2 q = abs(p) - b + vec2(r);\n"
            + "    vec2 s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "    if (max(q.x, q.y) > 0.0) {\n"
            + "        return s * normalize(max(q, vec2(0.0001)));\n"
            + "    }\n"
            + "    return q.x > q.y ? vec2(s.x, 0.0) : vec2(0.0, s.y);\n"
            + "}\n"
            + "float hash12(vec2 p) {\n"
            + "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"
            + "    p3 += dot(p3, p3.yzx + 33.33);\n"
            + "    return fract((p3.x + p3.y) * p3.z);\n"
            + "}\n";

    /** Quads in GUI space, through the game's own matrices. */
    private static final String QUAD_VERT = "#version 120\n"
            + "void main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }\n";

    /** Full-target passes: vertices already in clip space. */
    private static final String PASS_VERT = "#version 120\n"
            + "varying vec2 vUv;\n"
            + "void main() { vUv = gl_MultiTexCoord0.xy; gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";

    private static final String DOWN_FRAG = "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uHalf;\n"
            + "uniform float uOffset;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "    vec2 o = uHalf * uOffset;\n"
            + "    vec4 sum = texture2D(uTex, vUv) * 4.0;\n"
            + "    sum += texture2D(uTex, vUv - o);\n"
            + "    sum += texture2D(uTex, vUv + o);\n"
            + "    sum += texture2D(uTex, vUv + vec2(o.x, -o.y));\n"
            + "    sum += texture2D(uTex, vUv - vec2(o.x, -o.y));\n"
            + "    gl_FragColor = vec4((sum / 8.0).rgb, 1.0);\n"
            + "}\n";

    private static final String UP_FRAG = "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uHalf;\n"
            + "uniform float uOffset;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "    vec2 o = uHalf * uOffset;\n"
            + "    vec4 sum = texture2D(uTex, vUv + vec2(-o.x * 2.0, 0.0));\n"
            + "    sum += texture2D(uTex, vUv + vec2(-o.x, o.y)) * 2.0;\n"
            + "    sum += texture2D(uTex, vUv + vec2(0.0, o.y * 2.0));\n"
            + "    sum += texture2D(uTex, vUv + vec2(o.x, o.y)) * 2.0;\n"
            + "    sum += texture2D(uTex, vUv + vec2(o.x * 2.0, 0.0));\n"
            + "    sum += texture2D(uTex, vUv + vec2(o.x, -o.y)) * 2.0;\n"
            + "    sum += texture2D(uTex, vUv + vec2(0.0, -o.y * 2.0));\n"
            + "    sum += texture2D(uTex, vUv + vec2(-o.x, -o.y)) * 2.0;\n"
            + "    gl_FragColor = vec4((sum / 12.0).rgb, 1.0);\n"
            + "}\n";

    /* Output is premultiplied; drawn with ONE, ONE_MINUS_SRC_ALPHA. */
    private static final String SHAPE_FRAG = COMMON
            + "uniform vec4 uRect;\n"
            + "uniform float uRadius;\n"
            + "uniform vec4 uTop;\n"
            + "uniform vec4 uBottom;\n"
            + "uniform float uHoriz;\n"
            + "uniform float uBorder;\n"
            + "uniform vec4 uBTop;\n"
            + "uniform vec4 uBBottom;\n"
            + "uniform float uSoft;\n"
            + "uniform float uLine;\n"
            + "uniform vec4 uSeg;\n"
            + "uniform float uAlpha;\n"
            + "void main() {\n"
            + "    if (uLine > 0.0) {\n"
            + "        vec2 pa = gl_FragCoord.xy - uSeg.xy;\n"
            + "        vec2 ba = uSeg.zw - uSeg.xy;\n"
            + "        float hh = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);\n"
            + "        float ds = length(pa - ba * hh) - uLine;\n"
            + "        float cs = clamp(0.5 - ds, 0.0, 1.0) * uTop.a * uAlpha;\n"
            + "        gl_FragColor = vec4(uTop.rgb * cs, cs);\n"
            + "        return;\n"
            + "    }\n"
            + "    vec2 h = uRect.zw * 0.5;\n"
            + "    vec2 p = gl_FragCoord.xy - (uRect.xy + h);\n"
            + "    float r = min(uRadius, min(h.x, h.y));\n"
            + "    float d = sdRB(p, h, r);\n"
            + "    if (uSoft > 0.0) {\n"
            + "        float a = 1.0 - smoothstep(-uSoft * 0.75, uSoft, d);\n"
            + "        a = a * a;\n"
            + "        float A = uTop.a * a * uAlpha;\n"
            + "        gl_FragColor = vec4(uTop.rgb * A, A);\n"
            + "        return;\n"
            + "    }\n"
            + "    float ty = clamp((p.y + h.y) / uRect.w, 0.0, 1.0);\n"
            + "    float tx = clamp((p.x + h.x) / uRect.z, 0.0, 1.0);\n"
            + "    vec4 f = uHoriz > 0.5 ? mix(uTop, uBottom, tx) : mix(uBottom, uTop, ty);\n"
            + "    float cover = clamp(0.5 - d, 0.0, 1.0);\n"
            + "    float A = f.a * cover;\n"
            + "    vec3 C = f.rgb * A;\n"
            + "    if (uBorder > 0.0) {\n"
            + "        vec4 b = mix(uBBottom, uBTop, ty);\n"
            + "        float inner = clamp(0.5 - (d + uBorder), 0.0, 1.0);\n"
            + "        float ring = max(cover - inner, 0.0);\n"
            + "        float Ab = b.a * ring;\n"
            + "        C = b.rgb * Ab + C * (1.0 - Ab);\n"
            + "        A = Ab + A * (1.0 - Ab);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(C, A) * uAlpha;\n"
            + "}\n";

    private static final String GLASS_FRAG = COMMON
            + "uniform sampler2D uTex;\n"
            + "uniform sampler2D uSharp;\n"
            + "uniform float uClear;\n"
            + "uniform vec2 uRes;\n"
            + "uniform vec4 uRect;\n"
            + "uniform float uRadius;\n"
            + "uniform float uBezel;\n"
            + "uniform float uRefract;\n"
            + "uniform float uDisp;\n"
            + "uniform float uMag;\n"
            + "uniform float uFrost;\n"
            + "uniform float uSat;\n"
            + "uniform float uBright;\n"
            + "uniform vec4 uTint;\n"
            + "uniform vec2 uLight;\n"
            + "uniform float uSpec;\n"
            + "uniform float uShade;\n"
            + "uniform float uAlpha;\n"
            + "vec3 tap(vec2 fc) {\n"
            + "    return texture2D(uTex, clamp(fc / uRes, vec2(0.0005), vec2(0.9995))).rgb;\n"
            + "}\n"
            + "vec3 look(vec2 fc) {\n"
            + "    if (uFrost <= 0.0) {\n"
            + "        return tap(fc);\n"
            + "    }\n"
            + "    vec3 acc = tap(fc) * 0.2;\n"
            + "    float s = uFrost;\n"
            + "    acc += tap(fc + vec2( s, 0.0)) * 0.1;\n"
            + "    acc += tap(fc + vec2(-s, 0.0)) * 0.1;\n"
            + "    acc += tap(fc + vec2(0.0,  s)) * 0.1;\n"
            + "    acc += tap(fc + vec2(0.0, -s)) * 0.1;\n"
            + "    acc += tap(fc + vec2( s,  s) * 0.7071) * 0.1;\n"
            + "    acc += tap(fc + vec2(-s,  s) * 0.7071) * 0.1;\n"
            + "    acc += tap(fc + vec2( s, -s) * 0.7071) * 0.1;\n"
            + "    acc += tap(fc + vec2(-s, -s) * 0.7071) * 0.1;\n"
            + "    return acc;\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 h = uRect.zw * 0.5;\n"
            + "    vec2 c = uRect.xy + h;\n"
            + "    vec2 p = gl_FragCoord.xy - c;\n"
            + "    float r = min(uRadius, min(h.x, h.y));\n"
            + "    float d = sdRB(p, h, r);\n"
            + "    float cover = clamp(0.5 - d, 0.0, 1.0);\n"
            + "    if (cover <= 0.0) {\n"
            + "        discard;\n"
            + "    }\n"
            + "    vec2 n = gradRB(p, h, r);\n"
            + "    float e = max(-d, 0.0);\n"
            + "    float bez = max(uBezel, 0.001);\n"
            + "    float k = clamp(1.0 - e / bez, 0.0, 1.0);\n"
            + "    float bend = 1.0 - sqrt(max(1.0 - k * k, 0.0));\n"
            + "    vec2 off = -n * bend * uRefract;\n"
            + "    vec2 base = c + p / max(uMag, 0.01);\n"
            + "    vec3 col;\n"
            + "    col.r = look(base + off * (1.0 + uDisp)).r;\n"
            + "    col.g = look(base + off).g;\n"
            + "    col.b = look(base + off * (1.0 - uDisp)).b;\n"
            + "    if (uClear > 0.0) {\n"
            + "        vec3 sharp;\n"
            + "        sharp.r = texture2D(uSharp, clamp((base + off * (1.0 + uDisp)) / uRes, 0.0005, 0.9995)).r;\n"
            + "        sharp.g = texture2D(uSharp, clamp((base + off) / uRes, 0.0005, 0.9995)).g;\n"
            + "        sharp.b = texture2D(uSharp, clamp((base + off * (1.0 - uDisp)) / uRes, 0.0005, 0.9995)).b;\n"
            + "        col = mix(col, sharp, uClear * k * k);\n"
            + "    }\n"
            + "    float l = dot(col, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    col = mix(vec3(l), col, uSat) * uBright;\n"
            + "    col = mix(col, uTint.rgb, uTint.a);\n"
            + "    col *= 1.0 - uShade * bend;\n"
            + "    vec2 L = normalize(uLight - c + vec2(0.0001));\n"
            + "    float facing = dot(n, L) * 0.5 + 0.5;\n"
            + "    float rimLine = 1.0 - smoothstep(0.0, 1.8, e);\n"
            + "    float bevel = 1.0 - smoothstep(0.0, bez, e);\n"
            + "    float spec = rimLine * (0.16 + 0.84 * pow(facing, 3.0))\n"
            + "            + bevel * 0.22 * pow(facing, 5.0)\n"
            + "            + rimLine * 0.30 * pow(1.0 - facing, 5.0);\n"
            + "    col += vec3(uSpec * spec);\n"
            + "    col += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;\n"
            + "    float A = cover * uAlpha;\n"
            + "    gl_FragColor = vec4(clamp(col, 0.0, 1.0) * A, A);\n"
            + "}\n";

    /**
     * Compiles once. A driver that refuses any of the four leaves the menu on
     * the old geometry for the rest of the session rather than retrying a
     * compile every frame.
     */
    private static boolean compile() {
        if (tried) {
            return compiled;
        }
        tried = true;
        try {
            if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()) {
                return false;
            }
            shapeShader = new ShaderUtil(QUAD_VERT, SHAPE_FRAG);
            glassShader = new ShaderUtil(QUAD_VERT, GLASS_FRAG);
            downShader = new ShaderUtil(PASS_VERT, DOWN_FRAG);
            upShader = new ShaderUtil(PASS_VERT, UP_FRAG);
            compiled = shapeShader.getProgramID() != 0 && glassShader.getProgramID() != 0
                    && downShader.getProgramID() != 0 && upShader.getProgramID() != 0;
        } catch (Throwable ignored) {
            compiled = false;
        }
        return compiled;
    }

    /**
     * HudPanel (2026-10-08): the shaders and the framebuffer size the shape
     * shader converts with, without copying the screen -- for HUD panes drawn
     * with blur off, when no frame may have begun yet.
     */
    static void ensureCompiled() {
        compile();
        if (fbWidth != mc.displayWidth || fbHeight != mc.displayHeight) {
            if (capture == null) {
                fbWidth = mc.displayWidth;
                fbHeight = mc.displayHeight;
            } else {
                ensureTargets(mc.displayWidth, mc.displayHeight);
            }
        }
    }

    /** Shapes need only the shaders; the pane and lenses also need this frame's copies. */
    static boolean shapes() {
        return compiled;
    }

    static boolean glass() {
        return compiled && frameReady;
    }

    // ---- per frame ----------------------------------------------------

    /**
     * Copies and blurs whatever is on screen, before the menu draws anything.
     *
     * Every framebuffer bind here is undone in the finally: a pass that binds
     * an offscreen target and then throws would leave the game rendering into
     * a texture nobody displays -- the black screen NOTES §3 warns about.
     */
    static void beginFrame() {
        frameReady = false;
        draws = 0;
        setView(1.0F, 0.0F, 0.0F);
        scale = new ScaledResolution(mc).getScaleFactor();
        if (!compile()) {
            return;
        }
        if (!myau.util.render.FramebufferCompat.available()) {
            /* Framebuffers off this frame (OptiFine Fast Render, or FBOs
               disabled): a bind does nothing, so the blur passes below would
               draw their full-screen quads onto the screen -- the white
               screen. The pane uses the plain glass instead (2026-10-05). */
            return;
        }
        Framebuffer main = mc.getFramebuffer();
        try {
            ensureTargets(mc.displayWidth, mc.displayHeight);
            main.bindFramebuffer(false);
            copy(capture, 0, 0, fbWidth, fbHeight);
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            int levels = Math.max(0, Math.min(CHAIN.length, blurLevels));
            if (levels == 0) {
                blurred = capture.framebufferTexture;
            } else {
                int source = capture.framebufferTexture;
                for (int i = 0; i < levels; i++) {
                    pass(downShader, source, CHAIN[i]);
                    source = CHAIN[i].framebufferTexture;
                }
                for (int i = levels - 1; i > 0; i--) {
                    pass(upShader, CHAIN[i].framebufferTexture, CHAIN[i - 1]);
                }
                blurred = CHAIN[0].framebufferTexture;
            }
            frameReady = true;
        } catch (Throwable ignored) {
            frameReady = false;
        } finally {
            GL20.glUseProgram(0);
            main.bindFramebuffer(true);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.bindTexture(0);
        }
    }

    private static void ensureTargets(int width, int height) {
        if (capture != null && fbWidth == width && fbHeight == height) {
            return;
        }
        fbWidth = width;
        fbHeight = height;
        capture = target(capture, width, height);
        content = target(content, width, height);
        for (int i = 0; i < CHAIN.length; i++) {
            int divisor = 2 << i;
            CHAIN[i] = target(CHAIN[i], Math.max(1, width / divisor), Math.max(1, height / divisor));
        }
    }

    private static Framebuffer target(Framebuffer existing, int width, int height) {
        Framebuffer out = existing;
        if (out == null) {
            out = new Framebuffer(width, height, false);
        } else {
            out.createBindFramebuffer(width, height);
        }
        out.setFramebufferFilter(GL11.GL_LINEAR);
        /* The game's framebuffers clamp with GL_CLAMP, which blends in the
           border colour at the very edge; the blur would darken the screen's
           rim with it. */
        GlStateManager.bindTexture(out.framebufferTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.bindTexture(0);
        return out;
    }

    /** Copies a region of the bound framebuffer into the same place in a texture. */
    private static void copy(Framebuffer into, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        GlStateManager.bindTexture(into.framebufferTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, x, y, width, height);
        GlStateManager.bindTexture(0);
    }

    private static void pass(ShaderUtil shader, int source, Framebuffer into) {
        into.bindFramebuffer(true);
        shader.init();
        shader.setUniformi("uTex", 0);
        shader.setUniformf("uHalf", 0.5F / into.framebufferWidth, 0.5F / into.framebufferHeight);
        shader.setUniformf("uOffset", blurOffset);
        GlStateManager.bindTexture(source);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(-1.0F, -1.0F);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f(1.0F, -1.0F);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f(1.0F, 1.0F);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(-1.0F, 1.0F);
        GL11.glEnd();
        shader.unload();
    }

    /**
     * Copies what the menu has drawn so far, for the lenses to refract.
     * Only the window's own rectangle: nothing outside it is ever sampled.
     */
    static void captureContent(float x, float y, float x2, float y2) {
        if (!glass()) {
            return;
        }
        readView();
        int left = Math.max(0, (int) Math.floor(fbX(x)) - 2);
        int right = Math.min(fbWidth, (int) Math.ceil(fbX(x2)) + 2);
        int bottom = Math.max(0, (int) Math.floor(fbY(y2)) - 2);
        int top = Math.min(fbHeight, (int) Math.ceil(fbY(y)) + 2);
        try {
            mc.getFramebuffer().bindFramebuffer(false);
            copy(content, left, bottom, right - left, top - bottom);
        } catch (Throwable ignored) {
            frameReady = false;
        }
    }

    // ---- coordinates --------------------------------------------------

    /**
     * The transform the caller has put on the modelview, which every shader
     * needs to know where it is drawing: a uniform scale, then a translation.
     *
     * Told rather than read back. Reading the matrix with glGetFloat on every
     * draw -- three hundred times a frame -- makes a driver running its own
     * command thread (NVIDIA's threaded optimisation) stop and catch up each
     * time, which is a stall per draw.
     */
    static void setView(float scale, float translateX, float translateY) {
        viewScaleX = scale;
        viewScaleY = scale;
        viewX = translateX;
        viewY = translateY;
    }

    private static void readView() {
        draws++;
    }

    /** GUI x to framebuffer x. */
    private static float fbX(float x) {
        return (viewScaleX * x + viewX) * scale;
    }

    /** GUI y to framebuffer y, which counts up from the bottom. */
    private static float fbY(float y) {
        return fbHeight - (viewScaleY * y + viewY) * scale;
    }

    private static float fbLength(float length) {
        return length * scale * viewScaleX;
    }

    private static void quad(float x, float y, float x2, float y2) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y2);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x2, y);
        GL11.glEnd();
    }

    private static void colour(ShaderUtil shader, String name, int argb) {
        shader.setUniformf(name, ((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, ((argb >>> 24) & 0xFF) / 255.0F);
    }

    private static void begin() {
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        /* The screen's alpha test discards anything under 0.1, which is most
           of a soft shadow and every antialiased edge. */
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
    }

    private static void end() {
        GL20.glUseProgram(0);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
    }

    // ---- shapes -------------------------------------------------------

    static void rect(float x, float y, float x2, float y2, float radius, int colour) {
        shape(x, y, x2, y2, radius, colour, colour, false, 0.0F, 0, 0);
    }

    /** Vertical gradient, top colour first. */
    static void rect(float x, float y, float x2, float y2, float radius, int top, int bottom) {
        shape(x, y, x2, y2, radius, top, bottom, false, 0.0F, 0, 0);
    }

    /** Horizontal gradient, left colour first. */
    static void rectH(float x, float y, float x2, float y2, float radius, int left, int right) {
        shape(x, y, x2, y2, radius, left, right, true, 0.0F, 0, 0);
    }

    /** An edge only, brighter at the top than the bottom. */
    static void rim(float x, float y, float x2, float y2, float radius, float width, int top, int bottom) {
        shape(x, y, x2, y2, radius, 0, 0, false, width, top, bottom);
    }

    static void ring(float cx, float cy, float r, float width, int colour) {
        rim(cx - r, cy - r, cx + r, cy + r, r, width, colour, colour);
    }

    static void dot(float cx, float cy, float r, int colour) {
        rect(cx - r, cy - r, cx + r, cy + r, r, colour);
    }

    private static void shape(float x, float y, float x2, float y2, float radius, int top, int bottom,
                              boolean horizontal, float border, int borderTop, int borderBottom) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        if (!shapes()) {
            if (((top | bottom) >>> 24) != 0) {
                if (horizontal) {
                    Glass.fill(x, y, x2, y2, radius, Glass.alpha(top, alpha));
                } else {
                    Glass.fillVertical(x, y, x2, y2, radius, Glass.alpha(top, alpha), Glass.alpha(bottom, alpha));
                }
            }
            if (border > 0.0F) {
                Glass.rim(x, y, x2, y2, radius, Glass.alpha(borderTop, alpha), Glass.alpha(borderBottom, alpha));
            }
            return;
        }
        readView();
        begin();
        ShaderUtil s = shapeShader;
        s.init();
        s.setUniformf("uRect", fbX(x), fbY(y2), fbLength(x2 - x), fbLength(y2 - y));
        s.setUniformf("uRadius", fbLength(radius));
        colour(s, "uTop", top);
        colour(s, "uBottom", bottom);
        s.setUniformf("uHoriz", horizontal ? 1.0F : 0.0F);
        s.setUniformf("uBorder", fbLength(border));
        colour(s, "uBTop", borderTop);
        colour(s, "uBBottom", borderBottom);
        s.setUniformf("uSoft", 0.0F);
        s.setUniformf("uLine", 0.0F);
        s.setUniformf("uAlpha", alpha);
        float margin = 1.0F;
        quad(x - margin, y - margin, x2 + margin, y2 + margin);
        end();
    }

    /** A soft shadow or glow of the rounded rectangle, spreading by {@code soft}. */
    static void shadow(float x, float y, float x2, float y2, float radius, float soft, int colour, float dy) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        if (!shapes()) {
            Glass.shadow(x, y + dy, x2, y2 + dy, radius, 5, (int) (((colour >>> 24) & 0xFF) * alpha));
            return;
        }
        readView();
        begin();
        ShaderUtil s = shapeShader;
        s.init();
        s.setUniformf("uRect", fbX(x), fbY(y2 + dy), fbLength(x2 - x), fbLength(y2 - y));
        s.setUniformf("uRadius", fbLength(radius));
        colour(s, "uTop", colour);
        s.setUniformf("uSoft", fbLength(soft));
        s.setUniformf("uLine", 0.0F);
        s.setUniformf("uAlpha", alpha);
        float margin = soft * 1.2F + 1.0F;
        quad(x - margin, y + dy - margin, x2 + margin, y2 + dy + margin);
        end();
    }

    /** A round-capped line, for icons. */
    static void line(float ax, float ay, float bx, float by, float width, int colour) {
        if (!shapes()) {
            GlStateManager.enableBlend();
            GlStateManager.disableTexture2D();
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(Math.max(1.0F, width * scale));
            int c = Glass.alpha(colour, alpha);
            GL11.glColor4f(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F,
                    (c & 0xFF) / 255.0F, ((c >>> 24) & 0xFF) / 255.0F);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(ax, ay);
            GL11.glVertex2f(bx, by);
            GL11.glEnd();
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0F);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            return;
        }
        readView();
        begin();
        ShaderUtil s = shapeShader;
        s.init();
        s.setUniformf("uLine", fbLength(width) * 0.5F);
        s.setUniformf("uSeg", fbX(ax), fbY(ay), fbX(bx), fbY(by));
        colour(s, "uTop", colour);
        s.setUniformf("uAlpha", alpha);
        float margin = width + 1.0F;
        quad(Math.min(ax, bx) - margin, Math.min(ay, by) - margin,
                Math.max(ax, bx) + margin, Math.max(ay, by) + margin);
        end();
    }

    // ---- glass --------------------------------------------------------

    /** The window itself, over the blurred world. */
    static void pane(float x, float y, float x2, float y2, float radius, Style style) {
        if (!glass()) {
            paneFallback(x, y, x2, y2, radius);
            return;
        }
        glassDraw(x, y, x2, y2, radius, style, blurred, capture.framebufferTexture);
    }

    /** A droplet over the menu's own content, captured by captureContent. */
    static void lens(float x, float y, float x2, float y2, float radius, Style style) {
        if (!glass()) {
            Glass.glow(x, y, x2, y2, radius, style.tint | 0xFF000000,
                    (int) (70 * alpha));
            Glass.rim(x, y, x2, y2, radius, Glass.alpha(0x5CFFFFFF, alpha), Glass.alpha(0x10FFFFFF, alpha));
            return;
        }
        glassDraw(x, y, x2, y2, radius, style, content.framebufferTexture, content.framebufferTexture);
    }

    private static void glassDraw(float x, float y, float x2, float y2, float radius, Style style,
                                  int source, int sharp) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        readView();
        begin();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.bindTexture(sharp);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(source);
        ShaderUtil s = glassShader;
        s.init();
        s.setUniformi("uTex", 0);
        s.setUniformi("uSharp", 1);
        s.setUniformf("uClear", style.clear);
        s.setUniformf("uRes", fbWidth, fbHeight);
        s.setUniformf("uRect", fbX(x), fbY(y2), fbLength(x2 - x), fbLength(y2 - y));
        s.setUniformf("uRadius", fbLength(radius));
        s.setUniformf("uBezel", fbLength(style.bezel));
        s.setUniformf("uRefract", fbLength(style.refract));
        s.setUniformf("uDisp", style.dispersion);
        s.setUniformf("uMag", style.magnify);
        s.setUniformf("uFrost", fbLength(style.frost));
        s.setUniformf("uSat", style.saturation);
        s.setUniformf("uBright", style.brightness);
        colour(s, "uTint", style.tint);
        s.setUniformf("uLight", fbX(lightX), fbY(lightY));
        s.setUniformf("uSpec", style.specular);
        s.setUniformf("uShade", style.shade);
        s.setUniformf("uAlpha", alpha);
        quad(x, y, x2, y2);
        end();
        /* Unbound through the state cache rather than restored from a
           glGetInteger: the lightmap is re-bound by name whenever the game
           next needs it, and the cache now agrees that nothing is bound. */
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(0);
    }

    /**
     * The window the way it was drawn before shaders: the blur the other
     * menus use, masked to the pane, and the geometric glass over it.
     */
    private static void paneFallback(float x, float y, float x2, float y2, float radius) {
        boolean blurred = false;
        try {
            BlurUtils.prepareBlur();
            blurred = true;
            Glass.fill(x, y, x2, y2, radius, 0xFFFFFFFF);
        } catch (Throwable ignored) {
            // No blur; the pane still draws.
        } finally {
            if (blurred) {
                try {
                    BlurUtils.blurEnd(2, 9.0F);
                } catch (Throwable ignored) {
                    // Nothing further can be done.
                }
            }
        }
        Glass.pane(x, y, x2, y2, radius);
    }

    /** Scale of the GUI in framebuffer pixels, for anything snapping to them. */
    static int scale() {
        return scale;
    }

    /** Re-reads the GUI scale outside a frame, for work done before the first one. */
    static void refreshScale() {
        scale = new ScaledResolution(mc).getScaleFactor();
    }
}
