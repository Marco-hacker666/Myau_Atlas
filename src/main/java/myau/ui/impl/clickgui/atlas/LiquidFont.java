package myau.ui.impl.clickgui.atlas;

import myau.font.CFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * SF Pro, rasterised for the scale the GUI is actually drawn at.
 *
 * The client's own TTF renderer draws every font at half size from a glyph
 * sheet built at the requested point size. That is exactly one texel per
 * screen pixel at GUI scale 2 and blurry at every other scale, it uses a 2048
 * square sheet per font whatever the size, and its baseline is found by
 * adjusting a y offset until it looks right. This builds each font at
 * {@code size x scale} pixels and draws it at {@code 1 / scale}, so a glyph
 * texel is a screen pixel at any scale; places every glyph from its own
 * measured bounds, so text is centred on its cap height rather than on a
 * guess; and sizes each sheet to what it holds.
 *
 * Latin-1 only in its own sheets. A string with anything beyond Latin-1 --
 * the Chinese descriptions and labels of ModuleDocs (2026-10-04) -- is drawn
 * whole with the game's font, which has every BMP character in its unicode
 * pages, at this size, the same way as when the typeface failed to load.
 */
final class LiquidFont {

    /** The typeface file, or null for the game's own font. Set by the theme. */
    static String faceFile = "San-Francisco-Pro-Fonts.ttf";
    private static String loadedFace = faceFile;
    private static final int FIRST = 32;
    private static final int LAST = 255;
    private static final int SHEET_WIDTH = 1024;
    private static final int PAD = 2;

    /* Indexed by size in eighths of a pixel and weight, so a lookup -- a
       hundred and fifty of them a frame -- makes no string and no garbage. */
    private static final LiquidFont[] CACHE = new LiquidFont[1024];
    private static int cacheScale = -1;
    private static Font face;
    private static boolean faceTried;

    /** Multiplies the alpha of everything drawn, for the menu fading in. */
    static float alpha = 1.0F;

    private final float size;
    private final int scale;
    private DynamicTexture texture;
    private boolean ok;
    private float capHeight;

    private final float[] advance = new float[LAST + 1];
    private final float[] offsetX = new float[LAST + 1];
    private final float[] offsetY = new float[LAST + 1];
    private final float[] glyphW = new float[LAST + 1];
    private final float[] glyphH = new float[LAST + 1];
    private final float[] u0 = new float[LAST + 1];
    private final float[] v0 = new float[LAST + 1];
    private final float[] u1 = new float[LAST + 1];
    private final float[] v1 = new float[LAST + 1];
    private final boolean[] present = new boolean[LAST + 1];

    static LiquidFont of(float size, boolean bold) {
        int scale = Math.max(1, Liquid.scale());
        if (!java.util.Objects.equals(faceFile, loadedFace)) {
            /* A different typeface: every sheet is for the old one. */
            loadedFace = faceFile;
            face = null;
            faceTried = false;
            cacheScale = -1;
        }
        if (scale != cacheScale) {
            clear();
            cacheScale = scale;
        }
        int key = Math.max(0, Math.min(CACHE.length - 1, Math.round(size * 8.0F) * 2 + (bold ? 1 : 0)));
        LiquidFont font = CACHE[key];
        if (font == null) {
            font = new LiquidFont(size, bold, scale);
            CACHE[key] = font;
        }
        return font;
    }

    /** Drops every sheet, for a change of text size: the old sizes will not be asked for again. */
    static void clear() {
        for (int i = 0; i < CACHE.length; i++) {
            if (CACHE[i] != null) {
                CACHE[i].dispose();
                CACHE[i] = null;
            }
        }
    }

    private LiquidFont(float size, boolean bold, int scale) {
        this.size = size;
        this.scale = scale;
        try {
            build(bold);
            this.ok = true;
        } catch (Throwable ignored) {
            /* Drawn with the game's own font instead; see drawBaseline. */
            this.ok = false;
        }
    }

    private static Font face() {
        if (!faceTried) {
            faceTried = true;
            if (loadedFace == null) {
                face = null;
                return null;
            }
            try {
                face = CFontRenderer.getFontFromTTF(loadedFace, 12.0F, Font.TRUETYPE_FONT);
            } catch (Throwable ignored) {
                face = null;
            }
        }
        return face;
    }

