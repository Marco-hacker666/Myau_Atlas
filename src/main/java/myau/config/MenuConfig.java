package myau.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public final class MenuConfig {
    private static final File FILE = new File("./config/Myau/menu.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;
    private static int backgroundIndex = 2;
    /** The main menu's startup sound. On by default; see MyauMainMenu#playStartupOnce. */
    private static boolean startupSound = true;

    private MenuConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        loaded = true;
        backgroundIndex = 2;
        startupSound = true;

        if (!FILE.exists()) {
            save();
            return;
        }

        boolean missingStartupSound = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE))) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("backgroundIndex")) {
                    backgroundIndex = clamp(object.get("backgroundIndex").getAsInt());
                }
                if (object.has("startupSound")) {
                    startupSound = object.get("startupSound").getAsBoolean();
                } else {
                    missingStartupSound = true;
                }
            }
        } catch (Exception ignored) {
            backgroundIndex = 2;
            startupSound = true;
        }

        /* A switch the player cannot see is a switch the player will not find, so
           a menu.json written before this option existed is rewritten with it
           (2026-10-10). */
        if (missingStartupSound) {
            save();
        }
    }

    public static synchronized int getBackgroundIndex() {
        load();
        return clamp(backgroundIndex);
    }

    public static synchronized void setBackgroundIndex(int index) {
        load();
        backgroundIndex = clamp(index);
    }

    public static synchronized boolean isStartupSound() {
        load();
        return startupSound;
    }

    public static synchronized void setStartupSound(boolean enabled) {
        load();
        startupSound = enabled;
    }

    public static synchronized void save() {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            JsonObject object = new JsonObject();
            object.addProperty("backgroundIndex", clamp(backgroundIndex));
            object.addProperty("startupSound", startupSound);

            try (PrintWriter writer = new PrintWriter(new FileWriter(FILE))) {
                writer.println(GSON.toJson(object));
            }
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int index) {
        return Math.max(0, Math.min(5, index));
    }
}
