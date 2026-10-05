package myau.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/**
 * Dark or light, shared by the Atlas menu and every HUD element.
 *
 * The menu owns the setting ({@code appearance-mode} in atlas-theme.json) and
 * pushes it here every frame it is open. HUD elements only read it, and the
 * first read loads it from that file, so they follow the choice even in a game
 * where the menu has not been opened yet.
 */
public final class UiMode {
    private static final File THEME_FILE = new File("./config/Myau/atlas-theme.json");
    private static final String KEY = "appearance-mode";
    /** How far apart the brightest and dimmest channel may be for a colour to count as neutral. */
    private static final int NEUTRAL_SPREAD = 40;

    private static boolean light;
    private static boolean loaded;

    private UiMode() {
    }

    public static boolean isLight() {
        if (!loaded) {
            load();
        }
        return light;
    }

    public static void setLight(boolean value) {
        light = value;
        loaded = true;
    }

    /**
     * In light mode, swaps the lightness of a neutral colour (black, white or a
     * grey) and keeps its alpha: a translucent black panel becomes a translucent
     * white one, white text becomes near-black. Colours with a hue -- accents,
     * health, potion colours, the theme gradient -- are returned unchanged.
     * In dark mode every colour is returned unchanged.
     */
    public static int adapt(int argb) {
        if (!isLight()) {
            return argb;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min > NEUTRAL_SPREAD) {
            return argb;
        }
        return (argb & 0xFF000000) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
    }

    public static Color adapt(Color colour) {
        if (colour == null || !isLight()) {
            return colour;
        }
        int argb = adapt(colour.getRGB());
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, colour.getAlpha());
    }

    private static void load() {
        loaded = true;
        if (!THEME_FILE.exists()) {
            return;
        }
        Reader reader = null;
        try {
            reader = new FileReader(THEME_FILE);
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(KEY)) {
                    light = "Light".equalsIgnoreCase(object.get(KEY).getAsString());
                }
            }
        } catch (Exception ignored) {
            // Dark, the default.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // Nothing further to do.
                }
            }
        }
    }
}