    private void build(boolean bold) {
        Font base = face();
        if (base == null) {
            throw new IllegalStateException("no face");
        }
        Font font = base.deriveFont(bold ? Font.BOLD : Font.PLAIN, this.size * this.scale);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineMetrics metrics = font.getLineMetrics("Hgy", frc);
        this.capHeight = (float) font.createGlyphVector(frc, "H").getVisualBounds().getHeight() / this.scale;
        if (this.capHeight <= 0.0F) {
            this.capHeight = metrics.getAscent() * 0.7F / this.scale;
        }

        /* Measure and place every glyph first, so the sheet is exactly as
           tall as it needs to be. */
        Rectangle[] bounds = new Rectangle[LAST + 1];
        int[] placeX = new int[LAST + 1];
        int[] placeY = new int[LAST + 1];
        int x = PAD;
        int y = PAD;
        int rowHeight = 0;
        for (int c = FIRST; c <= LAST; c++) {
            GlyphVector vector = font.createGlyphVector(frc, String.valueOf((char) c));
            this.advance[c] = vector.getGlyphMetrics(0).getAdvanceX() / this.scale;
            Rectangle box = vector.getPixelBounds(frc, 0.0F, 0.0F);
            if (box.width <= 0 || box.height <= 0) {
                continue;
            }
            int w = box.width + PAD * 2;
            int h = box.height + PAD * 2;
            if (x + w > SHEET_WIDTH) {
                x = PAD;
                y += rowHeight;
                rowHeight = 0;
            }
            bounds[c] = box;
            placeX[c] = x;
            placeY[c] = y;
            x += w;
            rowHeight = Math.max(rowHeight, h);
        }
        int sheetHeight = ((y + rowHeight + PAD + 3) / 4) * 4;

        BufferedImage image = new BufferedImage(SHEET_WIDTH, sheetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setFont(font);
        g.setColor(Color.WHITE);
        for (int c = FIRST; c <= LAST; c++) {
            Rectangle box = bounds[c];
            if (box == null) {
                continue;
            }
            /* Drawn with its origin where the box's corner lands at the
               padded cell corner, so the offsets below are exact. */
            g.drawString(String.valueOf((char) c), placeX[c] + PAD - box.x, placeY[c] + PAD - box.y);
            this.present[c] = true;
            this.offsetX[c] = (box.x - PAD) / (float) this.scale;
            this.offsetY[c] = (box.y - PAD) / (float) this.scale;
            this.glyphW[c] = (box.width + PAD * 2) / (float) this.scale;
            this.glyphH[c] = (box.height + PAD * 2) / (float) this.scale;
            this.u0[c] = placeX[c] / (float) SHEET_WIDTH;
            this.v0[c] = placeY[c] / (float) sheetHeight;
            this.u1[c] = (placeX[c] + box.width + PAD * 2) / (float) SHEET_WIDTH;
            this.v1[c] = (placeY[c] + box.height + PAD * 2) / (float) sheetHeight;
        }
        g.dispose();

        /* Light text on a dark translucent surface reads thinner than the
           same coverage the other way round; lifting the midtones of the
           coverage a little puts the weight back. */
        int[] pixels = image.getRGB(0, 0, SHEET_WIDTH, sheetHeight, null, 0, SHEET_WIDTH);
        for (int i = 0; i < pixels.length; i++) {
            int a = (pixels[i] >>> 24) & 0xFF;
            if (a == 0) {
                continue;
            }
            int lifted = (int) Math.round(255.0 * Math.pow(a / 255.0, 0.82));
            pixels[i] = (lifted << 24) | 0x00FFFFFF;
        }
        image.setRGB(0, 0, SHEET_WIDTH, sheetHeight, pixels, 0, SHEET_WIDTH);
        this.texture = new DynamicTexture(image);
        GlStateManager.bindTexture(this.texture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.bindTexture(0);
    }

    private void dispose() {
        if (this.texture != null) {
            this.texture.deleteGlTexture();
            this.texture = null;
        }
        this.ok = false;
    }

    private static int glyphOf(char c) {
        return c >= FIRST && c <= LAST ? c : '?';
    }

    /** Whether the sheets can draw this: Latin-1 only. */
    private static boolean latin(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > LAST) {
                return false;
            }
        }
        return true;
    }

    float width(String text) {
        return width(text, 0.0F);
    }

