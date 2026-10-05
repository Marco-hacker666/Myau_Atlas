package myau.util.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;

/**
 * Whether the screen-copy effects (blur, the Atlas glass) can run this frame.
 *
 * They draw into off-screen framebuffers and copy the game's own. When the
 * game has framebuffers switched off, binding one does nothing, so every pass
 * meant for an off-screen target -- full-screen quads -- lands on the screen
 * instead, and the screen goes white. OptiFine's Fast Render does exactly
 * that (it reports framebuffers as unavailable, and otherwise renders around
 * the game's framebuffer), as do its antialiasing and the vanilla "Use FBOs"
 * setting turned off (2026-10-05).
 *
 * Asked every frame, not once: the setting can be changed while playing.
 * The effects then fall back to their plain drawing; nothing goes missing.
 */
public final class FramebufferCompat {
    private static Field fastRender;
    private static boolean looked;

    private FramebufferCompat() {
    }

    public static boolean available() {
        return OpenGlHelper.shadersSupported && OpenGlHelper.isFramebufferEnabled() && !optifineFastRender();
    }

    /** OptiFine's Fast Render ({@code GameSettings.ofFastRender}); false without OptiFine. */
    public static boolean optifineFastRender() {
        if (!looked) {
            looked = true;
            try {
                fastRender = GameSettings.class.getDeclaredField("ofFastRender");
                fastRender.setAccessible(true);
            } catch (Throwable ignored) {
                fastRender = null;
            }
        }
        if (fastRender == null) {
            return false;
        }
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            return settings != null && fastRender.getBoolean(settings);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