    float width(String text, float tracking) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        if (!this.ok || !latin(text)) {
            return fallbackFont().getStringWidth(text) * fallbackScale();
        }
        float w = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            w += this.advance[glyphOf(text.charAt(i))];
        }
        return w + tracking * (text.length() - 1);
    }

    float capHeight() {
        return this.ok ? this.capHeight : 7.0F * fallbackScale();
    }

    /* A fallback string is placed by the game font's own cap height, not this
       sheet's, so it sits on the same centre line as the Latin text. */
    private float capHeightFor(String text) {
        return this.ok && (text == null || latin(text)) ? this.capHeight : 7.0F * fallbackScale();
    }

    /** Draws with the cap height centred on {@code cy}; returns the width drawn. */
    float draw(String text, float x, float cy, int colour) {
        return drawBaseline(text, x, cy + capHeightFor(text) / 2.0F, colour, 0.0F);
    }

    float drawTracked(String text, float x, float cy, int colour, float tracking) {
        return drawBaseline(text, x, cy + capHeightFor(text) / 2.0F, colour, tracking);
    }

    float drawRight(String text, float right, float cy, int colour) {
        float w = width(text);
        draw(text, right - w, cy, colour);
        return w;
    }

    float drawCentred(String text, float cx, float cy, int colour) {
        float w = width(text);
        draw(text, cx - w / 2.0F, cy, colour);
        return w;
    }

    float drawBaseline(String text, float x, float baseline, int colour, float tracking) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        float a = ((colour >>> 24) & 0xFF) / 255.0F * alpha;
        if (a <= 0.003F) {
            return width(text, tracking);
        }
        if (!this.ok || !latin(text)) {
            float s = fallbackScale();
            GL11.glPushMatrix();
            GL11.glTranslatef(x, baseline - 7.0F * s, 0.0F);
            GL11.glScalef(s, s, 1.0F);
            int c = ((int) (a * 255.0F) << 24) | (colour & 0x00FFFFFF);
            fallbackFont().drawString(text, 0, 0, c);
            GL11.glPopMatrix();
            return width(text, tracking);
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.bindTexture(this.texture.getGlTextureId());
        GlStateManager.color(((colour >> 16) & 0xFF) / 255.0F, ((colour >> 8) & 0xFF) / 255.0F,
                (colour & 0xFF) / 255.0F, a);
        /* Snapped to the pixel grid, so a glyph texel lands on one screen
           pixel instead of being smeared across two. */
        float pen = Math.round(x * this.scale) / (float) this.scale;
        float base = Math.round(baseline * this.scale) / (float) this.scale;
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < text.length(); i++) {
            int c = glyphOf(text.charAt(i));
            if (this.present[c]) {
                float gx = pen + this.offsetX[c];
                float gy = base + this.offsetY[c];
                float gx2 = gx + this.glyphW[c];
                float gy2 = gy + this.glyphH[c];
                GL11.glTexCoord2f(this.u0[c], this.v0[c]);
                GL11.glVertex2f(gx, gy);
                GL11.glTexCoord2f(this.u0[c], this.v1[c]);
                GL11.glVertex2f(gx, gy2);
                GL11.glTexCoord2f(this.u1[c], this.v1[c]);
                GL11.glVertex2f(gx2, gy2);
                GL11.glTexCoord2f(this.u1[c], this.v0[c]);
                GL11.glVertex2f(gx2, gy);
            }
            pen += this.advance[c] + tracking;
        }
        GL11.glEnd();
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return pen - x;
    }

    /** Cut to fit, with an ellipsis, from the end. */
    String trim(String text, float maxWidth) {
        if (width(text) <= maxWidth) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && width(out + "...") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "...";
    }

    /** Word wrapping to a width. */
    List<String> wrap(String text, float maxWidth) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!latin(word) && width(word) > maxWidth) {
                /* Chinese has no spaces to break at: break between characters. */
                for (int i = 0; i < word.length(); i++) {
                    String attempt = line.toString() + word.charAt(i);
                    if (line.length() > 0 && width(attempt) > maxWidth) {
                        lines.add(line.toString());
                        line = new StringBuilder().append(word.charAt(i));
                    } else {
                        line = new StringBuilder(attempt);
                    }
                }
                continue;
            }
            String attempt = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width(attempt) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(attempt);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static FontRenderer fallbackFont() {
        return Minecraft.getMinecraft().fontRendererObj;
    }

    /** The game's font has a seven-pixel cap height on a nine-pixel line. */
    private float fallbackScale() {
        return this.size / 9.5F;
    }
}
